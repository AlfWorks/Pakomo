package com.pakomo.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pakomo.core.model.EngineRuntime
import com.pakomo.core.model.EngineStage
import com.pakomo.core.model.InstalledApp
import com.pakomo.core.model.NetworkRule
import com.pakomo.core.model.AppFaultTarget
import com.pakomo.core.model.BlackoutMode
import com.pakomo.core.model.DnsFailureResult
import com.pakomo.core.model.PakomoUiState
import com.pakomo.core.model.SpecialFault
import com.pakomo.core.model.SpecialFaultType
import com.pakomo.core.model.TargetScope
import com.pakomo.core.validation.DomainInputValidator
import com.pakomo.data.InstalledAppCatalog
import com.pakomo.data.PakomoPreferences
import com.pakomo.vpn.VpnServiceController
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    private var faultCommitJob: Job? = null

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
        val current = _state.value
        if (current.engineStage != EngineStage.FORWARDING) return
        val context = getApplication<Application>()
        val packages = current.selectedApps.map { it.packageName }
        val domainsByPackage = current.selectedApps
            .filter { it.domains.isNotEmpty() }
            .associate { it.packageName to it.domains }
        if (hotSwap) {
            VpnServiceController.update(
                context, current.scope, packages, current.addressDomains, domainsByPackage, current.activeRule,
            )
        } else {
            VpnServiceController.start(
                context, current.scope, packages, current.addressDomains, domainsByPackage, current.activeRule,
            )
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
        preferences.writeAddressDomains(updated)
        _state.update { it.copy(addressDomains = updated) }
        Log.i(TAG, "Address domain added: count=${updated.size}")
        updateIfRunning()
        return null
    }

    fun removeAddressDomain(domain: String) {
        val updated = _state.value.addressDomains.filterNot { it == domain }
        preferences.writeAddressDomains(updated)
        _state.update { it.copy(addressDomains = updated) }
        Log.i(TAG, "Address domain removed: count=${updated.size}")
        updateIfRunning()
    }

    fun selectRule(ruleId: String) {
        val rule = _state.value.rules.firstOrNull { it.id == ruleId } ?: return
        preferences.writeActiveRuleId(ruleId)
        _state.update { it.copy(activeRuleId = ruleId) }
        Log.i(TAG, "Rule selected: ${rule.name}")
        updateIfRunning()
    }

    fun saveRule(rule: NetworkRule) {
        val current = _state.value
        val existing = current.rules.firstOrNull { it.id == rule.id }
        // 弱网参数走这里保存；特殊故障只由 mutateFault 变更，保留已存规则的那一份，
        // 避免编辑器里的 draft 快照把用户在故障入口的改动覆盖回旧值。
        val merged = if (existing != null) rule.copy(specialFaults = existing.specialFaults) else rule
        val updated = if (existing != null) {
            current.rules.map { row -> if (row.id == rule.id) merged else row }
        } else {
            current.rules + merged
        }
        preferences.writeRules(updated)
        _state.update { it.copy(rules = updated) }
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
        preferences.writeRules(updated)
        preferences.writeActiveRuleId(active)
        _state.update { it.copy(rules = updated, activeRuleId = active) }
        Log.i(TAG, "Rule deleted: ${target.name}, total=${updated.size}")
    }

    /**
     * 编辑某条规则携带的一项特殊故障。改动即时持久化，若该规则正在生效则热更新，
     * 与弱网参数草稿的“保存”按钮分开。目标选择、参数、开关都走这里。
     */
    fun mutateFault(ruleId: String, type: SpecialFaultType, block: (SpecialFault) -> SpecialFault) {
        val current = _state.value
        val rule = current.rules.firstOrNull { it.id == ruleId } ?: return
        val updatedFault = block(rule.specialFaults.fault(type))
        if (updatedFault == rule.specialFaults.fault(type)) return
        val updatedRule = rule.copy(specialFaults = rule.specialFaults.withFault(updatedFault))
        val rules = current.rules.map { if (it.id == ruleId) updatedRule else it }
        _state.update { it.copy(rules = rules) }
        Log.i(TAG, "Special fault updated: rule=${rule.name}, type=${type.name}, enabled=${updatedFault.enabled}")
        // A user often toggles several targets in quick succession. Coalesce the expensive full
        // JSON write and VPN runtime rebuild instead of doing both on the UI thread for every tap.
        faultCommitJob?.cancel()
        faultCommitJob = viewModelScope.launch {
            delay(FAULT_COMMIT_DEBOUNCE_MS)
            val snapshot = _state.value
            withContext(Dispatchers.IO) {
                preferences.writeRules(snapshot.rules)
            }
            if (ruleId == snapshot.activeRuleId) updateIfRunning()
        }
    }

    fun setFaultEnabled(ruleId: String, type: SpecialFaultType, enabled: Boolean) =
        mutateFault(ruleId, type) { it.copy(enabled = enabled) }

    fun setFaultAppEnabled(ruleId: String, type: SpecialFaultType, packageName: String, enabled: Boolean) =
        mutateFault(ruleId, type) { fault ->
            val existing = fault.appTargets[packageName] ?: AppFaultTarget(packageName)
            fault.copy(appTargets = fault.appTargets + (packageName to existing.copy(enabled = enabled)))
        }

    fun toggleFaultAppDomain(
        ruleId: String,
        type: SpecialFaultType,
        packageName: String,
        domain: String,
        selected: Boolean,
    ) = mutateFault(ruleId, type) { fault ->
        val existing = fault.appTargets[packageName] ?: AppFaultTarget(packageName)
        val domains = if (selected) {
            (existing.domains + domain).distinct()
        } else {
            existing.domains.filterNot { it == domain }
        }
        fault.copy(appTargets = fault.appTargets + (packageName to existing.copy(domains = domains)))
    }

    fun setFaultBlackoutMode(ruleId: String, mode: BlackoutMode) =
        mutateFault(ruleId, SpecialFaultType.NETWORK_BLACKOUT) { it.copy(blackoutMode = mode) }

    fun setFaultDnsResult(ruleId: String, result: DnsFailureResult) =
        mutateFault(ruleId, SpecialFaultType.DNS_FAILURE) { it.copy(dnsResult = result) }

    fun setFaultDnsCacheGuard(ruleId: String, enabled: Boolean) =
        mutateFault(ruleId, SpecialFaultType.DNS_FAILURE) { it.copy(dnsCacheGuard = enabled) }

    fun toggleFaultAddress(ruleId: String, type: SpecialFaultType, domain: String, selected: Boolean) =
        mutateFault(ruleId, type) { fault ->
            val addresses = if (selected) {
                (fault.addressTargets + domain).distinct()
            } else {
                fault.addressTargets.filterNot { it == domain }
            }
            fault.copy(addressTargets = addresses)
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
        preferences.writeSelectedPackages(
            apps.filter(InstalledApp::isSelected).mapTo(linkedSetOf(), InstalledApp::packageName),
        )
        preferences.writeDomainsByPackage(
            apps.filter { it.domains.isNotEmpty() }.associate { it.packageName to it.domains },
        )
    }

    private companion object {
        const val TAG = "PakomoState"
        const val FAULT_COMMIT_DEBOUNCE_MS = 180L
    }
}
