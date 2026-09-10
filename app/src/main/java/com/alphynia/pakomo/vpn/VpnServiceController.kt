package com.alphynia.pakomo.vpn

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.alphynia.pakomo.core.model.EngineRuntime
import com.alphynia.pakomo.core.model.EngineStage
import com.alphynia.pakomo.core.model.NetworkRule
import com.alphynia.pakomo.core.model.RuntimeStats
import com.alphynia.pakomo.core.model.TargetScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object VpnServiceController {
    private val _runtime = MutableStateFlow(EngineRuntime())
    val runtime: StateFlow<EngineRuntime> = _runtime.asStateFlow()
    private val _activeRule = MutableStateFlow<NetworkRule?>(null)
    /** Rule confirmed by the service, rather than the user's next selected preset. */
    val activeRule: StateFlow<NetworkRule?> = _activeRule.asStateFlow()

    /** Guards the paired advance of [_activeRule] + [_appliedConfigId] in [publishAppliedConfig]. */
    private val configVersionLock = Any()

    private val _appliedConfigId = MutableStateFlow(0L)

    /**
     * The highest config id the service has finished applying. Because a start establishes the
     * pipeline and a hot update runs [reconfigure][WeakNetworkVpnService] on a background coroutine,
     * [EngineStage.FORWARDING] alone does not prove a specific configuration took effect. Callers
     * that need that confirmation compare this against the id returned by [start] / [update].
     * Monotonic; ids come from [VpnRuntimeConfigStore] which never reuses them within a process.
     */
    val appliedConfigId: StateFlow<Long> = _appliedConfigId.asStateFlow()

    private val _failedConfigId = MutableStateFlow(0L)

    /**
     * The highest config id whose hot apply ([reconfigure][WeakNetworkVpnService]) failed, so a
     * waiter can distinguish a genuine failure from a timeout. Cold-start failures instead surface
     * as [EngineStage.ERROR]. Monotonic.
     */
    val failedConfigId: StateFlow<Long> = _failedConfigId.asStateFlow()

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
    ): Long {
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
        return configId
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
    ): Long {
        val (intent, configId) = buildIntent(
            context, WeakNetworkVpnService.ACTION_UPDATE, scope,
            selectedPackages, targetDomains, domainsByPackage, rule,
        )
        runCatching { context.startService(intent) }
            .onFailure { error ->
                VpnRuntimeConfigStore.discard(configId)
                publishFailedConfig(configId)
                Log.e(TAG, "Unable to update VPN runtime", error)
            }
        return configId
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
            useKotlinKernel = com.alphynia.pakomo.BuildConfig.USE_KOTLIN_KERNEL,
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

    /** Hot-applies the latency-compensation toggle to a running tunnel (read from preferences). */
    fun refreshCompensation(context: Context) {
        val intent = Intent(context, WeakNetworkVpnService::class.java)
            .setAction(WeakNetworkVpnService.ACTION_SET_COMPENSATION)
        runCatching { context.startService(intent) }
            .onFailure { Log.w(TAG, "Unable to refresh latency compensation", it) }
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

    /**
     * Called by the service once it has finished applying the configuration with [id]. Advances the
     * rule and the id together, and only when [id] is the newest applied version, so the pair
     * `(appliedConfigId, activeRule)` stays same-source: were two apply callbacks to arrive out of
     * order, an older one can no longer overwrite the newer config's rule while [appliedConfigId]
     * keeps the newer id (which would make `selectRule`'s confirmation misjudge). Today the ordering
     * is already guaranteed by reconfigure's cancel + generation guard, so this is defensive.
     */
    internal fun publishAppliedConfig(id: Long, rule: NetworkRule) {
        synchronized(configVersionLock) {
            if (id >= _appliedConfigId.value) {
                _activeRule.value = rule
                _appliedConfigId.value = id
            }
        }
    }

    /** Called by the service when the hot apply (reconfigure) of [id] failed. Atomic max. */
    internal fun publishFailedConfig(id: Long) {
        _failedConfigId.update { current -> maxOf(current, id) }
    }

    private const val TAG = "PakomoVpn"
}
