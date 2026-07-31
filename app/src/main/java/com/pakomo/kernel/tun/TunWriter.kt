package com.pakomo.kernel.tun

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.FileDescriptor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class TunWriter(
    private val tunFd: FileDescriptor,
    private val engineScope: CoroutineScope,
) {
    // Bounded queue provides natural backpressure: when full, callers block
    private val queue = LinkedBlockingQueue<ByteArray>(QUEUE_CAPACITY)
    private val running = AtomicBoolean(true)
    private var writeJob: Job? = null

    @Volatile var writtenPackets = 0L
    @Volatile var writtenBytes = 0L

    fun start() {
        writeJob = engineScope.launch(Dispatchers.IO) {
            try {
                while (running.get() && isActive) {
                    // Block with timeout so we can check running flag
                    val packet = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    writePacket(packet)
                }
                // Drain remaining packets before stopping
                while (true) {
                    val packet = queue.poll() ?: break
                    writePacket(packet)
                }
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "TUN write loop failed", e)
            }
        }
    }

    fun stop() {
        running.set(false)
        writeJob?.cancel()
    }

    /**
     * Enqueue a packet for writing. Blocks if the queue is full (backpressure).
     */
    fun send(packet: ByteArray) {
        if (!running.get()) return
        try {
            queue.put(packet)
        } catch (_: InterruptedException) {
        }
    }

    private fun writePacket(packet: ByteArray) {
        while (true) {
            try {
                Os.write(tunFd, packet, 0, packet.size)
                writtenPackets++
                writtenBytes += packet.size
                return
            } catch (e: ErrnoException) {
                // The TUN fd is non-blocking; a full kernel queue returns EAGAIN. Retry the same
                // packet after a brief pause instead of dropping or crashing the writer.
                if (e.errno == OsConstants.EAGAIN && running.get()) {
                    Thread.sleep(1)
                    continue
                }
                if (running.get()) throw e
                return
            } catch (e: Exception) {
                if (running.get()) throw e
                return
            }
        }
    }

    companion object {
        private const val TAG = "PakomoTunWriter"
        private const val QUEUE_CAPACITY = 512
    }
}
