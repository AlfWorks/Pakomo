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
 * [core] reserves threads for the two TUN loops; [connRead], [connWrite], and [connSetup] have
 * independent budgets so parked reads cannot prevent uploads or new associations from progressing.
 * `Dispatchers.IO.limitedParallelism` views are elastic beyond the shared IO cap.
 *
 * Proper long-term fix: non-blocking NIO for upstream sockets (one selector, no thread per
 * connection), mirroring the local `Socks5Server`'s `NioSelectorLoop`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal object KernelDispatchers {
    /** TUN read loop + TUN write loop. Small and reserved so the datapath never starves. */
    val core = Dispatchers.IO.limitedParallelism(3)

    /** Long-lived blocking upstream reads. These may stay parked for the life of an idle flow. */
    val connRead = Dispatchers.IO.limitedParallelism(CONN_READ_PARALLELISM)

    /** Short blocking upstream writes, isolated so parked reads cannot starve uploads. */
    val connWrite = Dispatchers.IO.limitedParallelism(CONN_WRITE_PARALLELISM)

    /**
     * SOCKS connection setup (TCP connect, UDP associate). Kept on its own budget so establishing new
    * connections doesn't queue behind [connRead] threads parked in blocking relay reads — a flood of
     * idle-but-blocked reads must not stop new connections from opening (audit #6).
     */
    val connSetup = Dispatchers.IO.limitedParallelism(CONN_SETUP_PARALLELISM)

    private const val CONN_READ_PARALLELISM = 256
    private const val CONN_WRITE_PARALLELISM = 64
    private const val CONN_SETUP_PARALLELISM = 64
}
