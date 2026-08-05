package com.alphynia.pakomo.kernel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * Dedicated dispatcher views so blocking upstream socket I/O can never starve the tunnel's core
 * read/write loops.
 *
 * `Dispatchers.IO` alone caps at 64 threads. Each connection relays upstream with a *blocking*
 * `socket.read()` that holds an IO thread while it waits for data, so a burst of connections (e.g. a
 * Bilibili list refresh opening 100+ sockets at once) exhausts the pool — the TUN read/write loops,
 * which also run on IO, then can't get a thread and the whole engine freezes (real-time traffic
 * drops to zero).
 *
 * [core] reserves threads for the two TUN loops so they always run; [connIo] gives per-connection
 * blocking I/O a much larger, independent budget. `Dispatchers.IO.limitedParallelism` for the IO
 * dispatcher is allowed to spawn threads beyond the 64 cap, so these views don't fight each other.
 *
 * Proper long-term fix: non-blocking NIO for upstream sockets (one selector, no thread per
 * connection), mirroring the local `Socks5Server`'s `NioSelectorLoop`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal object KernelDispatchers {
    /** TUN read loop + TUN write loop. Small and reserved so the datapath never starves. */
    val core = Dispatchers.IO.limitedParallelism(3)

    /** Blocking upstream socket I/O (SOCKS connect, relay reads/writes). */
    val connIo = Dispatchers.IO.limitedParallelism(CONN_IO_PARALLELISM)

    private const val CONN_IO_PARALLELISM = 256
}
