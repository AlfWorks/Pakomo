package com.alphynia.pakomo.data

import com.alphynia.pakomo.core.model.DomainTarget
import org.json.JSONArray
import org.json.JSONObject

/**
 * (De)serialization for [DomainTarget] lists, factored out of [PakomoPreferences] so it can be
 * unit-tested without an Android Context.
 *
 * The stored form is a JSON array of objects `{"v": domain, "on": bool}`. A bare string element is
 * the **legacy** format (from before the enable/disable flag existed) and decodes to an *enabled*
 * target, so a list stored by an older build upgrades silently on the next write. Malformed input
 * decodes to empty rather than throwing.
 */
object DomainTargetCodec {
    /** Decode an address-scope list (a JSON array), tolerating the legacy string-array form. */
    fun decodeList(raw: String?): List<DomainTarget> {
        if (raw.isNullOrEmpty()) return emptyList()
        return runCatching { JSONArray(raw).toDomainTargets() }.getOrDefault(emptyList())
    }

    fun encodeList(targets: List<DomainTarget>): String = targets.toJsonArray().toString()

    /** Decode a per-package map (a JSON object of package -> array), tolerating the legacy form. */
    fun decodeMap(raw: String?): Map<String, List<DomainTarget>> {
        if (raw.isNullOrEmpty()) return emptyMap()
        return runCatching {
            val root = JSONObject(raw)
            root.keys().asSequence().associateWith { key -> root.getJSONArray(key).toDomainTargets() }
        }.getOrDefault(emptyMap())
    }

    fun encodeMap(map: Map<String, List<DomainTarget>>): String {
        val root = JSONObject()
        map.forEach { (key, targets) -> root.put(key, targets.toJsonArray()) }
        return root.toString()
    }

    private fun JSONArray.toDomainTargets(): List<DomainTarget> =
        (0 until length()).mapNotNull { index ->
            when (val element = get(index)) {
                is JSONObject -> element.optString("v")
                    .takeIf { it.isNotEmpty() }
                    ?.let { DomainTarget(it, element.optBoolean("on", true)) }
                is String -> element.takeIf { it.isNotEmpty() }?.let { DomainTarget(it, enabled = true) }
                else -> null
            }
        }

    private fun List<DomainTarget>.toJsonArray(): JSONArray {
        val array = JSONArray()
        forEach { target -> array.put(JSONObject().put("v", target.value).put("on", target.enabled)) }
        return array
    }
}
