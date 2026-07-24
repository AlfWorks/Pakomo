package com.pakomo.forwarding

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShapingPolicyTest {
    private fun origin(protocol: Int = ConnectionOrigin.PROTOCOL_TCP) = ConnectionOrigin(
        protocol = protocol,
        sourceAddress = InetAddress.getByName("10.0.0.2"),
        sourcePort = 40000,
        destinationAddress = InetAddress.getByName("93.184.216.34"),
        destinationPort = 443,
    )

    private fun preambleOf(o: ConnectionOrigin): ByteArray {
        val src = o.sourceAddress.address
        val dst = o.destinationAddress.address
        return byteArrayOf(
            'P'.code.toByte(), 'K'.code.toByte(), 'M'.code.toByte(), 'O'.code.toByte(),
            1, o.protocol.toByte(),
            (o.sourcePort shr 8).toByte(), o.sourcePort.toByte(),
            (o.destinationPort shr 8).toByte(), o.destinationPort.toByte(),
            src[0], src[1], src[2], src[3],
            dst[0], dst[1], dst[2], dst[3],
            0, 0,
        )
    }

    private fun app(pkg: String, label: String, vararg domains: String) =
        ShapedApplication(pkg, label, domains.toList())

    @Test
    fun `preamble round-trips through parse`() {
        val expected = origin()
        assertEquals(expected, ConnectionOrigin.parse(preambleOf(expected)))
    }

    @Test
    fun `parse rejects wrong magic`() {
        val bytes = preambleOf(origin()).copyOf()
        bytes[0] = 'X'.code.toByte()
        assertNull(ConnectionOrigin.parse(bytes))
    }

    @Test
    fun `parse rejects invalid protocol, zero port and wildcard address`() {
        val badProtocol = preambleOf(origin()).copyOf().also { it[5] = 1 }
        assertNull(ConnectionOrigin.parse(badProtocol))

        val zeroSrcPort = preambleOf(origin()).copyOf().also { it[6] = 0; it[7] = 0 }
        assertNull(ConnectionOrigin.parse(zeroSrcPort))

        val zeroDstPort = preambleOf(origin()).copyOf().also { it[8] = 0; it[9] = 0 }
        assertNull(ConnectionOrigin.parse(zeroDstPort))

        val wildcardSrc = preambleOf(origin()).copyOf().also {
            it[10] = 0; it[11] = 0; it[12] = 0; it[13] = 0
        }
        assertNull(ConnectionOrigin.parse(wildcardSrc))
    }

    @Test
    fun `per-app policy shapes only the owning app's own domains`() {
        val hits = mutableListOf<ShapingHit>()
        val policy = PerAppShapingPolicy(
            apps = listOf(
                app("app.a", "App A", "a.example.com"),
                app("app.b", "App B", "b.example.com"),
            ),
            attributor = { listOf("app.a") },
            reporter = { hits += it },
        )

        val shaping = policy.resolve(origin())

        assertTrue(shaping.shouldShape("a.example.com", 443))
        assertFalse(shaping.shouldShape("b.example.com", 443))
        assertEquals("App A", hits.single().appLabel)
        assertTrue(hits.single().attributed)
    }

    @Test
    fun `selected app without a domain filter shapes all its traffic`() {
        val policy = PerAppShapingPolicy(
            apps = listOf(app("app.a", "App A")),
            attributor = { listOf("app.a") },
            reporter = null,
        )
        assertTrue(policy.resolve(origin()).shouldShape("anything.example.com", 443))
    }

    @Test
    fun `shared UID group unions the members' domains`() {
        val policy = PerAppShapingPolicy(
            apps = listOf(
                app("app.a", "App A", "a.example.com"),
                app("app.b", "App B", "b.example.com"),
            ),
            // Both packages share a UID and are returned together.
            attributor = { listOf("app.a", "app.b") },
            reporter = null,
        )
        val shaping = policy.resolve(origin())
        assertTrue(shaping.shouldShape("a.example.com", 443))
        assertTrue(shaping.shouldShape("b.example.com", 443))
        assertFalse(shaping.shouldShape("c.example.com", 443))
    }

    @Test
    fun `shared UID group with a whole-app member shapes the whole group`() {
        val policy = PerAppShapingPolicy(
            apps = listOf(
                app("app.a", "App A", "a.example.com"),
                app("app.b", "App B"), // whole-app
            ),
            attributor = { listOf("app.a", "app.b") },
            reporter = null,
        )
        assertTrue(policy.resolve(origin()).shouldShape("unrelated.example.com", 443))
    }

    @Test
    fun `unattributed connection is bypassed when any app has a domain filter`() {
        val hits = mutableListOf<ShapingHit>()
        val policy = PerAppShapingPolicy(
            apps = listOf(app("app.a", "App A", "a.example.com")),
            attributor = { emptyList() },
            reporter = { hits += it },
        )
        val shaping = policy.resolve(origin())
        assertFalse(shaping.shouldShape("a.example.com", 443))
        assertFalse(hits.single().attributed)
        assertFalse(hits.single().shaped)
    }

    @Test
    fun `unattributed connection is shaped-all only when every app is whole-app`() {
        val policy = PerAppShapingPolicy(
            apps = listOf(app("app.a", "App A"), app("app.b", "App B")),
            attributor = { emptyList() },
            reporter = null,
        )
        assertTrue(policy.resolve(origin()).shouldShape("anything.example.com", 443))
    }
}
