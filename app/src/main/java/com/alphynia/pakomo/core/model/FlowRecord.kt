package com.alphynia.pakomo.core.model

/** Lifecycle state of a recorded flow. */
enum class FlowStatus { ACTIVE, CLOSED }

/**
 * One connection that passed through Pakomo, surfaced in the diagnostics「流量」page so the user can
 * see and filter what actually went through (protocol, host, size, whether it was shaped / held).
 * An immutable snapshot; the live counters live in the relay's flow handle.
 */
data class FlowRecord(
    val id: Long,
    val startedAtMs: Long,
    val protocol: String,
    val host: String,
    val port: Int,
    val uploadBytes: Long,
    val downloadBytes: Long,
    val shaped: Boolean,
    val held: Boolean,
    val status: FlowStatus,
)
