package com.alphynia.pakomo.core.model

/**
 * 运行时可命中的目标身份。用于「已选 N 个」计数、运行匹配以及重叠提示。
 *
 * 身份区分严格按接管模式：
 * - 应用模式的整应用用 [WholeApp]，应用下的域名用 [ApplicationDomain]，两者都带包名，
 *   因此不同应用即使域名文本相同也不会视为同一目标。
 * - 指定域名模式用 [AddressDomain]，与应用下的同名域名相互独立。
 * - 全局模式只有唯一的 [Global]。
 */
sealed interface FaultTarget {
    data object Global : FaultTarget
    data class WholeApp(val packageName: String) : FaultTarget
    data class ApplicationDomain(val packageName: String, val domain: String) : FaultTarget
    data class AddressDomain(val domain: String) : FaultTarget
}

/**
 * 纯函数：把一项特殊故障在当前接管模式下的配置解算成实际生效的运行目标。
 *
 * 输入的接管配置只包含当前仍被接管的对象：
 * - [selectedAppDomains]：包名 -> 该应用在 scope 里配置的域名列表（空列表表示应用无域名限制）。
 *   未被接管的应用不应出现在其中。
 * - [addressDomains]：当前指定域名配置。
 */
object SpecialFaultTargets {

    fun effectiveTargets(
        fault: SpecialFault,
        scope: TargetScope,
        selectedAppDomains: Map<String, List<String>>,
        addressDomains: List<String>,
    ): List<FaultTarget> {
        if (!fault.enabled) return emptyList()
        return when (scope) {
            TargetScope.GLOBAL -> listOf(FaultTarget.Global)
            TargetScope.APPLICATIONS -> selectedAppDomains.entries.flatMap { (pkg, configured) ->
                val target = fault.appTargets[pkg]
                if (target == null || !target.enabled) return@flatMap emptyList<FaultTarget>()
                if (configured.isEmpty()) {
                    // 应用无域名限制：故障对整应用生效。
                    listOf(FaultTarget.WholeApp(pkg))
                } else {
                    // 应用有域名配置：只有既被配置又被选中的域名生效；一个都没有则不生效。
                    val configuredSet = configured.toHashSet()
                    target.domains
                        .filter { it in configuredSet }
                        .map { FaultTarget.ApplicationDomain(pkg, it) }
                }
            }
            TargetScope.ADDRESSES -> {
                val configuredSet = addressDomains.toHashSet()
                fault.addressTargets
                .filter { it in configuredSet }
                .map { FaultTarget.AddressDomain(it) }
            }
        }
    }

    /**
     * 入口右侧「已选 N 个」的计数。全局模式没有目标选择，返回 0（UI 用开关状态表达）。
     * 其余模式统计实际生效的目标数。
     */
    fun effectiveCount(
        fault: SpecialFault,
        scope: TargetScope,
        selectedAppDomains: Map<String, List<String>>,
        addressDomains: List<String>,
    ): Int = if (scope == TargetScope.GLOBAL) {
        0
    } else {
        effectiveTargets(fault, scope, selectedAppDomains, addressDomains).size
    }

    /**
     * 计算被多种特殊故障同时命中的运行目标。仅针对“同一个运行目标”，天然不跨应用、不跨模式。
     * 返回 目标 -> 命中它的故障类型列表（列表长度 >= 2）。
     */
    fun overlaps(
        config: SpecialFaultConfig,
        scope: TargetScope,
        selectedAppDomains: Map<String, List<String>>,
        addressDomains: List<String>,
    ): Map<FaultTarget, List<SpecialFaultType>> {
        val byTarget = LinkedHashMap<FaultTarget, MutableList<SpecialFaultType>>()
        config.all.forEach { fault ->
            effectiveTargets(fault, scope, selectedAppDomains, addressDomains).forEach { target ->
                byTarget.getOrPut(target) { mutableListOf() }.add(fault.type)
            }
        }
        return byTarget.filterValues { it.size >= 2 }
    }
}
