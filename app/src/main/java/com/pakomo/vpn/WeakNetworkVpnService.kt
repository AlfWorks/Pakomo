package com.pakomo.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.pakomo.MainActivity
import com.pakomo.R
import com.pakomo.core.model.EngineStage
import com.pakomo.core.model.NetworkRule
import com.pakomo.core.model.TargetScope
import com.pakomo.forwarding.SocketProtector
import com.pakomo.forwarding.DomainRoutingPolicy
import com.pakomo.forwarding.DomainScopedShaping
import com.pakomo.forwarding.PerAppShapingPolicy
import com.pakomo.forwarding.ShapeEverythingShaping
import com.pakomo.forwarding.ShapedApplication
import com.pakomo.forwarding.ShapingPolicy
import com.pakomo.forwarding.Socks5Server
import com.pakomo.forwarding.SocksCredentials
import com.pakomo.security.SecurityPolicy
import com.pakomo.shaping.TrafficShaper
import hev.htproxy.TProxyService
import java.io.File
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Socket
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Local on-device forwarding pipeline:
 * Android TUN -> HEV tun2socks -> authenticated loopback SOCKS -> protected real sockets.
 */
class WeakNetworkVpnService : android.net.VpnService() {
    private var tunnelInterface: ParcelFileDescriptor? = null
    private var socksServer: Socks5Server? = null
    private var nativeTunnel: TProxyService? = null
    private var tunnelConfig: File? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var statsJob: Job? = null
    private var terminalError: String? = null
    private var hitTracker: RecentHitTracker? = null
    private var activeScopeLabel: String? = null
    private var attributor: AndroidConnectionAttributor? = null
    private var activeShaper: TrafficShaper? = null
    private var activeRuleName: String = "Pakomo"
    private var latestUploadBytesPerSecond: Long = 0L
    private var latestDownloadBytesPerSecond: Long = 0L
    private var startedAtElapsedMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "VPN service created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "Stop requested")
                stopValidation()
            }
            ACTION_START -> {
                terminalError = null
                val packages = intent.getStringArrayListExtra(EXTRA_ALLOWED_PACKAGES).orEmpty()
                val targetDomains = intent.getStringArrayListExtra(EXTRA_TARGET_DOMAINS).orEmpty()
                val domainsByPackage = intent.readDomainsByPackage()
                val scope = intent.readScope()
                val rule = intent.readRule()
                Log.i(
                    TAG,
                    "Starting forwarding: scope=${scope.name}, apps=${packages.size}, domains=${targetDomains.size}",
                )
                VpnServiceController.publish(EngineStage.STARTING, "正在建立本地转发链路")
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(
                        notificationTitle(rule.name, scope.label),
                        trafficNotificationText(0L, 0L),
                    ),
                )
                startForwarding(scope, packages, targetDomains, domainsByPackage, rule)
            }
            ACTION_UPDATE -> reconfigure(intent)
        }
        return START_NOT_STICKY
    }

    /**
     * Hot-swaps the shaper and shaping policy on the running pipeline (rule / domain edits) without
     * tearing down the tunnel, so the change takes effect on new connections immediately and no
     * active connection is dropped. Scope / selected-app changes still go through ACTION_START
     * because the VPN interface's allowed-app set can only be set when it is established.
     */
    private fun reconfigure(intent: Intent) {
        val socks = socksServer ?: return
        val scope = intent.readScope()
        val rule = intent.readRule()
        val allowedPackages = intent.getStringArrayListExtra(EXTRA_ALLOWED_PACKAGES).orEmpty()
        val targetDomains = intent.getStringArrayListExtra(EXTRA_TARGET_DOMAINS).orEmpty()
        val (shaper, shapingPolicy) = buildRuntime(
            scope = scope,
            allowedPackages = allowedPackages,
            targetDomains = targetDomains,
            domainsByPackage = intent.readDomainsByPackage(),
            rule = rule,
        )
        socks.reconfigure(shaper, shapingPolicy)
        Log.i(TAG, "Runtime configuration updated: scope=${scope.name}")
        updateRuntimeNotification()
    }

    override fun onRevoke() {
        Log.w(TAG, "VPN permission revoked")
        stopValidation()
        super.onRevoke()
    }

    override fun onDestroy() {
        Log.i(TAG, "VPN service destroyed")
        stopPipeline()
        serviceScope.cancel()
        terminalError?.let {
            VpnServiceController.publish(EngineStage.ERROR, it)
        } ?: VpnServiceController.publish(EngineStage.STOPPED)
        super.onDestroy()
    }

    private fun startForwarding(
        scope: TargetScope,
        allowedPackages: List<String>,
        targetDomains: List<String>,
        domainsByPackage: Map<String, List<String>>,
        rule: NetworkRule,
    ) {
        stopPipeline()
        try {
            val underlyingNetwork = readUnderlyingNetwork()
            val credentials = SocksCredentials(
                username = "pakomo_${UUID.randomUUID().toString().replace("-", "")}",
                password = UUID.randomUUID().toString().replace("-", "") +
                    UUID.randomUUID().toString().replace("-", ""),
            )
            val (shaper, shapingPolicy) = buildRuntime(
                scope, allowedPackages, targetDomains, domainsByPackage, rule,
            )
            val localSocks = Socks5Server(
                credentials = credentials,
                protector = object : SocketProtector {
                    override fun protect(socket: Socket): Boolean {
                        return runCatching {
                            underlyingNetwork.network.bindSocket(socket)
                            true
                        }.getOrElse {
                            Log.w(TAG, "Unable to bind TCP socket to underlying network", it)
                            false
                        }
                    }

                    override fun protect(socket: DatagramSocket): Boolean {
                        return runCatching {
                            underlyingNetwork.network.bindSocket(socket)
                            true
                        }.getOrElse {
                            Log.w(TAG, "Unable to bind UDP socket to underlying network", it)
                            false
                        }
                    }
                },
                shaper = shaper,
                shapingPolicy = shapingPolicy,
                expectOriginPreamble = true,
            )
            val socksPort = localSocks.start()
            socksServer = localSocks
            Log.i(TAG, "Local SOCKS5 server started")
            VpnServiceController.activeProxy = VpnServiceController.ActiveProxy(
                port = socksPort,
                username = credentials.username,
                password = credentials.password,
            )

            val builder = baseBuilder(
                allowedPackages = allowedPackages,
                applyAllowedApps = scope == TargetScope.APPLICATIONS,
                underlyingNetwork = underlyingNetwork,
            )
                .addRoute("0.0.0.0", 0)
                .setBlocking(false)
            tunnelInterface = builder.establish()
                ?: error("Android rejected the VPN interface")
            Log.i(TAG, "VPN interface established")

            val config = HevTunnelConfig.write(cacheDir, socksPort, credentials)
            tunnelConfig = config
            val hev = TProxyService()
            nativeTunnel = hev
            hev.start(config.absolutePath, tunnelInterface!!.fd)
            Log.i(TAG, "HEV forwarding engine started")

            // Keep the uptime running across a live reconfigure (which rebuilds the pipeline);
            // only a real stop resets it.
            if (startedAtElapsedMs == 0L) startedAtElapsedMs = SystemClock.elapsedRealtime()
            startStatsMonitoring(hev, localSocks)
            VpnServiceController.publish(EngineStage.FORWARDING, "本地转发链路已建立")
            Log.i(TAG, "Forwarding pipeline ready")
            updateRuntimeNotification()
        } catch (error: Throwable) {
            Log.e(TAG, "Failed to start local forwarding", error)
            stopWithError(error.toUserMessage())
        }
    }

    private fun stopValidation() {
        Log.i(TAG, "Stopping forwarding pipeline")
        terminalError = null
        startedAtElapsedMs = 0L
        stopPipeline()
        VpnServiceController.publish(EngineStage.STOPPED)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopPipeline() {
        statsJob?.cancel()
        statsJob = null
        runCatching { nativeTunnel?.stop() }
        nativeTunnel = null
        runCatching { tunnelInterface?.close() }
        tunnelInterface = null
        runCatching { socksServer?.close() }
        socksServer = null
        runCatching { tunnelConfig?.delete() }
        tunnelConfig = null
        hitTracker = null
        activeScopeLabel = null
        attributor = null
        activeShaper = null
        activeRuleName = "Pakomo"
        latestUploadBytesPerSecond = 0L
        latestDownloadBytesPerSecond = 0L
        VpnServiceController.activeProxy = null
    }

    private fun startStatsMonitoring(
        hev: TProxyService,
        localSocks: Socks5Server,
    ) {
        val sampler = TunnelStatsSampler()
        statsJob = serviceScope.launch {
            while (isActive) {
                delay(STATS_INTERVAL_MS)
                val values = runCatching { hev.stats() }
                    .onFailure { Log.w(TAG, "Unable to read tunnel stats", it) }
                    .getOrNull()
                if (values == null || values.size < 4) continue
                val currentShaper = activeShaper
                val stats = sampler.sample(
                    counters = TunnelCounters(
                        uploadPackets = values[0],
                        uploadBytes = values[1],
                        downloadPackets = values[2],
                        downloadBytes = values[3],
                    ),
                    nowNanos = SystemClock.elapsedRealtimeNanos(),
                    activeConnections = localSocks.activeSessionCount(),
                    droppedTransfers = currentShaper?.droppedCount() ?: 0,
                    delayedTransfers = currentShaper?.delayedCount() ?: 0,
                ).copy(
                    activeScopeLabel = activeScopeLabel,
                    recentHits = hitTracker?.snapshot().orEmpty(),
                    attributionAttempts = attributor?.stats()?.attempts ?: 0,
                    attributionMisses = attributor?.stats()?.misses ?: 0,
                    uptimeMs = if (startedAtElapsedMs > 0) {
                        SystemClock.elapsedRealtime() - startedAtElapsedMs
                    } else {
                        0
                    },
                )
                VpnServiceController.publishStats(stats)
                latestUploadBytesPerSecond = stats.uploadBytesPerSecond
                latestDownloadBytesPerSecond = stats.downloadBytesPerSecond
                updateRuntimeNotification()
            }
        }
    }

    private fun stopWithError(message: String) {
        Log.e(TAG, "Forwarding stopped with error: $message")
        terminalError = message
        startedAtElapsedMs = 0L
        stopPipeline()
        VpnServiceController.publish(EngineStage.ERROR, message)
        updateNotification("Pakomo", "启动失败 · $message")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun Throwable.toUserMessage(): String = when (this) {
        is UnsatisfiedLinkError -> "转发内核未能加载"
        is SecurityException -> "系统拒绝建立 VPN"
        else -> message?.takeIf { it.isNotBlank() }?.take(80) ?: "本地转发启动失败"
    }

    private fun baseBuilder(
        allowedPackages: List<String>,
        applyAllowedApps: Boolean,
        underlyingNetwork: UnderlyingNetwork,
    ): Builder {
        val builder = Builder()
            .setSession("Pakomo 本地弱网")
            .setMtu(SecurityPolicy.DEFAULT_MTU)
            .addAddress(SecurityPolicy.VALIDATION_TUN_ADDRESS, 32)
            .setMetered(false)
            .setUnderlyingNetworks(arrayOf(underlyingNetwork.network))
        underlyingNetwork.dnsServers.forEach(builder::addDnsServer)
        if (applyAllowedApps) {
            val packages = allowedPackages
                .distinct()
                .take(SecurityPolicy.MAX_SELECTED_APPLICATIONS)
            if (packages.isEmpty()) {
                // Android treats an empty allowlist as all applications. Keeping only
                // Pakomo here makes an empty application scope an idle running state.
                builder.addAllowedApplication(packageName)
            } else {
                packages.forEach { allowedPackage ->
                    runCatching { builder.addAllowedApplication(allowedPackage) }
                }
            }
        } else {
            builder.addDisallowedApplication(packageName)
        }
        return builder
    }

    /** Builds the shaper + shaping policy for a config and refreshes the diagnostics fields. */
    private fun buildRuntime(
        scope: TargetScope,
        allowedPackages: List<String>,
        targetDomains: List<String>,
        domainsByPackage: Map<String, List<String>>,
        rule: NetworkRule,
    ): Pair<TrafficShaper, ShapingPolicy> {
        val shaper = TrafficShaper(rule)
        val hits = RecentHitTracker()
        hitTracker = hits
        activeScopeLabel = scope.label
        activeShaper = shaper
        activeRuleName = rule.name
        val policy = buildShapingPolicy(scope, allowedPackages, targetDomains, domainsByPackage, hits)
        return shaper to policy
    }

    /**
     * Builds the per-connection shaping policy for the active scope. In APPLICATIONS mode
     * each selected app keeps its own domain filter (never merged across apps) and traffic
     * is attributed to its owning app through [AndroidConnectionAttributor].
     */
    private fun buildShapingPolicy(
        scope: TargetScope,
        allowedPackages: List<String>,
        targetDomains: List<String>,
        domainsByPackage: Map<String, List<String>>,
        hits: RecentHitTracker,
    ): ShapingPolicy = when (scope) {
        TargetScope.GLOBAL -> ShapingPolicy { ShapeEverythingShaping(scope.label, hits) }

        TargetScope.ADDRESSES -> {
            val policy = DomainRoutingPolicy(targetDomains)
            ShapingPolicy {
                DomainScopedShaping(
                    scope = scope.label,
                    policy = policy,
                    packageName = null,
                    appLabel = null,
                    attributed = true,
                    reporter = hits,
                )
            }
        }

        TargetScope.APPLICATIONS -> {
            val pm = packageManager
            val apps = allowedPackages.distinct().map { pkg ->
                ShapedApplication(
                    packageName = pkg,
                    label = runCatching {
                        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                    }.getOrDefault(pkg),
                    domains = domainsByPackage[pkg].orEmpty(),
                )
            }
            val appAttributor = AndroidConnectionAttributor(
                connectivity = getSystemService(ConnectivityManager::class.java),
                packageManager = pm,
                knownPackages = allowedPackages,
            )
            attributor = appAttributor
            PerAppShapingPolicy(apps, appAttributor, hits, scope.label)
        }
    }

    private fun readUnderlyingNetwork(): UnderlyingNetwork {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val network = connectivity.activeNetwork
            ?: error("当前没有可用的底层网络")
        val dnsServers = connectivity.getLinkProperties(network)
            ?.dnsServers
            .orEmpty()
            .filterIsInstance<Inet4Address>()
        if (dnsServers.isEmpty()) {
            error("底层网络没有可用的 IPv4 DNS")
        }
        return UnderlyingNetwork(network, dnsServers)
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.vpn_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "显示用户主动启动的 Pakomo 本地 VPN 服务状态"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(title: String, body: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, WeakNetworkVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "停止", stopIntent)
            .build()
    }

    private fun updateNotification(title: String, body: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(title, body))
    }

    private fun updateRuntimeNotification() {
        updateNotification(
            notificationTitle(activeRuleName, activeScopeLabel),
            trafficNotificationText(
                latestUploadBytesPerSecond,
                latestDownloadBytesPerSecond,
            ),
        )
    }

    private fun notificationTitle(ruleName: String, scopeLabel: String?): String =
        scopeLabel?.let { "$ruleName · $it" } ?: ruleName

    private fun trafficNotificationText(upload: Long, download: Long): String =
        "↑ ${formatRate(upload)}    ↓ ${formatRate(download)}"

    private fun formatRate(bytesPerSecond: Long): String {
        val value = bytesPerSecond.coerceAtLeast(0L)
        return when {
            value >= 1_000_000L ->
                String.format(Locale.US, "%.1f MB/s", value / 1_000_000.0)
            value >= 1_000L ->
                String.format(Locale.US, "%.1f KB/s", value / 1_000.0)
            else -> "$value B/s"
        }
    }

    private fun Intent.readDomainsByPackage(): Map<String, List<String>> {
        val bundle = getBundleExtra(EXTRA_DOMAINS_BY_PACKAGE) ?: return emptyMap()
        return bundle.keySet().associateWith { key ->
            bundle.getStringArrayList(key).orEmpty().toList()
        }
    }

    private fun Intent.readScope(): TargetScope = runCatching {
        TargetScope.valueOf(getStringExtra(EXTRA_SCOPE) ?: TargetScope.APPLICATIONS.name)
    }.getOrDefault(TargetScope.APPLICATIONS)

    private fun Intent.readRule(): NetworkRule {
        val download = getIntExtra(EXTRA_DOWNLOAD_KBPS, -1).takeIf { it > 0 }
        val upload = getIntExtra(EXTRA_UPLOAD_KBPS, -1).takeIf { it > 0 }
        return NetworkRule(
            id = getStringExtra(EXTRA_RULE_ID) ?: "runtime",
            name = getStringExtra(EXTRA_RULE_NAME) ?: "当前规则",
            latencyMs = getIntExtra(EXTRA_LATENCY_MS, 0).coerceIn(0, 60_000),
            jitterMs = getIntExtra(EXTRA_JITTER_MS, 0).coerceIn(0, 30_000),
            packetLossPercent = getIntExtra(EXTRA_LOSS_PERCENT, 0).coerceIn(0, 100),
            downloadKbps = download,
            uploadKbps = upload,
            isSystem = false,
            advanced = getBooleanExtra(EXTRA_ADVANCED, false),
            uploadLatencyMs = getIntExtra(EXTRA_UP_LATENCY_MS, 0).coerceIn(0, 60_000),
            downloadLatencyMs = getIntExtra(EXTRA_DOWN_LATENCY_MS, 0).coerceIn(0, 60_000),
            uploadJitterMs = getIntExtra(EXTRA_UP_JITTER_MS, 0).coerceIn(0, 30_000),
            downloadJitterMs = getIntExtra(EXTRA_DOWN_JITTER_MS, 0).coerceIn(0, 30_000),
            uploadLossPercent = getIntExtra(EXTRA_UP_LOSS_PERCENT, 0).coerceIn(0, 100),
            downloadLossPercent = getIntExtra(EXTRA_DOWN_LOSS_PERCENT, 0).coerceIn(0, 100),
        )
    }

    private data class UnderlyingNetwork(
        val network: Network,
        val dnsServers: List<Inet4Address>,
    )

    companion object {
        const val ACTION_START = "com.pakomo.action.START"
        const val ACTION_STOP = "com.pakomo.action.STOP"
        const val ACTION_UPDATE = "com.pakomo.action.UPDATE"
        const val EXTRA_ALLOWED_PACKAGES = "allowed_packages"
        const val EXTRA_TARGET_DOMAINS = "target_domains"
        const val EXTRA_DOMAINS_BY_PACKAGE = "domains_by_package"
        const val EXTRA_SCOPE = "scope"
        const val EXTRA_RULE_ID = "rule_id"
        const val EXTRA_RULE_NAME = "rule_name"
        const val EXTRA_LATENCY_MS = "latency_ms"
        const val EXTRA_JITTER_MS = "jitter_ms"
        const val EXTRA_LOSS_PERCENT = "loss_percent"
        const val EXTRA_DOWNLOAD_KBPS = "download_kbps"
        const val EXTRA_UPLOAD_KBPS = "upload_kbps"
        const val EXTRA_ADVANCED = "advanced"
        const val EXTRA_UP_LATENCY_MS = "up_latency_ms"
        const val EXTRA_DOWN_LATENCY_MS = "down_latency_ms"
        const val EXTRA_UP_JITTER_MS = "up_jitter_ms"
        const val EXTRA_DOWN_JITTER_MS = "down_jitter_ms"
        const val EXTRA_UP_LOSS_PERCENT = "up_loss_percent"
        const val EXTRA_DOWN_LOSS_PERCENT = "down_loss_percent"
        private const val CHANNEL_ID = "pakomo_vpn_status"
        private const val NOTIFICATION_ID = 4101
        private const val STATS_INTERVAL_MS = 1_000L
        private const val TAG = "PakomoVpn"
    }
}
