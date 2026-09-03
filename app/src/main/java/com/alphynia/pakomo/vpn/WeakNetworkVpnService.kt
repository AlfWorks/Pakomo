package com.alphynia.pakomo.vpn

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
import com.alphynia.pakomo.MainActivity
import com.alphynia.pakomo.R
import com.alphynia.pakomo.core.model.AppLanguage
import com.alphynia.pakomo.core.model.EngineStage
import com.alphynia.pakomo.core.model.NetworkRule
import com.alphynia.pakomo.core.model.TargetScope
import com.alphynia.pakomo.data.PakomoPreferences
import com.alphynia.pakomo.forwarding.FlowLog
import com.alphynia.pakomo.forwarding.SocketProtector
import com.alphynia.pakomo.forwarding.DomainRoutingPolicy
import com.alphynia.pakomo.forwarding.DomainScopedShaping
import com.alphynia.pakomo.BuildConfig
import com.alphynia.pakomo.forwarding.FaultHit
import com.alphynia.pakomo.forwarding.FaultHitReporter
import com.alphynia.pakomo.forwarding.FaultPolicy
import com.alphynia.pakomo.forwarding.FaultRuntime
import com.alphynia.pakomo.forwarding.PerAppShapingPolicy
import com.alphynia.pakomo.forwarding.ShapeEverythingShaping
import com.alphynia.pakomo.forwarding.ShapedApplication
import com.alphynia.pakomo.forwarding.ShapingPolicy
import com.alphynia.pakomo.forwarding.Socks5Server
import com.alphynia.pakomo.forwarding.SocksCredentials
import com.alphynia.pakomo.security.SecurityPolicy
import com.alphynia.pakomo.shaping.TrafficShaper
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
import com.alphynia.pakomo.kernel.Tun2SocksConfig
import com.alphynia.pakomo.kernel.Tun2SocksEngine
import java.io.FileDescriptor

/**
 * Local on-device forwarding pipeline:
 * Android TUN -> HEV tun2socks -> authenticated loopback SOCKS -> protected real sockets.
 */
class WeakNetworkVpnService : android.net.VpnService() {
    private var tunnelInterface: ParcelFileDescriptor? = null
    private var socksServer: Socks5Server? = null
    private var nativeTunnel: Any? = null
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

    // Debug-only per-connection fault trace: attribution scope/packages, sniffed host, per-fault
    // match booleans, and the final decision. Null in release so the fault path stays zero-overhead.
    // Read from logcat under tag "PakomoFaultDbg" to diagnose why a domain-scoped fault does/doesn't fire.
    private val faultTracer: ((String) -> Unit)? =
        if (BuildConfig.DEBUG) { message -> Log.d("PakomoFaultDbg", message) } else null

    // Interface language for user-visible service text (notification, scope labels shown in stats).
    // Read from preferences on demand; a mid-session language change applies from the next update.
    private val preferences by lazy { PakomoPreferences(this) }

    // Display-only attribution for the traffic list's source label: resolves the owning app of ANY
    // connection (empty known set), decoupled from the shaping/fault attributor so it never adds
    // attribution cost to global / address scope. Constant across configs; best-effort.
    private val displayAttributor by lazy {
        AndroidConnectionAttributor(
            connectivity = getSystemService(ConnectivityManager::class.java),
            packageManager = packageManager,
            knownPackages = emptyList(),
        )
    }
    private val appLanguage: AppLanguage
        get() = AppLanguage.fromName(preferences.readLanguage())

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
                // A scope change rebuilds the VPN interface, but it is still one continuous user
                // session. Keep the public runtime in FORWARDING so the fixed home controls and
                // traffic chart do not collapse and re-expand during the short handover.
                if (VpnServiceController.runtime.value.stage != EngineStage.FORWARDING) {
                    VpnServiceController.publish(
                        EngineStage.STARTING,
                        appLanguage.tr("正在建立本地转发链路", "Establishing local forwarding link"),
                    )
                }
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(
                        notificationTitle(config.rule.displayName(appLanguage), config.scope.label(appLanguage)),
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
                        config.useKotlinKernel,
                        config.configId,
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
            ACTION_SET_COMPENSATION -> {
                // Hot-apply the latency-compensation toggle to the running SOCKS relay without
                // rebuilding the tunnel; takes effect on new connections, existing flows keep theirs.
                socksServer?.setCompensateLatency(preferences.readLatencyCompensationEnabled())
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
            try {
                val runtime = buildRuntime(
                    scope = config.scope,
                    allowedPackages = config.selectedPackages,
                    targetDomains = config.targetDomains,
                    domainsByPackage = config.domainsByPackage,
                    rule = config.rule,
                )
                if (runtimeGeneration.get() != generation) return@launch // superseded by a newer config
                // Switch the fallible data plane FIRST; only publish the new runtime metadata (rule
                // name, policy refs, attribution) after it succeeds, so a reconfigure failure can't
                // leave the control plane showing the new rule while SOCKS still relays the old one.
                socks.reconfigure(runtime.shaper, runtime.shapingPolicy, runtime.faultPolicy)
                socks.setCompensateLatency(preferences.readLatencyCompensationEnabled())
                applyRuntime(runtime)
                Log.i(TAG, "Runtime configuration updated: scope=${config.scope.name}")
                runCatching { updateRuntimeNotification() }
                    .onFailure { Log.w(TAG, "Runtime notification update failed", it) }
                VpnServiceController.publishAppliedConfig(config.configId)
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel // superseded/cancelled — not a failure, and must not be swallowed
            } catch (error: Throwable) {
                // Covers buildRuntime, socks.reconfigure and applyRuntime. Report only for the
                // current generation so a superseded config is not misreported as failed.
                if (runtimeGeneration.get() == generation) {
                    VpnServiceController.publishFailedConfig(config.configId)
                }
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
        useKotlinKernel: Boolean,
        configId: Long,
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
                compensateLatency = preferences.readLatencyCompensationEnabled(),
                resolveSourcePackage = { origin -> origin?.let { displayAttributor.displayPackageFor(it) } },
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
            // Address scope whose targets are all literal IPv4s: capture only those destinations
            // (/32 routes) so every other connection stays on the direct path and is not torn down
            // when the tunnel comes up. Any domain target (or none) still needs a default route —
            // a domain resolves to changing IPs the VPN cannot pre-route.
            val ipTargets = if (scope == TargetScope.ADDRESSES) literalIpv4Targets(targetDomains) else null
            if (ipTargets != null && ipTargets.isNotEmpty()) {
                ipTargets.forEach { builder.addRoute(it, 32) }
                Log.i(TAG, "Address scope: routing ${ipTargets.size} literal IP target(s) only")
            } else {
                builder.addRoute("0.0.0.0", 0)
            }
            builder.setBlocking(false)
            tunnelInterface = builder.establish()
                ?: error("Android rejected the VPN interface")
            Log.i(TAG, "VPN interface established")

            if (useKotlinKernel) {
                val engineConfig = Tun2SocksConfig(
                    socksPort = socksPort,
                    socksUsername = credentials.username,
                    socksPassword = credentials.password,
                )
                val engine = Tun2SocksEngine()
                nativeTunnel = engine
                engine.start(engineConfig, tunnelInterface!!.fileDescriptor)
                Log.i(TAG, "Native forwarding engine started")
            } else {
                val hevConfig = HevTunnelConfig.write(cacheDir, socksPort, credentials)
                tunnelConfig = hevConfig
                val hev = TProxyService()
                nativeTunnel = hev
                hev.start(hevConfig.absolutePath, tunnelInterface!!.fd)
                Log.i(TAG, "HEV forwarding engine started")
            }
            Log.i(TAG, "HEV forwarding engine started")

            // Keep the uptime running across a live reconfigure (which rebuilds the pipeline);
            // only a real stop resets it.
            if (startedAtElapsedMs == 0L) startedAtElapsedMs = SystemClock.elapsedRealtime()
            startStatsMonitoring(nativeTunnel ?: return, localSocks)
            VpnServiceController.publish(EngineStage.FORWARDING, "本地转发链路已建立")
            Log.i(TAG, "Forwarding pipeline ready")
            runCatching { updateRuntimeNotification() }
                .onFailure { Log.w(TAG, "Runtime notification update failed", it) }
            VpnServiceController.publishAppliedConfig(configId)
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
        runCatching { (nativeTunnel as? TProxyService)?.stop() }
        runCatching { (nativeTunnel as? Tun2SocksEngine)?.stop() }
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
        tunnel: Any,
        localSocks: Socks5Server,
    ) {
        val sampler = TunnelStatsSampler()
        FlowLog.clear()
        statsJob = serviceScope.launch {
            while (isActive) {
                delay(STATS_INTERVAL_MS)
                val values = runCatching { (tunnel as? TProxyService)?.stats() ?: (tunnel as? Tun2SocksEngine)?.stats() }
                    .onFailure { Log.w(TAG, "Unable to read tunnel stats", it) }
                    .getOrNull()
                val currentShaper = activeShaper
                val raw = values!!
                val stats = sampler.sample(
                    counters = TunnelCounters(
                        uploadPackets = raw[0],
                        uploadBytes = raw[1],
                        downloadPackets = raw[2],
                        downloadBytes = raw[3],
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
                if (BuildConfig.DEBUG) {
                    val a = attributor?.stats()
                    if (a != null && a.attempts > 0L) {
                        // Per-app attribution cost probe: a miss pays up to MAX_RETRIES*RETRY_DELAY_MS
                        // of blocking sleep. High miss rate ⇒ the getConnectionOwnerUid retry loop is a
                        // real slice of per-connection setup latency in multi-app mode.
                        Log.d(
                            "PakomoFaultDbg",
                            "attribution attempts=${a.attempts} misses=${a.misses} " +
                                "missRate=${"%.1f".format(a.misses * 100.0 / a.attempts)}%",
                        )
                    }
                }
                FlowLog.pulse()
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

    /**
     * The address-scope targets when they are *all* literal IPv4 addresses, else null. In practice a
     * non-IPv4 target is always a domain: `DomainInputValidator` rejects IPv6 / v4-mapped literals at
     * input (its `substringBefore(':')` truncates them into an invalid single-label host), so the
     * broad-capture fallback below only ever applies to domains — never a silently-dropped IPv6 target.
     */
    private fun literalIpv4Targets(targets: List<String>): List<String>? {
        val ips = ArrayList<String>(targets.size)
        for (target in targets) {
            val value = target.trim()
            if (!isLiteralIpv4(value)) return null
            ips.add(value)
        }
        return ips
    }

    private fun isLiteralIpv4(value: String): Boolean {
        val parts = value.split('.')
        if (parts.size != 4) return false
        return parts.all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }
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
            scopeLabel = scope.label(appLanguage),
            attributor = shaping.attributor,
            ruleName = rule.displayName(appLanguage),
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
            tracer = faultTracer,
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
            policy = ShapingPolicy { ShapeEverythingShaping(scope.label(appLanguage), hits) },
            attributor = null,
        )

        TargetScope.ADDRESSES -> {
            val policy = DomainRoutingPolicy(targetDomains)
            RuntimeShaping(
                policy = ShapingPolicy {
                    DomainScopedShaping(
                        scope = scope.label(appLanguage),
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
                policy = PerAppShapingPolicy(apps, appAttributor, hits, scope.label(appLanguage)),
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
            appLanguage.tr("弱网模拟状态", "Weak-network status"),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = appLanguage.tr(
                "显示用户主动启动的 Pakomo 本地 VPN 服务状态",
                "Shows the status of the user-started local Pakomo VPN service",
            )
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
            .addAction(0, appLanguage.tr("停止", "Stop"), stopIntent)
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
        const val ACTION_SET_COMPENSATION = "com.pakomo.action.SET_COMPENSATION"
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
