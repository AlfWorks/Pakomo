package com.alphynia.pakomo.core.model

/** Lifecycle state of a recorded flow. */
enum class FlowStatus { ACTIVE, CLOSED }

/** One domain looked up over a DNS flow, with the IPv4 addresses it resolved to (empty if unseen). */
data class DnsQuery(val name: String, val ips: List<String> = emptyList())

/**
 * One connection that passed through Pakomo, surfaced in the diagnostics「流量」page so the user can
 * see and filter what actually went through (protocol, host, size, whether it was shaped / held).
 * An immutable snapshot; the live counters live in the relay's flow handle. All fields are `val`s of
 * stable types, so the Compose compiler already treats it as stable and the traffic list's unchanged
 * (closed) rows skip recomposition.
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
    /** Owning app package for the source label; empty when unknown / unattributed. */
    val pkg: String = "",
    /** Originating app socket address (from the connection origin); empty when unknown. */
    val sourceIp: String = "",
    val sourcePort: Int = 0,
    /** Raw destination IP, kept alongside [host] so the detail view can show both name and address. */
    val destIp: String = "",
    /** When the flow closed (epoch ms); 0 while still active. Used for the duration in the details. */
    val closedAtMs: Long = 0,
    /** Domains queried over this connection (DNS flows only) with their resolved IPs; else empty. */
    val dnsQueries: List<DnsQuery> = emptyList(),
)
