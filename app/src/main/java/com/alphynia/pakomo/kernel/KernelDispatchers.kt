package com.alphynia.pakomo.kernel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * Dedicated dispatcher views so upstream socket work can never starve the tunnel's core read/write
 * loops.
 *
 * Upstream TCP data relay is now non-blocking: a single [com.alphynia.pakomo.forwarding.NioSelectorLoop]
 * awaits readability/writability for every connection, so a read no longer parks a thread for the life
 * of a flow. This removed the old per-connection blocking-read model, where a burst of connections
 * (e.g. a Bilibili list refresh opening 100+ sockets at once) exhausted the shared `Dispatchers.IO`
 * pool and froze the whole engine.
 *
 * What remains here:
 * - [core] reserves threads for the two TUN loops so the datapath never starves.
 * - [connSetup] runs the one-shot SOCKS handshakes (TCP connect / UDP associate), which are still
 *   blocking but short-lived; its own budget keeps a flood of new connections from queueing behind
 *   the datapath (audit #6).
 *
 * `Dispatchers.IO.limitedParallelism` views are elastic beyond the shared IO cap.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal object KernelDispatchers {
    /** TUN read loop + TUN write loop. Small and reserved so the datapath never starves. */
    val core = Dispatchers.IO.limitedParallelism(3)

    /**
     * SOCKS connection setup (TCP connect + handshake, UDP associate). Kept on its own budget so
     * establishing new connections doesn't queue behind the datapath — a flood of new flows must not
     * stall the tunnel while their short blocking handshakes run (audit #6).
     */
    val connSetup = Dispatchers.IO.limitedParallelism(CONN_SETUP_PARALLELISM)

    private const val CONN_SETUP_PARALLELISM = 64
}
