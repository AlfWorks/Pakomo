package com.pakomo.core.model

import android.graphics.Bitmap

enum class TargetScope(val label: String, val description: String) {
    GLOBAL("全局", "设备中的 IPv4 流量都按当前规则处理"),
    APPLICATIONS("指定应用", "只处理已选择应用，可为每个应用限定多个域名"),
    ADDRESSES("指定地址", "只处理命中已添加域名的流量"),
}

data class InstalledApp(
    val label: String,
    val packageName: String,
    val isSelected: Boolean,
    val domains: List<String>,
    val icon: Bitmap? = null,
    val isExpanded: Boolean = false,
)
/**
 * Weak-network parameters. In simple mode the [latencyMs] / [jitterMs] / [packetLossPercent]
 * values describe the whole (round-trip) effect and are split evenly across upload and download by
 * the shaper, so the configured value is what the connection actually experiences. In advanced mode
 * ([advanced] == true) the per-direction fields are authoritative and set independently. Bandwidth
 * is already per-direction.
 */
data class NetworkRule(
    val id: String,
    val name: String,
    val latencyMs: Int,
    val jitterMs: Int,
    val packetLossPercent: Int,
    val downloadKbps: Int?,
    val uploadKbps: Int?,
    val isSystem: Boolean = true,
    val advanced: Boolean = false,
    val uploadLatencyMs: Int = 0,
    val downloadLatencyMs: Int = 0,
    val uploadJitterMs: Int = 0,
    val downloadJitterMs: Int = 0,
    val uploadLossPercent: Int = 0,
    val downloadLossPercent: Int = 0,
) {
    val summary: String
        get() = if (advanced) {
            buildList {
                add("↑${uploadLatencyMs} ↓${downloadLatencyMs}ms")
                add("丢包 ↑${uploadLossPercent}% ↓${downloadLossPercent}%")
                if (downloadKbps != null || uploadKbps != null) {
                    add("${downloadKbps ?: "∞"}/${uploadKbps ?: "∞"} Kbps")
                }
            }.joinToString(" · ")
        } else {
            buildList {
                add("${latencyMs}ms")
                if (jitterMs > 0) add("抖动 ${jitterMs}ms")
                add("丢包 $packetLossPercent%")
                if (downloadKbps != null || uploadKbps != null) {
                    add("${downloadKbps ?: "∞"}/${uploadKbps ?: "∞"} Kbps")
                }
            }.joinToString(" · ")
        }
}

data class RuntimeStats(
    val uploadBytesPerSecond: Long = 0,
    val downloadBytesPerSecond: Long = 0,
    val activeConnections: Int = 0,
    val droppedTransfers: Long = 0,
    val delayedTransfers: Long = 0,
    val activeScopeLabel: String? = null,
    val recentHits: List<ScopeHit> = emptyList(),
    val attributionAttempts: Long = 0,
    val attributionMisses: Long = 0,
    val uptimeMs: Long = 0,
)

/** A single traffic decision surfaced on the diagnostics screen. */
data class ScopeHit(
    val scopeLabel: String,
    val appLabel: String?,
    val packageName: String?,
    val host: String,
    val attributed: Boolean,
    val shaped: Boolean,
)

enum class EngineStage(val isActive: Boolean) {
    STOPPED(false),
    STARTING(true),
    FORWARDING(true),
    ERROR(false),
}

data class EngineRuntime(
    val stage: EngineStage = EngineStage.STOPPED,
    val stats: RuntimeStats = RuntimeStats(),
    val message: String? = null,
)

data class PakomoUiState(
    val scope: TargetScope = TargetScope.APPLICATIONS,
    val apps: List<InstalledApp> = emptyList(),
    val isLoadingApps: Boolean = true,
    val appQuery: String = "",
    val addressDomains: List<String> = emptyList(),
    val rules: List<NetworkRule> = defaultRules,
    val activeRuleId: String = defaultRules[1].id,
    val stats: RuntimeStats = RuntimeStats(),
    val engineStage: EngineStage = EngineStage.STOPPED,
    val engineMessage: String? = null,
) {
    val activeRule: NetworkRule
        get() = rules.first { it.id == activeRuleId }

    val selectedApps: List<InstalledApp>
        get() = apps.filter(InstalledApp::isSelected)
}

val defaultRules = listOf(
    NetworkRule(
        id = "normal",
        name = "正常网络",
        latencyMs = 0,
        jitterMs = 0,
        packetLossPercent = 0,
        downloadKbps = null,
        uploadKbps = null,
    ),
    NetworkRule(
        id = "light",
        name = "轻度弱网",
        latencyMs = 100,
        jitterMs = 30,
        packetLossPercent = 1,
        downloadKbps = 2_048,
        uploadKbps = 1_024,
    ),
    NetworkRule(
        id = "medium",
        name = "中度弱网",
        latencyMs = 300,
        jitterMs = 100,
        packetLossPercent = 5,
        downloadKbps = 512,
        uploadKbps = 128,
    ),
    NetworkRule(
        id = "severe",
        name = "重度弱网",
        latencyMs = 800,
        jitterMs = 300,
        packetLossPercent = 15,
        downloadKbps = 128,
        uploadKbps = 64,
    ),
    NetworkRule(
        id = "offline",
        name = "完全断网",
        latencyMs = 0,
        jitterMs = 0,
        packetLossPercent = 100,
        downloadKbps = null,
        uploadKbps = null,
    ),
)
