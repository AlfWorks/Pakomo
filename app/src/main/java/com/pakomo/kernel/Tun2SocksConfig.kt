package com.pakomo.kernel

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
    val tcpReadWriteTimeoutMs: Int = 300_000,
    val udpReadWriteTimeoutMs: Int = 30_000,
    val icmpReplyEnabled: Boolean = true,
)
