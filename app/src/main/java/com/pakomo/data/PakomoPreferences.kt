package com.pakomo.data

import android.content.Context
import androidx.core.content.edit
import com.pakomo.core.model.NetworkRule
import com.pakomo.core.model.TargetScope
import com.pakomo.core.model.defaultRules
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

    fun readDomainsByPackage(): Map<String, List<String>> {
        val raw = preferences.getString(KEY_APP_DOMAINS, null) ?: return emptyMap()
        return runCatching {
            val root = JSONObject(raw)
            root.keys().asSequence().associateWith { packageName ->
                root.getJSONArray(packageName).toStringList()
            }
        }.getOrDefault(emptyMap())
    }

    fun writeDomainsByPackage(domains: Map<String, List<String>>) {
        val root = JSONObject()
        domains.forEach { (packageName, values) ->
            root.put(packageName, JSONArray(values))
        }
        preferences.edit { putString(KEY_APP_DOMAINS, root.toString()) }
    }

    fun readAddressDomains(): List<String> {
        val raw = preferences.getString(KEY_ADDRESS_DOMAINS, null) ?: return emptyList()
        return runCatching { JSONArray(raw).toStringList() }.getOrDefault(emptyList())
    }

    fun writeAddressDomains(domains: List<String>) {
        preferences.edit { putString(KEY_ADDRESS_DOMAINS, JSONArray(domains).toString()) }
    }

    fun readActiveRuleId(): String =
        preferences.getString(KEY_ACTIVE_RULE, "light") ?: "light"

    fun writeActiveRuleId(id: String) {
        preferences.edit { putString(KEY_ACTIVE_RULE, id) }
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
                    .put("isSystem", rule.isSystem),
            )
        }
        preferences.edit { putString(KEY_RULES, array.toString()) }
    }

    fun clear() {
        preferences.edit { clear() }
    }

    private fun JSONArray.toStringList(): List<String> =
        List(length()) { index -> getString(index) }

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (isNull(key)) null else getInt(key)

    private companion object {
        const val FILE_NAME = "pakomo_private_state"
        const val KEY_SCOPE = "scope"
        const val KEY_SELECTED_PACKAGES = "selected_packages"
        const val KEY_APP_DOMAINS = "app_domains"
        const val KEY_ADDRESS_DOMAINS = "address_domains"
        const val KEY_ACTIVE_RULE = "active_rule"
        const val KEY_RULES = "rules"
    }
}
