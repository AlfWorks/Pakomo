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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.pakomo.core.model.NetworkRule
import com.pakomo.ui.screens.DiagnosticsScreen
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
    data class RuleEditor(val draft: NetworkRule) : Screen
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
            stack.removeAt(stack.lastIndex)
        }
        Unit
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
                onCreateRule = { navigate(Screen.RuleEditor(viewModel.newRule())) },
                onEditRule = { rule ->
                    val draft = if (rule.isSystem) {
                        viewModel.duplicateRule(rule.id)
                    } else {
                        rule
                    }
                    if (draft != null) navigate(Screen.RuleEditor(draft))
                },
                onCopyRule = { rule ->
                    viewModel.duplicateRule(rule.id)?.let { navigate(Screen.RuleEditor(it)) }
                },
                onDeleteRule = viewModel::deleteRule,
            )

            is Screen.RuleEditor -> RuleEditorScreen(
                draft = current.draft,
                onBack = goBack,
                onSave = { rule ->
                    viewModel.saveRule(rule)
                    viewModel.selectRule(rule.id)
                    goBack()
                },
            )

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
