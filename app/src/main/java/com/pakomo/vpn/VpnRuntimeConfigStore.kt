package com.pakomo.vpn

import com.pakomo.core.model.NetworkRule
import com.pakomo.core.model.TargetScope
import java.util.concurrent.atomic.AtomicLong

/**
 * Keeps large runtime configurations inside the app process instead of copying them through Binder.
 *
 * A valid configuration can contain hundreds of applications and thousands of domains. Putting
 * that graph into Intent extras risks TransactionTooLargeException precisely when a fault is
 * enabled. The VPN service runs in the same process, so the Intent only needs a small one-shot key.
 */
internal data class VpnRuntimeConfig(
    val scope: TargetScope,
    val selectedPackages: List<String>,
    val targetDomains: List<String>,
    val domainsByPackage: Map<String, List<String>>,
    val rule: NetworkRule,
)

internal object VpnRuntimeConfigStore {
    private val nextId = AtomicLong(0L)
    private val pending = LinkedHashMap<Long, VpnRuntimeConfig>()

    @Synchronized
    fun publish(
        scope: TargetScope,
        selectedPackages: List<String>,
        targetDomains: List<String>,
        domainsByPackage: Map<String, List<String>>,
        rule: NetworkRule,
    ): Long {
        val id = nextId.incrementAndGet()
        pending[id] = VpnRuntimeConfig(
            scope = scope,
            selectedPackages = selectedPackages.toList(),
            targetDomains = targetDomains.toList(),
            domainsByPackage = domainsByPackage.mapValues { (_, domains) -> domains.toList() },
            rule = rule,
        )
        while (pending.size > MAX_PENDING_CONFIGS) {
            pending.remove(pending.keys.first())
        }
        return id
    }

    @Synchronized
    fun consume(id: Long): VpnRuntimeConfig? = pending.remove(id)

    @Synchronized
    fun discard(id: Long) {
        pending.remove(id)
    }

    private const val MAX_PENDING_CONFIGS = 8
}
