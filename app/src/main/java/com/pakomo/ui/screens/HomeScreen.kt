package com.pakomo.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pakomo.core.model.EngineStage
import com.pakomo.core.model.PakomoUiState
import com.pakomo.core.model.TargetScope
import com.pakomo.ui.components.Hairline
import com.pakomo.ui.components.NavigationRow
import com.pakomo.ui.components.ScopeSelector
import com.pakomo.ui.theme.Accent
import com.pakomo.ui.theme.AccentTint
import com.pakomo.ui.theme.Border
import com.pakomo.ui.theme.Danger
import com.pakomo.ui.theme.Muted
import com.pakomo.ui.theme.OnSurface
import com.pakomo.ui.theme.OnSurfaceVariant

@Composable
fun HomeScreen(
    state: PakomoUiState,
    onScopeSelected: (TargetScope) -> Unit,
    onOpenScope: () -> Unit,
    onOpenRules: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSecurity: () -> Unit,
    onToggleService: () -> Unit,
    onEmergencyStop: () -> Unit,
) {
    var pendingGlobal by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(14.dp))
            Text(
                text = "Pakomo",
                style = MaterialTheme.typography.headlineSmall,
                color = OnSurface,
            )
            Text(
                text = "非 Root 弱网模拟",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
            Spacer(Modifier.height(16.dp))

            ServiceStatusCard(
                stage = state.engineStage,
                state = state,
                onToggle = onToggleService,
            )

            Spacer(Modifier.height(16.dp))
            Text(
                text = "接管范围",
                style = MaterialTheme.typography.labelMedium,
                color = OnSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            ScopeSelector(
                selected = state.scope,
                onSelected = { scope ->
                    if (scope == TargetScope.GLOBAL && state.scope != TargetScope.GLOBAL) {
                        pendingGlobal = true
                    } else {
                        onScopeSelected(scope)
                    }
                },
            )
            Spacer(Modifier.height(7.dp))
            Text(
                text = state.scope.description,
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
            Spacer(Modifier.height(12.dp))
        }

        NavigationRow(
            icon = Icons.Rounded.GridView,
            title = when (state.scope) {
                TargetScope.APPLICATIONS -> "选择应用"
                TargetScope.ADDRESSES -> "管理地址"
                TargetScope.GLOBAL -> "接管范围"
            },
            subtitle = when (state.scope) {
                TargetScope.APPLICATIONS -> "应用卡片内可添加多个域名"
                TargetScope.ADDRESSES -> "配置需要处理的域名"
                TargetScope.GLOBAL -> "全局模式启动前会再次确认"
            },
            value = when (state.scope) {
                TargetScope.APPLICATIONS -> "${state.selectedApps.size} 个"
                TargetScope.ADDRESSES -> "${state.addressDomains.size} 个"
                TargetScope.GLOBAL -> "全局"
            },
            onClick = onOpenScope,
        )
        Hairline()
        NavigationRow(
            icon = Icons.Rounded.Bolt,
            title = "弱网规则",
            subtitle = state.activeRule.summary,
            value = state.activeRule.name,
            valueColor = Accent,
            onClick = onOpenRules,
        )
        Hairline()
        NavigationRow(
            icon = Icons.Rounded.BugReport,
            title = "诊断",
            subtitle = "实时统计与本地链路状态",
            value = if (state.engineStage == EngineStage.FORWARDING) "实时" else "可用",
            valueColor = if (state.engineStage == EngineStage.FORWARDING) {
                Accent
            } else {
                OnSurfaceVariant
            },
            onClick = onOpenDiagnostics,
        )
        Hairline()
        NavigationRow(
            icon = Icons.Rounded.Settings,
            title = "设置",
            subtitle = "服务行为与界面偏好",
            onClick = onOpenSettings,
        )
        Hairline()
        NavigationRow(
            icon = Icons.Rounded.Security,
            title = "安全与隐私",
            subtitle = "权限、数据与开源说明",
            value = "无遥测",
            valueColor = Accent,
            onClick = onOpenSecurity,
        )

        Spacer(Modifier.weight(1f))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    enabled = state.engineStage.isActive,
                    onClick = onEmergencyStop,
                )
                .padding(horizontal = 16.dp, vertical = 17.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.Icon(
                imageVector = Icons.Rounded.PowerSettingsNew,
                contentDescription = null,
                tint = if (state.engineStage.isActive) Danger else Muted,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.size(14.dp))
            Column {
                Text(
                    text = "紧急恢复正常网络",
                    color = if (state.engineStage.isActive) Danger else Muted,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "立即停止服务并释放 VPN",
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    if (pendingGlobal) {
        AlertDialog(
            onDismissRequest = { pendingGlobal = false },
            title = { Text("确认全局接管") },
            text = { Text("全局模式影响范围较大，建议首次测试只选择目标应用。仍要切换到全局吗？") },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        pendingGlobal = false
                        onScopeSelected(TargetScope.GLOBAL)
                    },
                ) { Text("继续") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { pendingGlobal = false },
                ) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ServiceStatusCard(
    stage: EngineStage,
    state: PakomoUiState,
    onToggle: () -> Unit,
) {
    val isRunning = stage.isActive
    val isError = stage == EngineStage.ERROR
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(76.dp),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isError -> Color(0xFFFFF5F4)
                isRunning -> AccentTint
                else -> Color.White
            },
        ),
        border = BorderStroke(
            1.dp,
            when {
                isError -> Color(0xFFF0C8C4)
                isRunning -> Color(0xFFD7E1FA)
                else -> Border
            },
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(
                modifier = Modifier
                    .size(9.dp)
                    .background(
                        when {
                            isError -> Danger
                            isRunning -> Accent
                            else -> Muted
                        },
                        CircleShape,
                    ),
            )
            Spacer(Modifier.size(11.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = when (stage) {
                        EngineStage.STOPPED -> "服务已停止"
                        EngineStage.STARTING -> "正在启动"
                        EngineStage.FORWARDING -> "弱网模拟运行中"
                        EngineStage.ERROR -> "启动失败"
                    },
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = OnSurface,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = when (stage) {
                        EngineStage.STOPPED -> "开启后申请系统 VPN 权限"
                        EngineStage.STARTING -> state.engineMessage ?: "正在建立本地转发链路"
                        EngineStage.FORWARDING ->
                            "↑${state.stats.uploadBytesPerSecond}  ↓${state.stats.downloadBytesPerSecond} B/s"
                        EngineStage.ERROR -> state.engineMessage ?: "请打开诊断页查看原因"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Switch(
                checked = isRunning,
                onCheckedChange = { onToggle() },
                colors = SwitchDefaults.colors(
                    checkedTrackColor = Accent,
                    uncheckedTrackColor = Color(0xFFD7DBE0),
                    uncheckedBorderColor = Color.Transparent,
                ),
            )
        }
    }
}
