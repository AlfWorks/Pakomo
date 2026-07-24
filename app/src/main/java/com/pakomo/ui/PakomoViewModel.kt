package com.pakomo.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pakomo.core.model.EngineRuntime
import com.pakomo.core.model.InstalledApp
import com.pakomo.core.model.NetworkRule
import com.pakomo.core.model.PakomoUiState
import com.pakomo.core.model.TargetScope
import com.pakomo.core.validation.DomainInputValidator
import com.pakomo.data.InstalledAppCatalog
import com.pakomo.data.PakomoPreferences
import java.util.UUID
import kotlinx.coroutines.Dispatchers
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

    init {
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
        }
    }

    fun selectScope(scope: TargetScope) {
        preferences.writeScope(scope)
        _state.update { it.copy(scope = scope) }
    }

    fun setAppQuery(query: String) {
        _state.update { it.copy(appQuery = query) }
    }

    fun setShowSystemApps(show: Boolean) {
        _state.update { it.copy(showSystemApps = show) }
    }

    fun toggleApp(packageName: String) {
        updateApp(packageName) { app -> app.copy(isSelected = !app.isSelected) }
        persistApps()
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
        return null
    }

    fun removeDomain(packageName: String, domain: String) {
        updateApp(packageName) { app ->
            app.copy(domains = app.domains.filterNot { it == domain })
        }
        persistApps()
    }

    fun addAddressDomain(input: String): String? {
        val domain = DomainInputValidator.normalizeOrNull(input)
            ?: return "请输入有效域名，例如 api.example.com"
        if (_state.value.addressDomains.any { it.equals(domain, ignoreCase = true) }) {
            return "这个域名已经添加"
        }
        val updated = _state.value.addressDomains + domain
        preferences.writeAddressDomains(updated)
        _state.update { it.copy(addressDomains = updated) }
        return null
    }

    fun removeAddressDomain(domain: String) {
        val updated = _state.value.addressDomains.filterNot { it == domain }
        preferences.writeAddressDomains(updated)
        _state.update { it.copy(addressDomains = updated) }
    }

    fun selectRule(ruleId: String) {
        if (_state.value.rules.none { it.id == ruleId }) return
        preferences.writeActiveRuleId(ruleId)
        _state.update { it.copy(activeRuleId = ruleId) }
    }

    fun saveRule(rule: NetworkRule) {
        val current = _state.value
        val updated = if (current.rules.any { it.id == rule.id }) {
            current.rules.map { existing -> if (existing.id == rule.id) rule else existing }
        } else {
            current.rules + rule
        }
        preferences.writeRules(updated)
        _state.update { it.copy(rules = updated) }
    }

    fun duplicateRule(ruleId: String): NetworkRule? {
        val source = _state.value.rules.firstOrNull { it.id == ruleId } ?: return null
        return source.copy(
            id = UUID.randomUUID().toString(),
            name = "${source.name}副本",
            isSystem = false,
        )
    }

    fun newRule(): NetworkRule = NetworkRule(
        id = UUID.randomUUID().toString(),
        name = "新规则",
        latencyMs = 100,
        jitterMs = 20,
        packetLossPercent = 1,
        downloadKbps = 1_024,
        uploadKbps = 512,
        isSystem = false,
    )

    fun deleteRule(ruleId: String) {
        val current = _state.value
        val target = current.rules.firstOrNull { it.id == ruleId } ?: return
        if (target.isSystem || current.rules.size == 1) return
        val updated = current.rules.filterNot { it.id == ruleId }
        val active = if (current.activeRuleId == ruleId) updated.first().id else current.activeRuleId
        preferences.writeRules(updated)
        preferences.writeActiveRuleId(active)
        _state.update { it.copy(rules = updated, activeRuleId = active) }
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
        preferences.writeSelectedPackages(
            apps.filter(InstalledApp::isSelected).mapTo(linkedSetOf(), InstalledApp::packageName),
        )
        preferences.writeDomainsByPackage(
            apps.filter { it.domains.isNotEmpty() }.associate { it.packageName to it.domains },
        )
    }

}
