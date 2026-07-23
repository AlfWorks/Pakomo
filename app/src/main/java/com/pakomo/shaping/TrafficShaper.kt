package com.pakomo.shaping

import com.pakomo.core.model.NetworkRule
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.random.Random
import kotlinx.coroutines.delay

enum class TrafficDirection {
    UPLOAD,
    DOWNLOAD,
}

data class ShapingDecision(
    val drop: Boolean,
    val waitNanos: Long,
)

class BandwidthScheduler(private val bytesPerSecond: Long?) {
    private var nextAvailableNanos: Long = 0

    @Synchronized
    fun reserve(byteCount: Int, nowNanos: Long = System.nanoTime()): Long {
        val rate = bytesPerSecond ?: return 0
        if (rate <= 0 || byteCount <= 0) return 0
        val startNanos = max(nowNanos, nextAvailableNanos)
        val durationNanos = ((byteCount.toDouble() / rate) * NANOS_PER_SECOND).toLong()
        nextAvailableNanos = startNanos + durationNanos
        return max(0, startNanos - nowNanos)
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}

class TrafficShaper(
    private val rule: NetworkRule,
    private val random: Random = Random.Default,
) {
    private val upload = BandwidthScheduler(rule.uploadKbps?.kbpsToBytesPerSecond())
    private val download = BandwidthScheduler(rule.downloadKbps?.kbpsToBytesPerSecond())
    private val droppedDatagrams = AtomicLong(0)
    private val delayedTransfers = AtomicLong(0)

    fun decide(
        direction: TrafficDirection,
        byteCount: Int,
        isDatagram: Boolean,
        nowNanos: Long = System.nanoTime(),
    ): ShapingDecision {
        val lossHit = rule.packetLossPercent > 0 &&
            random.nextInt(100) < rule.packetLossPercent
        if (lossHit && (isDatagram || rule.packetLossPercent == 100)) {
            droppedDatagrams.incrementAndGet()
            return ShapingDecision(drop = true, waitNanos = 0)
        }

        val jitter = if (rule.jitterMs == 0) {
            0
        } else {
            random.nextInt(-rule.jitterMs, rule.jitterMs + 1)
        }
        val baseDelayMs = max(0, rule.latencyMs + jitter)
        val syntheticTcpRecoveryMs = if (lossHit && !isDatagram) {
            max(200, rule.latencyMs * 2)
        } else {
            0
        }
        val bandwidthWait = when (direction) {
            TrafficDirection.UPLOAD -> upload.reserve(byteCount, nowNanos)
            TrafficDirection.DOWNLOAD -> download.reserve(byteCount, nowNanos)
        }
        val waitNanos = bandwidthWait +
            (baseDelayMs + syntheticTcpRecoveryMs) * NANOS_PER_MILLISECOND
        if (waitNanos > 0) delayedTransfers.incrementAndGet()
        return ShapingDecision(drop = false, waitNanos = waitNanos)
    }

    suspend fun await(decision: ShapingDecision): Boolean {
        if (decision.drop) return false
        if (decision.waitNanos > 0) {
            val roundedMillis =
                (decision.waitNanos + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND
            delay(roundedMillis)
        }
        return true
    }

    fun droppedCount(): Long = droppedDatagrams.get()

    fun delayedCount(): Long = delayedTransfers.get()

    private fun Int.kbpsToBytesPerSecond(): Long =
        (toLong() * 1_000L / 8L).coerceAtLeast(1)

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
