package com.pakomo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.pakomo.core.model.NetworkRule
import com.pakomo.ui.screens.DiagnosticsScreen
import com.pakomo.ui.screens.HomeScreen
import com.pakomo.ui.screens.RuleEditorScreen
import com.pakomo.ui.screens.RulesScreen
import com.pakomo.ui.screens.ScopeScreen
import com.pakomo.ui.screens.SecurityScreen
import com.pakomo.ui.screens.SettingsScreen
import com.pakomo.ui.theme.Background

private sealed interface Screen {
    data object Home : Screen
    data object Scope : Screen
    data object Rules : Screen
    data class RuleEditor(val draft: NetworkRule) : Screen
    data object Diagnostics : Screen
    data object Settings : Screen
    data object Security : Screen
}

@Composable
fun PakomoApp(
    viewModel: PakomoViewModel,
    onToggleService: () -> Unit,
    onEmergencyStop: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
    val current = stack.last()
    val navigate: (Screen) -> Unit = { screen ->
        if (stack.lastOrNull() != screen) stack.add(screen)
    }
    val goBack = {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
        Unit
    }

    BackHandler(enabled = stack.size > 1, onBack = goBack)

    Surface(modifier = Modifier.fillMaxSize(), color = Background) {
        when (current) {
            Screen.Home -> HomeScreen(
                state = state,
                onScopeSelected = viewModel::selectScope,
                onOpenScope = { navigate(Screen.Scope) },
                onOpenRules = { navigate(Screen.Rules) },
                onOpenDiagnostics = { navigate(Screen.Diagnostics) },
                onOpenSettings = { navigate(Screen.Settings) },
                onOpenSecurity = { navigate(Screen.Security) },
                onToggleService = onToggleService,
                onEmergencyStop = onEmergencyStop,
            )

            Screen.Scope -> ScopeScreen(
                state = state,
                onBack = goBack,
                onScopeSelected = viewModel::selectScope,
                onQueryChange = viewModel::setAppQuery,
                onShowSystemAppsChange = viewModel::setShowSystemApps,
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
                onDiagnosticModeChange = viewModel::setDiagnosticMode,
            )

            Screen.Settings -> SettingsScreen(
                onBack = goBack,
            )

            Screen.Security -> SecurityScreen(
                state = state,
                onBack = goBack,
                onClearData = viewModel::clearLocalData,
            )
        }
    }
}
