package com.pakomo.data

import com.pakomo.core.model.AppFaultTarget
import com.pakomo.core.model.BlackoutMode
import com.pakomo.core.model.DnsFailureResult
import com.pakomo.core.model.ResetTiming
import com.pakomo.core.model.SpecialFault
import com.pakomo.core.model.SpecialFaultConfig
import com.pakomo.core.model.SpecialFaultType
import org.json.JSONArray
import org.json.JSONObject

/**
 * Shared JSON (de)serialization for [SpecialFaultConfig]. Used both by [PakomoPreferences] (nested
 * inside each rule) and by the VPN Intent extras that carry the active rule to the service, so the
 * on-disk and on-the-wire formats never drift apart.
 */
object SpecialFaultCodec {

    fun toJson(config: SpecialFaultConfig): JSONObject {
        val root = JSONObject()
        config.all.forEach { fault ->
            val appTargets = JSONObject()
            fault.appTargets.forEach { (packageName, target) ->
                appTargets.put(
                    packageName,
                    JSONObject()
                        .put("enabled", target.enabled)
                        .put("domains", JSONArray(target.domains)),
                )
            }
            root.put(
                fault.type.name,
                JSONObject()
                    .put("enabled", fault.enabled)
                    .put("dnsResult", fault.dnsResult.name)
                    .put("dnsCacheGuard", fault.dnsCacheGuard)
                    .put("blackoutMode", fault.blackoutMode.name)
                    .put("resetTiming", fault.resetTiming.name)
                    .put("appTargets", appTargets)
                    .put("addressTargets", JSONArray(fault.addressTargets)),
            )
        }
        return root
    }

    fun fromJson(root: JSONObject?): SpecialFaultConfig {
        if (root == null) return SpecialFaultConfig()
        var config = SpecialFaultConfig()
        SpecialFaultType.entries.forEach { type ->
            val obj = root.optJSONObject(type.name) ?: return@forEach
            config = config.withFault(readFault(type, obj))
        }
        return config
    }

    fun encode(config: SpecialFaultConfig): String = toJson(config).toString()

    fun decode(json: String?): SpecialFaultConfig {
        val root = json?.let { runCatching { JSONObject(it) }.getOrNull() }
        return fromJson(root)
    }

    private fun readFault(type: SpecialFaultType, obj: JSONObject): SpecialFault {
        val appTargets = obj.optJSONObject("appTargets")?.let { apps ->
            apps.keys().asSequence().associateWith { packageName ->
                val app = apps.getJSONObject(packageName)
                AppFaultTarget(
                    packageName = packageName,
                    enabled = app.optBoolean("enabled", false),
                    domains = app.optJSONArray("domains")?.toStringList().orEmpty(),
                )
            }
        }.orEmpty()
        return SpecialFault(
            type = type,
            enabled = obj.optBoolean("enabled", false),
            dnsResult = obj.optString("dnsResult")
                .let { name -> DnsFailureResult.entries.firstOrNull { it.name == name } }
                ?: DnsFailureResult.NXDOMAIN,
            dnsCacheGuard = obj.optBoolean("dnsCacheGuard", false),
            blackoutMode = obj.optString("blackoutMode")
                .let { name -> BlackoutMode.entries.firstOrNull { it.name == name } }
                ?: BlackoutMode.SILENT,
            resetTiming = obj.optString("resetTiming")
                .let { name -> ResetTiming.entries.firstOrNull { it.name == name } }
                ?: ResetTiming.IMMEDIATE,
            appTargets = appTargets,
            addressTargets = obj.optJSONArray("addressTargets")?.toStringList().orEmpty(),
        )
    }

    private fun JSONArray.toStringList(): List<String> =
        List(length()) { index -> getString(index) }
}
