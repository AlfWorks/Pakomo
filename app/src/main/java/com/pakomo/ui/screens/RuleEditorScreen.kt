package com.pakomo.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pakomo.core.model.NetworkRule
import com.pakomo.ui.components.ScreenHeader
import com.pakomo.ui.theme.Muted
import com.pakomo.ui.theme.OnSurfaceVariant

@Composable
fun RuleEditorScreen(
    draft: NetworkRule,
    onBack: () -> Unit,
    onSave: (NetworkRule) -> Unit,
) {
    var name by rememberSaveable(draft.id) { mutableStateOf(draft.name) }
    var latency by rememberSaveable(draft.id) { mutableStateOf(draft.latencyMs.toString()) }
    var jitter by rememberSaveable(draft.id) { mutableStateOf(draft.jitterMs.toString()) }
    var loss by rememberSaveable(draft.id) { mutableStateOf(draft.packetLossPercent.toString()) }
    var download by rememberSaveable(draft.id) {
        mutableStateOf(draft.downloadKbps?.toString().orEmpty())
    }
    var upload by rememberSaveable(draft.id) {
        mutableStateOf(draft.uploadKbps?.toString().orEmpty())
    }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        ScreenHeader(
            title = if (draft.name == "新规则") "新建规则" else "编辑规则",
            onBack = onBack,
            action = {
                TextButton(
                    onClick = {
                        val result = validateRule(
                            draft = draft,
                            name = name,
                            latency = latency,
                            jitter = jitter,
                            loss = loss,
                            download = download,
                            upload = upload,
                        )
                        if (result.rule != null) onSave(result.rule) else error = result.error
                    },
                ) { Text("保存") }
            },
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "规则只描述弱网参数，不在这里定义应用、包名或域名。接管对象统一在“接管范围”中维护。",
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceVariant,
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; error = null },
                label = { Text("规则名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
            )
            NumberField(
                "固定延迟",
                "ms",
                latency,
                modifier = Modifier.fillMaxWidth(),
            ) { latency = it; error = null }
            NumberField(
                "抖动",
                "ms",
                jitter,
                modifier = Modifier.fillMaxWidth(),
            ) { jitter = it; error = null }
            NumberField(
                "丢包率",
                "%",
                loss,
                modifier = Modifier.fillMaxWidth(),
            ) { loss = it; error = null }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                NumberField(
                    label = "下载",
                    suffix = "Kbps",
                    value = download,
                    modifier = Modifier.weight(1f),
                    placeholder = "不限",
                ) { download = it; error = null }
                NumberField(
                    label = "上传",
                    suffix = "Kbps",
                    value = upload,
                    modifier = Modifier.weight(1f),
                    placeholder = "不限",
                ) { upload = it; error = null }
            }
            error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                text = "范围：延迟 0–60,000 ms；抖动 0–30,000 ms；丢包 0–100%；限速留空表示不限。",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    val result = validateRule(
                        draft = draft,
                        name = name,
                        latency = latency,
                        jitter = jitter,
                        loss = loss,
                        download = download,
                        upload = upload,
                    )
                    if (result.rule != null) onSave(result.rule) else error = result.error
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text("保存并设为当前规则")
            }
        }
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
    latency: String,
    jitter: String,
    loss: String,
    download: String,
    upload: String,
): RuleValidation {
    if (name.isBlank()) return RuleValidation(error = "规则名称不能为空")
    val latencyValue = latency.toIntOrNull() ?: return RuleValidation(error = "请输入固定延迟")
    val jitterValue = jitter.toIntOrNull() ?: return RuleValidation(error = "请输入抖动")
    val lossValue = loss.toIntOrNull() ?: return RuleValidation(error = "请输入丢包率")
    if (latencyValue !in 0..60_000) return RuleValidation(error = "固定延迟超出范围")
    if (jitterValue !in 0..30_000) return RuleValidation(error = "抖动超出范围")
    if (lossValue !in 0..100) return RuleValidation(error = "丢包率必须在 0–100%")
    val downloadValue = download.toIntOrNull()
    val uploadValue = upload.toIntOrNull()
    if (download.isNotBlank() && (downloadValue == null || downloadValue <= 0)) {
        return RuleValidation(error = "下载限速必须大于 0")
    }
    if (upload.isNotBlank() && (uploadValue == null || uploadValue <= 0)) {
        return RuleValidation(error = "上传限速必须大于 0")
    }
    return RuleValidation(
        rule = draft.copy(
            name = name.trim(),
            latencyMs = latencyValue,
            jitterMs = jitterValue,
            packetLossPercent = lossValue,
            downloadKbps = downloadValue,
            uploadKbps = uploadValue,
            isSystem = false,
        ),
    )
}
