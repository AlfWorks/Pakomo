package com.pakomo.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pakomo.core.model.EngineRuntime
import com.pakomo.core.model.EngineStage
import com.pakomo.core.model.InstalledApp
import com.pakomo.core.model.NetworkRule
import com.pakomo.core.model.PakomoUiState
import com.pakomo.core.model.TargetScope
import com.pakomo.core.validation.DomainInputValidator
import com.pakomo.data.InstalledAppCatalog
import com.pakomo.data.PakomoPreferences
import com.pakomo.vpn.VpnServiceController
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PakomoViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = PakomoPreferences(application)
    private val appCatalog = InstalledAppCatalog(application)
    private val storedRules = preferences.readRules()
    private val storedActiveRuleId = preferences.readActiveRuleId()
        .takeIf { candidate -> storedRules.any { it.id == candidate } }
        ?: storedRules.first().id
    private val _state = MutableStateFlow(
        PakomoUiState(
            scope = preferences.readScope(),
            addressDomains = preferences.readAddressDomains(),
            rules = storedRules,
            activeRuleId = storedActiveRuleId,
        ),
    )
    val state: StateFlow<PakomoUiState> = _state.asStateFlow()
    private var configPushJob: Job? = null
    private var appPersistJob: Job? = null
    private var addressPersistJob: Job? = null

    init {
        Log.i(
            TAG,
            "State loaded: scope=${_state.value.scope.name}, rules=${storedRules.size}, addressDomains=${_state.value.addressDomains.size}",
        )
        refreshApps()
    }

    fun refreshApps() {
        viewModelScope.launch {
            _state.update { it.copy(isLoadingApps = true) }
            val apps = withContext(Dispatchers.IO) {
                appCatalog.load(
                    selectedPackages = preferences.readSelectedPackages(),
                    domainsByPackage = preferences.readDomainsByPackage(),
                )
            }
            _state.update { it.copy(apps = apps, isLoadingApps = false) }
            Log.i(TAG, "Application catalog loaded: total=${apps.size}, selected=${apps.count { it.isSelected }}")
        }
    }

    fun selectScope(scope: TargetScope) {
        val previous = _state.value.scope
        if (previous == scope) return
        preferences.writeScope(scope)
        _state.update { it.copy(scope = scope) }
        Log.i(TAG, "Scope changed: ${previous.name} -> ${scope.name}")
        reapplyIfRunning()
    }

    // Rule / domain edits hot-swap on the running pipeline (instant, no dropped connections).
    private fun updateIfRunning() = pushConfigIfRunning(hotSwap = true)

    // Scope / selected-app edits require re-establishing the VPN interface, so rebuild the pipeline.
    private fun reapplyIfRunning() = pushConfigIfRunning(hotSwap = false)

    private fun pushConfigIfRunning(hotSwap: Boolean) {
        val snapshot = _state.value
        if (snapshot.engineStage != EngineStage.FORWARDING) return
        configPushJob?.cancel()
        configPushJob = viewModelScope.launch(Dispatchers.Default) {
            val selectedApps = snapshot.apps.filter(InstalledApp::isSelected)
            val packages = selectedApps.map(InstalledApp::packageName)
            val domainsByPackage = selectedApps.asSequence()
                .filter { it.domains.isNotEmpty() }
                .associate { it.packageName to it.domains }
            ensureActive()
            if (_state.value.engineStage != EngineStage.FORWARDING) return@launch
            val context = getApplication<Application>()
            when {
                !hotSwap -> VpnServiceController.start(
                    context,
                    snapshot.scope,
                    packages,
                    snapshot.addressDomains,
                    domainsByPackage,
                    snapshot.activeRule,
                )
                else -> VpnServiceController.update(
                    context,
                    snapshot.scope,
                    packages,
                    snapshot.addressDomains,
                    domainsByPackage,
                    snapshot.activeRule,
                )
            }
        }
    }

    fun setAppQuery(query: String) {
        _state.update { it.copy(appQuery = query) }
    }

    fun toggleApp(packageName: String) {
        updateApp(packageName) { app -> app.copy(isSelected = !app.isSelected) }
        persistApps()
        val current = _state.value
        val selected = current.apps.firstOrNull { it.packageName == packageName }?.isSelected == true
        Log.i(TAG, "Application selection changed: selected=$selected, total=${current.selectedApps.size}")
        reapplyIfRunning() // changing the selected-app set re-establishes the VPN interface
    }

    fun toggleAppExpanded(packageName: String) {
        updateApp(packageName) { app -> app.copy(isExpanded = !app.isExpanded) }
    }

    fun addDomain(packageName: String, input: String): String? {
        val domain = DomainInputValidator.normalizeOrNull(input)
            ?: return "请输入有效域名，例如 api.example.com"
        val target = _state.value.apps.firstOrNull { it.packageName == packageName }
            ?: return "应用已不存在"
        if (target.domains.any { it.equals(domain, ignoreCase = true) }) {
            return "这个域名已经添加"
        }
        updateApp(packageName) { app -> app.copy(domains = app.domains + domain) }
        persistApps()
        val count = _state.value.apps.firstOrNull { it.packageName == packageName }?.domains?.size ?: 0
        Log.i(TAG, "Application domain added: count=$count")
        updateIfRunning()
        return null
    }

    fun removeDomain(packageName: String, domain: String) {
        updateApp(packageName) { app ->
            app.copy(domains = app.domains.filterNot { it == domain })
        }
        persistApps()
        val count = _state.value.apps.firstOrNull { it.packageName == packageName }?.domains?.size ?: 0
        Log.i(TAG, "Application domain removed: count=$count")
        updateIfRunning()
    }

    fun addAddressDomain(input: String): String? {
        val domain = DomainInputValidator.normalizeOrNull(input)
            ?: return "请输入有效域名，例如 api.example.com"
        if (_state.value.addressDomains.any { it.equals(domain, ignoreCase = true) }) {
            return "这个域名已经添加"
        }
        val updated = _state.value.addressDomains + domain
        _state.update { it.copy(addressDomains = updated) }
        persistAddressDomains(updated)
        Log.i(TAG, "Address domain added: count=${updated.size}")
        updateIfRunning()
        return null
    }

    fun removeAddressDomain(domain: String) {
        val updated = _state.value.addressDomains.filterNot { it == domain }
        _state.update { it.copy(addressDomains = updated) }
        persistAddressDomains(updated)
        Log.i(TAG, "Address domain removed: count=${updated.size}")
        updateIfRunning()
    }

    fun selectRule(ruleId: String) {
        val rule = _state.value.rules.firstOrNull { it.id == ruleId } ?: return
        if (_state.value.activeRuleId == ruleId) return
        preferences.writeActiveRuleId(ruleId)
        _state.update { it.copy(activeRuleId = ruleId) }
        Log.i(TAG, "Rule selected: ${rule.name}")
        updateIfRunning()
    }

    fun saveRule(rule: NetworkRule) {
        val current = _state.value
        val existing = current.rules.firstOrNull { it.id == rule.id }
        val updated = if (existing != null) {
            current.rules.map { row -> if (row.id == rule.id) rule else row }
        } else {
            current.rules + rule
        }
        _state.update { it.copy(rules = updated) }
        persistRules(updated)
        Log.i(
            TAG,
            "Rule saved: ${rule.name}, created=${current.rules.none { it.id == rule.id }}, total=${updated.size}",
        )
        if (rule.id == _state.value.activeRuleId) updateIfRunning()
    }

    fun duplicateRule(ruleId: String): NetworkRule? {
        val source = _state.value.rules.firstOrNull { it.id == ruleId } ?: return null
        Log.i(TAG, "Rule duplicated: ${source.name}")
        return source.copy(
            id = UUID.randomUUID().toString(),
            name = "${source.name}副本",
            isSystem = false,
        )
    }

    fun newRule(): NetworkRule {
        Log.i(TAG, "New rule draft created")
        return NetworkRule(
            id = UUID.randomUUID().toString(),
            name = "新规则",
            latencyMs = 100,
            jitterMs = 20,
            packetLossPercent = 1,
            downloadKbps = 1_024,
            uploadKbps = 512,
            isSystem = false,
        )
    }

    fun deleteRule(ruleId: String) {
        val current = _state.value
        val target = current.rules.firstOrNull { it.id == ruleId } ?: return
        if (target.isSystem || current.rules.size == 1) return
        val updated = current.rules.filterNot { it.id == ruleId }
        val active = if (current.activeRuleId == ruleId) updated.first().id else current.activeRuleId
        preferences.writeActiveRuleId(active)
        _state.update { it.copy(rules = updated, activeRuleId = active) }
        persistRules(updated)
        Log.i(TAG, "Rule deleted: ${target.name}, total=${updated.size}")
    }

    fun setEngineRuntime(runtime: EngineRuntime) {
        _state.update {
            it.copy(
                engineStage = runtime.stage,
                stats = runtime.stats,
                engineMessage = runtime.message,
            )
        }
    }

    fun clearLocalData() {
        Log.w(TAG, "Clearing all local configuration")
        preferences.clear()
        _state.value = PakomoUiState()
        refreshApps()
    }

    private fun updateApp(packageName: String, block: (InstalledApp) -> InstalledApp) {
        _state.update { current ->
            current.copy(
                apps = current.apps.map { app ->
                    if (app.packageName == packageName) block(app) else app
                },
            )
        }
    }

    private fun persistApps() {
        val apps = _state.value.apps
        appPersistJob?.cancel()
        appPersistJob = viewModelScope.launch(Dispatchers.IO) {
            preferences.writeSelectedPackages(
                apps.filter(InstalledApp::isSelected)
                    .mapTo(linkedSetOf(), InstalledApp::packageName),
            )
            preferences.writeDomainsByPackage(
                apps.asSequence()
                    .filter { it.domains.isNotEmpty() }
                    .associate { it.packageName to it.domains },
            )
        }
    }

    private fun persistAddressDomains(domains: List<String>) {
        addressPersistJob?.cancel()
        addressPersistJob = viewModelScope.launch(Dispatchers.IO) {
            preferences.writeAddressDomains(domains)
        }
    }

    private fun persistRules(rules: List<NetworkRule>) {
        viewModelScope.launch(Dispatchers.IO) {
            preferences.writeRules(rules)
        }
    }

    private companion object {
        const val TAG = "PakomoState"
    }
}
