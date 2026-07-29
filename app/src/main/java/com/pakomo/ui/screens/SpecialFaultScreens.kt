package com.pakomo.ui.screens

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pakomo.core.model.BlackoutMode
import com.pakomo.core.model.DnsFailureResult
import com.pakomo.core.model.InstalledApp
import com.pakomo.core.model.NetworkRule
import com.pakomo.core.model.SpecialFaultTargets
import com.pakomo.core.model.SpecialFaultType
import com.pakomo.core.model.TargetScope
import com.pakomo.ui.components.AppIcon
import com.pakomo.ui.components.EmptyArtKind
import com.pakomo.ui.components.EmptyStateArt
import com.pakomo.ui.components.MonoText
import com.pakomo.ui.components.ScreenHeader
import com.pakomo.ui.theme.LocalPakomoColors
import com.pakomo.ui.theme.LocalThemeMode
import com.pakomo.ui.theme.ThemeMode

/**
 * 规则编辑页底部的特殊故障区：三条独立入口、已选数量、冲突标识与故障表现。
 * 所有改动只写入编辑草稿，由规则页的保存按钮统一提交。
 */
@Composable
fun SpecialFaultSection(
    rule: NetworkRule,
    scope: TargetScope,
    selectedAppDomains: Map<String, List<String>>,
    addressDomains: List<String>,
    onToggle: (SpecialFaultType, Boolean) -> Unit,
    onDnsResult: (DnsFailureResult) -> Unit,
    onBlackoutMode: (BlackoutMode) -> Unit,
    onOpenTarget: (SpecialFaultType) -> Unit,
) {
    val colors = LocalPakomoColors.current
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(
            text = "特殊故障",
            style = MaterialTheme.typography.labelLarge,
            color = colors.textSecondary,
            fontWeight = FontWeight.SemiBold,
        )
        val effectiveCounts = remember(
            rule.specialFaults,
            scope,
            selectedAppDomains,
            addressDomains,
        ) {
            SpecialFaultType.entries.associateWith { type ->
                SpecialFaultTargets.effectiveCount(
                    rule.specialFaults.fault(type),
                    scope,
                    selectedAppDomains,
                    addressDomains,
                )
            }
        }
        val conflictingTypes = remember(
            rule.specialFaults,
            scope,
            selectedAppDomains,
            addressDomains,
        ) {
            SpecialFaultTargets.overlaps(
                rule.specialFaults,
                scope,
                selectedAppDomains,
                addressDomains,
            ).values.flatten().toSet()
        }
        SpecialFaultType.entries.forEach { type ->
            val fault = rule.specialFaults.fault(type)
            val canOpen = fault.enabled && scope != TargetScope.GLOBAL
            val status = if (fault.enabled && scope != TargetScope.GLOBAL) {
                "${effectiveCounts.getValue(type)} 个"
            } else {
                null
            }
            FaultEntryRow(
                title = type.entryLabel,
                status = status,
                hasConflict = type in conflictingTypes,
                enabled = fault.enabled,
                canOpen = canOpen,
                onToggle = { onToggle(type, it) },
                onOpen = { if (canOpen) onOpenTarget(type) },
            )
            if (type == SpecialFaultType.DNS_FAILURE && fault.enabled) {
                FaultOptionRow(
                    selected = fault.dnsResult,
                    options = DnsFailureResult.entries,
                    label = DnsFailureResult::behaviorLabel,
                    onSelect = onDnsResult,
                )
            }
            if (type == SpecialFaultType.NETWORK_BLACKOUT && fault.enabled) {
                FaultOptionRow(
                    selected = fault.blackoutMode,
                    options = BlackoutMode.entries,
                    label = BlackoutMode::behaviorLabel,
                    onSelect = onBlackoutMode,
                )
            }
            if (type == SpecialFaultType.CONNECTION_RESET && fault.enabled) {
                FaultBehaviorRow("连接重置（-101）")
            }
        }
    }
}

@Composable
private fun FaultEntryRow(
    title: String,
    status: String?,
    hasConflict: Boolean,
    enabled: Boolean,
    canOpen: Boolean,
    onToggle: (Boolean) -> Unit,
    onOpen: () -> Unit,
) {
    val colors = LocalPakomoColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(10.dp))
            .border(1.dp, if (enabled) colors.selectionBorder else colors.border, RoundedCornerShape(10.dp))
            .then(if (canOpen) Modifier.clickable(onClick = onOpen) else Modifier)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = colors.textPrimary)
        if (status != null) {
            Spacer(Modifier.size(7.dp))
            Text(
                text = status,
                style = MaterialTheme.typography.labelSmall,
                color = colors.textSecondary,
                modifier = Modifier
                    .background(colors.surfaceFold, RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        if (hasConflict) {
            Icon(
                imageVector = Icons.Rounded.WarningAmber,
                contentDescription = "存在与其他特殊故障重复选择的目标",
                tint = colors.warningStrong,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.size(6.dp))
        }
        if (canOpen) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.muted,
            )
            Spacer(Modifier.size(2.dp))
        }
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = colors.accent,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = colors.muted,
            ),
        )
    }
}

@Composable
private fun <T> FaultOptionRow(
    selected: T,
    options: List<T>,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    val colors = LocalPakomoColors.current
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .background(colors.surfaceFold, RoundedCornerShape(8.dp))
            .border(1.dp, colors.border, RoundedCornerShape(8.dp))
            .clickable { expanded = true }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "故障表现",
            style = MaterialTheme.typography.labelSmall,
            color = colors.textSecondary,
        )
        Spacer(Modifier.weight(1f))
        Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label(selected),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.accent,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    textAlign = TextAlign.End,
                )
                Icon(
                    imageVector = Icons.Rounded.ArrowDropDown,
                    contentDescription = "选择故障表现",
                    tint = colors.accent,
                    modifier = Modifier.size(22.dp),
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = label(option),
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.End,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        },
                        onClick = {
                            expanded = false
                            onSelect(option)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun FaultBehaviorRow(text: String) {
    val colors = LocalPakomoColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .background(colors.surfaceFold, RoundedCornerShape(8.dp))
            .border(1.dp, colors.border, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "故障表现",
            style = MaterialTheme.typography.labelSmall,
            color = colors.textSecondary,
        )
        Spacer(Modifier.size(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = colors.accent,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun DnsFailureResult.behaviorLabel(): String = when (this) {
    DnsFailureResult.NXDOMAIN -> "NXDOMAIN（-105）"
    DnsFailureResult.SERVFAIL -> "SERVFAIL（-137）"
    DnsFailureResult.REFUSED -> "REFUSED（-137）"
    DnsFailureResult.TIMEOUT -> "DNS 超时（不固定）"
}

private fun BlackoutMode.behaviorLabel(): String = when (this) {
    BlackoutMode.SILENT -> "静默中断（-7）"
    BlackoutMode.IMMEDIATE -> "立即中断（-102 / -101）"
}

/**
 * 特殊故障的目标选择页。故障表现直接在规则页调整，这里只显示当前接管模式对应的目标。
 */
@Composable
fun FaultTargetScreen(
    rule: NetworkRule,
    type: SpecialFaultType,
    scope: TargetScope,
    apps: List<InstalledApp>,
    addressDomains: List<String>,
    onBack: () -> Unit,
    onSetAppEnabled: (String, Boolean) -> Unit,
    onToggleAppDomain: (String, String, Boolean) -> Unit,
    onToggleAddress: (String, Boolean) -> Unit,
    onDnsCacheGuard: (Boolean) -> Unit,
) {
    val fault = rule.specialFaults.fault(type)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        ScreenHeader(title = type.label, onBack = onBack)
        Spacer(Modifier.height(4.dp))

        // Type-specific parameters.
        when (type) {
            SpecialFaultType.NETWORK_BLACKOUT -> Unit
            SpecialFaultType.DNS_FAILURE -> ToggleParamRow(
                title = "阻止缓存后的连接",
                checked = fault.dnsCacheGuard,
                onCheckedChange = onDnsCacheGuard,
            )
            SpecialFaultType.CONNECTION_RESET -> Unit
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (scope) {
                TargetScope.APPLICATIONS -> {
                    val selected = remember(apps) { apps.filter { it.isSelected } }
                    if (selected.isEmpty()) {
                        EmptyHint("当前没有已接管的应用，请先在接管范围里选择应用。")
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            item { SectionCaption("生效目标") }
                            items(selected, key = InstalledApp::packageName) { app ->
                                val target = fault.appTargets[app.packageName]
                                AppFaultCard(
                                    app = app,
                                    enabled = target?.enabled == true,
                                    selectedDomains = target?.domains.orEmpty(),
                                    onSetEnabled = { onSetAppEnabled(app.packageName, it) },
                                    onToggleDomain = { domain, on -> onToggleAppDomain(app.packageName, domain, on) },
                                )
                            }
                        }
                    }
                }

                TargetScope.ADDRESSES -> {
                    if (addressDomains.isEmpty()) {
                        EmptyHint("还没有指定域名，请先在接管范围里添加域名。")
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            item { SectionCaption("生效目标") }
                            items(addressDomains, key = { it }) { domain ->
                                val checked = domain in fault.addressTargets
                                CheckRow(
                                    title = domain,
                                    mono = true,
                                    checked = checked,
                                    onCheckedChange = { onToggleAddress(domain, it) },
                                )
                            }
                        }
                    }
                }

                TargetScope.GLOBAL -> EmptyHint("全局模式下开启即对整个接管范围生效，无需选择目标。")
            }
        }
    }
}

@Composable
private fun ToggleParamRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = LocalPakomoColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(colors.surface, RoundedCornerShape(12.dp))
            .border(1.dp, colors.border, RoundedCornerShape(12.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = colors.accent,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = colors.muted,
            ),
        )
    }
}

@Composable
private fun SectionCaption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = LocalPakomoColors.current.textSecondary,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@Composable
private fun AppFaultCard(
    app: InstalledApp,
    enabled: Boolean,
    selectedDomains: List<String>,
    onSetEnabled: (Boolean) -> Unit,
    onToggleDomain: (String, Boolean) -> Unit,
) {
    val colors = LocalPakomoColors.current
    val selectedDomainSet = remember(selectedDomains) { selectedDomains.toHashSet() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(12.dp))
            .border(1.dp, if (enabled) colors.selectionBorder else colors.border, RoundedCornerShape(12.dp)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(bitmap = app.icon, fallbackLabel = app.label)
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                MonoText(app.packageName)
            }
            Spacer(Modifier.size(10.dp))
            Switch(
                checked = enabled,
                onCheckedChange = onSetEnabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = colors.accent,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = colors.muted,
                ),
            )
        }

        if (enabled) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surfaceFold)
                    .padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 10.dp),
            ) {
                if (app.domains.isEmpty()) {
                    Text(
                        text = "该应用未限定域名，故障对整个应用生效。",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.muted,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                    )
                } else {
                    val noneSelected = app.domains.none { it in selectedDomainSet }
                    if (noneSelected) {
                        Text(
                            text = "至少选择一个域名，否则该故障对这个应用不生效。",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.danger,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                        )
                    }
                    app.domains.forEach { domain ->
                        CheckRow(
                            title = domain,
                            mono = true,
                            checked = domain in selectedDomainSet,
                            onCheckedChange = { onToggleDomain(domain, it) },
                            compact = true,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckRow(
    title: String,
    mono: Boolean,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    compact: Boolean = false,
) {
    val colors = LocalPakomoColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (compact) Modifier else Modifier.background(colors.surface, RoundedCornerShape(10.dp)).border(1.dp, colors.border, RoundedCornerShape(10.dp)))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = if (compact) 4.dp else 12.dp, vertical = if (compact) 2.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (mono) {
            MonoText(text = title, color = colors.textPrimary, modifier = Modifier.weight(1f))
        } else {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary, modifier = Modifier.weight(1f))
        }
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(
                checkedColor = colors.accent,
                uncheckedColor = colors.muted,
                checkmarkColor = Color.White,
            ),
        )
    }
}

@Composable
private fun EmptyHint(text: String) {
    val companion = LocalThemeMode.current == ThemeMode.Companion
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (companion) {
                EmptyStateArt(EmptyArtKind.Targets, Modifier.size(112.dp))
                Spacer(Modifier.height(10.dp))
            }
            Text(
                text = text,
                color = LocalPakomoColors.current.muted,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}
