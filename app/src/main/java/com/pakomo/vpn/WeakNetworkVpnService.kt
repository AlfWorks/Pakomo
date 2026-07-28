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
import com.pakomo.forwarding.FaultHit
import com.pakomo.forwarding.FaultHitReporter
import com.pakomo.forwarding.FaultPolicy
import com.pakomo.forwarding.FaultRuntime
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
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private var reconfigureJob: Job? = null
    private var startJob: Job? = null
    private val pipelineMutex = Mutex()
    private val runtimeGeneration = AtomicLong(0L)
    private var terminalError: String? = null
    private var hitTracker: RecentHitTracker? = null
    private var activeScopeLabel: String? = null
    @Volatile private var attributor: AndroidConnectionAttributor? = null
    private var activeShaper: TrafficShaper? = null
    private var activeRuleName: String = "Pakomo"
    private var latestUploadBytesPerSecond: Long = 0L
    private var latestDownloadBytesPerSecond: Long = 0L
    private var startedAtElapsedMs: Long = 0L
    private val faultHitLogger = FaultHitLogger(TAG)

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
                val config = intent.consumeRuntimeConfig()
                if (config == null) {
                    Log.e(TAG, "Start configuration is no longer available")
                    stopWithError("启动配置已失效，请重试")
                    return START_NOT_STICKY
                }
                terminalError = null
                Log.i(
                    TAG,
                    "Starting forwarding: scope=${config.scope.name}, " +
                        "apps=${config.selectedPackages.size}, domains=${config.targetDomains.size}",
                )
                VpnServiceController.publish(EngineStage.STARTING, "正在建立本地转发链路")
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(
                        notificationTitle(config.rule.name, config.scope.label),
                        trafficNotificationText(0L, 0L),
                    ),
                )
                startJob?.cancel()
                startJob = serviceScope.launch {
                    pipelineMutex.withLock {
                        startForwarding(
                            config.scope,
                            config.selectedPackages,
                            config.targetDomains,
                            config.domainsByPackage,
                            config.rule,
                        )
                    }
                }
            }
            ACTION_UPDATE -> {
                val config = intent.consumeRuntimeConfig()
                if (config == null) {
                    Log.w(TAG, "Ignoring an expired runtime update")
                } else {
                    reconfigure(config)
                }
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Hot-swaps the shaper and shaping policy on the running pipeline (rule / domain edits) without
     * tearing down the tunnel, so the change takes effect on new connections immediately and no
     * active connection is dropped. Scope / selected-app changes still go through ACTION_START
     * because the VPN interface's allowed-app set can only be set when it is established.
     */
    private fun reconfigure(config: VpnRuntimeConfig) {
        reconfigureJob?.cancel()
        val generation = runtimeGeneration.incrementAndGet()
        reconfigureJob = serviceScope.launch {
            val socks = socksServer ?: return@launch
            runCatching {
                buildRuntime(
                    scope = config.scope,
                    allowedPackages = config.selectedPackages,
                    targetDomains = config.targetDomains,
                    domainsByPackage = config.domainsByPackage,
                    rule = config.rule,
                )
            }.onSuccess { runtime ->
                if (runtimeGeneration.get() != generation) return@onSuccess
                applyRuntime(runtime)
                socks.reconfigure(runtime.shaper, runtime.shapingPolicy, runtime.faultPolicy)
                Log.i(TAG, "Runtime configuration updated: scope=${config.scope.name}")
                updateRuntimeNotification()
            }.onFailure { error ->
                Log.e(TAG, "Runtime configuration update failed", error)
            }
        }
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
            val runtime = buildRuntime(
                scope, allowedPackages, targetDomains, domainsByPackage, rule,
            )
            applyRuntime(runtime)
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
                shaper = runtime.shaper,
                shapingPolicy = runtime.shapingPolicy,
                faultPolicy = runtime.faultPolicy,
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
        startJob?.cancel()
        serviceScope.launch {
            pipelineMutex.withLock {
                Log.i(TAG, "Stopping forwarding pipeline")
                terminalError = null
                startedAtElapsedMs = 0L
                stopPipeline()
                VpnServiceController.publish(EngineStage.STOPPED)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun stopPipeline() {
        runtimeGeneration.incrementAndGet()
        statsJob?.cancel()
        statsJob = null
        reconfigureJob?.cancel()
        reconfigureJob = null
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

    /** Builds the shaper + shaping policy + fault policy for a config and refreshes diagnostics. */
    private fun buildRuntime(
        scope: TargetScope,
        allowedPackages: List<String>,
        targetDomains: List<String>,
        domainsByPackage: Map<String, List<String>>,
        rule: NetworkRule,
    ): RuntimeComponents {
        val shaper = TrafficShaper(rule)
        val hits = RecentHitTracker()
        val shaping = buildShapingPolicy(
            scope, allowedPackages, targetDomains, domainsByPackage, hits,
        )
        val faultPolicy = buildFaultPolicy(
            scope,
            allowedPackages,
            targetDomains,
            domainsByPackage,
            rule,
            shaping.attributor,
        )
        return RuntimeComponents(
            shaper = shaper,
            shapingPolicy = shaping.policy,
            faultPolicy = faultPolicy,
            hitTracker = hits,
            scopeLabel = scope.label,
            attributor = shaping.attributor,
            ruleName = rule.name,
        )
    }

    private fun applyRuntime(runtime: RuntimeComponents) {
        hitTracker = runtime.hitTracker
        activeScopeLabel = runtime.scopeLabel
        activeShaper = runtime.shaper
        activeRuleName = runtime.ruleName
        attributor = runtime.attributor
    }

    /**
     * Builds the special-fault enforcement layer for the active scope, reusing the same
     * [ConnectionAttributor] as shaping so a connection is attributed to the same app(s). Returns
     * [FaultPolicy.NONE] when no fault is enabled, so the normal path pays nothing.
     */
    private fun buildFaultPolicy(
        scope: TargetScope,
        allowedPackages: List<String>,
        targetDomains: List<String>,
        domainsByPackage: Map<String, List<String>>,
        rule: NetworkRule,
        connectionAttributor: AndroidConnectionAttributor?,
    ): FaultPolicy {
        if (rule.specialFaults.all.none { it.enabled }) return FaultPolicy.NONE
        val selectedAppDomains = allowedPackages.distinct()
            .associateWith { domainsByPackage[it].orEmpty() }
        val reporter = FaultHitReporter(faultHitLogger::report)
        return FaultRuntime(
            scope = scope,
            config = rule.specialFaults,
            selectedAppDomains = selectedAppDomains,
            addressDomains = targetDomains,
            attributor = connectionAttributor,
            reporter = reporter,
        )
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
    ): RuntimeShaping = when (scope) {
        TargetScope.GLOBAL -> RuntimeShaping(
            policy = ShapingPolicy { ShapeEverythingShaping(scope.label, hits) },
            attributor = null,
        )

        TargetScope.ADDRESSES -> {
            val policy = DomainRoutingPolicy(targetDomains)
            RuntimeShaping(
                policy = ShapingPolicy {
                    DomainScopedShaping(
                        scope = scope.label,
                        policy = policy,
                        packageName = null,
                        appLabel = null,
                        attributed = true,
                        reporter = hits,
                    )
                },
                attributor = null,
            )
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
            RuntimeShaping(
                policy = PerAppShapingPolicy(apps, appAttributor, hits, scope.label),
                attributor = appAttributor,
            )
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

    private fun Intent.consumeRuntimeConfig(): VpnRuntimeConfig? {
        val id = getLongExtra(EXTRA_CONFIG_ID, NO_CONFIG_ID)
        return if (id == NO_CONFIG_ID) null else VpnRuntimeConfigStore.consume(id)
    }

    private data class UnderlyingNetwork(
        val network: Network,
        val dnsServers: List<Inet4Address>,
    )

    private data class RuntimeShaping(
        val policy: ShapingPolicy,
        val attributor: AndroidConnectionAttributor?,
    )

    private data class RuntimeComponents(
        val shaper: TrafficShaper,
        val shapingPolicy: ShapingPolicy,
        val faultPolicy: FaultPolicy,
        val hitTracker: RecentHitTracker,
        val scopeLabel: String,
        val attributor: AndroidConnectionAttributor?,
        val ruleName: String,
    )

    companion object {
        const val ACTION_START = "com.pakomo.action.START"
        const val ACTION_STOP = "com.pakomo.action.STOP"
        const val ACTION_UPDATE = "com.pakomo.action.UPDATE"
        const val EXTRA_CONFIG_ID = "runtime_config_id"
        private const val NO_CONFIG_ID = -1L
        private const val CHANNEL_ID = "pakomo_vpn_status"
        private const val NOTIFICATION_ID = 4101
        private const val STATS_INTERVAL_MS = 1_000L
        private const val TAG = "PakomoVpn"
    }
}

/**
 * Faults can deliberately trigger aggressive reconnect loops. Keep the raw first hits visible,
 * then summarize bursts so logging itself cannot become the workload that destabilizes the app.
 */
private class FaultHitLogger(private val tag: String) {
    private var windowStartedAtMs = 0L
    private var emitted = 0
    private var suppressed = 0

    @Synchronized
    fun report(hit: FaultHit) {
        val now = SystemClock.elapsedRealtime()
        if (windowStartedAtMs == 0L || now - windowStartedAtMs >= WINDOW_MS) {
            if (suppressed > 0) {
                Log.i(tag, "Fault hits: $suppressed additional events suppressed in the last second")
            }
            windowStartedAtMs = now
            emitted = 0
            suppressed = 0
        }
        if (emitted >= MAX_LOGS_PER_WINDOW) {
            suppressed++
            return
        }
        emitted++
        Log.i(
            tag,
            "Fault enforced: type=${hit.type.name}, scope=${hit.scope}, " +
                "pkg=${hit.packageName ?: "-"}, target=${hit.target}, result=${hit.result}",
        )
    }

    private companion object {
        const val WINDOW_MS = 1_000L
        const val MAX_LOGS_PER_WINDOW = 6
    }
}
