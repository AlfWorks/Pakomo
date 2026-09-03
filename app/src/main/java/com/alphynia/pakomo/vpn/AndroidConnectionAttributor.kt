package com.alphynia.pakomo.vpn

import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.system.OsConstants
import com.alphynia.pakomo.forwarding.ConnectionAttributor
import com.alphynia.pakomo.forwarding.ConnectionOrigin
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
    private val soleKnownPackage = this.knownPackages.singleOrNull()?.let(::listOf)
    private val uidToPackages = ConcurrentHashMap<Int, List<String>>()
    // Same connection is attributed twice per flow (shaping + fault). Caching the 5-tuple result
    // makes the second lookup free and guarantees both layers see the same app(s). Bounded so a
    // long session's ever-changing ephemeral ports can't grow it without limit.
    private val originToPackages = ConcurrentHashMap<ConnectionOrigin, List<String>>()
    // Display attribution for the traffic list: the owning package of ANY app (not filtered to the
    // selected set), so a flow's source is shown even in global scope. Separate from the shaping/fault
    // attribution above, and best-effort (single lookup, no retry) so it never adds setup latency.
    private val originToDisplay = ConcurrentHashMap<ConnectionOrigin, String>()
    private val uidToDisplay = ConcurrentHashMap<Int, String>()
    private val attempts = AtomicLong(0)
    private val misses = AtomicLong(0)

    /** Total resolution attempts and the subset that never resolved a UID. */
    data class Stats(val attempts: Long, val misses: Long)

    fun stats(): Stats = Stats(attempts.get(), misses.get())

    override fun packagesFor(origin: ConnectionOrigin): List<String> {
        // The VPN allow-list guarantees that a one-app tunnel can only contain this application's
        // traffic. Avoid an expensive platform connection-table lookup for every new flow.
        soleKnownPackage?.let { return it }
        originToPackages[origin]?.let { return it }
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
            return cacheOrigin(origin, emptyList())
        }

        uidToPackages[uid]?.let { return cacheOrigin(origin, it) }
        // A transient PackageManager failure (null) must not be cached, or the app would show
        // as unattributed for the rest of the session; only cache a real resolved set.
        val packages = runCatching { packageManager.getPackagesForUid(uid) }.getOrNull()
            ?: return emptyList()
        // Only packages the user actually selected are relevant; the rest cannot reach the tunnel.
        val selected = packages.filter { it in knownPackages }
        if (selected.isNotEmpty()) uidToPackages[uid] = selected
        return cacheOrigin(origin, selected)
    }

    /**
     * The owning package of a connection for the traffic list's source label — resolved for ANY
     * app, not just the selected set. Best-effort: a single lookup with no retry, cached, so it
     * never adds the setup latency the shaping/fault path guards against. Returns null when unknown.
     */
    fun displayPackageFor(origin: ConnectionOrigin): String? {
        soleKnownPackage?.let { return it.first() }
        originToDisplay[origin]?.let { return it.ifBlank { null } }
        val protocol = when (origin.protocol) {
            ConnectionOrigin.PROTOCOL_TCP -> OsConstants.IPPROTO_TCP
            ConnectionOrigin.PROTOCOL_UDP -> OsConstants.IPPROTO_UDP
            else -> return null
        }
        val uid = runCatching {
            connectivity.getConnectionOwnerUid(
                protocol,
                InetSocketAddress(origin.sourceAddress, origin.sourcePort),
                InetSocketAddress(origin.destinationAddress, origin.destinationPort),
            )
        }.getOrDefault(INVALID_UID)
        val pkg = if (uid == INVALID_UID) {
            ""
        } else {
            uidToDisplay.getOrPut(uid) {
                runCatching { packageManager.getPackagesForUid(uid)?.firstOrNull() }.getOrNull().orEmpty()
            }
        }
        if (originToDisplay.size >= ORIGIN_CACHE_LIMIT) originToDisplay.clear()
        originToDisplay[origin] = pkg
        return pkg.ifBlank { null }
    }

    private fun cacheOrigin(origin: ConnectionOrigin, packages: List<String>): List<String> {
        if (originToPackages.size >= ORIGIN_CACHE_LIMIT) originToPackages.clear()
        originToPackages[origin] = packages
        return packages
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
        const val ORIGIN_CACHE_LIMIT = 2_048
    }
}
