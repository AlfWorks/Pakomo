package com.pakomo.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class TunnelStatsSamplerTest {
    @Test
    fun `reports per-second rates from cumulative tunnel counters`() {
        val sampler = TunnelStatsSampler()

        sampler.sample(
            counters = TunnelCounters(10, 1_000, 20, 2_000),
            nowNanos = 1_000_000_000,
            activeConnections = 1,
            droppedPackets = 2,
            delayedTransfers = 3,
        )
        val result = sampler.sample(
            counters = TunnelCounters(12, 2_000, 24, 4_000),
            nowNanos = 1_500_000_000,
            activeConnections = 2,
            droppedPackets = 4,
            delayedTransfers = 5,
        )

        assertEquals(2_000, result.uploadBytesPerSecond)
        assertEquals(4_000, result.downloadBytesPerSecond)
        assertEquals(2, result.activeConnections)
        assertEquals(4, result.droppedPackets)
        assertEquals(5, result.delayedPackets)
    }

    @Test
    fun `does not emit negative rates when native counters reset`() {
        val sampler = TunnelStatsSampler()
        sampler.sample(
            counters = TunnelCounters(10, 10_000, 10, 10_000),
            nowNanos = 1_000_000_000,
            activeConnections = 0,
            droppedPackets = 0,
            delayedTransfers = 0,
        )

        val result = sampler.sample(
            counters = TunnelCounters(0, 0, 0, 0),
            nowNanos = 2_000_000_000,
            activeConnections = 0,
            droppedPackets = 0,
            delayedTransfers = 0,
        )

        assertEquals(0, result.uploadBytesPerSecond)
        assertEquals(0, result.downloadBytesPerSecond)
    }
}
