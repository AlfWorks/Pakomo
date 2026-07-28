package com.pakomo.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.HorizontalDivider
import com.pakomo.core.model.NetworkRule
import com.pakomo.core.model.DnsFailureResult
import com.pakomo.core.model.BlackoutMode
import com.pakomo.core.model.SpecialFaultType
import com.pakomo.core.model.TargetScope
import com.pakomo.ui.components.ScreenHeader
import com.pakomo.ui.theme.Border
import com.pakomo.ui.theme.Accent
import com.pakomo.ui.theme.OnSurfaceVariant

@Composable
fun RuleEditorScreen(
    draft: NetworkRule,
    onBack: () -> Unit,
    onSave: (NetworkRule) -> Unit,
    scope: TargetScope,
    selectedAppDomains: Map<String, List<String>>,
    addressDomains: List<String>,
    onToggleFault: (SpecialFaultType, Boolean) -> Unit,
    onDnsResult: (DnsFailureResult) -> Unit,
    onBlackoutMode: (BlackoutMode) -> Unit,
    onOpenFaultTarget: (SpecialFaultType) -> Unit,
) {
    var name by rememberSaveable(draft.id) { mutableStateOf(draft.name) }
    var advanced by rememberSaveable(draft.id) { mutableStateOf(draft.advanced) }
    var latency by rememberSaveable(draft.id) { mutableStateOf(draft.latencyMs.toString()) }
    var jitter by rememberSaveable(draft.id) { mutableStateOf(draft.jitterMs.toString()) }
    var loss by rememberSaveable(draft.id) { mutableStateOf(draft.packetLossPercent.toString()) }
    // Advanced fields start from the stored per-direction values, or the even split of the simple
    // values so switching modes begins from the equivalent configuration.
    var upLatency by rememberSaveable(draft.id) {
        mutableStateOf((if (draft.advanced) draft.uploadLatencyMs else draft.latencyMs / 2).toString())
    }
    var downLatency by rememberSaveable(draft.id) {
        mutableStateOf((if (draft.advanced) draft.downloadLatencyMs else draft.latencyMs / 2).toString())
    }
    var upJitter by rememberSaveable(draft.id) {
        mutableStateOf((if (draft.advanced) draft.uploadJitterMs else draft.jitterMs / 2).toString())
    }
    var downJitter by rememberSaveable(draft.id) {
        mutableStateOf((if (draft.advanced) draft.downloadJitterMs else draft.jitterMs / 2).toString())
    }
    var upLoss by rememberSaveable(draft.id) {
        mutableStateOf((if (draft.advanced) draft.uploadLossPercent else draft.packetLossPercent / 2).toString())
    }
    var downLoss by rememberSaveable(draft.id) {
        mutableStateOf((if (draft.advanced) draft.downloadLossPercent else draft.packetLossPercent / 2).toString())
    }
    var download by rememberSaveable(draft.id) {
        mutableStateOf(draft.downloadKbps?.toString().orEmpty())
    }
    var upload by rememberSaveable(draft.id) {
        mutableStateOf(draft.uploadKbps?.toString().orEmpty())
    }
    var error by remember { mutableStateOf<String?>(null) }
    var showNameDialog by rememberSaveable(draft.id) { mutableStateOf(false) }
    var pendingName by rememberSaveable(draft.id) { mutableStateOf(draft.name) }

    fun buildRule(): RuleValidation = validateRule(
        draft = draft,
        name = name,
        advanced = advanced,
        latency = latency,
        jitter = jitter,
        loss = loss,
        upLatency = upLatency,
        downLatency = downLatency,
        upJitter = upJitter,
        downJitter = downJitter,
        upLoss = upLoss,
        downLoss = downLoss,
        download = download,
        upload = upload,
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        ScreenHeader(
            title = name,
            onBack = onBack,
            titleContent = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            pendingName = name
                            showNameDialog = true
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = "修改规则名称",
                        tint = OnSurfaceVariant,
                        modifier = Modifier
                            .padding(start = 5.dp, end = 8.dp)
                            .height(20.dp),
                    )
                }
            },
            action = {
                TextButton(
                    onClick = {
                        val result = buildRule()
                        if (result.rule != null) onSave(result.rule) else error = result.error
                    },
                ) { Text("保存") }
            },
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ModeToggle(advanced = advanced, onChange = { advanced = it; error = null })

            Crossfade(
                targetState = advanced,
                animationSpec = tween(110),
                label = "ruleModeContent",
            ) { advancedMode ->
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!advancedMode) {
                        NumberField("固定延迟", "ms", latency, Modifier.fillMaxWidth()) {
                            latency = it
                            error = null
                        }
                        NumberField("抖动", "ms", jitter, Modifier.fillMaxWidth()) {
                            jitter = it
                            error = null
                        }
                        NumberField("丢包率", "%", loss, Modifier.fillMaxWidth()) {
                            loss = it
                            error = null
                        }
                    } else {
                        DirectionRow("上行延迟", "下行延迟", "ms", upLatency, downLatency,
                            { upLatency = it; error = null }, { downLatency = it; error = null })
                        DirectionRow("上行抖动", "下行抖动", "ms", upJitter, downJitter,
                            { upJitter = it; error = null }, { downJitter = it; error = null })
                        DirectionRow("上行丢包", "下行丢包", "%", upLoss, downLoss,
                            { upLoss = it; error = null }, { downLoss = it; error = null })
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                NumberField("下载", "Kbps", download, Modifier.weight(1f), placeholder = "不限") {
                    download = it; error = null
                }
                NumberField("上传", "Kbps", upload, Modifier.weight(1f), placeholder = "不限") {
                    upload = it; error = null
                }
            }

            error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            HorizontalDivider(color = Border, thickness = 1.dp)
            SpecialFaultSection(
                rule = draft,
                scope = scope,
                selectedAppDomains = selectedAppDomains,
                addressDomains = addressDomains,
                onToggle = onToggleFault,
                onDnsResult = onDnsResult,
                onBlackoutMode = onBlackoutMode,
                onOpenTarget = onOpenFaultTarget,
            )
        }
    }

    if (showNameDialog) {
        AlertDialog(
            onDismissRequest = { showNameDialog = false },
            title = { Text("修改规则名称") },
            text = {
                OutlinedTextField(
                    value = pendingName,
                    onValueChange = { pendingName = it },
                    label = { Text("规则名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = pendingName.isNotBlank(),
                    onClick = {
                        name = pendingName.trim()
                        error = null
                        showNameDialog = false
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showNameDialog = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ModeToggle(advanced: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFF0F2F5), RoundedCornerShape(10.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        listOf(false to "简单", true to "高级").forEach { (value, label) ->
            val active = value == advanced
            val segmentColor by animateColorAsState(
                targetValue = if (active) Color.White else Color.Transparent,
                animationSpec = tween(140),
                label = "modeSegment",
            )
            val textColor by animateColorAsState(
                targetValue = if (active) Accent else OnSurfaceVariant,
                animationSpec = tween(140),
                label = "modeText",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(segmentColor)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.RadioButton,
                    ) { onChange(value) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = textColor,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

@Composable
private fun DirectionRow(
    upLabel: String,
    downLabel: String,
    suffix: String,
    upValue: String,
    downValue: String,
    onUpChange: (String) -> Unit,
    onDownChange: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        NumberField(upLabel, suffix, upValue, Modifier.weight(1f), onValueChange = onUpChange)
        NumberField(downLabel, suffix, downValue, Modifier.weight(1f), onValueChange = onDownChange)
    }
}

@Composable
private fun NumberField(
    label: String,
    suffix: String,
    value: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { input ->
            if (input.all(Char::isDigit)) onValueChange(input)
        },
        label = { Text(label) },
        suffix = { Text(suffix) },
        placeholder = placeholder?.let { text -> ({ Text(text) }) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
    )
}

private data class RuleValidation(
    val rule: NetworkRule? = null,
    val error: String? = null,
)

private fun validateRule(
    draft: NetworkRule,
    name: String,
    advanced: Boolean,
    latency: String,
    jitter: String,
    loss: String,
    upLatency: String,
    downLatency: String,
    upJitter: String,
    downJitter: String,
    upLoss: String,
    downLoss: String,
    download: String,
    upload: String,
): RuleValidation {
    if (name.isBlank()) return RuleValidation(error = "规则名称不能为空")
    val downloadValue = download.toIntOrNull()
    val uploadValue = upload.toIntOrNull()
    if (download.isNotBlank() && (downloadValue == null || downloadValue <= 0)) {
        return RuleValidation(error = "下载限速必须大于 0")
    }
    if (upload.isNotBlank() && (uploadValue == null || uploadValue <= 0)) {
        return RuleValidation(error = "上传限速必须大于 0")
    }

    if (advanced) {
        val ul = upLatency.toIntOrNull() ?: return RuleValidation(error = "请输入上行延迟")
        val dl = downLatency.toIntOrNull() ?: return RuleValidation(error = "请输入下行延迟")
        val uj = upJitter.toIntOrNull() ?: return RuleValidation(error = "请输入上行抖动")
        val dj = downJitter.toIntOrNull() ?: return RuleValidation(error = "请输入下行抖动")
        val ulo = upLoss.toIntOrNull() ?: return RuleValidation(error = "请输入上行丢包")
        val dlo = downLoss.toIntOrNull() ?: return RuleValidation(error = "请输入下行丢包")
        if (ul !in 0..60_000 || dl !in 0..60_000) return RuleValidation(error = "延迟超出范围")
        if (uj !in 0..30_000 || dj !in 0..30_000) return RuleValidation(error = "抖动超出范围")
        if (ulo !in 0..100 || dlo !in 0..100) return RuleValidation(error = "丢包率必须在 0–100%")
        return RuleValidation(
            rule = draft.copy(
                name = name.trim(),
                isSystem = false,
                advanced = true,
                uploadLatencyMs = ul,
                downloadLatencyMs = dl,
                uploadJitterMs = uj,
                downloadJitterMs = dj,
                uploadLossPercent = ulo,
                downloadLossPercent = dlo,
                downloadKbps = downloadValue,
                uploadKbps = uploadValue,
                // Combined nominal values kept for the summary and for switching back to simple.
                latencyMs = ul + dl,
                jitterMs = uj + dj,
                packetLossPercent = 100 - (100 - ulo) * (100 - dlo) / 100,
            ),
        )
    }

    val latencyValue = latency.toIntOrNull() ?: return RuleValidation(error = "请输入固定延迟")
    val jitterValue = jitter.toIntOrNull() ?: return RuleValidation(error = "请输入抖动")
    val lossValue = loss.toIntOrNull() ?: return RuleValidation(error = "请输入丢包率")
    if (latencyValue !in 0..60_000) return RuleValidation(error = "固定延迟超出范围")
    if (jitterValue !in 0..30_000) return RuleValidation(error = "抖动超出范围")
    if (lossValue !in 0..100) return RuleValidation(error = "丢包率必须在 0–100%")
    return RuleValidation(
        rule = draft.copy(
            name = name.trim(),
            isSystem = false,
            advanced = false,
            latencyMs = latencyValue,
            jitterMs = jitterValue,
            packetLossPercent = lossValue,
            downloadKbps = downloadValue,
            uploadKbps = uploadValue,
        ),
    )
}
