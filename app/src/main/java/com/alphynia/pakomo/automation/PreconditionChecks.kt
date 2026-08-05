package com.alphynia.pakomo.automation

import android.content.Context
import android.net.VpnService
import com.alphynia.pakomo.core.model.EngineStage
import com.alphynia.pakomo.core.model.TargetScope
import com.alphynia.pakomo.vpn.VpnServiceController

/**
 * Contract-level assertions the control layer runs before mutating anything. Every
 * check only *asserts* a precondition and reports it — it never tries to *satisfy* it (e.g. it does
 * not launch the VPN consent dialog; that is the environment's job, §8). Each returns a
 * [ControlError] to fail-fast on, or `null` when the precondition holds.
 */
internal object PreconditionChecks {

    /** C: VPN consent must already be granted (env provisioning via `appops`, §8). */
    fun consent(context: Context): ControlError? =
        if (VpnService.prepare(context) != null) ControlError.NEED_VPN_CONSENT else null

    /**
     * C: every targeted package must be installed. Injecting weak-network on an absent app is a
     * silent no-op that would hand automation a false "pass", so it is blocked explicitly. Only
     * meaningful for [TargetScope.APPLICATIONS]; other scopes never carry package targets.
     */
    fun appsInstalled(context: Context, scope: TargetScope, packages: List<String>): Missing? {
        if (scope != TargetScope.APPLICATIONS || packages.isEmpty()) return null
        val pm = context.packageManager
        val missing = packages.filter { pkg ->
            @Suppress("DEPRECATION")
            runCatching { pm.getPackageInfo(pkg, 0) }.isFailure
        }
        return if (missing.isEmpty()) null else Missing(missing)
    }

    /** D: hot-update requires a running tunnel; otherwise the caller meant [ControlCommand.START]. */
    fun engineForwarding(): ControlError? =
        if (VpnServiceController.runtime.value.stage == EngineStage.FORWARDING) {
            null
        } else {
            ControlError.WRONG_STATE
        }

    /** Carries the offending package names so the JSON response can name them for the caller. */
    data class Missing(val packages: List<String>)
}
