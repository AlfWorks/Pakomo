package com.alphynia.pakomo.vpn

import com.alphynia.pakomo.forwarding.FlowLatencyHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowLatencyTrackerTest {
    // A controllable clock so report()'s timestamp and tick()'s cadence are deterministic.
    private var now = 1_000_000L
    private fun tracker(
        flowCapacity: Int = 16,
        windowMs: Long = 60_000L,
        activeAfterIdleMs: Long = 12_000L,
    ) = FlowLatencyTracker(flowCapacity, windowMs, activeAfterIdleMs, maxSamplesPerFlow = 240) { now }

    private fun hit(
        host: String,
        pkg: String? = "com.example",
        shaped: Boolean = true,
        injectedMs: Int = 100,
        measuredRttMs: Int = 30,
    ) = FlowLatencyHit(host, pkg, shaped, injectedMs, measuredRttMs)

    @Test
    fun `report records nothing until a tick appends the sample`() {
        val t = tracker()
        t.report(hit("a.example", injectedMs = 120, measuredRttMs = 40))
        assertTrue("no sample before tick", t.snapshot().single().samples.isEmpty())

        t.tick(now)
        val s = t.snapshot().single()
        assertEquals("a.example", s.host)
        assertTrue(s.shaped)
        val sample = s.samples.single()
        assertEquals(120, sample.injectedMs)
        assertEquals(40, sample.measuredRttMs)
        assertEquals(now, sample.atMs)
    }

    @Test
    fun `ticks hold the last measured value into a continuous series`() {
        val t = tracker()
        t.report(hit("a.example", measuredRttMs = 30))
        t.tick(now)
        t.tick(now + 1_000)
        t.report(hit("a.example", measuredRttMs = 55)) // a fresh connection updates the held value
        t.tick(now + 2_000)

        val samples = t.snapshot().single().samples
        assertEquals(listOf(30, 30, 55), samples.map { it.measuredRttMs })
    }

    @Test
    fun `an idle flow stops sampling and is dropped once its window empties`() {
        val t = tracker(windowMs = 3_000L, activeAfterIdleMs = 1_000L)
        t.report(hit("a.example"))
        t.tick(now)
        assertEquals(1, t.snapshot().single().samples.size)

        // Past the active window: no new sample, and the old one falls outside the retention window.
        t.tick(now + 5_000)
        assertTrue("aged-out flow is dropped", t.snapshot().isEmpty())
    }

    @Test
    fun `different app on same host is a distinct flow`() {
        val t = tracker()
        t.report(hit("a.example", pkg = "com.one"))
        t.report(hit("a.example", pkg = "com.two"))
        t.tick(now)
        assertEquals(2, t.snapshot().size)
    }

    @Test
    fun `flow count is capped, least-recently-connected evicted`() {
        val t = tracker(flowCapacity = 2)
        t.report(hit("a.example"))
        t.report(hit("b.example"))
        t.report(hit("c.example")) // evicts the least-recently-connected (a)
        t.tick(now)

        val hosts = t.snapshot().map { it.host }
        assertEquals(2, hosts.size)
        assertFalse("a.example" in hosts)
        assertTrue("b.example" in hosts)
        assertTrue("c.example" in hosts)
    }

    @Test
    fun `snapshot is most-recently-connected first`() {
        val t = tracker()
        t.report(hit("a.example"))
        t.report(hit("b.example"))
        t.tick(now)
        assertEquals(listOf("b.example", "a.example"), t.snapshot().map { it.host })
    }

    @Test
    fun `unshaped flow holds zero injected delay`() {
        val t = tracker()
        t.report(hit("a.example", shaped = false, injectedMs = 0, measuredRttMs = 15))
        t.tick(now)
        val s = t.snapshot().single()
        assertFalse(s.shaped)
        assertEquals(0, s.samples.single().injectedMs)
    }

    @Test
    fun `samples older than the window scroll off`() {
        val t = tracker(windowMs = 3_000L, activeAfterIdleMs = 60_000L)
        t.report(hit("a.example"))
        t.tick(now)          // sample at now
        t.tick(now + 2_000)  // sample at now+2000
        t.tick(now + 4_000)  // sample at now+4000; the now sample (4s old) is now outside the 3s window

        val samples = t.snapshot().single().samples
        assertEquals(listOf(now + 2_000, now + 4_000), samples.map { it.atMs })
    }
}
