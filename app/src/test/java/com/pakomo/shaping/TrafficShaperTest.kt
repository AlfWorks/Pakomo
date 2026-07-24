package com.pakomo.shaping

import com.pakomo.core.model.defaultRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficShaperTest {
    @Test
    fun bandwidthScheduler_serializesReservationsAtConfiguredRate() {
        val scheduler = BandwidthScheduler(bytesPerSecond = 1_000)

        assertEquals(0, scheduler.reserve(byteCount = 1_000, nowNanos = 1_000))
        assertEquals(
            1_000_000_000L,
            scheduler.reserve(byteCount = 1_000, nowNanos = 1_000),
        )
    }

    @Test
    fun offlineRuleDropsTcpAndUdp() {
        val shaper = TrafficShaper(defaultRules.first { it.id == "offline" })

        assertTrue(shaper.blocksAllTraffic())
        assertTrue(
            shaper.decide(TrafficDirection.UPLOAD, 100, isDatagram = true).drop,
        )
        assertTrue(
            shaper.decide(TrafficDirection.DOWNLOAD, 100, isDatagram = false).drop,
        )
    }

    @Test
    fun normalRuleDoesNotDelayOrDrop() {
        val shaper = TrafficShaper(defaultRules.first { it.id == "normal" })

        assertFalse(shaper.blocksAllTraffic())
        val decision = shaper.decide(
            direction = TrafficDirection.DOWNLOAD,
            byteCount = 16_384,
            isDatagram = false,
            nowNanos = 1_000,
        )

        assertFalse(decision.drop)
        assertEquals(0, decision.waitNanos)
    }
}
