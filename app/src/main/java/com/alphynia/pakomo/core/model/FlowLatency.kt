package com.alphynia.pakomo.core.model

/**
 * One latency observation for a flow, taken when a new connection to the flow's destination is
 * established. Two paired signals so the overlay chart can tell shaping apart from the real network:
 * [injectedMs] is the synthetic round-trip delay the shaper adds (deterministic, straight from the
 * active rule — the "shaping is really acting on this flow" signal), and [measuredRttMs] is the real
 * outbound TCP handshake round-trip to the destination (genuine network latency a direct connection
 * would pay too).
 */
data class FlowLatencySample(
    val atMs: Long,
    val injectedMs: Int,
    val measuredRttMs: Int,
)

/**
 * A per-flow latency trend: the recent [samples] (oldest first) observed for one destination host,
 * grouped by the owning app. Emitted in [RuntimeStats.flowLatencies] so the quick-control overlay can
 * plot how a flow's latency moves over time and whether the active rule's shaping is taking effect.
 */
data class FlowLatencySeries(
    val host: String,
    val packageName: String?,
    val appLabel: String?,
    val shaped: Boolean,
    val samples: List<FlowLatencySample>,
)
