package com.pakomo.forwarding

import com.pakomo.core.model.BlackoutMode
import com.pakomo.core.model.DnsFailureResult
import com.pakomo.core.model.SpecialFault
import com.pakomo.core.model.SpecialFaultConfig
import com.pakomo.core.model.SpecialFaultType
import com.pakomo.core.model.TargetScope

/**
 * Runtime enforcement of the three special faults, layered on top of the weak-network shaping.
 * A fault only affects the targets it selected (see [com.pakomo.core.model.SpecialFaultTargets]);
 * everything else keeps flowing through the normal shaping path.
 *
 * Fixed priority per connection (so the result never depends on execution order):
 *   1. 网络中断 (blackout) — TCP and UDP
 *   2. DNS 失败 (dns failure) — DNS queries not already blacked out
 *   3. Connection Reset — TCP connections not already blacked out
 *   4. otherwise: normal weak-network shaping
 */

/** Fault action for a TCP connection, already resolved in priority order. */
sealed interface TcpFault {
    data object None : TcpFault
    data class Blackout(val mode: BlackoutMode) : TcpFault
    data object Reset : TcpFault
}

enum class TcpDecisionPhase {
    PRE_CONNECT,
    POST_CONNECT,
}

/** Fault action for a UDP datagram (DNS or plain). */
sealed interface UdpFault {
    data object None : UdpFault

    /** Drop a non-DNS datagram, or a DNS query configured to time out. */
    data object Drop : UdpFault

    /** Synthesize a DNS failure reply of the given kind. */
    data class Dns(val result: DnsFailureResult) : UdpFault
}

/** A single connection's fault decision, resolved once after attribution. */
interface ConnectionFault {
    /**
     * Whether the decision needs the real destination host (a domain-scoped fault is in play).
     * When false, every fault that could match this connection is host-independent (global scope
     * or a whole-app target), so the relay can decide before connecting outbound / sniffing.
     */
    val usesDomainFilter: Boolean

    fun decideTcp(
        host: String,
        port: Int,
        phase: TcpDecisionPhase = TcpDecisionPhase.PRE_CONNECT,
    ): TcpFault

    /** [dnsQueryName] is the parsed DNS question, or null when the datagram is not a DNS query. */
    fun decideUdp(host: String, port: Int, dnsQueryName: String?): UdpFault

    /**
     * Feeds an observed plaintext DNS response so domain-scoped faults learn their targets' IPs, and
     * can then match connections (QUIC / no-SNI TCP) by destination IP — the same mechanism the
     * weak-network domain shaping already uses.
     */
    fun observeDnsResponse(message: ByteArray)
}

/** Resolves the fault decision for a connection given its origin (null when unknown). */
fun interface FaultPolicy {
    fun resolve(origin: ConnectionOrigin?): ConnectionFault

    companion object {
        /** No special faults active — every connection flows normally. */
        val NONE = FaultPolicy { NoConnectionFault }
    }
}

object NoConnectionFault : ConnectionFault {
    override val usesDomainFilter = false
    override fun decideTcp(host: String, port: Int, phase: TcpDecisionPhase) = TcpFault.None
    override fun decideUdp(host: String, port: Int, dnsQueryName: String?) = UdpFault.None
    override fun observeDnsResponse(message: ByteArray) = Unit
}

/**
 * The set of targets one fault matches at runtime, precomputed for the active scope. Domain targets
 * are matched through [DomainRoutingPolicy] — the same policy the weak-network domain shaping uses —
 * so a connection matches by TLS SNI / HTTP host *or* by a destination IP learned from the domain's
 * plaintext DNS answer. That is what lets a domain-scoped fault catch QUIC / no-SNI flows, not just
 * plain-text TCP. Mirrors [com.pakomo.core.model.SpecialFaultTargets.effectiveTargets] so runtime
 * hits and the UI's "已选 N 个" count stay consistent.
 */
class FaultMatcher(
    private val global: Boolean,
    private val wholeApps: Set<String>,
    private val appDomains: Map<String, DomainRoutingPolicy>,
    private val addressDomains: DomainRoutingPolicy?,
) {
    val usesDomainFilter: Boolean = appDomains.isNotEmpty() || addressDomains != null

    val active: Boolean = global || wholeApps.isNotEmpty() || usesDomainFilter

    fun usesDomainFilterFor(packages: List<String>): Boolean =
        addressDomains != null || packages.any(appDomains::containsKey)

    /** Matches a TCP/UDP connection by host (SNI / HTTP host) or by a DNS-learned destination IP. */
    fun matchesConnection(packages: List<String>, host: String, port: Int): Boolean {
        if (global) return true
        if (packages.any { it in wholeApps }) return true
        for (pkg in packages) {
            if (appDomains[pkg]?.shouldShape(host, port) == true) return true
        }
        return addressDomains?.shouldShape(host, port) == true
    }

    /** Matches a DNS query's questioned name against the target domains (by name, no IP lookup). */
    fun matchesName(packages: List<String>, name: String): Boolean {
        if (global) return true
        if (packages.any { it in wholeApps }) return true
        for (pkg in packages) {
            if (appDomains[pkg]?.matchesConfiguredDomain(name) == true) return true
        }
        return addressDomains?.matchesConfiguredDomain(name) == true
    }

    /** Learns target IPs from an observed plaintext DNS answer. */
    fun observeDnsResponse(packages: List<String>, message: ByteArray) {
        if (addressDomains != null) {
            addressDomains.observeDnsResponse(message)
        } else {
            // In application mode a DNS response belongs to this attributed connection. Updating
            // every selected application's policy would parse the same packet hundreds of times.
            packages.asSequence()
                .mapNotNull(appDomains::get)
                .distinct()
                .forEach { it.observeDnsResponse(message) }
        }
    }

    companion object {
        val INACTIVE = FaultMatcher(false, emptySet(), emptyMap(), null)

        /** Builds the matcher for one fault in the current scope, ignoring disabled/unselected. */
        fun build(
            fault: SpecialFault,
            scope: TargetScope,
            selectedAppDomains: Map<String, List<String>>,
            addressDomains: List<String>,
        ): FaultMatcher {
            if (!fault.enabled) return INACTIVE
            return when (scope) {
                TargetScope.GLOBAL -> FaultMatcher(true, emptySet(), emptyMap(), null)

                TargetScope.APPLICATIONS -> {
                    val wholeApps = mutableSetOf<String>()
                    val appDomains = mutableMapOf<String, DomainRoutingPolicy>()
                    selectedAppDomains.forEach { (pkg, configured) ->
                        val target = fault.appTargets[pkg]
                        if (target == null || !target.enabled) return@forEach
                        if (configured.isEmpty()) {
                            wholeApps += pkg
                        } else {
                            val configuredSet = configured.toHashSet()
                            val selected = target.domains.filter { it in configuredSet }
                            if (selected.isNotEmpty()) appDomains[pkg] = DomainRoutingPolicy(selected)
                        }
                    }
                    FaultMatcher(false, wholeApps, appDomains, null)
                }

                TargetScope.ADDRESSES -> {
                    val configuredSet = addressDomains.toHashSet()
                    val selected = fault.addressTargets.filter { it in configuredSet }
                    FaultMatcher(
                        global = false,
                        wholeApps = emptySet(),
                        appDomains = emptyMap(),
                        addressDomains = if (selected.isEmpty()) null else DomainRoutingPolicy(selected),
                    )
                }
            }
        }
    }
}

/**
 * Concrete [FaultPolicy] for the active configuration. Reuses the same [ConnectionAttributor] as
 * shaping so faults and shaping attribute a connection to the same app(s); an unattributed
 * connection matches no app-scoped fault (fail-open, never silently breaks unrelated traffic).
 */
class FaultRuntime(
    private val scope: TargetScope,
    config: SpecialFaultConfig,
    selectedAppDomains: Map<String, List<String>>,
    addressDomains: List<String>,
    private val attributor: ConnectionAttributor?,
    private val reporter: FaultHitReporter? = null,
) : FaultPolicy {

    private val blackout = FaultMatcher.build(
        config.fault(SpecialFaultType.NETWORK_BLACKOUT), scope, selectedAppDomains, addressDomains,
    )
    private val dnsFailure = FaultMatcher.build(
        config.fault(SpecialFaultType.DNS_FAILURE), scope, selectedAppDomains, addressDomains,
    )
    private val reset = FaultMatcher.build(
        config.fault(SpecialFaultType.CONNECTION_RESET), scope, selectedAppDomains, addressDomains,
    )

    private val blackoutMode = config.fault(SpecialFaultType.NETWORK_BLACKOUT).blackoutMode
    private val dnsResult = config.fault(SpecialFaultType.DNS_FAILURE).dnsResult
    private val dnsCacheGuard = config.fault(SpecialFaultType.DNS_FAILURE).dnsCacheGuard

    private val anyActive = blackout.active || dnsFailure.active || reset.active
    override fun resolve(origin: ConnectionOrigin?): ConnectionFault {
        if (!anyActive) return NoConnectionFault
        val packages = if (scope == TargetScope.APPLICATIONS && origin != null) {
            origin.let { runCatching { attributor?.packagesFor(it) }.getOrNull() }.orEmpty()
        } else {
            emptyList()
        }
        return Resolved(packages)
    }

    private inner class Resolved(private val packages: List<String>) : ConnectionFault {
        override val usesDomainFilter: Boolean =
            blackout.usesDomainFilterFor(packages) ||
                dnsFailure.usesDomainFilterFor(packages) ||
                reset.usesDomainFilterFor(packages)
        private val reported = HashSet<String>()

        override fun decideTcp(host: String, port: Int, phase: TcpDecisionPhase): TcpFault {
            if (blackout.matchesConnection(packages, host, port)) {
                val action = when {
                    blackoutMode == BlackoutMode.SILENT -> "silent-park"
                    phase == TcpDecisionPhase.PRE_CONNECT -> "pre-connect-refused"
                    else -> "post-connect-reset"
                }
                report(SpecialFaultType.NETWORK_BLACKOUT, host, action)
                return TcpFault.Blackout(blackoutMode)
            }
            // Cache-robust DNS failure: a connection that still reaches a DNS-failure target (via a
            // stale cached IP, or by SNI) is failed too, so a DNS cache can't defeat the fault.
            if (dnsCacheGuard && dnsFailure.matchesConnection(packages, host, port)) {
                report(SpecialFaultType.DNS_FAILURE, host, "dns-cache-guard-reset")
                return TcpFault.Reset
            }
            if (reset.matchesConnection(packages, host, port)) {
                report(SpecialFaultType.CONNECTION_RESET, host, "post-connect-reset")
                return TcpFault.Reset
            }
            return TcpFault.None
        }

        override fun decideUdp(host: String, port: Int, dnsQueryName: String?): UdpFault {
            if (port == DNS_PORT && dnsQueryName != null) {
                // Network blackout deliberately leaves DNS alone. DNS failures own name-resolution
                // semantics so immediate/silent blackout remains distinguishable at connection time.
                if (dnsFailure.matchesName(packages, dnsQueryName)) {
                    report(
                        SpecialFaultType.DNS_FAILURE,
                        dnsQueryName,
                        "dns-${dnsResult.name.lowercase()}",
                    )
                    return UdpFault.Dns(dnsResult)
                }
                return UdpFault.None
            }
            if (blackout.matchesConnection(packages, host, port)) {
                report(SpecialFaultType.NETWORK_BLACKOUT, host, "udp-drop")
                return UdpFault.Drop
            }
            // Cache-robust DNS failure also covers datagrams (incl. QUIC) to a stale learned IP.
            if (dnsCacheGuard && dnsFailure.matchesConnection(packages, host, port)) {
                report(SpecialFaultType.DNS_FAILURE, host, "dns-cache-guard-drop")
                return UdpFault.Drop
            }
            // A connection-reset target that reaches its host over QUIC (UDP 443) would otherwise
            // slip past the TCP-only reset. Dropping the QUIC datagrams forces the app to fall back
            // to TCP, where the reset applies.
            if (port == HTTPS_PORT && reset.matchesConnection(packages, host, port)) {
                report(SpecialFaultType.CONNECTION_RESET, host, "quic-fallback-drop")
                return UdpFault.Drop
            }
            return UdpFault.None
        }

        override fun observeDnsResponse(message: ByteArray) = observeDns(packages, message)

        private fun report(type: SpecialFaultType, target: String, result: String) {
            val key = "${type.name}|$target|$result"
            if (synchronized(reported) { reported.add(key) }) {
                reporter?.report(FaultHit(type, scope.label, packages.firstOrNull(), target, result))
            }
        }
    }

    private fun observeDns(packages: List<String>, message: ByteArray) {
        blackout.observeDnsResponse(packages, message)
        dnsFailure.observeDnsResponse(packages, message)
        reset.observeDnsResponse(packages, message)
    }

    private companion object {
        const val DNS_PORT = 53
        const val HTTPS_PORT = 443
    }
}

/** A single fault enforcement, surfaced to logs / diagnostics. */
data class FaultHit(
    val type: SpecialFaultType,
    val scope: String,
    val packageName: String?,
    val target: String,
    val result: String,
)

/** Receives fault enforcement events. Implementations must be thread-safe. */
fun interface FaultHitReporter {
    fun report(hit: FaultHit)
}
