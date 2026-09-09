package com.alphynia.pakomo.kernel

/**
 * Configuration for the self-built tun2socks engine.
 * Replaces hev-socks5-tunnel's YAML config with a plain Kotlin data class.
 */
data class Tun2SocksConfig(
    val mtu: Int = 1500,
    val tunAddress: String = "10.77.0.1",
    val socksAddress: String = "127.0.0.1",
    val socksPort: Int,
    val socksUsername: String,
    val socksPassword: String,
    val tcpBufferSize: Int = 16384,
    val maxSessionCount: Int = 1024,
    val connectTimeoutMs: Int = 10000,
    // Idle timeout for ESTABLISHED connections. 300s (the old default) reaped long-lived heartbeats;
    // 0 (never reap) leaks connections whose peer silently vanished — each keeps a blocking-read thread
    // parked forever, squeezing the connIo pool. 15 min splits the difference: normal heartbeats
    // (seconds to a few minutes) survive, while a genuinely dead idle connection is reclaimed and its
    // read slot freed. 0 still opts out entirely for callers that want it.
    val tcpReadWriteTimeoutMs: Int = 900_000,
    val udpReadWriteTimeoutMs: Int = 30_000,
    val icmpReplyEnabled: Boolean = true,
)
