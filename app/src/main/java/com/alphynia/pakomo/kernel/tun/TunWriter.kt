package com.alphynia.pakomo.kernel.tun

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.alphynia.pakomo.kernel.KernelDispatchers
import java.io.FileDescriptor
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class TunWriter(
    private val tunFd: FileDescriptor,
    private val engineScope: CoroutineScope,
) {
    // Suspending channel: when the writer falls behind (e.g. a video download flood), `send`
    // SUSPENDS the calling coroutine — releasing its dispatcher thread — instead of blocking it.
    // The previous blocking queue froze every Dispatchers.Default thread under load, starving all
    // coroutines and timing out every connection at once ("刷 bilibili → 全局 timeout"). Backpressure
    // now propagates cleanly back through TCP flow control without freezing the tunnel.
    private val channel = Channel<ByteArray>(QUEUE_CAPACITY)
    private val running = AtomicBoolean(true)
    private var writeJob: Job? = null

    @Volatile var writtenPackets = 0L
    @Volatile var writtenBytes = 0L

    fun start() {
        writeJob = engineScope.launch(KernelDispatchers.core) {
            try {
                for (packet in channel) writePacket(packet)
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "TUN write loop failed", e)
            }
        }
    }

    fun stop() {
        running.set(false)
        channel.close()
        writeJob?.cancel()
    }

    /**
     * Enqueue a packet for writing. Suspends (backpressure) when the writer is behind; never blocks
     * the calling dispatcher thread.
     */
    suspend fun send(packet: ByteArray) {
        if (!running.get()) return
        try {
            channel.send(packet)
        } catch (_: Exception) {
            // Channel closed during shutdown — drop silently.
        }
    }

    private suspend fun writePacket(packet: ByteArray) {
        while (true) {
            try {
                Os.write(tunFd, packet, 0, packet.size)
                writtenPackets++
                writtenBytes += packet.size
                return
            } catch (e: ErrnoException) {
                // The TUN fd is non-blocking; a full kernel queue returns EAGAIN. Yield briefly and
                // retry the same packet — this is genuine backpressure (the app can't read faster),
                // and delay() keeps it off the IO thread.
                if (e.errno == OsConstants.EAGAIN && running.get()) {
                    delay(1)
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
        private const val QUEUE_CAPACITY = 1024
    }
}
