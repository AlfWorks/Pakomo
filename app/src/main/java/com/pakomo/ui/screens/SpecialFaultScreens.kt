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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pakomo.core.model.BlackoutMode
import com.pakomo.core.model.DnsFailureResult
import com.pakomo.core.model.FaultTarget
import com.pakomo.core.model.InstalledApp
import com.pakomo.core.model.NetworkRule
import com.pakomo.core.model.SpecialFaultTargets
import com.pakomo.core.model.SpecialFaultType
import com.pakomo.core.model.TargetScope
import com.pakomo.ui.components.AppIcon
import com.pakomo.ui.components.MonoText
import com.pakomo.ui.components.ScreenHeader
import com.pakomo.ui.theme.Accent
import com.pakomo.ui.theme.Border
import com.pakomo.ui.theme.Danger
import com.pakomo.ui.theme.Muted
import com.pakomo.ui.theme.OnSurface
import com.pakomo.ui.theme.OnSurfaceVariant
import com.pakomo.ui.theme.SurfaceFold

/**
 * 规则编辑页底部的特殊故障区：三条独立入口（开关 + 已选数量）与重叠提示。
 * 所有改动只写入编辑草稿，由规则页的保存按钮统一提交。
 */
@Composable
fun SpecialFaultSection(
    rule: NetworkRule,
    scope: TargetScope,
    selectedAppDomains: Map<String, List<String>>,
    addressDomains: List<String>,
    appLabels: Map<String, String>,
    onToggle: (SpecialFaultType, Boolean) -> Unit,
    onOpenTarget: (SpecialFaultType) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "特殊故障",
            style = MaterialTheme.typography.labelLarge,
            color = OnSurfaceVariant,
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
        SpecialFaultType.entries.forEach { type ->
            val fault = rule.specialFaults.fault(type)
            // Domain/app modes drill into target selection; global has no targets but blackout/DNS
            // still have a parameter worth opening for. Reset has no parameter, so nothing to open.
            val hasParams = type != SpecialFaultType.CONNECTION_RESET
            val canOpen = fault.enabled && (scope != TargetScope.GLOBAL || hasParams)
            val status = when {
                !fault.enabled -> "未启用"
                scope == TargetScope.GLOBAL -> "全局生效"
                else -> "已选 ${effectiveCounts.getValue(type)} 个"
            }
            FaultEntryRow(
                title = type.entryLabel,
                status = status,
                enabled = fault.enabled,
                canOpen = canOpen,
                onToggle = { onToggle(type, it) },
                onOpen = { if (canOpen) onOpenTarget(type) },
            )
        }

        val overlaps = remember(rule.specialFaults, scope, selectedAppDomains, addressDomains) {
            SpecialFaultTargets.overlaps(
                rule.specialFaults,
                scope,
                selectedAppDomains,
                addressDomains,
            )
        }
        if (overlaps.isNotEmpty()) {
            OverlapHint(overlaps = overlaps, appLabels = appLabels)
        }
    }
}

@Composable
private fun FaultEntryRow(
    title: String,
    status: String,
    enabled: Boolean,
    canOpen: Boolean,
    onToggle: (Boolean) -> Unit,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(10.dp))
            .border(1.dp, if (enabled) Color(0xFFD6DFF5) else Border, RoundedCornerShape(10.dp))
            .then(if (canOpen) Modifier.clickable(onClick = onOpen) else Modifier)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = OnSurface)
            Spacer(Modifier.height(2.dp))
            Text(status, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        if (canOpen) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = Muted,
            )
            Spacer(Modifier.size(4.dp))
        }
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Accent,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Muted,
            ),
        )
    }
}

@Composable
private fun OverlapHint(
    overlaps: Map<FaultTarget, List<SpecialFaultType>>,
    appLabels: Map<String, String>,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFFFF5E6), RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "以下目标被多种故障同时选中（不影响保存）：",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF8A5A00),
            fontWeight = FontWeight.SemiBold,
        )
        overlaps.forEach { (target, types) ->
            Text(
                text = "· ${targetLabel(target, appLabels)}：${types.joinToString("、") { it.entryLabel }}",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF8A5A00),
            )
        }
    }
}

private fun targetLabel(target: FaultTarget, appLabels: Map<String, String>): String = when (target) {
    is FaultTarget.Global -> "全局范围"
    is FaultTarget.WholeApp -> "${appLabels[target.packageName] ?: target.packageName} · 整个应用"
    is FaultTarget.ApplicationDomain ->
        "${target.domain}（${appLabels[target.packageName] ?: target.packageName}）"
    is FaultTarget.AddressDomain -> target.domain
}

/**
 * 特殊故障的详情页：顶部是该故障的参数（中断表现 / DNS 返回结果），下面按当前接管模式选择目标。
 * 全局模式没有目标，只显示参数与说明。
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
    onBlackoutMode: (BlackoutMode) -> Unit,
    onDnsResult: (DnsFailureResult) -> Unit,
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
            SpecialFaultType.NETWORK_BLACKOUT -> ParamSection(
                title = "中断表现",
                options = BlackoutMode.entries,
                selected = fault.blackoutMode,
                label = { it.label },
                onSelect = onBlackoutMode,
            )
            SpecialFaultType.DNS_FAILURE -> {
                ParamSection(
                    title = "返回结果",
                    options = DnsFailureResult.entries,
                    selected = fault.dnsResult,
                    label = { it.label },
                    onSelect = onDnsResult,
                )
                ToggleParamRow(
                    title = "阻止缓存后的连接",
                    checked = fault.dnsCacheGuard,
                    onCheckedChange = onDnsCacheGuard,
                )
            }
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(Color.White, RoundedCornerShape(12.dp))
            .border(1.dp, Border, RoundedCornerShape(12.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurface,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Accent,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Muted,
            ),
        )
    }
}

/** Single-choice parameter list (radio rows). */
@Composable
private fun <T> ParamSection(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        SectionCaption(title)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White, RoundedCornerShape(12.dp))
                .border(1.dp, Border, RoundedCornerShape(12.dp)),
        ) {
            options.forEachIndexed { index, option ->
                val isSelected = option == selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(option) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = label(option),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isSelected) Accent else OnSurface,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.weight(1f),
                    )
                    RadioButton(
                        selected = isSelected,
                        onClick = { onSelect(option) },
                        colors = RadioButtonDefaults.colors(selectedColor = Accent, unselectedColor = Muted),
                    )
                }
                if (index < options.lastIndex) {
                    androidx.compose.material3.HorizontalDivider(
                        modifier = Modifier.padding(start = 14.dp),
                        color = Border,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionCaption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = OnSurfaceVariant,
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
    val selectedDomainSet = remember(selectedDomains) { selectedDomains.toHashSet() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(12.dp))
            .border(1.dp, if (enabled) Color(0xFFD6DFF5) else Border, RoundedCornerShape(12.dp)),
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
                    color = OnSurface,
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
                    checkedTrackColor = Accent,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = Muted,
                ),
            )
        }

        if (enabled) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceFold)
                    .padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 10.dp),
            ) {
                if (app.domains.isEmpty()) {
                    Text(
                        text = "该应用未限定域名，故障对整个应用生效。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                    )
                } else {
                    val noneSelected = app.domains.none { it in selectedDomainSet }
                    if (noneSelected) {
                        Text(
                            text = "至少选择一个域名，否则该故障对这个应用不生效。",
                            style = MaterialTheme.typography.bodySmall,
                            color = Danger,
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (compact) Modifier else Modifier.background(Color.White, RoundedCornerShape(10.dp)).border(1.dp, Border, RoundedCornerShape(10.dp)))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = if (compact) 4.dp else 12.dp, vertical = if (compact) 2.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (mono) {
            MonoText(text = title, color = OnSurface, modifier = Modifier.weight(1f))
        } else {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = OnSurface, modifier = Modifier.weight(1f))
        }
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(
                checkedColor = Accent,
                uncheckedColor = Muted,
                checkmarkColor = Color.White,
            ),
        )
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Muted, style = MaterialTheme.typography.bodyMedium)
    }
}
