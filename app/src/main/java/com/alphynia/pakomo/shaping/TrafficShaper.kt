package com.alphynia.pakomo.shaping

import com.alphynia.pakomo.core.model.NetworkRule
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
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
        val lossPercent = lossPercentFor(direction)
        val latencyMs = latencyMsFor(direction)
        val jitterMs = jitterMsFor(direction)

        val lossHit = lossPercent > 0 && random.nextInt(100) < lossPercent
        if (lossHit && (isDatagram || lossPercent == 100)) {
            droppedDatagrams.incrementAndGet()
            return ShapingDecision(drop = true, waitNanos = 0)
        }

        val jitter = if (jitterMs == 0) {
            0
        } else {
            random.nextInt(-jitterMs, jitterMs + 1)
        }
        val baseDelayMs = max(0, latencyMs + jitter)
        val syntheticTcpRecoveryMs = if (lossHit && !isDatagram) {
            max(200, latencyMs * 2)
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

    fun blocksAllTraffic(): Boolean =
        lossPercentFor(TrafficDirection.UPLOAD) >= 100 ||
            lossPercentFor(TrafficDirection.DOWNLOAD) >= 100

    /** True when the shaper applies no latency, jitter, loss or bandwidth limit in either direction. */
    fun isNoOp(): Boolean =
        rule.uploadKbps == null && rule.downloadKbps == null &&
            lossPercentFor(TrafficDirection.UPLOAD) == 0 && lossPercentFor(TrafficDirection.DOWNLOAD) == 0 &&
            latencyMsFor(TrafficDirection.UPLOAD) == 0 && latencyMsFor(TrafficDirection.DOWNLOAD) == 0 &&
            jitterMsFor(TrafficDirection.UPLOAD) == 0 && jitterMsFor(TrafficDirection.DOWNLOAD) == 0

    private fun latencyMsFor(direction: TrafficDirection): Int = when {
        !rule.advanced -> rule.latencyMs / 2
        direction == TrafficDirection.UPLOAD -> rule.uploadLatencyMs
        else -> rule.downloadLatencyMs
    }

    private fun jitterMsFor(direction: TrafficDirection): Int = when {
        !rule.advanced -> rule.jitterMs / 2
        direction == TrafficDirection.UPLOAD -> rule.uploadJitterMs
        else -> rule.downloadJitterMs
    }

    private fun lossPercentFor(direction: TrafficDirection): Int = when {
        !rule.advanced -> splitLoss(rule.packetLossPercent)
        direction == TrafficDirection.UPLOAD -> rule.uploadLossPercent
        else -> rule.downloadLossPercent
    }

    /**
     * Per-direction loss so the round-trip loss matches the simple value: with independent
     * per-direction drops, total = 1 - (1 - p_dir)^2, so p_dir = 1 - sqrt(1 - p).
     */
    private fun splitLoss(totalPercent: Int): Int = when {
        totalPercent <= 0 -> 0
        totalPercent >= 100 -> 100
        else -> (100.0 * (1.0 - sqrt(1.0 - totalPercent / 100.0))).roundToInt()
    }

    private fun Int.kbpsToBytesPerSecond(): Long =
        (toLong() * 1_000L / 8L).coerceAtLeast(1)

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
