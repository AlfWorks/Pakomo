package com.pakomo.forwarding

import com.pakomo.core.model.AppFaultTarget
import com.pakomo.core.model.BlackoutMode
import com.pakomo.core.model.DnsFailureResult
import com.pakomo.core.model.SpecialFault
import com.pakomo.core.model.SpecialFaultConfig
import com.pakomo.core.model.SpecialFaultType
import com.pakomo.core.model.TargetScope
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FaultRuntimeTest {

    private fun origin(protocol: Int = ConnectionOrigin.PROTOCOL_TCP) = ConnectionOrigin(
        protocol = protocol,
        sourceAddress = InetAddress.getByName("10.0.0.2"),
        sourcePort = 40000,
        destinationAddress = InetAddress.getByName("93.184.216.34"),
        destinationPort = 443,
    )

    private fun attributor(packages: List<String>) = ConnectionAttributor { packages }

    @Test
    fun noEnabledFaultResolvesToNoFault() {
        val runtime = FaultRuntime(
            scope = TargetScope.GLOBAL,
            config = SpecialFaultConfig(),
            selectedAppDomains = emptyMap(),
            addressDomains = emptyList(),
            attributor = null,
        )
        assertEquals(NoConnectionFault, runtime.resolve(origin()))
    }

    @Test
    fun globalBlackoutHitsEveryTcpConnection() {
        val config = SpecialFaultConfig().withFault(
            SpecialFault(SpecialFaultType.NETWORK_BLACKOUT, enabled = true, blackoutMode = BlackoutMode.IMMEDIATE),
        )
        val runtime = FaultRuntime(TargetScope.GLOBAL, config, emptyMap(), emptyList(), null)
        val fault = runtime.resolve(origin())
        assertEquals(TcpFault.Blackout(BlackoutMode.IMMEDIATE), fault.decideTcp("anything.example.com", 443))
    }

    @Test
    fun blackoutTakesPriorityOverReset() {
        val target = mapOf("com.a" to AppFaultTarget("com.a", enabled = true))
        val config = SpecialFaultConfig(
            connectionReset = SpecialFault(SpecialFaultType.CONNECTION_RESET, enabled = true, appTargets = target),
            networkBlackout = SpecialFault(SpecialFaultType.NETWORK_BLACKOUT, enabled = true, appTargets = target),
        )
        val runtime = FaultRuntime(
            TargetScope.APPLICATIONS, config,
            selectedAppDomains = mapOf("com.a" to emptyList()),
            addressDomains = emptyList(),
            attributor = attributor(listOf("com.a")),
        )
        val fault = runtime.resolve(origin())
        assertTrue(fault.decideTcp("host", 443) is TcpFault.Blackout)
    }

    @Test
    fun resetOnlyMatchesSelectedAppDomain() {
        val config = SpecialFaultConfig(
            connectionReset = SpecialFault(
                SpecialFaultType.CONNECTION_RESET,
                enabled = true,
                appTargets = mapOf("com.a" to AppFaultTarget("com.a", enabled = true, domains = listOf("x.example.com"))),
            ),
        )
        val runtime = FaultRuntime(
            TargetScope.APPLICATIONS, config,
            selectedAppDomains = mapOf("com.a" to listOf("x.example.com")),
            addressDomains = emptyList(),
            attributor = attributor(listOf("com.a")),
        )
        val fault = runtime.resolve(origin())
        assertTrue(fault.usesDomainFilter)
        assertEquals(TcpFault.Reset, fault.decideTcp("x.example.com", 443))
        assertEquals(TcpFault.Reset, fault.decideTcp("api.x.example.com", 443)) // subdomain
        assertEquals(TcpFault.None, fault.decideTcp("other.example.com", 443))
    }

    @Test
    fun anotherAppsDomainFaultDoesNotForceHostSniffing() {
        val config = SpecialFaultConfig(
            connectionReset = SpecialFault(
                SpecialFaultType.CONNECTION_RESET,
                enabled = true,
                appTargets = mapOf(
                    "com.a" to AppFaultTarget(
                        "com.a",
                        enabled = true,
                        domains = listOf("x.example.com"),
                    ),
                ),
            ),
        )
        val runtime = FaultRuntime(
            TargetScope.APPLICATIONS,
            config,
            selectedAppDomains = mapOf(
                "com.a" to listOf("x.example.com"),
                "com.b" to emptyList(),
            ),
            addressDomains = emptyList(),
            attributor = attributor(listOf("com.b")),
        )
        val fault = runtime.resolve(origin())
        assertEquals(false, fault.usesDomainFilter)
        assertEquals(TcpFault.None, fault.decideTcp("1.2.3.4", 443))
    }

    @Test
    fun repeatedPacketsReportOneHitPerResolvedConnection() {
        val reports = AtomicInteger()
        val config = SpecialFaultConfig().withFault(
            SpecialFault(SpecialFaultType.NETWORK_BLACKOUT, enabled = true),
        )
        val runtime = FaultRuntime(
            TargetScope.GLOBAL,
            config,
            emptyMap(),
            emptyList(),
            null,
            FaultHitReporter { reports.incrementAndGet() },
        )
        val fault = runtime.resolve(origin(ConnectionOrigin.PROTOCOL_UDP))
        repeat(100) {
            assertEquals(UdpFault.Drop, fault.decideUdp("1.2.3.4", 443, null))
        }
        assertEquals(1, reports.get())
    }

    @Test
    fun unattributedConnectionMatchesNoAppFault() {
        val config = SpecialFaultConfig().withFault(
            SpecialFault(
                SpecialFaultType.CONNECTION_RESET,
                enabled = true,
                appTargets = mapOf("com.a" to AppFaultTarget("com.a", enabled = true)),
            ),
        )
        val runtime = FaultRuntime(
            TargetScope.APPLICATIONS, config,
            selectedAppDomains = mapOf("com.a" to emptyList()),
            addressDomains = emptyList(),
            attributor = attributor(emptyList()), // attribution miss
        )
        assertEquals(TcpFault.None, runtime.resolve(origin()).decideTcp("host", 443))
    }

    @Test
    fun dnsFailureMatchesByQueriedName() {
        val config = SpecialFaultConfig().withFault(
            SpecialFault(
                SpecialFaultType.DNS_FAILURE,
                enabled = true,
                dnsResult = DnsFailureResult.SERVFAIL,
                addressTargets = listOf("blocked.example.com"),
            ),
        )
        val runtime = FaultRuntime(
            TargetScope.ADDRESSES, config,
            selectedAppDomains = emptyMap(),
            addressDomains = listOf("blocked.example.com"),
            attributor = null,
        )
        val fault = runtime.resolve(origin(ConnectionOrigin.PROTOCOL_UDP))
        // Matched by the queried name, not the DNS server host.
        assertEquals(
            UdpFault.Dns(DnsFailureResult.SERVFAIL),
            fault.decideUdp("8.8.8.8", 53, "blocked.example.com"),
        )
        assertEquals(UdpFault.None, fault.decideUdp("8.8.8.8", 53, "allowed.example.com"))
    }

    @Test
    fun refusedDnsFailureIsPreservedByRuntimeDecision() {
        val target = listOf("blocked.example.com")
        val config = SpecialFaultConfig().withFault(
            SpecialFault(
                SpecialFaultType.DNS_FAILURE,
                enabled = true,
                dnsResult = DnsFailureResult.REFUSED,
                addressTargets = target,
            ),
        )
        val runtime = FaultRuntime(
            TargetScope.ADDRESSES,
            config,
            selectedAppDomains = emptyMap(),
            addressDomains = target,
            attributor = null,
        )
        val fault = runtime.resolve(origin(ConnectionOrigin.PROTOCOL_UDP))
        assertEquals(
            UdpFault.Dns(DnsFailureResult.REFUSED),
            fault.decideUdp("8.8.8.8", 53, "blocked.example.com"),
        )
    }

    @Test
    fun dnsCacheGuardOnlyBlocksConnectionsWhenExplicitlyEnabled() {
        fun runtime(guard: Boolean): FaultRuntime {
            val target = listOf("blocked.example.com")
            return FaultRuntime(
                TargetScope.ADDRESSES,
                SpecialFaultConfig().withFault(
                    SpecialFault(
                        SpecialFaultType.DNS_FAILURE,
                        enabled = true,
                        dnsCacheGuard = guard,
                        addressTargets = target,
                    ),
                ),
                emptyMap(),
                target,
                null,
            )
        }

        assertEquals(
            TcpFault.None,
            runtime(false).resolve(origin()).decideTcp("blocked.example.com", 443),
        )
        assertEquals(
            TcpFault.Reset,
            runtime(true).resolve(origin()).decideTcp("blocked.example.com", 443),
        )
    }

    /** Builds a minimal DNS A-record response mapping [name] -> [ip]. */
    private fun dnsAResponse(name: String, ip: String): ByteArray {
        val header = byteArrayOf(
            0x00, 0x00, 0x81.toByte(), 0x80.toByte(), // id, flags QR+RD+RA
            0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, // QD=1 AN=1 NS=0 AR=0
        )
        val qname = buildList {
            name.split('.').forEach { label ->
                add(label.length.toByte()); label.forEach { add(it.code.toByte()) }
            }
            add(0.toByte())
        }.toByteArray()
        val question = qname + byteArrayOf(0x00, 0x01, 0x00, 0x01) // A, IN
        val ipBytes = ip.split('.').map { it.toInt().toByte() }.toByteArray()
        val answer = byteArrayOf(
            0xC0.toByte(), 0x0C, // name pointer to question
            0x00, 0x01, 0x00, 0x01, // A, IN
            0x00, 0x00, 0x00, 0x3C, // ttl 60
            0x00, 0x04, // rdlength
        ) + ipBytes
        return header + question + answer
    }

    @Test
    fun domainResetMatchesLearnedIpForQuicAndNoSni() {
        val config = SpecialFaultConfig().withFault(
            SpecialFault(
                SpecialFaultType.CONNECTION_RESET,
                enabled = true,
                appTargets = mapOf("com.a" to AppFaultTarget("com.a", enabled = true, domains = listOf("x.example.com"))),
            ),
        )
        val runtime = FaultRuntime(
            TargetScope.APPLICATIONS, config,
            selectedAppDomains = mapOf("com.a" to listOf("x.example.com")),
            addressDomains = emptyList(),
            attributor = attributor(listOf("com.a")),
        )
        val fault = runtime.resolve(origin())
        // SNI host matches immediately.
        assertEquals(TcpFault.Reset, fault.decideTcp("x.example.com", 443))
        // A bare IP (no SNI / QUIC) does not match until the domain's DNS answer is observed.
        assertEquals(TcpFault.None, fault.decideTcp("1.2.3.4", 443))
        assertEquals(UdpFault.None, fault.decideUdp("1.2.3.4", 443, null))
        fault.observeDnsResponse(dnsAResponse("x.example.com", "1.2.3.4"))
        // Now the learned IP matches: TCP resets, QUIC (UDP 443) is dropped to force TCP fallback.
        assertEquals(TcpFault.Reset, fault.decideTcp("1.2.3.4", 443))
        assertEquals(UdpFault.Drop, fault.decideUdp("1.2.3.4", 443, null))
    }

    @Test
    fun dnsFailureOwnsDnsWhenBlackoutTargetsTheSameDomain() {
        val addr = listOf("z.example.com")
        val config = SpecialFaultConfig(
            dnsFailure = SpecialFault(SpecialFaultType.DNS_FAILURE, enabled = true, addressTargets = addr),
            networkBlackout = SpecialFault(SpecialFaultType.NETWORK_BLACKOUT, enabled = true, addressTargets = addr),
        )
        val runtime = FaultRuntime(
            TargetScope.ADDRESSES, config, emptyMap(), addr, attributor = null,
        )
        val fault = runtime.resolve(origin(ConnectionOrigin.PROTOCOL_UDP))
        assertEquals(
            UdpFault.Dns(DnsFailureResult.NXDOMAIN),
            fault.decideUdp("1.1.1.1", 53, "z.example.com"),
        )
    }

    @Test
    fun blackoutAloneLeavesDnsQueriesUntouched() {
        val addr = listOf("z.example.com")
        val config = SpecialFaultConfig(
            networkBlackout = SpecialFault(
                SpecialFaultType.NETWORK_BLACKOUT,
                enabled = true,
                addressTargets = addr,
            ),
        )
        val runtime = FaultRuntime(
            TargetScope.ADDRESSES, config, emptyMap(), addr, attributor = null,
        )
        val fault = runtime.resolve(origin(ConnectionOrigin.PROTOCOL_UDP))
        assertEquals(UdpFault.None, fault.decideUdp("1.1.1.1", 53, "z.example.com"))
    }
}
