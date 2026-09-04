package com.alphynia.pakomo.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.alphynia.pakomo.core.model.AppListAccess
import com.alphynia.pakomo.core.model.DomainTarget
import com.alphynia.pakomo.core.model.EngineRuntime
import com.alphynia.pakomo.core.model.EngineStage
import com.alphynia.pakomo.core.model.InstalledApp
import com.alphynia.pakomo.core.model.NetworkRule
import com.alphynia.pakomo.core.model.PakomoUiState
import com.alphynia.pakomo.core.model.TargetScope
import com.alphynia.pakomo.core.validation.DomainInputValidator
import com.alphynia.pakomo.data.InstalledAppCatalog
import com.alphynia.pakomo.data.PakomoPreferences
import com.alphynia.pakomo.core.model.AppLanguage
import com.alphynia.pakomo.ui.theme.ThemeMode
import com.alphynia.pakomo.vpn.VpnServiceController
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
            addressDomains = preferences.readAddressTargets(),
            rules = storedRules,
            activeRuleId = storedActiveRuleId,
        ),
    )
    val state: StateFlow<PakomoUiState> = _state.asStateFlow()

    // Theme lives in its own flow so switching it never recomposes the tree keyed on `state`
    // (stats update once per second, so folding theme into PakomoUiState would be wasteful).
    // Default to the Pako (Companion) theme when the user has never chosen one.
    private val _themeMode = MutableStateFlow(
        runCatching { ThemeMode.valueOf(preferences.readThemeMode() ?: ThemeMode.Companion.name) }
            .getOrDefault(ThemeMode.Companion),
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    // Interface language, kept in its own flow for the same reason as [themeMode].
    private val _language = MutableStateFlow(AppLanguage.fromName(preferences.readLanguage()))
    val language: StateFlow<AppLanguage> = _language.asStateFlow()

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
            _state.update {
                it.copy(
                    isLoadingApps = true,
                    appListAccess = AppListAccess.CHECKING,
                )
            }
            val result = withContext(Dispatchers.IO) {
                appCatalog.load(
                    selectedPackages = preferences.readSelectedPackages(),
                    domainsByPackage = preferences.readDomainTargetsByPackage(),
                )
            }
            val shouldFallbackToAddresses =
                !result.isAvailable && _state.value.scope == TargetScope.APPLICATIONS
            _state.update { current ->
                current.copy(
                    apps = result.apps,
                    isLoadingApps = false,
                    appListAccess = if (result.isAvailable) {
                        AppListAccess.AVAILABLE
                    } else {
                        AppListAccess.UNAVAILABLE
                    },
                    scope = if (shouldFallbackToAddresses) {
                        TargetScope.ADDRESSES
                    } else {
                        current.scope
                    },
                )
            }
            if (shouldFallbackToAddresses) {
                preferences.writeScope(TargetScope.ADDRESSES)
                Log.w(TAG, "Application list unavailable; scope changed to ADDRESSES")
                reapplyIfRunning()
            }
            Log.i(
                TAG,
                "Application catalog loaded: available=${result.isAvailable}, " +
                    "total=${result.apps.size}, selected=${result.apps.count { it.isSelected }}",
            )
        }
    }

    fun selectScope(scope: TargetScope) {
        if (
            scope == TargetScope.APPLICATIONS &&
            _state.value.appListAccess == AppListAccess.UNAVAILABLE
        ) {
            return
        }
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
            // Only enabled domains reach the runtime; an app with domains but none enabled drops out
            // of the map, so it falls through to whole-app capture (same as having no domains).
            val domainsByPackage = selectedApps.asSequence()
                .map { app -> app.packageName to app.domains.filter { it.enabled }.map { it.value } }
                .filter { it.second.isNotEmpty() }
                .toMap()
            val addressDomains = snapshot.addressDomains.filter { it.enabled }.map { it.value }
            ensureActive()
            if (_state.value.engineStage != EngineStage.FORWARDING) return@launch
            val context = getApplication<Application>()
            when {
                !hotSwap -> VpnServiceController.start(
                    context,
                    snapshot.scope,
                    packages,
                    addressDomains,
                    domainsByPackage,
                    snapshot.activeRule,
                )
                else -> VpnServiceController.update(
                    context,
                    snapshot.scope,
                    packages,
                    addressDomains,
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
            ?: return _language.value.tr("请输入有效域名，例如 api.example.com", "Enter a valid domain, e.g. api.example.com")
        val target = _state.value.apps.firstOrNull { it.packageName == packageName }
            ?: return _language.value.tr("应用已不存在", "This app no longer exists")
        if (target.domains.any { it.value.equals(domain, ignoreCase = true) }) {
            return _language.value.tr("这个域名已经添加", "This domain is already added")
        }
        updateApp(packageName) { app -> app.copy(domains = app.domains + DomainTarget(domain)) }
        persistApps()
        val count = _state.value.apps.firstOrNull { it.packageName == packageName }?.domains?.size ?: 0
        Log.i(TAG, "Application domain added: count=$count")
        updateIfRunning()
        return null
    }

    fun removeDomain(packageName: String, domain: String) {
        updateApp(packageName) { app ->
            app.copy(domains = app.domains.filterNot { it.value == domain })
        }
        persistApps()
        val count = _state.value.apps.firstOrNull { it.packageName == packageName }?.domains?.size ?: 0
        Log.i(TAG, "Application domain removed: count=$count")
        updateIfRunning()
    }

    /** Toggle one per-app domain's enabled flag without deleting it; hot-applies to the runtime. */
    fun toggleDomain(packageName: String, domain: String) {
        updateApp(packageName) { app ->
            app.copy(
                domains = app.domains.map {
                    if (it.value == domain) it.copy(enabled = !it.enabled) else it
                },
            )
        }
        persistApps()
        val enabled = _state.value.apps.firstOrNull { it.packageName == packageName }
            ?.domains?.firstOrNull { it.value == domain }?.enabled
        Log.i(TAG, "Application domain toggled: enabled=$enabled")
        updateIfRunning()
    }

    /** Rename one per-app domain in place, keeping its enabled flag. Returns an error message or null. */
    fun editDomain(packageName: String, oldValue: String, input: String): String? {
        val domain = DomainInputValidator.normalizeOrNull(input)
            ?: return _language.value.tr("请输入有效域名，例如 api.example.com", "Enter a valid domain, e.g. api.example.com")
        val target = _state.value.apps.firstOrNull { it.packageName == packageName }
            ?: return _language.value.tr("应用已不存在", "This app no longer exists")
        if (target.domains.any { it.value != oldValue && it.value.equals(domain, ignoreCase = true) }) {
            return _language.value.tr("这个域名已经添加", "This domain is already added")
        }
        updateApp(packageName) { app ->
            app.copy(domains = app.domains.map { if (it.value == oldValue) it.copy(value = domain) else it })
        }
        persistApps()
        Log.i(TAG, "Application domain edited")
        updateIfRunning()
        return null
    }

    fun addAddressDomain(input: String): String? {
        val domain = DomainInputValidator.normalizeOrNull(input)
            ?: return _language.value.tr("请输入有效域名，例如 api.example.com", "Enter a valid domain, e.g. api.example.com")
        if (_state.value.addressDomains.any { it.value.equals(domain, ignoreCase = true) }) {
            return _language.value.tr("这个域名已经添加", "This domain is already added")
        }
        val updated = _state.value.addressDomains + DomainTarget(domain)
        _state.update { it.copy(addressDomains = updated) }
        persistAddressDomains(updated)
        Log.i(TAG, "Address domain added: count=${updated.size}")
        updateIfRunning()
        return null
    }

    fun removeAddressDomain(domain: String) {
        val updated = _state.value.addressDomains.filterNot { it.value == domain }
        _state.update { it.copy(addressDomains = updated) }
        persistAddressDomains(updated)
        Log.i(TAG, "Address domain removed: count=${updated.size}")
        updateIfRunning()
    }

    /** Toggle one address target's enabled flag without deleting it; hot-applies to the runtime. */
    fun toggleAddressDomain(domain: String) {
        val updated = _state.value.addressDomains.map {
            if (it.value == domain) it.copy(enabled = !it.enabled) else it
        }
        _state.update { it.copy(addressDomains = updated) }
        persistAddressDomains(updated)
        Log.i(TAG, "Address domain toggled: enabledCount=${updated.count { it.enabled }}")
        updateIfRunning()
    }

    /** Rename one address target in place, keeping its enabled flag. Returns an error message or null. */
    fun editAddressDomain(oldValue: String, input: String): String? {
        val domain = DomainInputValidator.normalizeOrNull(input)
            ?: return _language.value.tr("请输入有效域名，例如 api.example.com", "Enter a valid domain, e.g. api.example.com")
        if (_state.value.addressDomains.any { it.value != oldValue && it.value.equals(domain, ignoreCase = true) }) {
            return _language.value.tr("这个域名已经添加", "This domain is already added")
        }
        val updated = _state.value.addressDomains.map {
            if (it.value == oldValue) it.copy(value = domain) else it
        }
        _state.update { it.copy(addressDomains = updated) }
        persistAddressDomains(updated)
        Log.i(TAG, "Address domain edited")
        updateIfRunning()
        return null
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
            name = _language.value.let { lang ->
                lang.tr("${source.displayName(lang)}副本", "${source.displayName(lang)} copy")
            },
            isSystem = false,
        )
    }

    fun newRule(): NetworkRule {
        Log.i(TAG, "New rule draft created")
        return NetworkRule(
            id = UUID.randomUUID().toString(),
            name = _language.value.tr("新规则", "New rule"),
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

    fun setThemeMode(mode: ThemeMode) {
        if (_themeMode.value == mode) return
        _themeMode.value = mode
        Log.i(TAG, "Theme mode changed: ${mode.name}")
        viewModelScope.launch(Dispatchers.IO) { preferences.writeThemeMode(mode.name) }
    }

    fun setLanguage(language: AppLanguage) {
        if (_language.value == language) return
        _language.value = language
        Log.i(TAG, "Language changed: ${language.name}")
        viewModelScope.launch(Dispatchers.IO) { preferences.writeLanguage(language.name) }
    }

    fun clearLocalData() {
        Log.w(TAG, "Clearing all local configuration")
        preferences.clear()
        _state.value = PakomoUiState()
        _themeMode.value = ThemeMode.Companion
        _language.value = AppLanguage.DEFAULT
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

    private fun persistAddressDomains(domains: List<DomainTarget>) {
        addressPersistJob?.cancel()
        addressPersistJob = viewModelScope.launch(Dispatchers.IO) {
            preferences.writeAddressTargets(domains)
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
