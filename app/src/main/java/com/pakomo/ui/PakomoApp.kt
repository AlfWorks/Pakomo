package com.pakomo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.pakomo.core.model.AppFaultTarget
import com.pakomo.core.model.InstalledApp
import com.pakomo.core.model.NetworkRule
import com.pakomo.core.model.SpecialFault
import com.pakomo.core.model.SpecialFaultType
import com.pakomo.ui.screens.DiagnosticsScreen
import com.pakomo.ui.screens.FaultTargetScreen
import com.pakomo.ui.screens.HomeScreen
import com.pakomo.ui.screens.LatencyTestScreen
import com.pakomo.ui.screens.RuleEditorScreen
import com.pakomo.ui.screens.RulesScreen
import com.pakomo.ui.screens.ScopeScreen
import com.pakomo.ui.screens.SecurityScreen
import com.pakomo.ui.screens.SettingsScreen
import com.pakomo.ui.screens.rememberTrafficChartState
import com.pakomo.ui.theme.Background

private sealed interface Screen {
    data object Home : Screen
    data object Scope : Screen
    data object Rules : Screen
    data object RuleEditor : Screen
    data class FaultTarget(val type: SpecialFaultType) : Screen
    data object Diagnostics : Screen
    data object Settings : Screen
    data object Security : Screen
    data object LatencyTest : Screen
}

@Composable
fun PakomoApp(
    viewModel: PakomoViewModel,
    onToggleService: () -> Unit,
    onEmergencyStop: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val trafficChartState = rememberTrafficChartState(state)
    val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
    val editorStateHolder = rememberSaveableStateHolder()
    var editingDraft by remember { mutableStateOf<NetworkRule?>(null) }
    var hasNavigated by remember { mutableStateOf(false) }
    val current = stack.last()
    val navigate: (Screen) -> Unit = { screen ->
        if (stack.lastOrNull() != screen) {
            hasNavigated = true
            stack.add(screen)
        }
    }
    val goBack = {
        if (stack.size > 1) {
            hasNavigated = true
            val removed = stack.removeAt(stack.lastIndex)
            if (removed == Screen.RuleEditor) {
                editingDraft?.let { editorStateHolder.removeState("rule-editor-${it.id}") }
                editingDraft = null
            }
        }
        Unit
    }
    val openRuleEditor: (NetworkRule) -> Unit = { draft ->
        editingDraft = draft
        navigate(Screen.RuleEditor)
    }
    val mutateDraftFault: (SpecialFaultType, (SpecialFault) -> SpecialFault) -> Unit =
        { type, transform ->
            editingDraft = editingDraft?.let { draft ->
                val updated = transform(draft.specialFaults.fault(type))
                draft.copy(specialFaults = draft.specialFaults.withFault(updated))
            }
        }

    BackHandler(enabled = stack.size > 1, onBack = goBack)
    val pageProgress = remember(current) {
        Animatable(if (hasNavigated) 0f else 1f)
    }
    LaunchedEffect(current) {
        if (pageProgress.value < 1f) {
            pageProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = 180,
                    easing = LinearOutSlowInEasing,
                ),
            )
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Background) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = 0.35f + pageProgress.value * 0.65f
                },
        ) {
            when (current) {
            Screen.Home -> HomeScreen(
                state = state,
                trafficChartState = trafficChartState,
                onScopeSelected = viewModel::selectScope,
                onOpenScope = { navigate(Screen.Scope) },
                onOpenRules = { navigate(Screen.Rules) },
                onOpenDiagnostics = { navigate(Screen.Diagnostics) },
                onOpenLatencyTest = { navigate(Screen.LatencyTest) },
                onOpenSettings = { navigate(Screen.Settings) },
                onToggleService = onToggleService,
                onEmergencyStop = onEmergencyStop,
            )

            Screen.Scope -> ScopeScreen(
                state = state,
                onBack = goBack,
                onScopeSelected = viewModel::selectScope,
                onQueryChange = viewModel::setAppQuery,
                onRefreshApps = viewModel::refreshApps,
                onToggleApp = viewModel::toggleApp,
                onToggleExpanded = viewModel::toggleAppExpanded,
                onAddDomain = viewModel::addDomain,
                onRemoveDomain = viewModel::removeDomain,
                onAddAddress = viewModel::addAddressDomain,
                onRemoveAddress = viewModel::removeAddressDomain,
            )

            Screen.Rules -> RulesScreen(
                state = state,
                onBack = goBack,
                onSelectRule = viewModel::selectRule,
                onCreateRule = { openRuleEditor(viewModel.newRule()) },
                onEditRule = { rule ->
                    val draft = if (rule.isSystem) {
                        viewModel.duplicateRule(rule.id)
                    } else {
                        rule
                    }
                    if (draft != null) openRuleEditor(draft)
                },
                onCopyRule = { rule ->
                    viewModel.duplicateRule(rule.id)?.let(openRuleEditor)
                },
                onDeleteRule = viewModel::deleteRule,
            )

            Screen.RuleEditor -> {
                val editing = editingDraft
                if (editing == null) {
                    LaunchedEffect(Unit) { goBack() }
                    return@Box
                }
                val selectedAppDomains = remember(state.apps) {
                    state.apps.asSequence()
                        .filter(InstalledApp::isSelected)
                        .associate { it.packageName to it.domains }
                }
                val appLabels = remember(state.apps) {
                    state.apps.associate { it.packageName to it.label }
                }
                editorStateHolder.SaveableStateProvider("rule-editor-${editing.id}") {
                    RuleEditorScreen(
                        draft = editing,
                        onBack = goBack,
                        onSave = { rule ->
                            viewModel.saveRule(rule)
                            viewModel.selectRule(rule.id)
                            goBack()
                        },
                        scope = state.scope,
                        selectedAppDomains = selectedAppDomains,
                        addressDomains = state.addressDomains,
                        appLabels = appLabels,
                        onToggleFault = { type, enabled ->
                            mutateDraftFault(type) { it.copy(enabled = enabled) }
                        },
                        onDnsResult = { result ->
                            mutateDraftFault(SpecialFaultType.DNS_FAILURE) {
                                it.copy(dnsResult = result)
                            }
                        },
                        onBlackoutMode = { mode ->
                            mutateDraftFault(SpecialFaultType.NETWORK_BLACKOUT) {
                                it.copy(blackoutMode = mode)
                            }
                        },
                        onOpenFaultTarget = { type ->
                            navigate(Screen.FaultTarget(type))
                        },
                    )
                }
            }

            is Screen.FaultTarget -> {
                val faultNav = current
                val rule = editingDraft
                if (rule == null) {
                    LaunchedEffect(Unit) { goBack() }
                } else {
                    FaultTargetScreen(
                        rule = rule,
                        type = faultNav.type,
                        scope = state.scope,
                        apps = state.apps,
                        addressDomains = state.addressDomains,
                        onBack = goBack,
                        onSetAppEnabled = { pkg, enabled ->
                            mutateDraftFault(faultNav.type) { fault ->
                                val target = fault.appTargets[pkg]
                                    ?: AppFaultTarget(pkg)
                                fault.copy(
                                    appTargets = fault.appTargets +
                                        (pkg to target.copy(enabled = enabled)),
                                )
                            }
                        },
                        onToggleAppDomain = { pkg, domain, on ->
                            mutateDraftFault(faultNav.type) { fault ->
                                val target = fault.appTargets[pkg]
                                    ?: AppFaultTarget(pkg)
                                val domains = if (on) {
                                    (target.domains + domain).distinct()
                                } else {
                                    target.domains.filterNot { it == domain }
                                }
                                fault.copy(
                                    appTargets = fault.appTargets +
                                        (pkg to target.copy(domains = domains)),
                                )
                            }
                        },
                        onToggleAddress = { domain, on ->
                            mutateDraftFault(faultNav.type) { fault ->
                                val addresses = if (on) {
                                    (fault.addressTargets + domain).distinct()
                                } else {
                                    fault.addressTargets.filterNot { it == domain }
                                }
                                fault.copy(addressTargets = addresses)
                            }
                        },
                        onDnsCacheGuard = { enabled ->
                            mutateDraftFault(faultNav.type) { it.copy(dnsCacheGuard = enabled) }
                        },
                    )
                }
            }

            Screen.Diagnostics -> DiagnosticsScreen(
                state = state,
                onBack = goBack,
            )

            Screen.Settings -> SettingsScreen(
                onBack = goBack,
                onOpenSecurity = { navigate(Screen.Security) },
            )

            Screen.Security -> SecurityScreen(
                state = state,
                onBack = goBack,
                onClearData = viewModel::clearLocalData,
            )

            Screen.LatencyTest -> LatencyTestScreen(onBack = goBack)
            }
        }
    }
}
