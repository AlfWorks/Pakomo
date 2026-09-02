package com.alphynia.pakomo.forwarding

import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Test

/** Draw-down math for the per-connection latency-compensation credit ([drawDownCompensation]). */
class LatencyCompensationTest {

    @Test
    fun nullCreditLeavesDelayUnchanged() {
        assertEquals(100L, drawDownCompensation(null, 100L))
    }

    @Test
    fun nonPositiveDelayIsUnchanged() {
        val credit = AtomicLong(500L)
        assertEquals(0L, drawDownCompensation(credit, 0L))
        assertEquals(-1L, drawDownCompensation(credit, -1L))
        assertEquals(500L, credit.get()) // credit untouched
    }

    @Test
    fun creditCoveringTheDelayFloorsItToZero() {
        val credit = AtomicLong(300L)
        assertEquals(0L, drawDownCompensation(credit, 100L))
        assertEquals(200L, credit.get())
    }

    @Test
    fun creditSmallerThanDelayIsPartiallyApplied() {
        val credit = AtomicLong(40L)
        assertEquals(60L, drawDownCompensation(credit, 100L)) // 100 - 40
        assertEquals(0L, credit.get())
    }

    @Test
    fun onceCreditIsSpentTheSteadyStreamIsUnaffected() {
        // credit = 300, four chunks of 100ns each: first three fully absorbed, the fourth pays full.
        val credit = AtomicLong(300L)
        assertEquals(0L, drawDownCompensation(credit, 100L))
        assertEquals(0L, drawDownCompensation(credit, 100L))
        assertEquals(0L, drawDownCompensation(credit, 100L))
        assertEquals(100L, drawDownCompensation(credit, 100L)) // credit exhausted → full delay
        assertEquals(0L, credit.get())
    }
}
