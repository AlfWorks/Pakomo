package com.pakomo.vpn

import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.system.OsConstants
import com.pakomo.forwarding.ConnectionAttributor
import com.pakomo.forwarding.ConnectionOrigin
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Resolves the selected application packages owning a connection origin using the platform
 * connection-owner lookup on the 5-tuple carried in the HEV attribution preamble. A shared
 * UID can host several packages, so all of the selected ones are returned. The UID→packages
 * mapping is cached because it is stable for the lifetime of a session.
 *
 * Short-lived connections are sometimes not yet visible in the kernel connection table when
 * the SOCKS server first asks, so the UID lookup is retried a few times over a few
 * milliseconds before giving up. Attribution attempts and misses are counted so the real
 * miss rate can be observed on a device.
 */
class AndroidConnectionAttributor(
    private val connectivity: ConnectivityManager,
    private val packageManager: PackageManager,
    knownPackages: Collection<String>,
) : ConnectionAttributor {
    private val knownPackages = knownPackages.toSet()
    private val uidToPackages = ConcurrentHashMap<Int, List<String>>()
    private val attempts = AtomicLong(0)
    private val misses = AtomicLong(0)

    /** Total resolution attempts and the subset that never resolved a UID. */
    data class Stats(val attempts: Long, val misses: Long)

    fun stats(): Stats = Stats(attempts.get(), misses.get())

    override fun packagesFor(origin: ConnectionOrigin): List<String> {
        val protocol = when (origin.protocol) {
            ConnectionOrigin.PROTOCOL_TCP -> OsConstants.IPPROTO_TCP
            ConnectionOrigin.PROTOCOL_UDP -> OsConstants.IPPROTO_UDP
            else -> return emptyList()
        }
        attempts.incrementAndGet()
        val uid = resolveUid(
            protocol = protocol,
            local = InetSocketAddress(origin.sourceAddress, origin.sourcePort),
            remote = InetSocketAddress(origin.destinationAddress, origin.destinationPort),
        )
        if (uid == INVALID_UID) {
            misses.incrementAndGet()
            return emptyList()
        }

        uidToPackages[uid]?.let { return it }
        // A transient PackageManager failure (null) must not be cached, or the app would show
        // as unattributed for the rest of the session; only cache a real resolved set.
        val packages = runCatching { packageManager.getPackagesForUid(uid) }.getOrNull()
            ?: return emptyList()
        // Only packages the user actually selected are relevant; the rest cannot reach the tunnel.
        val selected = packages.filter { it in knownPackages }
        if (selected.isNotEmpty()) uidToPackages[uid] = selected
        return selected
    }

    private fun resolveUid(
        protocol: Int,
        local: InetSocketAddress,
        remote: InetSocketAddress,
    ): Int {
        var attempt = 0
        while (true) {
            val uid = runCatching {
                connectivity.getConnectionOwnerUid(protocol, local, remote)
            }.getOrDefault(INVALID_UID)
            if (uid != INVALID_UID || attempt >= MAX_RETRIES) return uid
            attempt++
            try {
                Thread.sleep(RETRY_DELAY_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return uid
            }
        }
    }

    private companion object {
        const val INVALID_UID = -1
        const val MAX_RETRIES = 5
        const val RETRY_DELAY_MS = 2L
    }
}
