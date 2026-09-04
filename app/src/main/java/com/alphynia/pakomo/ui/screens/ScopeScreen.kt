package com.alphynia.pakomo.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alphynia.pakomo.core.model.DomainTarget
import com.alphynia.pakomo.core.model.InstalledApp
import com.alphynia.pakomo.core.model.PakomoUiState
import com.alphynia.pakomo.core.model.TargetScope
import com.alphynia.pakomo.ui.components.AppIcon
import com.alphynia.pakomo.ui.components.EmptyArtKind
import com.alphynia.pakomo.ui.components.EmptyStateArt
import com.alphynia.pakomo.ui.components.MonoText
import com.alphynia.pakomo.ui.components.ScreenHeader
import com.alphynia.pakomo.ui.theme.LocalAppLanguage
import com.alphynia.pakomo.ui.theme.LocalPakomoColors
import com.alphynia.pakomo.ui.theme.LocalThemeMode
import com.alphynia.pakomo.ui.theme.ThemeMode
import com.alphynia.pakomo.ui.theme.t
import java.text.Collator
import java.util.Locale

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
    onToggleDomain: (String, String) -> Unit,
    onEditDomain: (String, String, String) -> String?,
    onAddAddress: (String) -> String?,
    onRemoveAddress: (String) -> Unit,
    onToggleAddress: (String) -> Unit,
    onEditAddress: (String, String) -> String?,
) {
    // The domain add/edit dialog request: [context] is a package name or [ADDRESS_TARGET]; [editing]
    // is null for a new entry, or the existing value being edited.
    var dialog by remember { mutableStateOf<DomainDialogRequest?>(null) }
    val focusManager = LocalFocusManager.current
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
            .statusBarsPadding()
            // Tap anywhere outside the search field (empty list space, headers) to drop focus and hide
            // the keyboard; no ripple so the whole screen doesn't read as a button.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { focusManager.clearFocus() },
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
                onRequestAddDomain = { pkg -> dialog = DomainDialogRequest(pkg, editing = null) },
                onRemoveDomain = onRemoveDomain,
                onToggleDomain = onToggleDomain,
                onRequestEditDomain = { pkg, value -> dialog = DomainDialogRequest(pkg, editing = value) },
            )

            TargetScope.ADDRESSES -> AddressScopeContent(
                domains = state.addressDomains,
                onRequestAdd = { dialog = DomainDialogRequest(ADDRESS_TARGET, editing = null) },
                onRemove = onRemoveAddress,
                onToggle = onToggleAddress,
                onRequestEdit = { value -> dialog = DomainDialogRequest(ADDRESS_TARGET, editing = value) },
            )
        }
    }

    dialog?.let { request ->
        val editing = request.editing
        DomainInputDialog(
            initial = editing.orEmpty(),
            isEdit = editing != null,
            onDismiss = { dialog = null },
            onSubmit = { input ->
                val error = when {
                    editing != null && request.context == ADDRESS_TARGET -> onEditAddress(editing, input)
                    editing != null -> onEditDomain(request.context, editing, input)
                    request.context == ADDRESS_TARGET -> onAddAddress(input)
                    else -> onAddDomain(request.context, input)
                }
                if (error == null) dialog = null
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
    onToggleDomain: (String, String) -> Unit,
    onRequestEditDomain: (String, String) -> Unit,
) {
    val cacheWindow = remember {
        LazyLayoutCacheWindow(aheadFraction = 2f, behindFraction = 2f)
    }
    val listState = rememberLazyListState(cacheWindow = cacheWindow)
    val focusManager = LocalFocusManager.current
    val collator = remember { Collator.getInstance(Locale.getDefault()) }
    // Display order: selected apps first, then alphabetical — a full re-sort so deselecting an app
    // drops it back to its alphabetical spot. It is recomputed only when the query changes or the app
    // set is (re)loaded — NOT on a bare selection toggle (the key is the package-name list, which a
    // toggle leaves unchanged) — so (de)selecting moves an app on the next refresh rather than yanking
    // it under the user's finger.
    val orderedPackages = remember(state.query, state.apps.map { it.packageName }) {
        state.apps
            .sortedWith(
                compareByDescending<InstalledApp> { it.isSelected }
                    .thenComparator { a, b -> collator.compare(a.label, b.label) },
            )
            .map { it.packageName }
    }
    val visibleApps = remember(orderedPackages, state.apps, state.query) {
        val byPackage = state.apps.associateBy { it.packageName }
        orderedPackages.mapNotNull(byPackage::get).filter { app ->
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
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Rounded.Close, contentDescription = t("清除", "Clear"))
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
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
                    onToggleDomain = { domain -> onToggleDomain(app.packageName, domain) },
                    onRequestEditDomain = { domain -> onRequestEditDomain(app.packageName, domain) },
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
    onToggleDomain: (String) -> Unit,
    onRequestEditDomain: (String) -> Unit,
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
                        target = domain,
                        onToggle = { onToggleDomain(domain.value) },
                        onEdit = { onRequestEditDomain(domain.value) },
                        onRemove = { onRemoveDomain(domain.value) },
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
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(t("添加域名", "Add domain"))
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = null,
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DomainRow(
    target: DomainTarget,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = LocalPakomoColors.current
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Enable / disable on the left: a filled check when active (accent), a hollow circle when
        // paused (muted).
        RowIcon(
            icon = if (target.enabled) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
            tint = if (target.enabled) colors.accent else colors.muted,
            description = if (target.enabled) {
                t("停用 ${target.value}", "Disable ${target.value}")
            } else {
                t("启用 ${target.value}", "Enable ${target.value}")
            },
            onClick = onToggle,
        )
        MonoText(
            text = target.value,
            // Dim a paused entry so an enabled/disabled row is legible at a glance; tapping the value
            // also toggles it.
            color = if (target.enabled) colors.textPrimary else colors.muted,
            modifier = Modifier
                .weight(1f)
                // Toggle on tap, but with no ripple/press feedback so the value doesn't read as a button.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onToggle,
                )
                .padding(vertical = 12.dp, horizontal = 2.dp),
        )
        // Edit + delete folded into one overflow menu on the right.
        Box {
            RowIcon(
                icon = Icons.Rounded.MoreVert,
                tint = colors.muted,
                description = t("更多操作", "More actions"),
                onClick = { menuOpen = true },
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(t("编辑", "Edit")) },
                    leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    onClick = {
                        menuOpen = false
                        onEdit()
                    },
                )
                DropdownMenuItem(
                    text = { Text(t("删除", "Delete")) },
                    leadingIcon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    onClick = {
                        menuOpen = false
                        onRemove()
                    },
                )
            }
        }
    }
}

@Composable
private fun RowIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    description: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(38.dp)) {
        Icon(imageVector = icon, contentDescription = description, tint = tint, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun AddressScopeContent(
    domains: List<DomainTarget>,
    onRequestAdd: () -> Unit,
    onRemove: (String) -> Unit,
    onToggle: (String) -> Unit,
    onRequestEdit: (String) -> Unit,
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
            items(domains, key = { it.value }) { target ->
                Card(
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.surface),
                    border = BorderStroke(1.dp, colors.border),
                    elevation = CardDefaults.cardElevation(0.dp),
                ) {
                    DomainRow(
                        target = target,
                        onToggle = { onToggle(target.value) },
                        onEdit = { onRequestEdit(target.value) },
                        onRemove = { onRemove(target.value) },
                    )
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
                // Centre the label across the full width, with the + pinned at the start, so the text
                // reads as centred instead of shoved right by a leading icon+label group.
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(t("添加域名", "Add domain"))
                    Icon(
                        Icons.Rounded.Add,
                        contentDescription = null,
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .size(18.dp),
                    )
                }
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
    initial: String,
    isEdit: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (String) -> String?,
) {
    var value by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEdit) t("编辑域名", "Edit domain") else t("添加域名", "Add domain")) },
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
            ) { Text(if (isEdit) t("保存", "Save") else t("添加", "Add")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(t("取消", "Cancel")) }
        },
    )
}

private const val ADDRESS_TARGET = "__address_scope__"

/** An open add/edit request for the domain dialog: [context] is a package name or [ADDRESS_TARGET]. */
private data class DomainDialogRequest(val context: String, val editing: String?)
