package com.pakomo

import android.Manifest
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.pakomo.ui.PakomoApp
import com.pakomo.ui.PakomoViewModel
import com.pakomo.ui.theme.PakomoTheme
import com.pakomo.vpn.VpnServiceController

class MainActivity : ComponentActivity() {
    private val viewModel: PakomoViewModel by viewModels()

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                Log.i(TAG, "VPN permission granted")
                startVpn()
            } else {
                Log.w(TAG, "VPN permission denied")
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "Application started")
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            val serviceRuntime by VpnServiceController.runtime.collectAsState()
            LaunchedEffect(serviceRuntime) {
                viewModel.setEngineRuntime(serviceRuntime)
            }

            PakomoTheme {
                PakomoApp(
                    viewModel = viewModel,
                    onToggleService = {
                        if (!serviceRuntime.stage.isActive) {
                            requestVpnPermission()
                        } else {
                            VpnServiceController.stop(this@MainActivity)
                        }
                    },
                    onEmergencyStop = {
                        VpnServiceController.stop(this@MainActivity)
                    },
                )
            }
        }
    }

    private fun requestVpnPermission() {
        val permissionIntent: Intent? = VpnService.prepare(this)
        if (permissionIntent == null) {
            Log.i(TAG, "VPN permission already granted")
            startVpn()
        } else {
            Log.i(TAG, "Requesting VPN permission")
            vpnPermissionLauncher.launch(permissionIntent)
        }
    }

    private fun startVpn() {
        val state = viewModel.state.value
        VpnServiceController.start(
            context = this,
            scope = state.scope,
            selectedPackages = state.selectedApps.map { it.packageName },
            targetDomains = state.addressDomains,
            domainsByPackage = state.selectedApps
                .filter { it.domains.isNotEmpty() }
                .associate { it.packageName to it.domains },
            rule = state.activeRule,
        )
    }

    private companion object {
        const val TAG = "PakomoApp"
    }
}
