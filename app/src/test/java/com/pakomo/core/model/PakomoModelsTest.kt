package com.pakomo.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PakomoModelsTest {
    @Test
    fun defaultRules_haveUniqueIdsAndValidRanges() {
        assertEquals(defaultRules.size, defaultRules.map { it.id }.toSet().size)
        defaultRules.forEach { rule ->
            assertTrue(rule.latencyMs >= 0)
            assertTrue(rule.jitterMs >= 0)
            assertTrue(rule.packetLossPercent in 0..100)
        }
    }

    @Test
    fun summary_containsQuantifiedValues() {
        val medium = defaultRules.first { it.id == "medium" }

        assertEquals(
            "300ms · 抖动 100ms · 丢包 5% · 512/128 Kbps",
            medium.summary(AppLanguage.ZH),
        )
        assertEquals(
            "300ms · jitter 100ms · loss 5% · 512/128 Kbps",
            medium.summary(AppLanguage.EN),
        )
    }
}
