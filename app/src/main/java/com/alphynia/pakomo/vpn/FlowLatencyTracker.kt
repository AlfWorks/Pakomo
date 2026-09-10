package com.alphynia.pakomo.vpn

import com.alphynia.pakomo.core.model.FlowLatencySample
import com.alphynia.pakomo.core.model.FlowLatencySeries
import com.alphynia.pakomo.forwarding.FlowLatencyHit
import com.alphynia.pakomo.forwarding.FlowLatencyReporter

/**
 * Collects per-flow latency observations so the quick-control overlay can chart how each flow's
 * latency moves over time and whether the active rule's shaping is taking effect. Observations are
 * grouped by destination host + owning app; each group keeps a bounded, oldest-first ring of the
 * newest [samplesPerFlow] samples, and the tracker keeps the [flowCapacity] most-recently-active
 * flows. Bounded on both axes so a long session can't grow it without limit.
 */
class FlowLatencyTracker(
    private val flowCapacity: Int = DEFAULT_FLOW_CAPACITY,
    private val samplesPerFlow: Int = DEFAULT_SAMPLES_PER_FLOW,
) : FlowLatencyReporter {
    private class Series(val host: String, val packageName: String?) {
        var shaped: Boolean = false
        val samples = ArrayDeque<FlowLatencySample>()
    }

    // Access-ordered so the least-recently-updated flow is evicted first once the map is full.
    private val flows = object : LinkedHashMap<String, Series>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Series>): Boolean =
            size > flowCapacity
    }

    @Synchronized
    override fun report(hit: FlowLatencyHit) {
        val key = "${hit.packageName ?: "?"}|${hit.host}"
        val series = flows.getOrPut(key) { Series(hit.host, hit.packageName) }
        series.shaped = hit.shaped
        series.samples.addLast(
            FlowLatencySample(
                atMs = System.currentTimeMillis(),
                injectedMs = hit.injectedMs,
                measuredRttMs = hit.measuredRttMs,
            ),
        )
        while (series.samples.size > samplesPerFlow) series.samples.removeFirst()
    }

    /** Most-recently-active-first snapshot of the tracked per-flow latency series. */
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
        const val DEFAULT_SAMPLES_PER_FLOW = 30
    }
}
