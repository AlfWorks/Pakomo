package com.pakomo.vpn

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.pakomo.core.model.EngineRuntime
import com.pakomo.core.model.EngineStage
import com.pakomo.core.model.NetworkRule
import com.pakomo.core.model.RuntimeStats
import com.pakomo.core.model.TargetScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object VpnServiceController {
    private val _runtime = MutableStateFlow(EngineRuntime())
    val runtime: StateFlow<EngineRuntime> = _runtime.asStateFlow()

    /**
     * The running loopback SOCKS proxy, or null when stopped. Lets in-app diagnostics (the latency
     * test) route through the tunnel's shaping instead of Pakomo's own always-bypassed traffic.
     */
    data class ActiveProxy(val port: Int, val username: String, val password: String)

    @Volatile
    var activeProxy: ActiveProxy? = null
        internal set

    fun start(
        context: Context,
        scope: TargetScope,
        selectedPackages: List<String>,
        targetDomains: List<String>,
        domainsByPackage: Map<String, List<String>>,
        rule: NetworkRule,
    ) {
        if (_runtime.value.stage != EngineStage.FORWARDING) {
            publish(EngineStage.STARTING, "正在建立本地转发链路")
        }
        val (intent, configId) = buildIntent(
            context, WeakNetworkVpnService.ACTION_START, scope,
            selectedPackages, targetDomains, domainsByPackage, rule,
        )
        runCatching { ContextCompat.startForegroundService(context, intent) }
            .onFailure { error ->
                VpnRuntimeConfigStore.discard(configId)
                Log.e(TAG, "Unable to start VPN service", error)
                publish(EngineStage.ERROR, error.message ?: "无法启动 VPN 服务")
            }
    }

    /**
     * Hot-applies a rule / domain change to the already-running service without rebuilding the
     * tunnel (no dropped connections). Use [start] for scope / selected-app changes, which require
     * re-establishing the VPN interface.
     */
    fun update(
        context: Context,
        scope: TargetScope,
        selectedPackages: List<String>,
        targetDomains: List<String>,
        domainsByPackage: Map<String, List<String>>,
        rule: NetworkRule,
    ) {
        val (intent, configId) = buildIntent(
            context, WeakNetworkVpnService.ACTION_UPDATE, scope,
            selectedPackages, targetDomains, domainsByPackage, rule,
        )
        runCatching { context.startService(intent) }
            .onFailure { error ->
                VpnRuntimeConfigStore.discard(configId)
                Log.e(TAG, "Unable to update VPN runtime", error)
            }
    }

    private fun buildIntent(
        context: Context,
        action: String,
        scope: TargetScope,
        selectedPackages: List<String>,
        targetDomains: List<String>,
        domainsByPackage: Map<String, List<String>>,
        rule: NetworkRule,
    ): Pair<Intent, Long> {
        val configId = VpnRuntimeConfigStore.publish(
            scope, selectedPackages, targetDomains, domainsByPackage, rule,
        )
        val intent = Intent(context, WeakNetworkVpnService::class.java)
            .setAction(action)
            .putExtra(WeakNetworkVpnService.EXTRA_CONFIG_ID, configId)
        return intent to configId
    }

    fun stop(context: Context) {
        val intent = Intent(context, WeakNetworkVpnService::class.java)
            .setAction(WeakNetworkVpnService.ACTION_STOP)
        context.startService(intent)
    }

    internal fun publish(
        stage: EngineStage,
        message: String? = null,
        stats: RuntimeStats = if (stage == EngineStage.FORWARDING) {
            _runtime.value.stats
        } else {
            RuntimeStats()
        },
    ) {
        _runtime.value = EngineRuntime(stage = stage, stats = stats, message = message)
    }

    internal fun publishStats(stats: RuntimeStats) {
        val current = _runtime.value
        if (current.stage == EngineStage.FORWARDING) {
            _runtime.value = current.copy(stats = stats)
        }
    }

    private const val TAG = "PakomoVpn"
}
