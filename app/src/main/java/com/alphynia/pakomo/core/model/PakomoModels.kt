package com.alphynia.pakomo.core.model

import android.graphics.Bitmap

enum class TargetScope {
    GLOBAL,
    APPLICATIONS,
    ADDRESSES;

    fun label(language: AppLanguage): String = when (this) {
        GLOBAL -> language.tr("全局", "Global")
        APPLICATIONS -> language.tr("指定应用", "By app")
        ADDRESSES -> language.tr("指定地址", "By address")
    }

    fun description(language: AppLanguage): String = when (this) {
        GLOBAL -> language.tr(
            "设备中的 IPv4 流量都按当前规则处理",
            "All device IPv4 traffic is handled by the current rule",
        )
        APPLICATIONS -> language.tr(
            "只处理已选择应用，可为每个应用限定多个域名",
            "Only handle selected apps; each app can be limited to specific domains",
        )
        ADDRESSES -> language.tr(
            "只处理命中已添加域名的流量",
            "Only handle traffic matching the added domains",
        )
    }
}

/**
 * A domain/address target the user has entered for scope routing, with an [enabled] flag so a rule can
 * be temporarily paused without deleting it. Only enabled targets are projected to the runtime; the
 * disabled ones are still persisted and shown in the UI (dimmed). Used for both per-app domains and the
 * address-scope list.
 */
data class DomainTarget(
    val value: String,
    val enabled: Boolean = true,
)

data class InstalledApp(
    val label: String,
    val packageName: String,
    val isSelected: Boolean,
    val domains: List<DomainTarget>,
    val icon: Bitmap? = null,
    val isExpanded: Boolean = false,
)

/** The enabled targets projected to their plain values — exactly what reaches the runtime. */
fun List<DomainTarget>.enabledValues(): List<String> = filter { it.enabled }.map { it.value }

/**
 * Per-package enabled domains for the selected apps — the single projection every start/reconfigure
 * path shares (so the enable/disable semantics can never drift between them). An app whose domains are
 * all disabled drops out of the map, so it falls through to whole-app capture — the same as having no
 * domains at all.
 */
fun List<InstalledApp>.enabledDomainsByPackage(): Map<String, List<String>> =
    asSequence()
        .filter { it.isSelected }
        .map { app -> app.packageName to app.domains.enabledValues() }
        .filter { it.second.isNotEmpty() }
        .toMap()
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
    val specialFaults: SpecialFaultConfig = SpecialFaultConfig(),
) {
    /** Localized name for the built-in presets; user rules keep their stored [name]. */
    fun displayName(language: AppLanguage): String = if (isSystem) {
        when (id) {
            "normal" -> language.tr("正常网络", "Normal")
            "light" -> language.tr("轻度弱网", "Light")
            "medium" -> language.tr("中度弱网", "Medium")
            "severe" -> language.tr("重度弱网", "Severe")
            "offline" -> language.tr("完全断网", "Offline")
            else -> name
        }
    } else {
        name
    }

    fun summary(language: AppLanguage): String = if (advanced) {
        buildList {
            add("↑${uploadLatencyMs} ↓${downloadLatencyMs}ms")
            add(language.tr("丢包", "loss") + " ↑${uploadLossPercent}% ↓${downloadLossPercent}%")
            if (downloadKbps != null || uploadKbps != null) {
                add("${downloadKbps ?: "∞"}/${uploadKbps ?: "∞"} Kbps")
            }
        }.joinToString(" · ")
    } else {
        buildList {
            add("${latencyMs}ms")
            if (jitterMs > 0) add(language.tr("抖动", "jitter") + " ${jitterMs}ms")
            add(language.tr("丢包", "loss") + " $packetLossPercent%")
            if (downloadKbps != null || uploadKbps != null) {
                add("${downloadKbps ?: "∞"}/${uploadKbps ?: "∞"} Kbps")
            }
        }.joinToString(" · ")
    }
}

/** The independently-toggleable special faults. */
enum class SpecialFaultType {
    CONNECTION_RESET,
    DNS_FAILURE,
    NETWORK_BLACKOUT,
    RESPONSE_HOLD;

    fun label(language: AppLanguage): String = when (this) {
        CONNECTION_RESET -> language.tr("连接重置", "Connection reset")
        DNS_FAILURE -> language.tr("DNS 失败", "DNS failure")
        NETWORK_BLACKOUT -> language.tr("网络中断", "Network blackout")
        RESPONSE_HOLD -> language.tr("慢响应", "Slow response")
    }

    /** Entry-row label; identical to [label] today but kept separate for future divergence. */
    fun entryLabel(language: AppLanguage): String = label(language)
}

/** DNS 解析失败的返回结果。 */
enum class DnsFailureResult(val label: String) {
    NXDOMAIN("域名不存在 (NXDOMAIN)"),
    SERVFAIL("服务失败 (SERVFAIL)"),
    REFUSED("拒绝解析 (REFUSED)"),
    TIMEOUT("超时不响应"),
}

/** 网络中断的两种表现。 */
enum class BlackoutMode(val label: String) {
    SILENT("静默中断（丢弃流量，等待超时）"),
    IMMEDIATE("立即失败（拒绝新连接并关闭已有连接）"),
}

/** Connection Reset 触发时机；第一阶段仅立即重置。 */
enum class ResetTiming(val label: String) {
    IMMEDIATE("立即重置"),
}

/** 响应暂扣默认时长（毫秒）。逐包固定延迟：每个下行分片各自延后这么久再放行。 */
const val DEFAULT_RESPONSE_HOLD_MS: Long = 20_000L

/**
 * 单个应用在“指定应用”模式下对某项特殊故障的目标选择。
 *
 * [enabled] 是应用父开关。[domains] 是该应用被选中的域名子集：
 * - 应用未开启域名限制（scope 里该应用无域名配置）时，[domains] 为空且故障对整应用生效。
 * - 应用有域名配置时，[domains] 必须至少包含一个域名才生效；为空表示未选择、不生效。
 */
data class AppFaultTarget(
    val packageName: String,
    val enabled: Boolean = false,
    val domains: List<String> = emptyList(),
)

/**
 * 一项特殊故障的完整配置：启用状态、故障参数，以及分别保存的“指定应用”与“指定域名”目标。
 * 全局模式不保存额外目标，仅由 [enabled] 决定是否对整个接管范围生效。
 * 运行时只读取当前接管模式对应的目标字段。
 */
data class SpecialFault(
    val type: SpecialFaultType,
    val enabled: Boolean = false,
    // 类型相关参数：仅与 [type] 匹配的字段有意义。
    val dnsResult: DnsFailureResult = DnsFailureResult.NXDOMAIN,
    val dnsCacheGuard: Boolean = false,
    val blackoutMode: BlackoutMode = BlackoutMode.SILENT,
    val resetTiming: ResetTiming = ResetTiming.IMMEDIATE,
    // 响应暂扣：下行分片的固定延迟时长（毫秒）。仅 RESPONSE_HOLD 有意义。
    val holdMs: Long = DEFAULT_RESPONSE_HOLD_MS,
    // 响应暂扣：下行累计不超过该字节数的连接立即放行、不暂扣（用于放过心跳/探测这类小响应）。
    // 0 = 关闭，全部暂扣。仅 RESPONSE_HOLD 有意义。
    val holdBypassBytes: Int = 0,
    // 指定应用模式：按包名保存每个应用的父开关与选中域名。
    val appTargets: Map<String, AppFaultTarget> = emptyMap(),
    // 指定域名模式：被选中的指定域名列表。
    val addressTargets: List<String> = emptyList(),
)

/**
 * 一条规则携带的三套特殊故障配置。三者互不互斥，可同时启用。
 * 该配置随 [NetworkRule] 一起保存、复制与升级。
 */
data class SpecialFaultConfig(
    val connectionReset: SpecialFault = SpecialFault(SpecialFaultType.CONNECTION_RESET),
    val dnsFailure: SpecialFault = SpecialFault(SpecialFaultType.DNS_FAILURE),
    val networkBlackout: SpecialFault = SpecialFault(SpecialFaultType.NETWORK_BLACKOUT),
    val responseHold: SpecialFault = SpecialFault(SpecialFaultType.RESPONSE_HOLD),
) {
    val all: List<SpecialFault>
        get() = listOf(connectionReset, dnsFailure, networkBlackout, responseHold)

    fun fault(type: SpecialFaultType): SpecialFault = when (type) {
        SpecialFaultType.CONNECTION_RESET -> connectionReset
        SpecialFaultType.DNS_FAILURE -> dnsFailure
        SpecialFaultType.NETWORK_BLACKOUT -> networkBlackout
        SpecialFaultType.RESPONSE_HOLD -> responseHold
    }

    fun withFault(fault: SpecialFault): SpecialFaultConfig = when (fault.type) {
        SpecialFaultType.CONNECTION_RESET -> copy(connectionReset = fault)
        SpecialFaultType.DNS_FAILURE -> copy(dnsFailure = fault)
        SpecialFaultType.NETWORK_BLACKOUT -> copy(networkBlackout = fault)
        SpecialFaultType.RESPONSE_HOLD -> copy(responseHold = fault)
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

enum class AppListAccess {
    CHECKING,
    AVAILABLE,
    UNAVAILABLE,
}

data class PakomoUiState(
    val scope: TargetScope = TargetScope.APPLICATIONS,
    val apps: List<InstalledApp> = emptyList(),
    val isLoadingApps: Boolean = true,
    val appListAccess: AppListAccess = AppListAccess.CHECKING,
    val appQuery: String = "",
    val addressDomains: List<DomainTarget> = emptyList(),
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
