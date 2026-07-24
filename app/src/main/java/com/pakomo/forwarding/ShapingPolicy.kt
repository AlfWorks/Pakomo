package com.pakomo.forwarding

import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Original connection 5-tuple carried by the Pakomo attribution preamble that the
 * patched HEV tunnel writes to the local SOCKS server before the SOCKS5 greeting.
 */
data class ConnectionOrigin(
    val protocol: Int,
    val sourceAddress: InetAddress,
    val sourcePort: Int,
    val destinationAddress: InetAddress,
    val destinationPort: Int,
) {
    companion object {
        const val PREAMBLE_SIZE = 20
        const val PROTOCOL_TCP = 6
        const val PROTOCOL_UDP = 17
        private const val VERSION = 1

        /** Parses the fixed 20-byte preamble; returns null when the magic/version mismatch. */
        fun parse(bytes: ByteArray): ConnectionOrigin? {
            if (bytes.size < PREAMBLE_SIZE) return null
            if (bytes[0].toInt() != 'P'.code || bytes[1].toInt() != 'K'.code ||
                bytes[2].toInt() != 'M'.code || bytes[3].toInt() != 'O'.code
            ) {
                return null
            }
            if (bytes[4].toInt() and 0xFF != VERSION) return null
            val protocol = bytes[5].toInt() and 0xFF
            if (protocol != PROTOCOL_TCP && protocol != PROTOCOL_UDP) return null
            val sourcePort = ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF)
            val destinationPort = ((bytes[8].toInt() and 0xFF) shl 8) or (bytes[9].toInt() and 0xFF)
            if (sourcePort !in 1..0xFFFF || destinationPort !in 1..0xFFFF) return null
            val sourceAddress = InetAddress.getByAddress(bytes.copyOfRange(10, 14))
            val destinationAddress = InetAddress.getByAddress(bytes.copyOfRange(14, 18))
            if (sourceAddress.isAnyLocalAddress || destinationAddress.isAnyLocalAddress) return null
            return ConnectionOrigin(
                protocol = protocol,
                sourceAddress = sourceAddress,
                sourcePort = sourcePort,
                destinationAddress = destinationAddress,
                destinationPort = destinationPort,
            )
        }
    }
}

/** A single shaping decision, surfaced to the diagnostics screen. */
data class ShapingHit(
    val scope: String,
    val packageName: String?,
    val appLabel: String?,
    val host: String,
    val attributed: Boolean,
    val shaped: Boolean,
)

/** Receives shaping decisions for diagnostics. Implementations must be thread-safe. */
fun interface ShapingHitReporter {
    fun report(hit: ShapingHit)
}

/** Per-connection shaping decision, resolved once per accepted SOCKS connection. */
interface ConnectionShaping {
    /**
     * Whether the shaping decision depends on the destination host. When true, the relay sniffs the
     * real hostname from the connection's first bytes (TLS SNI / HTTP Host) so domain matching works
     * even when the app resolved the name via encrypted or cached DNS.
     */
    val usesDomainFilter: Boolean
    fun shouldShape(host: String, port: Int): Boolean
    fun observeDnsResponse(message: ByteArray)
}

/** Resolves how a single connection is shaped, given its original origin (null if unknown). */
fun interface ShapingPolicy {
    fun resolve(origin: ConnectionOrigin?): ConnectionShaping
}

/**
 * Resolves the selected application packages owning a connection origin. A shared UID can
 * host several packages, so the whole set is returned; an empty list means the connection
 * could not be attributed to any selected application.
 */
fun interface ConnectionAttributor {
    fun packagesFor(origin: ConnectionOrigin): List<String>
}

/**
 * Shapes every connection unconditionally (keeps DNS traffic shaped too). Used for the
 * GLOBAL scope, for a selected application without a domain filter, and — only when every
 * selected application is whole-app — as the fallback for an unattributed connection.
 */
class ShapeEverythingShaping(
    private val scope: String,
    private val reporter: ShapingHitReporter?,
    private val packageName: String? = null,
    private val appLabel: String? = null,
    private val attributed: Boolean = true,
) : ConnectionShaping {
    override val usesDomainFilter: Boolean = false

    override fun shouldShape(host: String, port: Int): Boolean {
        reporter?.report(ShapingHit(scope, packageName, appLabel, host, attributed, shaped = true))
        return true
    }

    override fun observeDnsResponse(message: ByteArray) = Unit
}

/** Delegates the decision to a domain policy, reporting matches for diagnostics. */
class DomainScopedShaping(
    private val scope: String,
    private val policy: DomainRoutingPolicy,
    private val packageName: String?,
    private val appLabel: String?,
    private val attributed: Boolean,
    private val reporter: ShapingHitReporter?,
) : ConnectionShaping {
    override val usesDomainFilter: Boolean = true

    override fun shouldShape(host: String, port: Int): Boolean {
        val shape = policy.shouldShape(host, port)
        if (shape) {
            reporter?.report(ShapingHit(scope, packageName, appLabel, host, attributed, shaped = true))
        }
        return shape
    }

    override fun observeDnsResponse(message: ByteArray) = policy.observeDnsResponse(message)
}

/**
 * Bypass for a connection that could not be attributed while at least one selected app has
 * a domain filter. The traffic is left untouched — applying another app's domain rules would
 * be wrong — and the miss is recorded so the diagnostics screen can surface it.
 */
class UnattributedBypassShaping(
    private val scope: String,
    private val reporter: ShapingHitReporter?,
) : ConnectionShaping {
    override val usesDomainFilter: Boolean = false

    override fun shouldShape(host: String, port: Int): Boolean {
        reporter?.report(ShapingHit(scope, null, null, host, attributed = false, shaped = false))
        return false
    }

    override fun observeDnsResponse(message: ByteArray) = Unit
}

/** A selected application and its per-app domain filter (empty domains means whole-app). */
class ShapedApplication(
    val packageName: String,
    val label: String,
    val domains: List<String>,
)

/**
 * Per-application shaping for the APPLICATIONS scope. Each selected app keeps its own domain
 * filter; domains are never merged across independent apps. Connections are attributed to the
 * owning app(s) via [ConnectionAttributor]:
 *
 * - A shared UID that owns several selected apps is treated as one group: their domains are
 *   unioned, and if any app in the group is whole-app the whole group shapes all its traffic.
 * - When a connection cannot be attributed, it is only shaped-all if *every* selected app is
 *   whole-app (so no domain filter can be violated); otherwise it is bypassed and recorded as
 *   unattributed, so an occasional UID lookup miss never silently defeats a domain filter.
 */
class PerAppShapingPolicy(
    private val apps: List<ShapedApplication>,
    private val attributor: ConnectionAttributor,
    private val reporter: ShapingHitReporter?,
    private val scope: String = "指定应用",
) : ShapingPolicy {
    private val byPackage = apps.associateBy { it.packageName }
    private val allWholeApp = apps.isNotEmpty() && apps.all { it.domains.isEmpty() }
    private val groupCache = ConcurrentHashMap<String, ConnectionShaping>()

    override fun resolve(origin: ConnectionOrigin?): ConnectionShaping {
        val packages = origin
            ?.let { runCatching { attributor.packagesFor(it) }.getOrNull() }
            .orEmpty()
        val group = packages.mapNotNull { byPackage[it] }
        if (group.isEmpty()) {
            return if (allWholeApp) {
                ShapeEverythingShaping(
                    scope = scope,
                    reporter = reporter,
                    packageName = packages.firstOrNull(),
                    appLabel = null,
                    attributed = false,
                )
            } else {
                UnattributedBypassShaping(scope, reporter)
            }
        }
        val key = group.map { it.packageName }.sorted().joinToString("|")
        return groupCache.getOrPut(key) { buildGroupShaping(group) }
    }

    private fun buildGroupShaping(group: List<ShapedApplication>): ConnectionShaping {
        val label = group.joinToString("、") { it.label }
        val packageName = group.joinToString("|") { it.packageName }
        // A shared-UID group where any member is whole-app can only be treated as whole-app,
        // since the OS cannot tell the members' sockets apart.
        return if (group.any { it.domains.isEmpty() }) {
            ShapeEverythingShaping(
                scope = scope,
                reporter = reporter,
                packageName = packageName,
                appLabel = label,
                attributed = true,
            )
        } else {
            DomainScopedShaping(
                scope = scope,
                policy = DomainRoutingPolicy(group.flatMap { it.domains }.distinct()),
                packageName = packageName,
                appLabel = label,
                attributed = true,
                reporter = reporter,
            )
        }
    }
}
