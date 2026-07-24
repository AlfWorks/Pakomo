package com.pakomo.forwarding

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
 * the queue, updates interest ops, and resumes continuations. No locks are needed on the
 * registration state because it is touched by one thread.
 */
class NioSelectorLoop : AutoCloseable {
    private val selector: Selector = Selector.open()
    private val pending = ConcurrentLinkedQueue<Runnable>()

    @Volatile
    private var running = true

    private val thread = Thread(::loop, "pakomo-nio-relay").apply {
        isDaemon = true
        start()
    }

    private class Registration {
        var readCont: CancellableContinuation<Unit>? = null
        var writeCont: CancellableContinuation<Unit>? = null
        var readDeadline: Long = Long.MAX_VALUE
        var writeDeadline: Long = Long.MAX_VALUE
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
            pending.add(Runnable {
                try {
                    val key = channel.keyFor(selector)
                    val reg = (key?.attachment() as? Registration) ?: Registration()
                    if (op == SelectionKey.OP_READ) {
                        reg.readCont = cont
                        reg.readDeadline = deadline
                    } else {
                        reg.writeCont = cont
                        reg.writeDeadline = deadline
                    }
                    val ops = interestOf(reg)
                    if (key == null || !key.isValid) {
                        channel.register(selector, ops, reg)
                    } else {
                        key.interestOps(ops)
                        key.attach(reg)
                    }
                } catch (error: Throwable) {
                    if (cont.isActive) cont.resumeWithException(error)
                }
            })
            cont.invokeOnCancellation {
                pending.add(Runnable {
                    val key = channel.keyFor(selector) ?: return@Runnable
                    val reg = key.attachment() as? Registration ?: return@Runnable
                    if (op == SelectionKey.OP_READ) reg.readCont = null else reg.writeCont = null
                    updateInterest(key, reg)
                })
                selector.wakeup()
            }
            selector.wakeup()
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
                    if (ready and SelectionKey.OP_READ != 0) {
                        reg.readCont?.let { c -> reg.readCont = null; reg.readDeadline = Long.MAX_VALUE; if (c.isActive) c.resume(Unit) }
                    }
                    if (ready and SelectionKey.OP_WRITE != 0) {
                        reg.writeCont?.let { c -> reg.writeCont = null; reg.writeDeadline = Long.MAX_VALUE; if (c.isActive) c.resume(Unit) }
                    }
                    updateInterest(key, reg)
                }

                // Idle/backpressure timeouts.
                for (key in selector.keys()) {
                    val reg = key.attachment() as? Registration ?: continue
                    if (reg.readCont != null && reg.readDeadline <= now) {
                        val c = reg.readCont
                        reg.readCont = null
                        reg.readDeadline = Long.MAX_VALUE
                        c?.let { if (it.isActive) it.resumeWithException(SocketTimeoutException("relay read idle")) }
                    }
                    if (reg.writeCont != null && reg.writeDeadline <= now) {
                        val c = reg.writeCont
                        reg.writeCont = null
                        reg.writeDeadline = Long.MAX_VALUE
                        c?.let { if (it.isActive) it.resumeWithException(SocketTimeoutException("relay write stalled")) }
                    }
                    runCatching { updateInterest(key, reg) }
                }
            }
        } catch (_: IOException) {
            // selector closed
        } catch (_: CancelledKeyException) {
            // key cancelled concurrently with close; reactor is shutting down
        } finally {
            failAll()
        }
    }

    private fun failAll() {
        for (key in runCatching { selector.keys() }.getOrNull().orEmpty()) {
            val reg = key.attachment() as? Registration ?: continue
            reg.readCont?.let { if (it.isActive) it.resumeWithException(IOException("relay closed")) }
            reg.writeCont?.let { if (it.isActive) it.resumeWithException(IOException("relay closed")) }
        }
        runCatching { selector.close() }
    }

    override fun close() {
        running = false
        selector.wakeup()
    }

    private companion object {
        const val SELECT_TIMEOUT_MS = 1_000L
    }
}
