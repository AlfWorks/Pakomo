package com.pakomo.automation

import android.content.Context
import com.pakomo.core.model.NetworkRule
import com.pakomo.core.model.TargetScope
import com.pakomo.core.model.defaultRules
import com.pakomo.data.PakomoPreferences
import com.pakomo.data.SpecialFaultCodec
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Immutable snapshot of everything [com.pakomo.vpn.VpnServiceController.start] needs. Assembled
 * either from the app's persisted UI state (P1, [fromPreferences]) or fully specified by an
 * external profile file (P2, [loadProfile]) so automation runs can be hermetic.
 */
internal data class ControlRequest(
    val scope: TargetScope,
    val packages: List<String>,
    val domainsByPackage: Map<String, List<String>>,
    val addressDomains: List<String>,
    val rule: NetworkRule,
)

/** Success or a typed failure carrying the error code + human message for the JSON response. */
internal sealed interface ProfileResult {
    data class Ok(val request: ControlRequest) : ProfileResult
    data class Err(val error: ControlError, val message: String) : ProfileResult
}

/**
 * Turns the wire-level rule/profile references into a concrete [ControlRequest]. Rule JSON fields
 * mirror [PakomoPreferences] exactly (and reuse [SpecialFaultCodec] for faults) so the on-disk,
 * on-the-wire, and automation formats never drift. Debug-only, per §9.
 */
internal object ProfileCodec {

    private const val PROFILES_DIR = "pakomo/profiles"

    /**
     * P1 path: take the app's persisted scope/apps/domains and (optionally) override just the rule
     * by preset id or saved-rule id/name. This mirrors QuickControlService's start assembly, so
     * automation drives the same configuration the UI would.
     */
    fun fromPreferences(prefs: PakomoPreferences, ruleRef: String?): ProfileResult {
        val rule = if (ruleRef == null) {
            activeRule(prefs)
        } else {
            resolveRule(prefs, ruleRef)
                ?: return ProfileResult.Err(
                    ControlError.PROFILE_NOT_FOUND,
                    "no preset or saved rule named '$ruleRef'",
                )
        }
        val packages = prefs.readSelectedPackages().toList()
        return ProfileResult.Ok(
            ControlRequest(
                scope = prefs.readScope(),
                packages = packages,
                domainsByPackage = prefs.readDomainsByPackage().filterKeys(packages::contains),
                addressDomains = prefs.readAddressDomains(),
                rule = rule,
            ),
        )
    }

    /** Resolve the "normal" preset for the RESET command. Always present in [defaultRules]. */
    fun normalRule(): NetworkRule = defaultRules.first { it.id == "normal" }

    /** P2 path: fully specify the request from a pushed profile JSON file. */
    fun loadProfile(context: Context, prefs: PakomoPreferences, name: String): ProfileResult {
        val safe = name.substringBeforeLast(".json").trim()
        if (safe.isEmpty() || safe.contains('/') || safe.contains('\\') || safe.contains("..")) {
            return ProfileResult.Err(ControlError.INVALID_ARGS, "illegal profile name '$name'")
        }
        val file = File(File(context.getExternalFilesDir(null), PROFILES_DIR), "$safe.json")
        if (!file.isFile) {
            return ProfileResult.Err(ControlError.PROFILE_NOT_FOUND, "missing ${file.absolutePath}")
        }
        val root = runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrNull()
            ?: return ProfileResult.Err(ControlError.PROFILE_INVALID, "not valid JSON: ${file.name}")
        return parseProfile(prefs, root)
    }

    // ---- internals ----

    private fun activeRule(prefs: PakomoPreferences): NetworkRule {
        val rules = prefs.readRules()
        return rules.firstOrNull { it.id == prefs.readActiveRuleId() }
            ?: rules.firstOrNull()
            ?: defaultRules[1]
    }

    private fun resolveRule(prefs: PakomoPreferences, ref: String): NetworkRule? {
        defaultRules.firstOrNull { it.id.equals(ref, ignoreCase = true) }?.let { return it }
        return prefs.readRules().firstOrNull {
            it.id.equals(ref, ignoreCase = true) || it.name.equals(ref, ignoreCase = true)
        }
    }

    private fun parseProfile(prefs: PakomoPreferences, root: JSONObject): ProfileResult {
        val scope = scopeFromWire(root.optString("scope", TargetScope.GLOBAL.name))
            ?: return ProfileResult.Err(
                ControlError.PROFILE_INVALID,
                "scope must be one of global|applications|addresses",
            )

        val packages = root.optJSONArray("apps")?.toStringList().orEmpty()
        val domainsByPackage = root.optJSONObject("domainsByApp")?.let { obj ->
            obj.keys().asSequence().associateWith { key -> obj.getJSONArray(key).toStringList() }
        }.orEmpty()
        val addressDomains = root.optJSONArray("domains")?.toStringList().orEmpty()

        val rule = when (val ruleField = root.opt("rule")) {
            is String -> resolveRule(prefs, ruleField)
                ?: return ProfileResult.Err(
                    ControlError.PROFILE_NOT_FOUND,
                    "profile references unknown rule '$ruleField'",
                )
            is JSONObject -> parseInlineRule(ruleField)
                ?: return ProfileResult.Err(ControlError.PROFILE_INVALID, "invalid inline rule")
            else -> return ProfileResult.Err(
                ControlError.PROFILE_INVALID,
                "'rule' must be a preset name or an inline rule object",
            )
        }

        validateRule(rule)?.let { return ProfileResult.Err(ControlError.PROFILE_INVALID, it) }

        return ProfileResult.Ok(
            ControlRequest(scope, packages, domainsByPackage, addressDomains, rule),
        )
    }

    private fun scopeFromWire(value: String?): TargetScope? = when (value?.lowercase()) {
        "global", "all" -> TargetScope.GLOBAL
        "applications", "apps", "app" -> TargetScope.APPLICATIONS
        "addresses", "address" -> TargetScope.ADDRESSES
        else -> TargetScope.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }

    /** Mirrors [PakomoPreferences]'s per-rule JSON shape; missing fields fall back to sane defaults. */
    private fun parseInlineRule(obj: JSONObject): NetworkRule? = runCatching {
        NetworkRule(
            id = obj.optString("id").ifBlank { "auto-${System.currentTimeMillis()}" },
            name = obj.optString("name").ifBlank { "automation" },
            latencyMs = obj.optInt("latencyMs", 0),
            jitterMs = obj.optInt("jitterMs", 0),
            packetLossPercent = obj.optInt("packetLossPercent", 0),
            downloadKbps = obj.optIntOrNull("downloadKbps"),
            uploadKbps = obj.optIntOrNull("uploadKbps"),
            isSystem = false,
            advanced = obj.optBoolean("advanced", false),
            uploadLatencyMs = obj.optInt("uploadLatencyMs", 0),
            downloadLatencyMs = obj.optInt("downloadLatencyMs", 0),
            uploadJitterMs = obj.optInt("uploadJitterMs", 0),
            downloadJitterMs = obj.optInt("downloadJitterMs", 0),
            uploadLossPercent = obj.optInt("uploadLossPercent", 0),
            downloadLossPercent = obj.optInt("downloadLossPercent", 0),
            specialFaults = SpecialFaultCodec.fromJson(obj.optJSONObject("specialFaults")),
        )
    }.getOrNull()

    /** Range validation aligned with core/validation + PakomoModelsTest expectations. */
    private fun validateRule(rule: NetworkRule): String? = when {
        rule.latencyMs < 0 || rule.jitterMs < 0 -> "latency/jitter must be >= 0"
        rule.packetLossPercent !in 0..100 -> "packetLossPercent must be 0..100"
        rule.uploadLossPercent !in 0..100 || rule.downloadLossPercent !in 0..100 ->
            "per-direction loss must be 0..100"
        rule.downloadKbps != null && rule.downloadKbps <= 0 -> "downloadKbps must be > 0 or null"
        rule.uploadKbps != null && rule.uploadKbps <= 0 -> "uploadKbps must be > 0 or null"
        else -> null
    }

    private fun JSONArray.toStringList(): List<String> = List(length()) { getString(it) }

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (!has(key) || isNull(key)) null else getInt(key)
}
