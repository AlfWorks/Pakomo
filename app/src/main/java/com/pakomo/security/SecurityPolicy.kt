package com.pakomo.security

object SecurityPolicy {
    const val DEFAULT_MTU = 1_500
    const val VALIDATION_TUN_ADDRESS = "10.77.0.1"
    const val MAX_SELECTED_APPLICATIONS = 500
    const val MAX_DOMAINS_PER_APPLICATION = 200
    const val MAX_RULES = 100
    const val MAX_LATENCY_MS = 60_000
    const val MAX_JITTER_MS = 30_000
    const val MAX_QUEUED_BYTES = 64L * 1024L * 1024L
    const val MAX_ACTIVE_FLOWS = 1_024
    const val SOCKS_LOOPBACK_ADDRESS = "127.0.0.1"
    const val SOCKS_LISTEN_BACKLOG = 64
    const val SOCKS_HANDSHAKE_TIMEOUT_MS = 10_000
    const val OUTBOUND_CONNECT_TIMEOUT_MS = 10_000
    const val SOCKS_COPY_BUFFER_BYTES = 64 * 1024
    const val MAX_UDP_PACKET_BYTES = 65_535
}
