package com.alphynia.pakomo.forwarding

import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.channels.CancelledKeyException
import java.nio.channels.SelectableChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * A single-threaded NIO reactor that lets coroutines await socket readability/writability
 * without parking a thread. This replaces the previous blocking-read relay, where every TCP
 * session held two blocking `Dispatchers.IO` threads and long-lived connections quickly
 * exhausted the pool (SOCKS connected, but relayed 0 bytes).
 *
 * Correctness invariant: every [SelectionKey]/[Registration] mutation happens **only** on the
 * reactor thread. Coroutines merely enqueue a task and wake the selector; the reactor drains
 * the queue, updates interest ops, and resumes continuations.
 *
 * Every awaiting continuation is always resumed exactly once, including on teardown:
 * - the reactor keeps its own [registrations] map (not just selector keys), because the selector
 *   drops a key the moment its channel is closed — so an awaiter whose channel is closed underneath
 *   it would otherwise be lost. A maintenance sweep fails such awaiters with an IOException.
 * - [close] flips `running` under [lifecycle] so no task is enqueued into a dead loop, then the
 *   reactor's shutdown drains any queued registrations (each self-fails) and fails everything still
 *   registered. [await] called after close resumes immediately with an error rather than hanging.
 */
class NioSelectorLoop : AutoCloseable {
    private val selector: Selector = Selector.open()
    private val pending = ConcurrentLinkedQueue<Runnable>()

    // Orders await()'s enqueue against close(): a task is placed on [pending] only while running is
    // still true, which guarantees the reactor (in its loop or its shutdown drain) will run it.
    private val lifecycle = Any()

    @Volatile
    private var running = true

    // Reactor-thread-only. Channels with at least one waiting continuation. Outlives selector keys so
    // teardown and channel-close can reach every awaiter.
    private val registrations = HashMap<SelectableChannel, Registration>()

    private val thread = Thread(::loop, "pakomo-nio-relay").apply {
        isDaemon = true
        start()
    }

    private class Registration(val channel: SelectableChannel) {
        var readCont: CancellableContinuation<Unit>? = null
        var writeCont: CancellableContinuation<Unit>? = null
        var readDeadline: Long = Long.MAX_VALUE
        var writeDeadline: Long = Long.MAX_VALUE
        fun isEmpty(): Boolean = readCont == null && writeCont == null
    }

    /** Reads once into [buffer] (non-blocking), suspending until readable; returns bytes, or -1 at EOF. */
    suspend fun read(channel: SocketChannel, buffer: ByteBuffer, idleTimeoutMs: Long): Int {
        while (true) {
            val count = channel.read(buffer)
            if (count != 0) return count
            await(channel, SelectionKey.OP_READ, idleTimeoutMs)
        }
    }

    /** Writes the whole remaining [buffer] (non-blocking), suspending on backpressure. */
    suspend fun writeFully(channel: SocketChannel, buffer: ByteBuffer, timeoutMs: Long) {
        while (buffer.hasRemaining()) {
            if (channel.write(buffer) == 0) {
                await(channel, SelectionKey.OP_WRITE, timeoutMs)
            }
        }
    }

    private suspend fun await(channel: SelectableChannel, op: Int, timeoutMs: Long) =
        suspendCancellableCoroutine { cont: CancellableContinuation<Unit> ->
            val deadline = if (timeoutMs > 0) System.currentTimeMillis() + timeoutMs else Long.MAX_VALUE
            // Enqueue only while the reactor is alive; otherwise nothing would ever run the task.
            val enqueued = synchronized(lifecycle) {
                if (!running) return@synchronized false
                pending.add(Runnable { register(channel, op, cont, deadline) })
                true
            }
            if (!enqueued) {
                cont.resumeWithException(IOException("relay closed"))
                return@suspendCancellableCoroutine
            }
            cont.invokeOnCancellation {
                pending.add(Runnable {
                    val reg = registrations[channel] ?: return@Runnable
                    if (op == SelectionKey.OP_READ) { reg.readCont = null; reg.readDeadline = Long.MAX_VALUE }
                    else { reg.writeCont = null; reg.writeDeadline = Long.MAX_VALUE }
                    channel.keyFor(selector)?.let { updateInterest(it, reg) }
                    if (reg.isEmpty()) registrations.remove(channel)
                })
                selector.wakeup()
            }
            selector.wakeup()
        }

    /** Reactor thread only: bind [cont] to [channel]'s interest, or fail it if the reactor is gone. */
    private fun register(channel: SelectableChannel, op: Int, cont: CancellableContinuation<Unit>, deadline: Long) {
        if (!running) {
            if (cont.isActive) cont.resumeWithException(IOException("relay closed"))
            return
        }
        try {
            val reg = registrations.getOrPut(channel) { Registration(channel) }
            if (op == SelectionKey.OP_READ) {
                reg.readCont = cont
                reg.readDeadline = deadline
            } else {
                reg.writeCont = cont
                reg.writeDeadline = deadline
            }
            val ops = interestOf(reg)
            val key = channel.keyFor(selector)
            if (key == null || !key.isValid) {
                channel.register(selector, ops, reg)
            } else {
                key.interestOps(ops)
                key.attach(reg)
            }
        } catch (error: Throwable) {
            registrations.remove(channel)
            if (cont.isActive) cont.resumeWithException(error)
        }
    }

    private fun interestOf(reg: Registration): Int =
        (if (reg.readCont != null) SelectionKey.OP_READ else 0) or
            (if (reg.writeCont != null) SelectionKey.OP_WRITE else 0)

    private fun updateInterest(key: SelectionKey, reg: Registration) {
        if (!key.isValid) return
        // Never cancel the key for "no interest": a pump re-awaits immediately, and a cancelled
        // key that has not yet been flushed by select() makes the next register() throw
        // CancelledKeyException. Leave the key registered with 0 interest; the JDK cancels it
        // automatically when the channel is closed.
        runCatching { key.interestOps(interestOf(reg)) }
    }

    private fun loop() {
        try {
            while (running) {
                while (true) {
                    (pending.poll() ?: break).run()
                }
                selector.select(SELECT_TIMEOUT_MS)
                if (!running) break
                val now = System.currentTimeMillis()

                val selected = selector.selectedKeys().iterator()
                while (selected.hasNext()) {
                    val key = selected.next()
                    selected.remove()
                    val reg = key.attachment() as? Registration ?: continue
                    val ready = runCatching { key.readyOps() }.getOrDefault(0)
                    if (ready and SelectionKey.OP_READ != 0) resumeReady(reg, read = true)
                    if (ready and SelectionKey.OP_WRITE != 0) resumeReady(reg, read = false)
                    updateInterest(key, reg)
                    if (reg.isEmpty()) registrations.remove(reg.channel)
                }

                sweep(now)
            }
        } catch (_: IOException) {
            // selector closed
        } catch (_: CancelledKeyException) {
            // key cancelled concurrently with close; reactor is shutting down
        } finally {
            shutdown()
        }
    }

    private fun resumeReady(reg: Registration, read: Boolean) {
        val cont = if (read) reg.readCont else reg.writeCont
        if (read) { reg.readCont = null; reg.readDeadline = Long.MAX_VALUE }
        else { reg.writeCont = null; reg.writeDeadline = Long.MAX_VALUE }
        cont?.let { if (it.isActive) it.resume(Unit) }
    }

    /**
     * Reactor thread only. Fails awaiters whose channel was closed underneath them (the selector has
     * already dropped such keys, so only this map still reaches them) and fires idle/backpressure
     * deadlines. Runs each select cycle.
     */
    private fun sweep(now: Long) {
        if (registrations.isEmpty()) return
        val iterator = registrations.values.iterator()
        while (iterator.hasNext()) {
            val reg = iterator.next()
            if (!reg.channel.isOpen) {
                failRegistration(reg, IOException("channel closed"))
                iterator.remove()
                continue
            }
            if (reg.readCont != null && reg.readDeadline <= now) {
                val cont = reg.readCont
                reg.readCont = null
                reg.readDeadline = Long.MAX_VALUE
                cont?.let { if (it.isActive) it.resumeWithException(SocketTimeoutException("relay read idle")) }
            }
            if (reg.writeCont != null && reg.writeDeadline <= now) {
                val cont = reg.writeCont
                reg.writeCont = null
                reg.writeDeadline = Long.MAX_VALUE
                cont?.let { if (it.isActive) it.resumeWithException(SocketTimeoutException("relay write stalled")) }
            }
            reg.channel.keyFor(selector)?.let { updateInterest(it, reg) }
            if (reg.isEmpty()) iterator.remove()
        }
    }

    private fun failRegistration(reg: Registration, error: Throwable) {
        reg.readCont?.let { reg.readCont = null; if (it.isActive) it.resumeWithException(error) }
        reg.writeCont?.let { reg.writeCont = null; if (it.isActive) it.resumeWithException(error) }
    }

    /** Reactor thread only, on exit: run queued registrations (each self-fails) and fail all waiters. */
    private fun shutdown() {
        // A registration still in the queue self-fails because running is already false. Draining it
        // here (rather than dropping the queue) is what closes the "await raced with close" hole.
        while (true) {
            (pending.poll() ?: break).run()
        }
        for (reg in registrations.values) failRegistration(reg, IOException("relay closed"))
        registrations.clear()
        runCatching { selector.close() }
    }

    override fun close() {
        synchronized(lifecycle) { running = false }
        selector.wakeup()
    }

    private companion object {
        const val SELECT_TIMEOUT_MS = 1_000L
    }
}
