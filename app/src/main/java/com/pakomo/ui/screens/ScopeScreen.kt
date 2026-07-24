package com.pakomo.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pakomo.core.model.InstalledApp
import com.pakomo.core.model.PakomoUiState
import com.pakomo.core.model.TargetScope
import com.pakomo.ui.components.AppIcon
import com.pakomo.ui.components.MonoText
import com.pakomo.ui.components.ScreenHeader
import com.pakomo.ui.theme.Accent
import com.pakomo.ui.theme.AccentTint
import com.pakomo.ui.theme.Border
import com.pakomo.ui.theme.Muted
import com.pakomo.ui.theme.OnSurface
import com.pakomo.ui.theme.OnSurfaceVariant
import com.pakomo.ui.theme.SurfaceFold

@Composable
fun ScopeScreen(
    state: PakomoUiState,
    onBack: () -> Unit,
    onScopeSelected: (TargetScope) -> Unit,
    onQueryChange: (String) -> Unit,
    onShowSystemAppsChange: (Boolean) -> Unit,
    onRefreshApps: () -> Unit,
    onToggleApp: (String) -> Unit,
    onToggleExpanded: (String) -> Unit,
    onAddDomain: (String, String) -> String?,
    onRemoveDomain: (String, String) -> Unit,
    onAddAddress: (String) -> String?,
    onRemoveAddress: (String) -> Unit,
) {
    var domainTarget by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        ScreenHeader(
            title = when (state.scope) {
                TargetScope.APPLICATIONS -> "选择应用"
                TargetScope.ADDRESSES -> "管理地址"
                TargetScope.GLOBAL -> "全局接管"
            },
            onBack = onBack,
            action = {
                if (state.scope == TargetScope.APPLICATIONS) {
                    IconButton(onClick = onRefreshApps) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "刷新应用")
                    }
                }
            },
        )
        Spacer(Modifier.height(4.dp))

        when (state.scope) {
            TargetScope.GLOBAL -> GlobalScopeNotice()
            TargetScope.APPLICATIONS -> ApplicationScopeContent(
                state = state,
                onQueryChange = onQueryChange,
                onShowSystemAppsChange = onShowSystemAppsChange,
                onToggleApp = onToggleApp,
                onToggleExpanded = onToggleExpanded,
                onRequestAddDomain = { domainTarget = it },
                onRemoveDomain = onRemoveDomain,
            )

            TargetScope.ADDRESSES -> AddressScopeContent(
                domains = state.addressDomains,
                onRequestAdd = { domainTarget = ADDRESS_TARGET },
                onRemove = onRemoveAddress,
            )
        }
    }

    domainTarget?.let { target ->
        DomainInputDialog(
            onDismiss = { domainTarget = null },
            onSubmit = { input ->
                val error = if (target == ADDRESS_TARGET) {
                    onAddAddress(input)
                } else {
                    onAddDomain(target, input)
                }
                if (error == null) domainTarget = null
                error
            },
        )
    }
}

@Composable
private fun ApplicationScopeContent(
    state: PakomoUiState,
    onQueryChange: (String) -> Unit,
    onShowSystemAppsChange: (Boolean) -> Unit,
    onToggleApp: (String) -> Unit,
    onToggleExpanded: (String) -> Unit,
    onRequestAddDomain: (String) -> Unit,
    onRemoveDomain: (String, String) -> Unit,
) {
    val visibleApps = remember(
        state.apps,
        state.appQuery,
        state.showSystemApps,
    ) {
        state.apps.filter { app ->
            (state.showSystemApps || !app.isSystem) &&
                (
                    state.appQuery.isBlank() ||
                        app.label.contains(state.appQuery, ignoreCase = true) ||
                        app.packageName.contains(state.appQuery, ignoreCase = true)
                    )
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp,
            end = 16.dp,
            bottom = 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            OutlinedTextField(
                value = state.appQuery,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                placeholder = { Text("搜索应用或包名") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "已选择 ${state.selectedApps.size} 个应用",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                FilterChip(
                    selected = state.showSystemApps,
                    onClick = { onShowSystemAppsChange(!state.showSystemApps) },
                    label = { Text("系统应用") },
                )
            }
        }
        if (state.isLoadingApps) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                }
            }
        } else if (visibleApps.isEmpty()) {
            item {
                EmptyMessage("没有找到应用")
            }
        } else {
            items(visibleApps, key = InstalledApp::packageName) { app ->
                ApplicationCard(
                    app = app,
                    onToggle = { onToggleApp(app.packageName) },
                    onToggleExpanded = { onToggleExpanded(app.packageName) },
                    onRequestAddDomain = { onRequestAddDomain(app.packageName) },
                    onRemoveDomain = { domain -> onRemoveDomain(app.packageName, domain) },
                )
            }
        }
    }
}

@Composable
private fun ApplicationCard(
    app: InstalledApp,
    onToggle: () -> Unit,
    onToggleExpanded: () -> Unit,
    onRequestAddDomain: () -> Unit,
    onRemoveDomain: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, if (app.isSelected) Color(0xFFD6DFF5) else Border),
        elevation = CardDefaults.cardElevation(0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clickable(onClick = onToggleExpanded)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(packageName = app.packageName, fallbackLabel = app.label)
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = app.label,
                        style = MaterialTheme.typography.titleMedium,
                        color = OnSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (app.domains.isNotEmpty()) {
                        Spacer(Modifier.size(6.dp))
                        Text(
                            text = "${app.domains.size} 个域名",
                            style = MaterialTheme.typography.bodySmall,
                            color = Muted,
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                MonoText(app.packageName)
            }
            Spacer(Modifier.size(10.dp))
            Switch(
                checked = app.isSelected,
                onCheckedChange = { onToggle() },
                colors = SwitchDefaults.colors(
                    checkedTrackColor = Accent,
                    uncheckedTrackColor = Color(0xFFD7DBE0),
                    uncheckedBorderColor = Color.Transparent,
                ),
            )
        }

        if (app.isExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceFold)
                    .padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 10.dp),
            ) {
                app.domains.forEachIndexed { index, domain ->
                    DomainRow(
                        domain = domain,
                        onRemove = { onRemoveDomain(domain) },
                    )
                    if (index < app.domains.lastIndex) {
                        androidx.compose.material3.HorizontalDivider(
                            modifier = Modifier.padding(start = 4.dp),
                            color = Border,
                        )
                    }
                }
                Spacer(Modifier.height(5.dp))
                Button(
                    onClick = onRequestAddDomain,
                    enabled = app.isSelected,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentTint,
                        contentColor = Accent,
                        disabledContainerColor = Color(0xFFEFF1F4),
                        disabledContentColor = Muted,
                    ),
                    elevation = ButtonDefaults.buttonElevation(0.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(4.dp))
                    Text("添加域名")
                }
            }
        }
    }
}

@Composable
private fun DomainRow(domain: String, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MonoText(
            text = domain,
            color = OnSurface,
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp),
        )
        IconButton(
            onClick = onRemove,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "删除 $domain",
                tint = Muted,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun AddressScopeContent(
    domains: List<String>,
    onRequestAdd: () -> Unit,
    onRemove: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp,
            end = 16.dp,
            bottom = 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(domains, key = { it }) { domain ->
            Card(
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Border),
                elevation = CardDefaults.cardElevation(0.dp),
            ) {
                DomainRow(domain = domain, onRemove = { onRemove(domain) })
            }
        }
        item {
            Button(
                onClick = onRequestAdd,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                shape = RoundedCornerShape(9.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentTint,
                    contentColor = Accent,
                ),
                elevation = ButtonDefaults.buttonElevation(0.dp),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(Modifier.size(6.dp))
                Text("添加域名")
            }
        }
    }
}

@Composable
private fun GlobalScopeNotice() {
    Column(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(10.dp))
            .padding(16.dp),
    ) {
        Text("全局接管", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "设备中的 IPv4 流量都会按当前规则处理。",
            style = MaterialTheme.typography.bodySmall,
            color = OnSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyMessage(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Muted, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun DomainInputDialog(
    onDismiss: () -> Unit,
    onSubmit: (String) -> String?,
) {
    var value by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加域名") },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = {
                        value = it
                        error = null
                    },
                    singleLine = true,
                    label = { Text("域名") },
                    placeholder = { Text("api.example.com") },
                    isError = error != null,
                    supportingText = error?.let { message -> ({ Text(message) }) },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { error = onSubmit(value) },
            ) { Text("添加") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private const val ADDRESS_TARGET = "__address_scope__"
