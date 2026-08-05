package com.alphynia.pakomo.update

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.alphynia.pakomo.BuildConfig
import com.alphynia.pakomo.ui.theme.LocalAppLanguage
import com.alphynia.novi.compose.NoviUpdateDialogState
import com.alphynia.novi.compose.NoviUpdatePhase
import java.util.Locale

/**
 * Pakomo-native update dialog. Renders the same [NoviUpdateDialogState] the controller drives, but
 * adds an expandable verification-details panel exposing the concrete values Novi checked (version,
 * size, expected SHA-256, manifest key, required signer) for trust transparency. Novi core/compose
 * are unchanged — this replaces only the presentation.
 */
@Composable
fun PakomoUpdateDialog(
    state: NoviUpdateDialogState,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit,
    onIgnore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lang = LocalAppLanguage.current
    fun t(zh: String, en: String) = lang.tr(zh, en)

    val manifest = state.release.manifest
    val busy = state.phase in setOf(
        NoviUpdatePhase.DOWNLOADING,
        NoviUpdatePhase.VERIFYING,
        NoviUpdatePhase.INSTALLING,
    )
    val canDismiss = !manifest.mandatory && !busy

    AlertDialog(
        modifier = modifier,
        onDismissRequest = { if (canDismiss) onDismiss() },
        title = { Text(t("发现新版本", "Update available") + " · " + manifest.versionName) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Phase status line + progress.
                when (state.phase) {
                    NoviUpdatePhase.AVAILABLE ->
                        Text(manifest.changelog ?: t("有可用的新版本。", "A new version is ready."))
                    NoviUpdatePhase.DOWNLOADING -> {
                        val p = state.progress
                        Text(if (p == null) t("下载中…", "Downloading…") else t("下载中… %d%%", "Downloading… %d%%").format((p * 100).toInt()))
                        if (p == null) LinearProgressIndicator() else LinearProgressIndicator(progress = { p })
                    }
                    NoviUpdatePhase.VERIFYING -> Text(t("正在校验更新…", "Verifying the update…"))
                    NoviUpdatePhase.READY_TO_INSTALL -> Text(t("校验通过，准备安装。", "Verified and ready to install."))
                    NoviUpdatePhase.MANUAL_INSTALL -> Text(t("请手动下载并安装此更新。", "Download and install this update manually."))
                    NoviUpdatePhase.INSTALLING -> Text(t("等待系统安装器…", "Waiting for the system installer…"))
                    NoviUpdatePhase.FAILED -> Text(state.errorMessage ?: t("更新未能完成。", "The update could not be completed."))
                }

                VerificationDetails(
                    versionLine = "${manifest.versionName} (${manifest.versionCode})",
                    applicationId = manifest.applicationId,
                    size = formatBytes(manifest.artifact.sizeBytes),
                    schema = manifest.schemaVersion,
                    keyId = manifest.keyId,
                    sha256 = manifest.artifact.sha256,
                    signer = BuildConfig.NOVI_APK_SIGNER_SHA256,
                    sources = BuildConfig.NOVI_UPDATE_SOURCES,
                    artifactPath = manifest.artifact.path,
                    mandatory = manifest.mandatory,
                    t = ::t,
                )
            }
        },
        // Custom button row: "忽略该版本" on the left, "稍后 / 更新" kept together on the right.
        // (AlertDialog's confirm/dismiss slots right-align everything, so both go in confirmButton.)
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (canDismiss && state.phase == NoviUpdatePhase.AVAILABLE) {
                    TextButton(onClick = onIgnore) { Text(t("忽略该版本", "Ignore")) }
                } else {
                    Row {}
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (canDismiss) {
                        TextButton(onClick = onDismiss) { Text(t("稍后", "Later")) }
                    }
                    TextButton(onClick = onUpdate, enabled = !busy) {
                        Text(primaryLabel(state.phase, ::t))
                    }
                }
            }
        },
        dismissButton = null,
    )
}

@Composable
private fun VerificationDetails(
    versionLine: String,
    applicationId: String,
    size: String,
    schema: Int,
    keyId: String,
    sha256: String,
    signer: String,
    sources: String,
    artifactPath: String,
    mandatory: Boolean,
    t: (String, String) -> String,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = (if (expanded) "▾ " else "▸ ") + t("校验详情", "Verification details"),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
        )
        if (expanded) {
            DetailRow(t("版本", "Version"), versionLine)
            DetailRow(t("包名", "Package"), applicationId)
            DetailRow(t("大小", "Size"), size)
            DetailRow(t("是否强制", "Mandatory"), if (mandatory) t("是", "Yes") else t("否", "No"))
            DetailRow(t("清单版本", "Schema"), schema.toString())
            DetailRow(t("清单密钥", "Manifest key"), keyId)
            DetailRow(t("SHA-256（已校验）", "SHA-256 (verified)"), shortHash(sha256), mono = true)
            DetailRow(t("要求签名者", "Required signer"), shortHash(signer), mono = true)
            DetailRow(t("更新源", "Source"), sources)
            DetailRow(t("制品路径", "Artifact"), artifactPath)
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, mono: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        )
    }
}

/** Git-style short hash for a 64-char SHA-256: first 10 + … + last 6. */
private fun shortHash(hash: String): String =
    if (hash.length <= 20) hash else "${hash.take(10)}…${hash.takeLast(6)}"

private fun primaryLabel(phase: NoviUpdatePhase, t: (String, String) -> String): String = when (phase) {
    NoviUpdatePhase.AVAILABLE -> t("更新", "Update")
    NoviUpdatePhase.DOWNLOADING -> t("下载中", "Downloading")
    NoviUpdatePhase.VERIFYING -> t("校验中", "Verifying")
    NoviUpdatePhase.READY_TO_INSTALL -> t("安装", "Install")
    NoviUpdatePhase.MANUAL_INSTALL -> t("打开手动下载", "Open manual download")
    NoviUpdatePhase.INSTALLING -> t("安装中", "Installing")
    NoviUpdatePhase.FAILED -> t("重试", "Retry")
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val mb = bytes / (1024.0 * 1024.0)
    if (mb >= 1) return String.format(Locale.US, "%.1f MB", mb)
    val kb = bytes / 1024.0
    return String.format(Locale.US, "%.0f KB", kb)
}
