package com.alphynia.pakomo.forwarding

/**
 * A single flow-latency observation reported by the relay when a new connection is established:
 * the destination [host], the owning app [packageName] (best-effort, may be null), whether the flow
 * is being shaped, the shaper's configured round-trip injected delay [injectedMs] (0 when unshaped),
 * and the real outbound TCP handshake round-trip [measuredRttMs].
 */
data class FlowLatencyHit(
    val host: String,
    val packageName: String?,
    val shaped: Boolean,
    val injectedMs: Int,
    val measuredRttMs: Int,
)

/** Receives per-connection latency observations for the overlay's latency chart. Thread-safe. */
fun interface FlowLatencyReporter {
    fun report(hit: FlowLatencyHit)
}
