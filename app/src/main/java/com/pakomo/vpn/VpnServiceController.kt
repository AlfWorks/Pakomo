package com.pakomo.vpn

import android.content.Context
import android.content.Intent
import android.os.Bundle
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

    fun start(
        context: Context,
        scope: TargetScope,
        selectedPackages: List<String>,
        targetDomains: List<String>,
        domainsByPackage: Map<String, List<String>>,
        rule: NetworkRule,
    ) {
        val domainsBundle = Bundle().apply {
            domainsByPackage.forEach { (pkg, domains) ->
                putStringArrayList(pkg, ArrayList(domains))
            }
        }
        val intent = Intent(context, WeakNetworkVpnService::class.java)
            .setAction(WeakNetworkVpnService.ACTION_START)
            .putExtra(WeakNetworkVpnService.EXTRA_SCOPE, scope.name)
            .putExtra(WeakNetworkVpnService.EXTRA_DOMAINS_BY_PACKAGE, domainsBundle)
            .putExtra(WeakNetworkVpnService.EXTRA_RULE_ID, rule.id)
            .putExtra(WeakNetworkVpnService.EXTRA_RULE_NAME, rule.name)
            .putExtra(WeakNetworkVpnService.EXTRA_LATENCY_MS, rule.latencyMs)
            .putExtra(WeakNetworkVpnService.EXTRA_JITTER_MS, rule.jitterMs)
            .putExtra(WeakNetworkVpnService.EXTRA_LOSS_PERCENT, rule.packetLossPercent)
            .putExtra(WeakNetworkVpnService.EXTRA_DOWNLOAD_KBPS, rule.downloadKbps ?: -1)
            .putExtra(WeakNetworkVpnService.EXTRA_UPLOAD_KBPS, rule.uploadKbps ?: -1)
            .putExtra(WeakNetworkVpnService.EXTRA_ADVANCED, rule.advanced)
            .putExtra(WeakNetworkVpnService.EXTRA_UP_LATENCY_MS, rule.uploadLatencyMs)
            .putExtra(WeakNetworkVpnService.EXTRA_DOWN_LATENCY_MS, rule.downloadLatencyMs)
            .putExtra(WeakNetworkVpnService.EXTRA_UP_JITTER_MS, rule.uploadJitterMs)
            .putExtra(WeakNetworkVpnService.EXTRA_DOWN_JITTER_MS, rule.downloadJitterMs)
            .putExtra(WeakNetworkVpnService.EXTRA_UP_LOSS_PERCENT, rule.uploadLossPercent)
            .putExtra(WeakNetworkVpnService.EXTRA_DOWN_LOSS_PERCENT, rule.downloadLossPercent)
            .putStringArrayListExtra(
                WeakNetworkVpnService.EXTRA_ALLOWED_PACKAGES,
                ArrayList(selectedPackages),
            )
            .putStringArrayListExtra(
                WeakNetworkVpnService.EXTRA_TARGET_DOMAINS,
                ArrayList(targetDomains),
            )
        ContextCompat.startForegroundService(context, intent)
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
}
