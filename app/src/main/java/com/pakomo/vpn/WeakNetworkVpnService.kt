package com.pakomo.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
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
import com.pakomo.forwarding.Socks5Server
import com.pakomo.forwarding.SocksCredentials
import com.pakomo.security.SecurityPolicy
import com.pakomo.shaping.TrafficShaper
import hev.htproxy.TProxyService
import java.io.File
import java.net.DatagramSocket
import java.net.Socket
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

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopValidation()
            ACTION_START -> {
                terminalError = null
                val packages = intent.getStringArrayListExtra(EXTRA_ALLOWED_PACKAGES).orEmpty()
                val scope = intent.readScope()
                val rule = intent.readRule()
                VpnServiceController.publish(EngineStage.STARTING, "正在建立本地转发链路")
                startForeground(NOTIFICATION_ID, buildNotification("正在启动本地转发"))
                if (scope == TargetScope.ADDRESSES) {
                    startSafeValidation(packages)
                } else {
                    startForwarding(scope, packages, rule)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        stopValidation()
        super.onRevoke()
    }

    override fun onDestroy() {
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
        rule: NetworkRule,
    ) {
        stopPipeline()
        if (scope == TargetScope.APPLICATIONS && allowedPackages.isEmpty()) {
            stopWithError("请先选择至少一个应用")
            return
        }
        try {
            val credentials = SocksCredentials(
                username = "pakomo_${UUID.randomUUID().toString().replace("-", "")}",
                password = UUID.randomUUID().toString().replace("-", "") +
                    UUID.randomUUID().toString().replace("-", ""),
            )
            val shaper = TrafficShaper(rule)
            val localSocks = Socks5Server(
                credentials = credentials,
                protector = object : SocketProtector {
                    override fun protect(socket: Socket): Boolean =
                        this@WeakNetworkVpnService.protect(socket)

                    override fun protect(socket: DatagramSocket): Boolean =
                        this@WeakNetworkVpnService.protect(socket)
                },
                shaper = shaper,
            )
            val socksPort = localSocks.start()
            socksServer = localSocks

            val builder = baseBuilder(allowedPackages, applyAllowedApps = scope == TargetScope.APPLICATIONS)
                .addRoute("0.0.0.0", 0)
                .setBlocking(true)
            tunnelInterface = builder.establish()
                ?: error("Android rejected the VPN interface")

            val config = HevTunnelConfig.write(cacheDir, socksPort, credentials)
            tunnelConfig = config
            val hev = TProxyService()
            nativeTunnel = hev
            hev.start(config.absolutePath, tunnelInterface!!.fd)

            startStatsMonitoring(hev, localSocks, shaper)
            VpnServiceController.publish(EngineStage.FORWARDING, "本地转发链路已建立")
            updateNotification("弱网模拟运行中 · ${rule.name}")
        } catch (error: Throwable) {
            Log.e(TAG, "Failed to start local forwarding", error)
            stopWithError(error.toUserMessage())
        }
    }

    private fun startSafeValidation(allowedPackages: List<String>) {
        stopPipeline()
        val builder = Builder()
            .setSession("Pakomo 安全验证")
            .setMtu(SecurityPolicy.DEFAULT_MTU)
            .addAddress(SecurityPolicy.VALIDATION_TUN_ADDRESS, 32)
            .setBlocking(false)

        allowedPackages
            .distinct()
            .take(SecurityPolicy.MAX_SELECTED_APPLICATIONS)
            .forEach { packageName ->
                runCatching { builder.addAllowedApplication(packageName) }
            }

        tunnelInterface = builder.establish()
        if (tunnelInterface == null) {
            stopValidation()
            return
        }
        VpnServiceController.publish(EngineStage.SAFE_VALIDATION)
        updateNotification("指定地址模式尚未接入域名识别，当前不接管流量")
    }

    private fun stopValidation() {
        terminalError = null
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
    }

    private fun startStatsMonitoring(
        hev: TProxyService,
        localSocks: Socks5Server,
        shaper: TrafficShaper,
    ) {
        val sampler = TunnelStatsSampler()
        statsJob = serviceScope.launch {
            while (isActive) {
                delay(STATS_INTERVAL_MS)
                val values = runCatching { hev.stats() }
                    .onFailure { Log.w(TAG, "Unable to read tunnel stats", it) }
                    .getOrNull()
                if (values == null || values.size < 4) continue
                val stats = sampler.sample(
                    counters = TunnelCounters(
                        uploadPackets = values[0],
                        uploadBytes = values[1],
                        downloadPackets = values[2],
                        downloadBytes = values[3],
                    ),
                    nowNanos = SystemClock.elapsedRealtimeNanos(),
                    activeConnections = localSocks.activeSessionCount(),
                    droppedPackets = shaper.droppedCount(),
                    delayedTransfers = shaper.delayedCount(),
                )
                VpnServiceController.publishStats(stats)
            }
        }
    }

    private fun stopWithError(message: String) {
        terminalError = message
        stopPipeline()
        VpnServiceController.publish(EngineStage.ERROR, message)
        updateNotification("启动失败 · $message")
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
    ): Builder {
        val builder = Builder()
            .setSession("Pakomo 本地弱网")
            .setMtu(SecurityPolicy.DEFAULT_MTU)
            .addAddress(SecurityPolicy.VALIDATION_TUN_ADDRESS, 32)
            .setMetered(false)
        if (applyAllowedApps) {
            allowedPackages
                .distinct()
                .take(SecurityPolicy.MAX_SELECTED_APPLICATIONS)
                .forEach { packageName ->
                    runCatching { builder.addAllowedApplication(packageName) }
                }
        }
        return builder
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

    private fun buildNotification(body: String): Notification {
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
            .setContentTitle(getString(R.string.vpn_notification_title))
            .setContentText(body)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "停止", stopIntent)
            .build()
    }

    private fun updateNotification(body: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(body))
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
        )
    }

    companion object {
        const val ACTION_START = "com.pakomo.action.START"
        const val ACTION_STOP = "com.pakomo.action.STOP"
        const val EXTRA_ALLOWED_PACKAGES = "allowed_packages"
        const val EXTRA_SCOPE = "scope"
        const val EXTRA_RULE_ID = "rule_id"
        const val EXTRA_RULE_NAME = "rule_name"
        const val EXTRA_LATENCY_MS = "latency_ms"
        const val EXTRA_JITTER_MS = "jitter_ms"
        const val EXTRA_LOSS_PERCENT = "loss_percent"
        const val EXTRA_DOWNLOAD_KBPS = "download_kbps"
        const val EXTRA_UPLOAD_KBPS = "upload_kbps"
        private const val CHANNEL_ID = "pakomo_vpn_status"
        private const val NOTIFICATION_ID = 4101
        private const val STATS_INTERVAL_MS = 1_000L
        private const val TAG = "PakomoVpn"
    }
}
