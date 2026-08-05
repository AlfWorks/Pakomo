package com.alphynia.pakomo.vpn

import com.alphynia.pakomo.core.model.ScopeHit
import com.alphynia.pakomo.forwarding.ShapingHit
import com.alphynia.pakomo.forwarding.ShapingHitReporter

/**
 * Collects the most recent distinct shaping matches so the diagnostics screen can show
 * which scope, application and domain the active rule is really acting on. Matches are
 * deduplicated by scope + owner + host and capped to keep only the newest entries.
 */
class RecentHitTracker(private val capacity: Int = DEFAULT_CAPACITY) : ShapingHitReporter {
    private val hits = LinkedHashMap<String, ScopeHit>()

    @Synchronized
    override fun report(hit: ShapingHit) {
        val owner = hit.packageName ?: hit.appLabel ?: "?"
        val key = "${hit.scope}|$owner|${hit.host}"
        hits.remove(key)
        hits[key] = ScopeHit(
            scopeLabel = hit.scope,
            appLabel = hit.appLabel,
            packageName = hit.packageName,
            host = hit.host,
            attributed = hit.attributed,
            shaped = hit.shaped,
        )
        while (hits.size > capacity) {
            hits.remove(hits.keys.iterator().next())
        }
    }

    /** Most-recent-first snapshot of the tracked matches. */
    @Synchronized
    fun snapshot(): List<ScopeHit> = hits.values.toList().asReversed()

    private companion object {
        const val DEFAULT_CAPACITY = 12
    }
}
