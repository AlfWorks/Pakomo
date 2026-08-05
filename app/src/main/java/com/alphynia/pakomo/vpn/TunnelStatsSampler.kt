package com.alphynia.pakomo.vpn

import com.alphynia.pakomo.core.model.RuntimeStats

data class TunnelCounters(
    val uploadPackets: Long,
    val uploadBytes: Long,
    val downloadPackets: Long,
    val downloadBytes: Long,
)

class TunnelStatsSampler {
    private var previous: Sample? = null

    fun sample(
        counters: TunnelCounters,
        nowNanos: Long,
        activeConnections: Int,
        droppedTransfers: Long,
        delayedTransfers: Long,
    ): RuntimeStats {
        val current = Sample(counters, nowNanos)
        val last = previous
        previous = current
        if (last == null || nowNanos <= last.nowNanos) {
            return RuntimeStats(
                activeConnections = activeConnections,
                droppedTransfers = droppedTransfers,
                delayedTransfers = delayedTransfers,
            )
        }

        val elapsedNanos = nowNanos - last.nowNanos
        return RuntimeStats(
            uploadBytesPerSecond = rate(
                current = counters.uploadBytes,
                previous = last.counters.uploadBytes,
                elapsedNanos = elapsedNanos,
            ),
            downloadBytesPerSecond = rate(
                current = counters.downloadBytes,
                previous = last.counters.downloadBytes,
                elapsedNanos = elapsedNanos,
            ),
            activeConnections = activeConnections,
            droppedTransfers = droppedTransfers,
            delayedTransfers = delayedTransfers,
        )
    }

    private fun rate(current: Long, previous: Long, elapsedNanos: Long): Long {
        val bytes = (current - previous).coerceAtLeast(0)
        return (bytes.toDouble() * NANOS_PER_SECOND / elapsedNanos).toLong()
    }

    private data class Sample(
        val counters: TunnelCounters,
        val nowNanos: Long,
    )

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}
