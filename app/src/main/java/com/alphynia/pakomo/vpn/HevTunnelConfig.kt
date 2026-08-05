package com.alphynia.pakomo.vpn

import com.alphynia.pakomo.forwarding.SocksCredentials
import com.alphynia.pakomo.security.SecurityPolicy
import java.io.File

object HevTunnelConfig {
    fun write(
        directory: File,
        socksPort: Int,
        credentials: SocksCredentials,
    ): File {
        require(socksPort in 1..65_535)
        require(credentials.username.matches(SAFE_TOKEN))
        require(credentials.password.matches(SAFE_TOKEN))
        val config = """
            tunnel:
              mtu: ${SecurityPolicy.DEFAULT_MTU}
              ipv4: ${SecurityPolicy.VALIDATION_TUN_ADDRESS}
              icmp: reply
            socks5:
              address: ${SecurityPolicy.SOCKS_LOOPBACK_ADDRESS}
              port: $socksPort
              udp: udp
              username: '${credentials.username}'
              password: '${credentials.password}'
            misc:
              task-stack-size: 32768
              tcp-buffer-size: 16384
              max-session-count: ${SecurityPolicy.MAX_ACTIVE_FLOWS}
              connect-timeout: ${SecurityPolicy.OUTBOUND_CONNECT_TIMEOUT_MS}
              tcp-read-write-timeout: 300000
              udp-read-write-timeout: 60000
              log-file: stderr
              log-level: warn
        """.trimIndent()
        return File(directory, "hev-pakomo.yml").apply {
            writeText(config, Charsets.UTF_8)
        }
    }

    private val SAFE_TOKEN = Regex("[A-Za-z0-9_-]{16,128}")
}
