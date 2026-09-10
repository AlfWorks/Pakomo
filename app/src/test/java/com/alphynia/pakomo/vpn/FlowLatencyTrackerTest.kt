package com.alphynia.pakomo.vpn

import com.alphynia.pakomo.forwarding.FlowLatencyHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowLatencyTrackerTest {
    private fun hit(
        host: String,
        pkg: String? = "com.example",
        shaped: Boolean = true,
        injectedMs: Int = 100,
        measuredRttMs: Int = 30,
    ) = FlowLatencyHit(host, pkg, shaped, injectedMs, measuredRttMs)

    @Test
    fun `records paired injected and measured latency per flow`() {
        val tracker = FlowLatencyTracker()
        tracker.report(hit("a.example", injectedMs = 120, measuredRttMs = 40))

        val series = tracker.snapshot().single()
        assertEquals("a.example", series.host)
        assertEquals("com.example", series.packageName)
        assertTrue(series.shaped)
        val sample = series.samples.single()
        assertEquals(120, sample.injectedMs)
        assertEquals(40, sample.measuredRttMs)
        assertTrue(sample.atMs > 0)
    }

    @Test
    fun `same host and app accumulate into one oldest-first series`() {
        val tracker = FlowLatencyTracker()
        tracker.report(hit("a.example", measuredRttMs = 10))
        tracker.report(hit("a.example", measuredRttMs = 20))
        tracker.report(hit("a.example", measuredRttMs = 30))

        val series = tracker.snapshot().single()
        assertEquals(listOf(10, 20, 30), series.samples.map { it.measuredRttMs })
    }

    @Test
    fun `different app on same host is a distinct flow`() {
        val tracker = FlowLatencyTracker()
        tracker.report(hit("a.example", pkg = "com.one"))
        tracker.report(hit("a.example", pkg = "com.two"))

        assertEquals(2, tracker.snapshot().size)
    }

    @Test
    fun `per-flow samples are capped to the newest, oldest dropped`() {
        val tracker = FlowLatencyTracker(samplesPerFlow = 3)
        repeat(5) { tracker.report(hit("a.example", measuredRttMs = it)) }

        val series = tracker.snapshot().single()
        assertEquals(3, series.samples.size)
        // 0 and 1 evicted; 2,3,4 kept oldest-first.
        assertEquals(listOf(2, 3, 4), series.samples.map { it.measuredRttMs })
    }

    @Test
    fun `flow count is capped, least-recently-updated evicted`() {
        val tracker = FlowLatencyTracker(flowCapacity = 2)
        tracker.report(hit("a.example"))
        tracker.report(hit("b.example"))
        tracker.report(hit("c.example")) // evicts the least-recently-updated (a)

        val hosts = tracker.snapshot().map { it.host }
        assertEquals(2, hosts.size)
        assertFalse("a.example" in hosts)
        assertTrue("b.example" in hosts)
        assertTrue("c.example" in hosts)
    }

    @Test
    fun `touching a flow keeps it from eviction`() {
        val tracker = FlowLatencyTracker(flowCapacity = 2)
        tracker.report(hit("a.example"))
        tracker.report(hit("b.example"))
        tracker.report(hit("a.example")) // re-touch a → b is now least-recently-updated
        tracker.report(hit("c.example")) // evicts b, not a

        val hosts = tracker.snapshot().map { it.host }
        assertTrue("a.example" in hosts)
        assertFalse("b.example" in hosts)
        assertTrue("c.example" in hosts)
    }

    @Test
    fun `snapshot is most-recently-updated first`() {
        val tracker = FlowLatencyTracker()
        tracker.report(hit("a.example"))
        tracker.report(hit("b.example"))

        assertEquals(listOf("b.example", "a.example"), tracker.snapshot().map { it.host })
    }

    @Test
    fun `unshaped flow reports zero injected delay and clears shaped`() {
        val tracker = FlowLatencyTracker()
        tracker.report(hit("a.example", shaped = false, injectedMs = 0, measuredRttMs = 15))

        val series = tracker.snapshot().single()
        assertFalse(series.shaped)
        assertEquals(0, series.samples.single().injectedMs)
    }
}
