package com.alphynia.pakomo.forwarding

import com.alphynia.pakomo.core.model.DnsQuery
import com.alphynia.pakomo.core.model.FlowRecord
import com.alphynia.pakomo.core.model.FlowStatus
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A live per-connection flow record. The relay increments [up]/[down] as bytes move and flips
 * [held] when Response Hold actually delays a downstream chunk (so it reflects reality, not just the
 * rule's intent — a small response let through by the bypass stays unheld). [close] marks it done.
 */
class FlowHandle internal constructor(
    val id: Long,
    val startedAtMs: Long,
    val protocol: String,
    val host: String,
    val port: Int,
    val pkg: String,
    val sourceIp: String,
    val sourcePort: Int,
    val destIp: String,
) {
    val up = AtomicLong(0)
    val down = AtomicLong(0)

    @Volatile var shaped: Boolean = false
    @Volatile var held: Boolean = false
    @Volatile internal var closed: Boolean = false
    @Volatile private var closedAtMs: Long = 0

    // Domains queried over this connection (DNS flows only) mapped to the IPs they resolved to;
    // insertion-ordered, deduped and bounded. A single DNS association carries many lookups, so the
    // detail view lists them (with results) here.
    private val queried = LinkedHashMap<String, MutableSet<String>>()

    /** Record a domain queried over this (DNS) connection. Thread-safe; ignores blanks; bounded. */
    fun addQueriedName(name: String) {
        if (name.isEmpty()) return
        synchronized(queried) {
            if (name !in queried && queried.size >= MAX_QUERIED_NAMES) return
            queried.getOrPut(name) { LinkedHashSet() }
        }
    }

    /** Attach resolved answers (name → IP) observed on this connection's DNS responses. */
    fun addResolvedAnswers(answers: List<Pair<String, String>>) {
        if (answers.isEmpty()) return
        synchronized(queried) {
            for ((name, ip) in answers) {
                if (name.isEmpty() || ip.isEmpty()) continue
                if (name !in queried && queried.size >= MAX_QUERIED_NAMES) continue
                val ips = queried.getOrPut(name) { LinkedHashSet() }
                if (ips.size < MAX_IPS_PER_NAME) ips.add(ip)
            }
        }
    }

    // A closed flow never changes again, so its snapshot is built once and reused. This keeps each
    // per-second pulse from re-allocating a FlowRecord for every historical (closed) connection —
    // only the handful of still-active flows are rebuilt — cutting the GC churn that caused the
    // traffic list to hitch while scrolling.
    @Volatile private var closedSnapshot: FlowRecord? = null

    fun close() {
        closedAtMs = System.currentTimeMillis()
        closed = true
    }

    internal fun snapshot(): FlowRecord {
        closedSnapshot?.let { return it }
        val snap = FlowRecord(
            id = id,
            startedAtMs = startedAtMs,
            protocol = protocol,
            host = host,
            port = port,
            uploadBytes = up.get(),
            downloadBytes = down.get(),
            shaped = shaped,
            held = held,
            status = if (closed) FlowStatus.CLOSED else FlowStatus.ACTIVE,
            pkg = pkg,
            sourceIp = sourceIp,
            sourcePort = sourcePort,
            destIp = destIp,
            closedAtMs = closedAtMs,
            dnsQueries = synchronized(queried) {
                if (queried.isEmpty()) emptyList() else queried.map { (name, ips) -> DnsQuery(name, ips.toList()) }
            },
        )
        // Freeze the first snapshot taken after close (bytes are final once the relay stops writing).
        if (closed) closedSnapshot = snap
        return snap
    }

    private companion object {
        const val MAX_QUERIED_NAMES = 64
        const val MAX_IPS_PER_NAME = 16
    }
}

/**
 * Records connections passing through the relay so the diagnostics「流量」page can list and filter
 * them. A bounded ring keeps the newest [MAX_ENTRIES]; the UI reads [flows]. Snapshots are emitted on
 * [pulse] (called ~once per second by the service) and [clear], so live byte counts refresh without
 * emitting on every connection open/close under churn.
 */
object FlowLog {
    private const val MAX_ENTRIES = 400

    private val lock = Any()
    private val entries = ArrayDeque<FlowHandle>()
    private val nextId = AtomicLong(1)

    private val _flows = MutableStateFlow<List<FlowRecord>>(emptyList())
    val flows: StateFlow<List<FlowRecord>> = _flows.asStateFlow()

    fun open(
        protocol: String,
        host: String,
        port: Int,
        pkg: String = "",
        sourceIp: String = "",
        sourcePort: Int = 0,
        destIp: String = "",
    ): FlowHandle {
        val handle = FlowHandle(
            id = nextId.getAndIncrement(),
            startedAtMs = System.currentTimeMillis(),
            protocol = protocol,
            host = host,
            port = port,
            pkg = pkg,
            sourceIp = sourceIp,
            sourcePort = sourcePort,
            destIp = destIp,
        )
        synchronized(lock) {
            entries.addLast(handle)
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
        }
        return handle
    }

    /** Re-emit a snapshot (newest first) so active flows' live byte counts refresh. */
    fun pulse() = emit()

    fun clear() {
        synchronized(lock) {
            entries.clear()
            nextId.set(1)
        }
        emit()
    }

    private fun emit() {
        _flows.value = synchronized(lock) { entries.reversed().map { it.snapshot() } }
    }
}
