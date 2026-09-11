package com.alphynia.pakomo.vpn

import com.alphynia.pakomo.core.model.FlowLatencySample
import com.alphynia.pakomo.core.model.FlowLatencySeries
import com.alphynia.pakomo.forwarding.FlowLatencyHit
import com.alphynia.pakomo.forwarding.FlowLatencyReporter

/**
 * Builds a live, rolling per-flow latency time series for the quick-control overlay chart. Flows are
 * grouped by destination host + owning app. [report] only records a flow's newest measurement (the
 * real handshake RTT and the shaper's injected delay); the actual time series is produced by [tick],
 * which the stats loop calls on a fixed cadence and which appends one held sample per still-active
 * flow. That regular cadence — rather than one point per connection — is what makes each flow a
 * continuous curve that scrolls smoothly, holding its last RTT between connections.
 *
 * Bounded on every axis: only the last [windowMs] of samples are kept (older ones scroll off), a flow
 * stops being sampled [activeAfterIdleMs] after its last connection and is dropped once it has no
 * samples left, and at most [flowCapacity] flows are tracked (least-recently-connected evicted).
 */
class FlowLatencyTracker(
    private val flowCapacity: Int = DEFAULT_FLOW_CAPACITY,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val activeAfterIdleMs: Long = DEFAULT_ACTIVE_AFTER_IDLE_MS,
    private val maxSamplesPerFlow: Int = DEFAULT_MAX_SAMPLES_PER_FLOW,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : FlowLatencyReporter {
    private class Series(val host: String, val packageName: String?) {
        var shaped: Boolean = false
        var lastRtt: Int = 0
        var lastInjected: Int = 0
        var lastConnAtMs: Long = 0
        val samples = ArrayDeque<FlowLatencySample>()
    }

    // Access-ordered so the least-recently-connected flow is evicted first once the map is full.
    private val flows = object : LinkedHashMap<String, Series>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Series>): Boolean =
            size > flowCapacity
    }

    /** Records a flow's newest measurement. The sample itself is appended on the next [tick]. */
    @Synchronized
    override fun report(hit: FlowLatencyHit) {
        val key = "${hit.packageName ?: "?"}|${hit.host}"
        val series = flows.getOrPut(key) { Series(hit.host, hit.packageName) }
        series.shaped = hit.shaped
        series.lastRtt = hit.measuredRttMs
        series.lastInjected = hit.injectedMs
        series.lastConnAtMs = clock()
    }

    /**
     * Appends one held sample per still-active flow (its last connection within [activeAfterIdleMs]),
     * prunes samples older than [windowMs], and drops flows that have aged out entirely. Called on the
     * stats cadence so the series advance at a regular interval.
     */
    @Synchronized
    fun tick(nowMs: Long) {
        val iterator = flows.values.iterator()
        while (iterator.hasNext()) {
            val series = iterator.next()
            if (nowMs - series.lastConnAtMs <= activeAfterIdleMs) {
                series.samples.addLast(
                    FlowLatencySample(atMs = nowMs, injectedMs = series.lastInjected, measuredRttMs = series.lastRtt),
                )
            }
            while (series.samples.isNotEmpty() && nowMs - series.samples.first().atMs > windowMs) {
                series.samples.removeFirst()
            }
            while (series.samples.size > maxSamplesPerFlow) series.samples.removeFirst()
            if (series.samples.isEmpty()) iterator.remove()
        }
    }

    /** Most-recently-connected-first snapshot of the tracked per-flow latency series. */
    @Synchronized
    fun snapshot(): List<FlowLatencySeries> =
        flows.values.map { series ->
            FlowLatencySeries(
                host = series.host,
                packageName = series.packageName,
                appLabel = null,
                shaped = series.shaped,
                samples = series.samples.toList(),
            )
        }.asReversed()

    private companion object {
        const val DEFAULT_FLOW_CAPACITY = 16
        // A little beyond the chart's visible span so a sample scrolls off the left edge before it is
        // pruned here (otherwise the tail would vanish mid-plot instead of exiting cleanly).
        const val DEFAULT_WINDOW_MS = 66_000L
        const val DEFAULT_ACTIVE_AFTER_IDLE_MS = 12_000L
        const val DEFAULT_MAX_SAMPLES_PER_FLOW = 240
    }
}
