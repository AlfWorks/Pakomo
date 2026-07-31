package com.pakomo.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pakomo.core.model.InstalledApp
import com.pakomo.core.model.PakomoUiState
import com.pakomo.core.model.TargetScope
import com.pakomo.ui.components.AppIcon
import com.pakomo.ui.components.EmptyArtKind
import com.pakomo.ui.components.EmptyStateArt
import com.pakomo.ui.components.MonoText
import com.pakomo.ui.components.ScreenHeader
import com.pakomo.ui.theme.LocalAppLanguage
import com.pakomo.ui.theme.LocalPakomoColors
import com.pakomo.ui.theme.LocalThemeMode
import com.pakomo.ui.theme.ThemeMode
import com.pakomo.ui.theme.t

@Immutable
private data class ApplicationScopeUiState(
    val apps: List<InstalledApp>,
    val query: String,
    val isLoading: Boolean,
)

@Composable
fun ScopeScreen(
    state: PakomoUiState,
    onBack: () -> Unit,
    onScopeSelected: (TargetScope) -> Unit,
    onQueryChange: (String) -> Unit,
    onRefreshApps: () -> Unit,
    onToggleApp: (String) -> Unit,
    onToggleExpanded: (String) -> Unit,
    onAddDomain: (String, String) -> String?,
    onRemoveDomain: (String, String) -> Unit,
    onAddAddress: (String) -> String?,
    onRemoveAddress: (String) -> Unit,
) {
    var domainTarget by remember { mutableStateOf<String?>(null) }
    val applicationState = remember(state.apps, state.appQuery, state.isLoadingApps) {
        ApplicationScopeUiState(
            apps = state.apps,
            query = state.appQuery,
            isLoading = state.isLoadingApps,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        ScreenHeader(
            title = when (state.scope) {
                TargetScope.APPLICATIONS -> t("选择应用", "Select apps")
                TargetScope.ADDRESSES -> t("管理地址", "Manage addresses")
                TargetScope.GLOBAL -> t("全局接管", "Global capture")
            },
            onBack = onBack,
            action = {
                if (state.scope == TargetScope.APPLICATIONS) {
                    IconButton(onClick = onRefreshApps) {
                        Icon(Icons.Rounded.Refresh, contentDescription = t("刷新应用", "Refresh apps"))
                    }
                }
            },
        )
        Spacer(Modifier.height(4.dp))

        when (state.scope) {
            TargetScope.GLOBAL -> Unit
            TargetScope.APPLICATIONS -> ApplicationScopeContent(
                state = applicationState,
                onQueryChange = onQueryChange,
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
@OptIn(ExperimentalFoundationApi::class)
private fun ApplicationScopeContent(
    state: ApplicationScopeUiState,
    onQueryChange: (String) -> Unit,
    onToggleApp: (String) -> Unit,
    onToggleExpanded: (String) -> Unit,
    onRequestAddDomain: (String) -> Unit,
    onRemoveDomain: (String, String) -> Unit,
) {
    val cacheWindow = remember {
        LazyLayoutCacheWindow(aheadFraction = 2f, behindFraction = 2f)
    }
    val listState = rememberLazyListState(cacheWindow = cacheWindow)
    val visibleApps = remember(
        state.apps,
        state.query,
    ) {
        state.apps.filter { app ->
            state.query.isBlank() ||
                app.label.contains(state.query, ignoreCase = true) ||
                app.packageName.contains(state.query, ignoreCase = true)
        }
    }

    LazyColumn(
        state = listState,
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
                value = state.query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                placeholder = { Text(t("搜索应用或包名", "Search apps or package names")) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            )
        }
        if (state.isLoading) {
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
                EmptyMessage(t("没有找到应用", "No apps found"))
            }
        } else {
            items(
                items = visibleApps,
                key = InstalledApp::packageName,
                contentType = { "application" },
            ) { app ->
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
    val colors = LocalPakomoColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(12.dp))
            .border(
                width = 1.dp,
                color = if (app.isSelected) colors.selectionBorder else colors.border,
                shape = RoundedCornerShape(12.dp),
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clickable(onClick = onToggleExpanded)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(bitmap = app.icon, fallbackLabel = app.label)
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = app.label,
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                Spacer(Modifier.height(2.dp))
                MonoText(app.packageName)
            }
            Spacer(Modifier.size(10.dp))
            Checkbox(
                checked = app.isSelected,
                onCheckedChange = { onToggle() },
                colors = CheckboxDefaults.colors(
                    checkedColor = colors.accent,
                    uncheckedColor = colors.muted,
                    checkmarkColor = Color.White,
                ),
            )
        }

        if (app.isExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surfaceFold)
                    .padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 10.dp),
            ) {
                if (app.domains.isEmpty()) {
                    Text(
                        text = t("全部流量", "All traffic"),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.muted,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                    )
                }
                app.domains.forEachIndexed { index, domain ->
                    DomainRow(
                        domain = domain,
                        onRemove = { onRemoveDomain(domain) },
                    )
                    if (index < app.domains.lastIndex) {
                        androidx.compose.material3.HorizontalDivider(
                            modifier = Modifier.padding(start = 4.dp),
                            color = colors.border,
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
                        containerColor = colors.accentTint,
                        contentColor = colors.accent,
                        disabledContainerColor = colors.disabledContainer,
                        disabledContentColor = colors.muted,
                    ),
                    elevation = ButtonDefaults.buttonElevation(0.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(t("添加域名", "Add domain"))
                }
            }
        }
    }
}

@Composable
private fun DomainRow(domain: String, onRemove: () -> Unit) {
    val colors = LocalPakomoColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MonoText(
            text = domain,
            color = colors.textPrimary,
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
                contentDescription = t("删除 $domain", "Delete $domain"),
                tint = colors.muted,
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
    val colors = LocalPakomoColors.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp,
            end = 16.dp,
            bottom = 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (domains.isEmpty()) {
            item { EmptyMessage(t("还没有指定地址", "No addresses yet")) }
        } else {
            items(domains, key = { it }) { domain ->
                Card(
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.surface),
                    border = BorderStroke(1.dp, colors.border),
                    elevation = CardDefaults.cardElevation(0.dp),
                ) {
                    DomainRow(domain = domain, onRemove = { onRemove(domain) })
                }
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
                    containerColor = colors.accentTint,
                    contentColor = colors.accent,
                ),
                elevation = ButtonDefaults.buttonElevation(0.dp),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(Modifier.size(6.dp))
                Text(t("添加域名", "Add domain"))
            }
        }
    }
}

@Composable
private fun EmptyMessage(text: String) {
    val companion = LocalThemeMode.current == ThemeMode.Companion
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (companion) 200.dp else 160.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (companion) {
                EmptyStateArt(EmptyArtKind.Search, Modifier.size(120.dp))
                Spacer(Modifier.height(8.dp))
            }
            Text(text, color = LocalPakomoColors.current.muted, style = MaterialTheme.typography.bodyMedium)
        }
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
        title = { Text(t("添加域名", "Add domain")) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = {
                        value = it
                        error = null
                    },
                    singleLine = true,
                    label = { Text(t("域名", "Domain")) },
                    placeholder = { Text("api.example.com") },
                    isError = error != null,
                    supportingText = error?.let { message -> ({ Text(message) }) },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { error = onSubmit(value) },
            ) { Text(t("添加", "Add")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(t("取消", "Cancel")) }
        },
    )
}

private const val ADDRESS_TARGET = "__address_scope__"
