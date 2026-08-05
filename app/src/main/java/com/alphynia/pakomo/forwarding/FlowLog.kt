package com.alphynia.pakomo.forwarding

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
) {
    val up = AtomicLong(0)
    val down = AtomicLong(0)

    @Volatile var shaped: Boolean = false
    @Volatile var held: Boolean = false
    @Volatile internal var closed: Boolean = false

    fun close() {
        closed = true
    }

    internal fun snapshot(): FlowRecord = FlowRecord(
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
    )
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

    fun open(protocol: String, host: String, port: Int): FlowHandle {
        val handle = FlowHandle(
            id = nextId.getAndIncrement(),
            startedAtMs = System.currentTimeMillis(),
            protocol = protocol,
            host = host,
            port = port,
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
