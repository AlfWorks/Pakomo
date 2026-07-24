package com.pakomo.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pakomo.BuildConfig
import com.pakomo.core.model.EngineStage
import com.pakomo.core.model.PakomoUiState
import com.pakomo.ui.components.ScreenHeader
import com.pakomo.ui.components.SectionLabel
import com.pakomo.ui.theme.Accent
import com.pakomo.ui.theme.AccentTint
import com.pakomo.ui.theme.Border
import com.pakomo.ui.theme.Danger
import com.pakomo.ui.theme.Muted
import com.pakomo.ui.theme.OnSurface
import com.pakomo.ui.theme.OnSurfaceVariant
import java.util.Locale

@Composable
fun DiagnosticsScreen(
    state: PakomoUiState,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        ScreenHeader(title = "诊断", onBack = onBack)
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            SectionLabel("当前状态")
            InfoCard {
                InfoRow(
                    "服务",
                    when (state.engineStage) {
                        EngineStage.STOPPED -> "已停止"
                        EngineStage.STARTING -> "正在启动"
                        EngineStage.FORWARDING -> "弱网模拟运行中"
                        EngineStage.ERROR -> "启动失败"
                    },
                    valueColor = when (state.engineStage) {
                        EngineStage.ERROR -> Danger
                        EngineStage.STOPPED -> OnSurfaceVariant
                        else -> Accent
                    },
                )
                state.engineMessage?.let { message ->
                    InfoRow("链路状态", message)
                }
                InfoRow("上行速率", formatRate(state.stats.uploadBytesPerSecond))
                InfoRow("下行速率", formatRate(state.stats.downloadBytesPerSecond))
                InfoRow("活跃连接", state.stats.activeConnections.toString())
                InfoRow("主动丢弃次数", state.stats.droppedTransfers.toString())
                InfoRow("延迟处理次数", state.stats.delayedTransfers.toString())
            }

            SectionLabel("统计说明")
            InfoCard {
                InfoRow("数据来源", "本地转发链路")
                InfoRow("流量内容", "不记录")
                InfoRow("统计保留", "服务停止后清零")
            }
            Text(
                text = "主动丢弃和延迟处理是当前规则的累计执行次数；未产生目标流量时数值为 0。",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    var resumeLast by remember { mutableStateOf(false) }
    var showSystemWarning by remember { mutableStateOf(true) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        ScreenHeader(title = "设置", onBack = onBack)
        Column {
            SectionLabel("服务")
            InfoCard {
                SettingSwitchRow(
                    title = "恢复上次运行状态",
                    subtitle = "默认关闭，避免启动应用后自动接管网络",
                    checked = resumeLast,
                    onCheckedChange = { resumeLast = it },
                )
                SettingSwitchRow(
                    title = "敏感应用提醒",
                    subtitle = "选择电话、认证、密码管理等应用时给出提示",
                    checked = showSystemWarning,
                    onCheckedChange = { showSystemWarning = it },
                )
            }
            SectionLabel("网络")
            InfoCard {
                InfoRow("IP 协议", "仅 IPv4")
                InfoRow("IPv6", "旁路，不接管")
                InfoRow("转发内核", "HEV + 本地 SOCKS5")
            }
        }
    }
}

@Composable
fun SecurityScreen(
    state: PakomoUiState,
    onBack: () -> Unit,
    onClearData: () -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        ScreenHeader(title = "安全与隐私", onBack = onBack)
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            SectionLabel("隐私状态")
            InfoCard {
                InfoRow("遥测与上报", "无", Accent)
                InfoRow("广告 SDK", "无", Accent)
                InfoRow("流量内容记录", "无", Accent)
                InfoRow("运行统计", "仅保存在内存")
                InfoRow("云端备份", "已禁用")
            }
            SectionLabel("当前权限")
            InfoCard {
                InfoRow("VPN 服务", "启动时由系统授权")
                InfoRow("应用列表", "用于选择测试目标")
                InfoRow("通知", "显示前台服务状态")
            }
            SectionLabel("本地数据")
            InfoCard {
                InfoRow("已选应用", "${state.selectedApps.size} 个")
                InfoRow(
                    "已存域名",
                    "${state.addressDomains.size + state.apps.sumOf { it.domains.size }} 个",
                )
                TextButton(
                    onClick = { confirmClear = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("清除全部本地数据", color = Danger)
                }
            }
            SectionLabel("关于")
            InfoCard {
                InfoRow("版本", BuildConfig.VERSION_NAME)
                InfoRow("最低系统", "Android 10")
                InfoRow("实现状态", "IPv4 本地弱网转发")
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清除全部本地数据") },
            text = { Text("已选应用、域名、规则和偏好都会被删除。此操作无法撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearData()
                        confirmClear = false
                    },
                ) { Text("清除", color = Danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            },
        )
    }
}

private fun formatRate(bytesPerSecond: Long): String = when {
    bytesPerSecond >= 1_000_000 -> String.format(
        Locale.US,
        "%.1f MB/s",
        bytesPerSecond / 1_000_000.0,
    )
    bytesPerSecond >= 1_000 -> String.format(
        Locale.US,
        "%.1f KB/s",
        bytesPerSecond / 1_000.0,
    )
    else -> "$bytesPerSecond B/s"
}

@Composable
private fun InfoCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Border),
        elevation = CardDefaults.cardElevation(0.dp),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 5.dp),
            content = content,
        )
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    valueColor: Color = OnSurfaceVariant,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = OnSurface)
        Spacer(Modifier.padding(horizontal = 8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = valueColor,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurface,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
        }
        Spacer(Modifier.padding(horizontal = 6.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
