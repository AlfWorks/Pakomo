package com.pakomo.vpn

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.pakomo.core.model.EngineStage
import com.pakomo.core.model.NetworkRule
import com.pakomo.core.model.TargetScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object VpnServiceController {
    private val _stage = MutableStateFlow(EngineStage.STOPPED)
    val stage: StateFlow<EngineStage> = _stage.asStateFlow()

    fun start(
        context: Context,
        scope: TargetScope,
        selectedPackages: List<String>,
        rule: NetworkRule,
    ) {
        val intent = Intent(context, WeakNetworkVpnService::class.java)
            .setAction(WeakNetworkVpnService.ACTION_START)
            .putExtra(WeakNetworkVpnService.EXTRA_SCOPE, scope.name)
            .putExtra(WeakNetworkVpnService.EXTRA_RULE_ID, rule.id)
            .putExtra(WeakNetworkVpnService.EXTRA_RULE_NAME, rule.name)
            .putExtra(WeakNetworkVpnService.EXTRA_LATENCY_MS, rule.latencyMs)
            .putExtra(WeakNetworkVpnService.EXTRA_JITTER_MS, rule.jitterMs)
            .putExtra(WeakNetworkVpnService.EXTRA_LOSS_PERCENT, rule.packetLossPercent)
            .putExtra(WeakNetworkVpnService.EXTRA_DOWNLOAD_KBPS, rule.downloadKbps ?: -1)
            .putExtra(WeakNetworkVpnService.EXTRA_UPLOAD_KBPS, rule.uploadKbps ?: -1)
            .putStringArrayListExtra(
                WeakNetworkVpnService.EXTRA_ALLOWED_PACKAGES,
                ArrayList(selectedPackages),
            )
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
        val intent = Intent(context, WeakNetworkVpnService::class.java)
            .setAction(WeakNetworkVpnService.ACTION_STOP)
        context.startService(intent)
    }

    internal fun publish(stage: EngineStage) {
        _stage.value = stage
    }
}
