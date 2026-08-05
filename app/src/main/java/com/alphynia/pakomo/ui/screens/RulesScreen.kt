package com.alphynia.pakomo.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alphynia.pakomo.core.model.NetworkRule
import com.alphynia.pakomo.core.model.PakomoUiState
import com.alphynia.pakomo.ui.components.ScreenHeader
import com.alphynia.pakomo.ui.theme.LocalAppLanguage
import com.alphynia.pakomo.ui.theme.LocalPakomoColors
import com.alphynia.pakomo.ui.theme.t

@Composable
fun RulesScreen(
    state: PakomoUiState,
    onBack: () -> Unit,
    onSelectRule: (String) -> Unit,
    onCreateRule: () -> Unit,
    onEditRule: (NetworkRule) -> Unit,
    onCopyRule: (NetworkRule) -> Unit,
    onDeleteRule: (String) -> Unit,
) {
    val colors = LocalPakomoColors.current
    var pendingDelete by remember { mutableStateOf<NetworkRule?>(null) }
    Scaffold(
        modifier = Modifier.statusBarsPadding(),
        containerColor = colors.background,
        topBar = {
            ScreenHeader(
                title = t("规则", "Rules"),
                onBack = onBack,
                action = {
                    IconButton(onClick = onCreateRule) {
                        Icon(Icons.Rounded.Add, contentDescription = t("新建规则", "New rule"))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 12.dp,
                bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(state.rules, key = NetworkRule::id) { rule ->
                RuleCard(
                    rule = rule,
                    selected = rule.id == state.activeRuleId,
                    onSelect = { onSelectRule(rule.id) },
                    onEdit = { onEditRule(rule) },
                    onCopy = { onCopyRule(rule) },
                    onDelete = { pendingDelete = rule },
                )
            }
        }
    }

    pendingDelete?.let { rule ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(t("删除规则", "Delete rule")) },
            text = { Text(t("确定删除“${rule.name}”吗？", "Delete “${rule.name}”?")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteRule(rule.id)
                        pendingDelete = null
                    },
                ) { Text(t("删除", "Delete"), color = colors.danger) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(t("取消", "Cancel")) }
            },
        )
    }
}
@Composable
private fun RuleCard(
    rule: NetworkRule,
    selected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = LocalPakomoColors.current
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(92.dp)
            .clickable(onClick = onSelect),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        border = BorderStroke(1.dp, if (selected) colors.selectionBorder else colors.border),
        elevation = CardDefaults.cardElevation(0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = selected,
                onClick = onSelect,
                colors = RadioButtonDefaults.colors(selectedColor = colors.accent),
            )
            Spacer(Modifier.size(6.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = rule.displayName(LocalAppLanguage.current),
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (rule.isSystem) {
                        Spacer(Modifier.size(7.dp))
                        Text(
                            text = t("内置", "Built-in"),
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.muted,
                        )
                    }
                }
                Spacer(Modifier.height(7.dp))
                Text(
                    text = rule.summary(LocalAppLanguage.current),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            androidx.compose.foundation.layout.Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = t("更多操作", "More actions"),
                        tint = colors.textSecondary,
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(if (rule.isSystem) t("复制并编辑", "Copy & edit") else t("编辑", "Edit")) },
                        leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(t("复制", "Copy")) },
                        leadingIcon = {
                            Icon(Icons.Rounded.ContentCopy, contentDescription = null)
                        },
                        onClick = {
                            menuOpen = false
                            onCopy()
                        },
                    )
                    if (!rule.isSystem) {
                        DropdownMenuItem(
                            text = { Text(t("删除", "Delete"), color = colors.danger) },
                            leadingIcon = {
                                Icon(
                                    Icons.Rounded.DeleteOutline,
                                    contentDescription = null,
                                    tint = colors.danger,
                                )
                            },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            },
                        )
                    }
                }
            }
        }
    }
}
