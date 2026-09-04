package com.alphynia.pakomo.data

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.alphynia.pakomo.core.model.DomainTarget
import com.alphynia.pakomo.core.model.NetworkRule
import com.alphynia.pakomo.core.model.TargetScope
import com.alphynia.pakomo.core.model.defaultRules
import org.json.JSONArray
import org.json.JSONObject

class PakomoPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun readScope(): TargetScope =
        runCatching {
            TargetScope.valueOf(
                preferences.getString(KEY_SCOPE, TargetScope.APPLICATIONS.name)
                    ?: TargetScope.APPLICATIONS.name,
            )
        }.getOrDefault(TargetScope.APPLICATIONS)

    fun writeScope(scope: TargetScope) {
        preferences.edit { putString(KEY_SCOPE, scope.name) }
    }

    fun readSelectedPackages(): Set<String> =
        preferences.getStringSet(KEY_SELECTED_PACKAGES, emptySet()).orEmpty()

    fun writeSelectedPackages(packages: Set<String>) {
        preferences.edit { putStringSet(KEY_SELECTED_PACKAGES, packages) }
    }

    /** Full per-app domain targets including disabled ones — for the UI/state. */
    fun readDomainTargetsByPackage(): Map<String, List<DomainTarget>> {
        val raw = preferences.getString(KEY_APP_DOMAINS, null)
        warnIfMalformed(raw, KEY_APP_DOMAINS, expectMap = true)
        return DomainTargetCodec.decodeMap(raw)
    }

    /**
     * Only the **enabled** domains per package, projected to plain strings for the runtime and the
     * automation path. A package whose domains are all disabled drops out of the map entirely, so
     * downstream "no domains = whole app" applies — consistent with having no domains at all.
     */
    fun readDomainsByPackage(): Map<String, List<String>> =
        readDomainTargetsByPackage()
            .mapValues { (_, targets) -> targets.filter { it.enabled }.map { it.value } }
            .filterValues { it.isNotEmpty() }

    fun writeDomainsByPackage(domains: Map<String, List<DomainTarget>>) {
        preferences.edit { putString(KEY_APP_DOMAINS, DomainTargetCodec.encodeMap(domains)) }
    }

    /** Full address-scope targets including disabled ones — for the UI/state. */
    fun readAddressTargets(): List<DomainTarget> {
        val raw = preferences.getString(KEY_ADDRESS_DOMAINS, null)
        warnIfMalformed(raw, KEY_ADDRESS_DOMAINS, expectMap = false)
        return DomainTargetCodec.decodeList(raw)
    }

    /**
     * Surface corrupt stored domain data instead of letting the next write overwrite it silently: the
     * decoders treat malformed input as empty, so without this a parse failure would look identical to
     * "no domains" and be clobbered on the next edit.
     */
    private fun warnIfMalformed(raw: String?, key: String, expectMap: Boolean) {
        if (DomainTargetCodec.isMalformed(raw, expectMap)) {
            Log.w(TAG, "Stored '$key' is unparseable; treating as empty (next write overwrites it)")
        }
    }

    /** Only the **enabled** address targets, projected to strings for the runtime and automation. */
    fun readAddressDomains(): List<String> =
        readAddressTargets().filter { it.enabled }.map { it.value }

    fun writeAddressTargets(targets: List<DomainTarget>) {
        preferences.edit { putString(KEY_ADDRESS_DOMAINS, DomainTargetCodec.encodeList(targets)) }
    }

    fun readActiveRuleId(): String =
        preferences.getString(KEY_ACTIVE_RULE, "light") ?: "light"

    fun writeActiveRuleId(id: String) {
        preferences.edit { putString(KEY_ACTIVE_RULE, id) }
    }

    fun readQuickControlEnabled(): Boolean =
        preferences.getBoolean(KEY_QUICK_CONTROL_ENABLED, false)

    fun writeQuickControlEnabled(enabled: Boolean) {
        preferences.edit { putBoolean(KEY_QUICK_CONTROL_ENABLED, enabled) }
    }

    /**
     * Whether latency compensation is enabled. When on, the shaper absorbs the tunnel's own
     * per-connection setup overhead into the injected delay so the configured latency is the
     * observed result rather than an addition on top of the baseline. Default off — opt-in.
     */
    fun readLatencyCompensationEnabled(): Boolean =
        preferences.getBoolean(KEY_LATENCY_COMPENSATION, false)

    fun writeLatencyCompensationEnabled(enabled: Boolean) {
        preferences.edit { putBoolean(KEY_LATENCY_COMPENSATION, enabled) }
    }

    /** Stored as the [com.alphynia.pakomo.ui.theme.ThemeMode] name; null when never set (→ default Standard). */
    fun readThemeMode(): String? = preferences.getString(KEY_THEME_MODE, null)

    fun writeThemeMode(mode: String) {
        preferences.edit { putString(KEY_THEME_MODE, mode) }
    }

    /** Stored as the [com.alphynia.pakomo.core.model.AppLanguage] name; null when never set (→ default). */
    fun readLanguage(): String? = preferences.getString(KEY_LANGUAGE, null)

    fun writeLanguage(language: String) {
        preferences.edit { putString(KEY_LANGUAGE, language) }
    }

    fun readRules(): List<NetworkRule> {
        val raw = preferences.getString(KEY_RULES, null) ?: return defaultRules
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                NetworkRule(
                    id = item.getString("id"),
                    name = item.getString("name"),
                    latencyMs = item.getInt("latencyMs"),
                    jitterMs = item.getInt("jitterMs"),
                    packetLossPercent = item.getInt("packetLossPercent"),
                    downloadKbps = item.optIntOrNull("downloadKbps"),
                    uploadKbps = item.optIntOrNull("uploadKbps"),
                    isSystem = item.optBoolean("isSystem", false),
                    advanced = item.optBoolean("advanced", false),
                    uploadLatencyMs = item.optInt("uploadLatencyMs", 0),
                    downloadLatencyMs = item.optInt("downloadLatencyMs", 0),
                    uploadJitterMs = item.optInt("uploadJitterMs", 0),
                    downloadJitterMs = item.optInt("downloadJitterMs", 0),
                    uploadLossPercent = item.optInt("uploadLossPercent", 0),
                    downloadLossPercent = item.optInt("downloadLossPercent", 0),
                    specialFaults = SpecialFaultCodec.fromJson(item.optJSONObject("specialFaults")),
                )
            }
        }.getOrDefault(defaultRules)
    }

    fun writeRules(rules: List<NetworkRule>) {
        val array = JSONArray()
        rules.forEach { rule ->
            array.put(
                JSONObject()
                    .put("id", rule.id)
                    .put("name", rule.name)
                    .put("latencyMs", rule.latencyMs)
                    .put("jitterMs", rule.jitterMs)
                    .put("packetLossPercent", rule.packetLossPercent)
                    .put("downloadKbps", rule.downloadKbps ?: JSONObject.NULL)
                    .put("uploadKbps", rule.uploadKbps ?: JSONObject.NULL)
                    .put("isSystem", rule.isSystem)
                    .put("advanced", rule.advanced)
                    .put("uploadLatencyMs", rule.uploadLatencyMs)
                    .put("downloadLatencyMs", rule.downloadLatencyMs)
                    .put("uploadJitterMs", rule.uploadJitterMs)
                    .put("downloadJitterMs", rule.downloadJitterMs)
                    .put("uploadLossPercent", rule.uploadLossPercent)
                    .put("downloadLossPercent", rule.downloadLossPercent)
                    .put("specialFaults", SpecialFaultCodec.toJson(rule.specialFaults)),
            )
        }
        preferences.edit { putString(KEY_RULES, array.toString()) }
    }

    fun clear() {
        preferences.edit { clear() }
    }

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (isNull(key)) null else getInt(key)

    private companion object {
        const val TAG = "PakomoPrefs"
        const val FILE_NAME = "pakomo_private_state"
        const val KEY_SCOPE = "scope"
        const val KEY_SELECTED_PACKAGES = "selected_packages"
        const val KEY_APP_DOMAINS = "app_domains"
        const val KEY_ADDRESS_DOMAINS = "address_domains"
        const val KEY_ACTIVE_RULE = "active_rule"
        const val KEY_RULES = "rules"
        const val KEY_QUICK_CONTROL_ENABLED = "quick_control_enabled"
        const val KEY_LATENCY_COMPENSATION = "latency_compensation_enabled"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_LANGUAGE = "app_language"
    }
}
