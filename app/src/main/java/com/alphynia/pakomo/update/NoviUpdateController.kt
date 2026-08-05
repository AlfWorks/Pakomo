package com.alphynia.pakomo.update

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import com.alphynia.pakomo.BuildConfig
import com.alphynia.novi.CheckResult
import com.alphynia.novi.DownloadListener
import com.alphynia.novi.DownloadedApk
import com.alphynia.novi.HttpUpdateSource
import com.alphynia.novi.Novi
import com.alphynia.novi.NoviConfig
import com.alphynia.novi.NoviError
import com.alphynia.novi.NoviKeys
import com.alphynia.novi.UpdateRelease
import com.alphynia.novi.VerifiedApk
import com.alphynia.novi.VerificationResult
import com.alphynia.novi.compose.NoviUpdateDialogState
import com.alphynia.novi.compose.NoviUpdatePhase
import com.alphynia.novi.install.InstallCallback
import com.alphynia.novi.install.InstallState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URL

/** Status of an explicit user-triggered "check for updates" (for the About screen's feedback). */
sealed interface UpdateCheckStatus {
    data object Idle : UpdateCheckStatus
    data object Checking : UpdateCheckStatus
    data object UpToDate : UpdateCheckStatus
    data object Disabled : UpdateCheckStatus
    data class Failed(val message: String) : UpdateCheckStatus
}

/**
 * Application-scoped orchestrator around [Novi]. Owns the update dialog state and drives the
 * check -> download -> verify -> install progression; the Activity only renders [dialog] and
 * launches whatever lands in [pendingIntent] while it is in the foreground.
 *
 * Sources come from [BuildConfig.NOVI_UPDATE_SOURCES] (packaging-time, ordered = priority). The
 * trust anchors ([BuildConfig.NOVI_MANIFEST_PUBLIC_KEY] / [BuildConfig.NOVI_APK_SIGNER_SHA256])
 * are the only security-critical values; when any of these is unset self-update stays disabled
 * ([isEnabled] == false) instead of running without a trust boundary.
 *
 * Novi dispatches all callbacks on the main thread, so [dialog]/[pendingIntent] are only ever
 * mutated there.
 */
class NoviUpdateController(app: Application) {

    private val appContext: Context = app.applicationContext
    private val novi: Novi? = buildNovi(appContext)
    private val prefs = appContext.getSharedPreferences("pakomo_update", Context.MODE_PRIVATE)

    /** versionCode the user chose to ignore. The auto-check skips it; a manual check still shows it. */
    private var ignoredVersion: Long
        get() = prefs.getLong("ignored_version", -1L)
        set(value) { prefs.edit().putLong("ignored_version", value).apply() }

    /** True when sources and trust anchors are provisioned and Novi initialised cleanly. */
    val isEnabled: Boolean get() = novi != null

    private val _dialog = MutableStateFlow<NoviUpdateDialogState?>(null)
    val dialog: StateFlow<NoviUpdateDialogState?> = _dialog.asStateFlow()

    /** One-shot intents (system installer confirmation / manual-download browser) for the Activity. */
    private val _pendingIntent = MutableStateFlow<Intent?>(null)
    val pendingIntent: StateFlow<Intent?> = _pendingIntent.asStateFlow()

    /** Result of an explicit [checkNow] request, surfaced to the About screen. */
    private val _checkStatus = MutableStateFlow<UpdateCheckStatus>(UpdateCheckStatus.Idle)
    val checkStatus: StateFlow<UpdateCheckStatus> = _checkStatus.asStateFlow()

    /** Resolved per-flavor update-source root URLs (base + kernel/hev track) — the exact URLs Novi checks. */
    val sourceUrls: List<String> get() = resolvedSourceUrls()

    private var verifiedApk: VerifiedApk? = null
    private var manualInstallIntent: Intent? = null

    /**
     * Recover a session that may have committed while a previous process was replaced. Best-effort
     * and silent: the happy path is that we relaunched into the already-installed new version.
     */
    fun reconcileOnStart() {
        val n = novi ?: return
        runCatching { n.installer().reconcilePending() }
            .onSuccess { state -> if (state != null) Log.i(TAG, "Reconciled pending install: $state") }
            .onFailure { Log.w(TAG, "reconcilePending failed", it) }
    }

    /** Kick off the once-per-process check. No-op when disabled or already run. */
    fun checkOnce() {
        val n = novi ?: return
        n.checkOncePerProcess { result ->
            when (result) {
                is CheckResult.Available ->
                    if (result.release.manifest.versionCode == ignoredVersion) {
                        Log.i(TAG, "Update ${result.release.manifest.versionCode} ignored by user")
                    } else {
                        _dialog.value = available(result.release)
                    }
                is CheckResult.UpToDate -> Log.i(TAG, "Up to date")
                is CheckResult.Failed -> Log.i(TAG, "Update check failed: ${result.errors}")
            }
        }
    }

    /** Explicit user-triggered check (re-runnable, unlike [checkOnce]); surfaces [checkStatus] for the UI. */
    fun checkNow() {
        val n = novi ?: run { _checkStatus.value = UpdateCheckStatus.Disabled; return }
        if (_checkStatus.value == UpdateCheckStatus.Checking) return
        _checkStatus.value = UpdateCheckStatus.Checking
        n.check { result ->
            when (result) {
                is CheckResult.Available -> {
                    _checkStatus.value = UpdateCheckStatus.Idle
                    _dialog.value = available(result.release)
                }
                is CheckResult.UpToDate -> _checkStatus.value = UpdateCheckStatus.UpToDate
                is CheckResult.Failed -> _checkStatus.value = UpdateCheckStatus.Failed(
                    result.errors.firstOrNull()?.let { it.message ?: it.code.name } ?: "unknown",
                )
            }
        }
    }

    /** Primary button dispatch, driven by the current phase. */
    fun onPrimaryAction() {
        val state = _dialog.value ?: return
        when (state.phase) {
            NoviUpdatePhase.AVAILABLE, NoviUpdatePhase.FAILED -> startDownload(state.release)
            NoviUpdatePhase.READY_TO_INSTALL -> startInstall(state.release)
            NoviUpdatePhase.MANUAL_INSTALL -> manualInstallIntent?.let { _pendingIntent.value = it }
            NoviUpdatePhase.DOWNLOADING,
            NoviUpdatePhase.VERIFYING,
            NoviUpdatePhase.INSTALLING -> Unit // busy; button is disabled in the dialog
        }
    }

    fun dismiss() {
        _dialog.value = null
    }

    /** Suppress the current release's version from future auto-checks (a manual check still shows it). */
    fun ignoreCurrentVersion() {
        val release = _dialog.value?.release ?: return
        ignoredVersion = release.manifest.versionCode
        _dialog.value = null
    }

    fun consumeIntent() {
        _pendingIntent.value = null
    }

    private fun startDownload(release: UpdateRelease) {
        val n = novi ?: return
        _dialog.value = NoviUpdateDialogState(release, NoviUpdatePhase.DOWNLOADING, progress = 0f)
        n.download(
            release,
            object : DownloadListener {
                override fun onProgress(downloadedBytes: Long, totalBytes: Long) {
                    val progress = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else null
                    _dialog.value = NoviUpdateDialogState(release, NoviUpdatePhase.DOWNLOADING, progress = progress)
                }

                override fun onSuccess(apk: DownloadedApk) = startVerify(release, apk)

                override fun onFailure(error: NoviError) = fail(release, error)
            },
        )
    }

    private fun startVerify(release: UpdateRelease, apk: DownloadedApk) {
        val n = novi ?: return
        _dialog.value = NoviUpdateDialogState(release, NoviUpdatePhase.VERIFYING)
        n.verify(apk, release) { result ->
            when (result) {
                is VerificationResult.Success -> {
                    verifiedApk = result.apk
                    // Tapping "Update" is already the install intent, and the system installer is
                    // the real consent gate — proceed straight to install rather than parking on a
                    // redundant second tap.
                    startInstall(release)
                }
                is VerificationResult.Failure -> fail(release, result.error)
            }
        }
    }

    private fun startInstall(release: UpdateRelease) {
        val n = novi ?: return
        val apk = verifiedApk ?: return
        _dialog.value = NoviUpdateDialogState(release, NoviUpdatePhase.INSTALLING)
        // Without the "install unknown apps" access, hand the verified local APK to the system
        // package installer via an intent. Android then shows its own "requests to install" prompt
        // and routes the user to enable this source before continuing — instead of Novi's silent
        // browser-download fallback. With the access granted, use Novi's PackageInstaller session
        // (byte re-verification on write + reconcile across process death).
        if (!appContext.packageManager.canRequestPackageInstalls()) {
            launchSystemInstaller(apk)
            return
        }
        n.installer().install(
            apk,
            InstallCallback { state ->
                when (state) {
                    is InstallState.Preparing,
                    is InstallState.Installing -> _dialog.value = NoviUpdateDialogState(release, NoviUpdatePhase.INSTALLING)
                    // Surface the system confirmation intent; keep showing "installing" underneath.
                    is InstallState.PendingUserAction -> _pendingIntent.value = state.intent
                    is InstallState.ManualInstallRequired -> {
                        manualInstallIntent = state.intent
                        _dialog.value = NoviUpdateDialogState(release, NoviUpdatePhase.MANUAL_INSTALL)
                    }
                    is InstallState.Success -> _dialog.value = null
                    is InstallState.Failed -> fail(release, state.error)
                }
            },
        )
    }

    /** Launch the OS package installer on the verified APK; Android handles the source-permission prompt. */
    private fun launchSystemInstaller(apk: VerifiedApk) {
        val uri = FileProvider.getUriForFile(appContext, "${BuildConfig.APPLICATION_ID}.updateprovider", apk.file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        _pendingIntent.value = intent
    }

    private fun fail(release: UpdateRelease, error: NoviError) {
        _dialog.value = NoviUpdateDialogState(
            release = release,
            phase = NoviUpdatePhase.FAILED,
            errorMessage = error.message ?: error.code.name,
        )
    }

    private fun available(release: UpdateRelease) =
        NoviUpdateDialogState(release, NoviUpdatePhase.AVAILABLE)

    private companion object {
        const val TAG = "NoviUpdate"

        /**
         * Packaging-time base source roots + per-flavor track (kernel/hev derived from applicationId) —
         * the exact per-track root URLs Novi checks. Empty when no sources are configured.
         */
        fun resolvedSourceUrls(): List<String> {
            val track = BuildConfig.APPLICATION_ID.substringAfterLast('.')
            return BuildConfig.NOVI_UPDATE_SOURCES.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                .map { it.trimEnd('/') + "/" + track + "/" }
        }

        fun buildNovi(context: Context): Novi? {
            val roots = resolvedSourceUrls()
            val publicKey = BuildConfig.NOVI_MANIFEST_PUBLIC_KEY.trim()
            val signer = BuildConfig.NOVI_APK_SIGNER_SHA256.trim().lowercase()
            if (roots.isEmpty() || publicKey.isEmpty() || signer.isEmpty()) {
                Log.i(TAG, "Self-update disabled: sources or trust anchors not provisioned")
                return null
            }
            val sources = roots.mapIndexed { index, url ->
                HttpUpdateSource(id = "s$index", rootUrl = URL(url))
            }
            return runCatching {
                Novi(
                    NoviConfig(
                        context = context,
                        applicationId = BuildConfig.APPLICATION_ID,
                        currentVersionCode = BuildConfig.VERSION_CODE.toLong(),
                        sources = sources,
                        manifestKeys = mapOf(
                            BuildConfig.NOVI_MANIFEST_KEY_ID to NoviKeys.p256PublicKey(publicKey),
                        ),
                        allowedApkSigners = setOf(signer),
                    ),
                )
            }.getOrElse {
                Log.e(TAG, "Novi init failed; self-update disabled", it)
                null
            }
        }
    }
}
