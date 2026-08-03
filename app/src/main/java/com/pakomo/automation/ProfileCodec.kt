package com.pakomo.automation

import android.content.Context
import com.pakomo.core.model.NetworkRule
import com.pakomo.core.model.BlackoutMode
import com.pakomo.core.model.DnsFailureResult
import com.pakomo.core.model.ResetTiming
import com.pakomo.core.model.SpecialFaultConfig
import com.pakomo.core.model.SpecialFaultType
import com.pakomo.core.model.TargetScope
import com.pakomo.core.model.defaultRules
import com.pakomo.data.PakomoPreferences
import com.pakomo.data.SpecialFaultCodec
import org.json.JSONArray
import org.json.JSONException
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
 * on-the-wire, and automation formats never drift across build types.
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
        // Any structural type error (wrong JSON type for a field) throws JSONException; classify the
        // whole lot as PROFILE_INVALID (with the offending field from the message) rather than
        // letting it bubble up as an opaque ENGINE_ERROR.
        try {
            val scope = scopeFromWire(root.strictString("scope", TargetScope.GLOBAL.name))
                ?: return ProfileResult.Err(
                    ControlError.PROFILE_INVALID,
                    "scope must be one of global|applications|addresses",
                )

            val packages = jsonStringList(root, "apps")
            val domainsByPackage = if (!root.has("domainsByApp") || root.isNull("domainsByApp")) {
                emptyMap()
            } else {
                // getJSONObject / getJSONArray throw on a wrong-typed field → caught below as
                // PROFILE_INVALID, matching the strict handling of the array fields.
                val obj = root.getJSONObject("domainsByApp")
                obj.keys().asSequence().associateWith { key -> obj.getJSONArray(key).toStringList() }
            }
            val addressDomains = jsonStringList(root, "domains")

            val rule = when (val ruleField = root.opt("rule")) {
                is String -> resolveRule(prefs, ruleField)
                    ?: return ProfileResult.Err(
                        ControlError.PROFILE_NOT_FOUND,
                        "profile references unknown rule '$ruleField'",
                    )
                is JSONObject -> parseInlineRule(ruleField)
                else -> return ProfileResult.Err(
                    ControlError.PROFILE_INVALID,
                    "'rule' must be a preset name or an inline rule object",
                )
            }

            validateRule(rule)?.let { return ProfileResult.Err(ControlError.PROFILE_INVALID, it) }

            return ProfileResult.Ok(
                ControlRequest(scope, packages, domainsByPackage, addressDomains, rule),
            )
        } catch (e: JSONException) {
            return ProfileResult.Err(ControlError.PROFILE_INVALID, "malformed profile field: ${e.message}")
        }
    }

    /** Strict string-array read: a present-but-wrong-typed field is an error, not a silent empty. */
    private fun jsonStringList(root: JSONObject, key: String): List<String> = when {
        !root.has(key) || root.isNull(key) -> emptyList()
        else -> root.getJSONArray(key).toStringList()
    }

    private fun scopeFromWire(value: String?): TargetScope? = when (value?.lowercase()) {
        "global", "all" -> TargetScope.GLOBAL
        "applications", "apps", "app" -> TargetScope.APPLICATIONS
        "addresses", "address" -> TargetScope.ADDRESSES
        else -> TargetScope.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }

    /**
     * Mirrors [PakomoPreferences]'s per-rule JSON shape; missing fields fall back to sane defaults,
     * but a present-but-wrong-typed field throws JSONException (caught by [parseProfile] as
     * PROFILE_INVALID) rather than being silently coerced to a default — e.g. `"latencyMs":"oops"`
     * or `"specialFaults":[]` are rejected, not ignored.
     */
    private fun parseInlineRule(obj: JSONObject): NetworkRule = NetworkRule(
        id = obj.strictString("id", "").ifBlank { "auto-${System.currentTimeMillis()}" },
        name = obj.strictString("name", "").ifBlank { "automation" },
        latencyMs = obj.strictInt("latencyMs", 0),
        jitterMs = obj.strictInt("jitterMs", 0),
        packetLossPercent = obj.strictInt("packetLossPercent", 0),
        downloadKbps = obj.strictNullableInt("downloadKbps"),
        uploadKbps = obj.strictNullableInt("uploadKbps"),
        isSystem = false,
        advanced = obj.strictBoolean("advanced", false),
        uploadLatencyMs = obj.strictInt("uploadLatencyMs", 0),
        downloadLatencyMs = obj.strictInt("downloadLatencyMs", 0),
        uploadJitterMs = obj.strictInt("uploadJitterMs", 0),
        downloadJitterMs = obj.strictInt("downloadJitterMs", 0),
        uploadLossPercent = obj.strictInt("uploadLossPercent", 0),
        downloadLossPercent = obj.strictInt("downloadLossPercent", 0),
        specialFaults = if (!obj.has("specialFaults") || obj.isNull("specialFaults")) {
            SpecialFaultConfig()
        } else {
            obj.getJSONObject("specialFaults").also(::validateSpecialFaults)
                .let(SpecialFaultCodec::fromJson)
        },
    )

    /** Range validation aligned with core/validation + PakomoModelsTest expectations. */
    private fun validateRule(rule: NetworkRule): String? = when {
        !rule.advanced && rule.latencyMs !in 0..60_000 -> "latencyMs must be 0..60000"
        !rule.advanced && rule.jitterMs !in 0..30_000 -> "jitterMs must be 0..30000"
        rule.advanced && (rule.uploadLatencyMs !in 0..60_000 || rule.downloadLatencyMs !in 0..60_000) ->
            "per-direction latency must be 0..60000"
        rule.advanced && (rule.uploadJitterMs !in 0..30_000 || rule.downloadJitterMs !in 0..30_000) ->
            "per-direction jitter must be 0..30000"
        rule.packetLossPercent !in 0..100 -> "packetLossPercent must be 0..100"
        rule.uploadLossPercent !in 0..100 || rule.downloadLossPercent !in 0..100 ->
            "per-direction loss must be 0..100"
        rule.downloadKbps != null && rule.downloadKbps <= 0 -> "downloadKbps must be > 0 or null"
        rule.uploadKbps != null && rule.uploadKbps <= 0 -> "uploadKbps must be > 0 or null"
        else -> null
    }

    private fun JSONArray.toStringList(): List<String> = List(length()) { getString(it) }

    private fun JSONObject.strictNullableInt(key: String): Int? =
        if (!has(key) || isNull(key)) null else strictInt(key, 0)

    /** Absent/null -> default; present -> require the actual JSON type, without string coercion. */
    private fun JSONObject.strictInt(key: String, default: Int): Int {
        if (!has(key) || isNull(key)) return default
        val value = get(key)
        if (value !is Number) throw JSONException("'$key' must be an integer")
        val asDouble = value.toDouble()
        val asLong = value.toLong()
        if (
            !asDouble.isFinite() || asDouble != asLong.toDouble() ||
            asLong < Int.MIN_VALUE.toLong() || asLong > Int.MAX_VALUE.toLong()
        ) {
            throw JSONException("'$key' must be a 32-bit integer")
        }
        return asLong.toInt()
    }

    private fun JSONObject.strictLong(key: String, default: Long): Long {
        if (!has(key) || isNull(key)) return default
        val value = get(key)
        if (value !is Number) throw JSONException("'$key' must be an integer")
        val asDouble = value.toDouble()
        val asLong = value.toLong()
        if (!asDouble.isFinite() || asDouble != asLong.toDouble()) {
            throw JSONException("'$key' must be an integer")
        }
        return asLong
    }

    private fun JSONObject.strictBoolean(key: String, default: Boolean): Boolean {
        if (!has(key) || isNull(key)) return default
        return get(key) as? Boolean ?: throw JSONException("'$key' must be a boolean")
    }

    private fun JSONObject.strictString(key: String, default: String): String {
        if (!has(key) || isNull(key)) return default
        return get(key) as? String ?: throw JSONException("'$key' must be a string")
    }

    /** Validate the nested special-fault graph before reusing the preferences codec (which is lenient). */
    private fun validateSpecialFaults(root: JSONObject) {
        SpecialFaultType.entries.forEach { type ->
            if (!root.has(type.name) || root.isNull(type.name)) return@forEach
            val fault = root.getJSONObject(type.name)
            fault.strictBoolean("enabled", false)
            fault.strictBoolean("dnsCacheGuard", false)
            fault.strictString("dnsResult", DnsFailureResult.NXDOMAIN.name).let { value ->
                if (DnsFailureResult.entries.none { it.name == value }) {
                    throw JSONException("'${type.name}.dnsResult' has unknown value '$value'")
                }
            }
            fault.strictString("blackoutMode", BlackoutMode.SILENT.name).let { value ->
                if (BlackoutMode.entries.none { it.name == value }) {
                    throw JSONException("'${type.name}.blackoutMode' has unknown value '$value'")
                }
            }
            fault.strictString("resetTiming", ResetTiming.IMMEDIATE.name).let { value ->
                if (ResetTiming.entries.none { it.name == value }) {
                    throw JSONException("'${type.name}.resetTiming' has unknown value '$value'")
                }
            }
            if (fault.strictLong("holdMs", 0L) < 0L) throw JSONException("'${type.name}.holdMs' must be >= 0")
            if (fault.strictInt("holdBypassBytes", 0) < 0) {
                throw JSONException("'${type.name}.holdBypassBytes' must be >= 0")
            }
            if (fault.has("addressTargets") && !fault.isNull("addressTargets")) {
                fault.getJSONArray("addressTargets").toStringList()
            }
            if (fault.has("appTargets") && !fault.isNull("appTargets")) {
                val apps = fault.getJSONObject("appTargets")
                apps.keys().forEach { packageName ->
                    val target = apps.getJSONObject(packageName)
                    target.strictBoolean("enabled", false)
                    if (target.has("domains") && !target.isNull("domains")) {
                        target.getJSONArray("domains").toStringList()
                    }
                }
            }
        }
    }
}
