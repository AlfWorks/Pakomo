package com.alphynia.pakomo

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.net.VpnService
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.alphynia.pakomo.core.model.AppListAccess
import com.alphynia.pakomo.core.model.TargetScope
import com.alphynia.pakomo.data.PakomoPreferences
import com.alphynia.pakomo.overlay.QuickControlService
import com.alphynia.pakomo.ui.PakomoApp
import com.alphynia.pakomo.ui.PakomoViewModel
import com.alphynia.pakomo.ui.theme.PakomoTheme
import com.alphynia.pakomo.update.PakomoUpdateDialog
import com.alphynia.pakomo.vpn.VpnServiceController

class MainActivity : ComponentActivity() {
    private val viewModel: PakomoViewModel by viewModels()
    private val preferences by lazy { PakomoPreferences(this) }
    private val updateController by lazy { (application as PakomoApplication).updateController }
    private var vpnPermissionGranted by mutableStateOf(false)
    private var notificationPermissionGranted by mutableStateOf(false)
    private var quickControlEnabled by mutableStateOf(false)
    private var startAfterVpnPermission = false
    private var enableQuickControlAfterPermission = false

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                Log.i(TAG, "VPN permission granted")
                refreshPermissionStates()
                if (startAfterVpnPermission) startVpn()
            } else {
                Log.w(TAG, "VPN permission denied")
                refreshPermissionStates()
            }
            startAfterVpnPermission = false
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshPermissionStates()
        }

    private val appPermissionSettingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshPermissionStates()
            viewModel.refreshApps()
        }

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val granted = Settings.canDrawOverlays(this)
            if (enableQuickControlAfterPermission && granted) {
                enableQuickControl()
            } else {
                quickControlEnabled = false
            }
            enableQuickControlAfterPermission = false
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "Application started")
        enableEdgeToEdge()
        refreshPermissionStates()
        QuickControlService.setHostVisible(true)
        if (quickControlEnabled) QuickControlService.start(this)

        setContent {
            val serviceRuntime by VpnServiceController.runtime.collectAsState()
            val themeMode by viewModel.themeMode.collectAsState()
            val language by viewModel.language.collectAsState()
            LaunchedEffect(serviceRuntime) {
                viewModel.setEngineRuntime(serviceRuntime)
            }

            PakomoTheme(themeMode = themeMode, language = language) {
                PakomoApp(
                    viewModel = viewModel,
                    vpnPermissionGranted = vpnPermissionGranted,
                    notificationPermissionGranted = notificationPermissionGranted,
                    quickControlEnabled = quickControlEnabled,
                    themeMode = themeMode,
                    onThemeModeChange = viewModel::setThemeMode,
                    language = language,
                    onLanguageChange = viewModel::setLanguage,
                    onToggleService = {
                        if (!serviceRuntime.stage.isActive) {
                            val current = viewModel.state.value
                            if (
                                current.scope == TargetScope.APPLICATIONS &&
                                current.appListAccess != AppListAccess.AVAILABLE
                            ) {
                                Toast.makeText(
                                    this@MainActivity,
                                    language.tr("正在检查应用列表，请稍后再启动", "Checking the app list, please start again shortly"),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            } else {
                                requestVpnPermission(startAfterGrant = true)
                            }
                        } else {
                            VpnServiceController.stop(this@MainActivity)
                        }
                    },
                    onVpnPermissionClick = {
                        if (vpnPermissionGranted) {
                            startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
                        } else {
                            requestVpnPermission(startAfterGrant = false)
                        }
                    },
                    onNotificationPermissionClick = {
                        if (notificationPermissionGranted) {
                            openNotificationSettings()
                        } else {
                            requestNotificationPermission()
                        }
                    },
                    onAppListPermissionClick = {
                        openAppPermissionSettings()
                    },
                    onQuickControlChanged = ::updateQuickControlEnabled,
                    onClearData = {
                        viewModel.clearLocalData()
                        updateQuickControlEnabled(false)
                    },
                )

                // Self-update surface: Novi owns the state, we only render and launch intents
                // while foregrounded (system installer confirmation / manual-download browser).
                val updateDialog by updateController.dialog.collectAsState()
                updateDialog?.let { state ->
                    PakomoUpdateDialog(
                        state = state,
                        onUpdate = { updateController.onPrimaryAction() },
                        onDismiss = { updateController.dismiss() },
                    )
                }
                val updateIntent by updateController.pendingIntent.collectAsState()
                LaunchedEffect(updateIntent) {
                    updateIntent?.let {
                        startActivity(it)
                        updateController.consumeIntent()
                    }
                }
            }
        }
        updateController.checkOnce()
        handleQuickControlIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleQuickControlIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        QuickControlService.setHostVisible(true)
    }

    override fun onStop() {
        QuickControlService.setHostVisible(false)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStates()
    }

    private fun requestVpnPermission(startAfterGrant: Boolean) {
        startAfterVpnPermission = startAfterGrant
        val permissionIntent: Intent? = VpnService.prepare(this)
        if (permissionIntent == null) {
            Log.i(TAG, "VPN permission already granted")
            refreshPermissionStates()
            if (startAfterGrant) startVpn()
            startAfterVpnPermission = false
        } else {
            Log.i(TAG, "Requesting VPN permission")
            vpnPermissionLauncher.launch(permissionIntent)
        }
    }

    private fun requestNotificationPermission() {
        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            openNotificationSettings()
        }
    }

    private fun openNotificationSettings() {
        startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
        )
    }

    private fun refreshPermissionStates() {
        vpnPermissionGranted = VpnService.prepare(this) == null
        notificationPermissionGranted =
            NotificationManagerCompat.from(this).areNotificationsEnabled()
        val overlayGranted = Settings.canDrawOverlays(this)
        val storedQuickControl = preferences.readQuickControlEnabled()
        if (storedQuickControl && !overlayGranted) {
            preferences.writeQuickControlEnabled(false)
            QuickControlService.stop(this)
        }
        quickControlEnabled = storedQuickControl && overlayGranted
    }

    private fun updateQuickControlEnabled(enabled: Boolean) {
        if (!enabled) {
            enableQuickControlAfterPermission = false
            preferences.writeQuickControlEnabled(false)
            quickControlEnabled = false
            QuickControlService.stop(this)
            return
        }
        if (Settings.canDrawOverlays(this)) {
            enableQuickControl()
            return
        }
        enableQuickControlAfterPermission = true
        runCatching {
            overlayPermissionLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
        }.onFailure {
            enableQuickControlAfterPermission = false
            Toast.makeText(
                this,
                viewModel.language.value.tr("无法打开悬浮窗授权页面", "Unable to open the overlay permission page"),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun enableQuickControl() {
        preferences.writeQuickControlEnabled(true)
        quickControlEnabled = true
        QuickControlService.start(this)
    }

    private fun handleQuickControlIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_START_FROM_QUICK_CONTROL, false) != true) return
        intent.removeExtra(EXTRA_START_FROM_QUICK_CONTROL)
        requestVpnPermission(startAfterGrant = true)
    }

    private fun openAppPermissionSettings() {
        appPermissionSettingsLauncher.launch(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName"),
            ),
        )
    }

    private fun startVpn() {
        val state = viewModel.state.value
        val selectedApps = state.apps.filter { it.isSelected }
        VpnServiceController.start(
            context = this,
            scope = state.scope,
            selectedPackages = selectedApps.map { it.packageName },
            targetDomains = state.addressDomains,
            domainsByPackage = selectedApps
                .filter { it.domains.isNotEmpty() }
                .associate { it.packageName to it.domains },
            rule = state.activeRule,
        )
    }

    companion object {
        const val EXTRA_START_FROM_QUICK_CONTROL = "start_from_quick_control"
        const val TAG = "PakomoApp"
    }
}
