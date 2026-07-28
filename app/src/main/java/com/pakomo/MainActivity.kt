package com.pakomo

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.net.VpnService
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
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
import com.pakomo.ui.PakomoApp
import com.pakomo.ui.PakomoViewModel
import com.pakomo.ui.theme.PakomoTheme
import com.pakomo.vpn.VpnServiceController

class MainActivity : ComponentActivity() {
    private val viewModel: PakomoViewModel by viewModels()
    private var vpnPermissionGranted by mutableStateOf(false)
    private var notificationPermissionGranted by mutableStateOf(false)
    private var appListPermissionGranted by mutableStateOf(false)
    private var startAfterVpnPermission = false

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "Application started")
        enableEdgeToEdge()
        refreshPermissionStates()

        setContent {
            val serviceRuntime by VpnServiceController.runtime.collectAsState()
            LaunchedEffect(serviceRuntime) {
                viewModel.setEngineRuntime(serviceRuntime)
            }

            PakomoTheme {
                PakomoApp(
                    viewModel = viewModel,
                    vpnPermissionGranted = vpnPermissionGranted,
                    notificationPermissionGranted = notificationPermissionGranted,
                    appListPermissionGranted = appListPermissionGranted,
                    onToggleService = {
                        if (!serviceRuntime.stage.isActive) {
                            requestVpnPermission(startAfterGrant = true)
                        } else {
                            VpnServiceController.stop(this@MainActivity)
                        }
                    },
                    onEmergencyStop = {
                        VpnServiceController.stop(this@MainActivity)
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
                        appPermissionSettingsLauncher.launch(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:$packageName"),
                            ),
                        )
                    },
                )
            }
        }
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
        appListPermissionGranted =
            Build.VERSION.SDK_INT < 30 ||
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.QUERY_ALL_PACKAGES,
                ) == PackageManager.PERMISSION_GRANTED
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

    private companion object {
        const val TAG = "PakomoApp"
    }
}
