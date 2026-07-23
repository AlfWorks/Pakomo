package com.pakomo.ui.screens

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
import androidx.compose.material3.FloatingActionButton
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
import com.pakomo.core.model.NetworkRule
import com.pakomo.core.model.PakomoUiState
import com.pakomo.ui.components.ScreenHeader
import com.pakomo.ui.theme.Accent
import com.pakomo.ui.theme.Background
import com.pakomo.ui.theme.Border
import com.pakomo.ui.theme.Danger
import com.pakomo.ui.theme.Muted
import com.pakomo.ui.theme.OnSurface
import com.pakomo.ui.theme.OnSurfaceVariant

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
    var pendingDelete by remember { mutableStateOf<NetworkRule?>(null) }
    Scaffold(
        modifier = Modifier.statusBarsPadding(),
        containerColor = Background,
        topBar = {
            ScreenHeader(
                title = "弱网规则",
                onBack = onBack,
                action = {
                    IconButton(onClick = onCreateRule) {
                        Icon(Icons.Rounded.Add, contentDescription = "新建规则")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onCreateRule,
                containerColor = Accent,
                contentColor = Color.White,
            ) {
                Icon(Icons.Rounded.Add, contentDescription = "新建规则")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 10.dp,
                bottom = 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = "同一时间只生效一条规则。卡片直接显示实际参数，不再用“轻度/中度”代替量化信息。",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceVariant,
                )
            }
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
            title = { Text("删除规则") },
            text = { Text("确定删除“${rule.name}”吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteRule(rule.id)
                        pendingDelete = null
                    },
                ) { Text("删除", color = Danger) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
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
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(92.dp)
            .clickable(onClick = onSelect),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, if (selected) Color(0xFFCAD8F7) else Border),
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
                colors = RadioButtonDefaults.colors(selectedColor = Accent),
            )
            Spacer(Modifier.size(6.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = rule.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = OnSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (rule.isSystem) {
                        Spacer(Modifier.size(7.dp))
                        Text(
                            text = "内置",
                            style = MaterialTheme.typography.labelMedium,
                            color = Muted,
                        )
                    }
                }
                Spacer(Modifier.height(7.dp))
                Text(
                    text = rule.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            androidx.compose.foundation.layout.Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = "更多操作",
                        tint = OnSurfaceVariant,
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(if (rule.isSystem) "复制并编辑" else "编辑") },
                        leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("复制") },
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
                            text = { Text("删除", color = Danger) },
                            leadingIcon = {
                                Icon(
                                    Icons.Rounded.DeleteOutline,
                                    contentDescription = null,
                                    tint = Danger,
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
