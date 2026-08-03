package com.pakomo.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.pakomo.BuildConfig
import com.pakomo.core.model.EngineStage
import com.pakomo.data.PakomoPreferences
import com.pakomo.vpn.VpnServiceController
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Exported entry point for external automation (design §5). Registered only by the debug manifest,
 * so it is absent from release APKs; it also asserts [BuildConfig.AUTOMATION_ENABLED] as
 * defence-in-depth. The receiver parses the wire protocol, runs the precondition asserts
 * ([PreconditionChecks], §14), and delegates every mutation to
 * [VpnServiceController][com.pakomo.vpn.VpnServiceController] — no engine or policy logic lives here.
 *
 * Examples:
 * ```
 * adb shell am broadcast -a com.pakomo.automation.CONTROL --es cmd status
 * adb shell am broadcast -a com.pakomo.automation.CONTROL --es cmd start --es rule medium
 * adb shell am broadcast -a com.pakomo.automation.CONTROL --es cmd start --es profile checkout_flow
 * adb shell am broadcast -a com.pakomo.automation.CONTROL --es cmd stop
 * ```
 *
 * Command → rule resolution:
 * - `--es profile <name>`: load `profiles/<name>.json` (hermetic, fully specifies scope/apps/rule).
 * - `--es rule <name>`: keep the app's persisted scope/apps, override just the rule by preset or
 *   saved-rule name.
 * - neither: use the app's persisted active rule.
 */
class ControlReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // §14.A defence-in-depth: refuse if the automation flag is off (never true in release,
        // which does not even contain this class).
        if (!BuildConfig.AUTOMATION_ENABLED) {
            emitSync(context, StatusReporter.failure(null, ControlError.AUTOMATION_DISABLED))
            return
        }

        val cmd = ControlCommand.fromWire(intent.getStringExtra(AutomationContract.EXTRA_CMD))
        if (cmd == null) {
            emitSync(
                context,
                StatusReporter.failure(
                    null,
                    ControlError.INVALID_ARGS,
                    "unknown or missing cmd; expected one of " +
                        ControlCommand.entries.joinToString(",") { it.wire },
                ),
            )
            return
        }

        // §14.A token gate (opt-in; enforced only when a token file is configured).
        AutomationConfig.verifyToken(context, intent.getStringExtra(AutomationContract.EXTRA_TOKEN))
            ?.let { emitSync(context, StatusReporter.failure(cmd, it, "token required or mismatched")); return }

        // Commands may block (start/stop wait for a stage transition), so hop off the main thread.
        val ordered = isOrderedBroadcast
        val pending = goAsync()
        val appContext = context.applicationContext
        thread(name = "pakomo-automation") {
            val payload = runCatching { handle(appContext, intent, cmd) }
                .getOrElse { StatusReporter.failure(cmd, ControlError.ENGINE_ERROR, it.message) }
            emitAsync(appContext, pending, ordered, payload)
        }
    }

    private fun handle(context: Context, intent: Intent, cmd: ControlCommand): JSONObject {
        val prefs = PakomoPreferences(context)
        return when (cmd) {
            ControlCommand.STATUS -> {
                val waitMs = waitMillis(intent, default = 0L)
                if (waitMs > 0) waitForStage(waitMs) { it == EngineStage.FORWARDING }
                StatusReporter.success(cmd)
            }

            ControlCommand.START -> start(context, intent, prefs, hotSwap = false)
            ControlCommand.UPDATE -> start(context, intent, prefs, hotSwap = true)

            ControlCommand.STOP -> {
                VpnServiceController.stop(context)
                if (waitMillis(intent, DEFAULT_WAIT_MS) > 0) {
                    waitForStage(DEFAULT_WAIT_MS) { !it.isActive }
                }
                StatusReporter.success(cmd)
            }

            ControlCommand.RESET -> {
                if (VpnServiceController.runtime.value.stage == EngineStage.FORWARDING) {
                    applyRunning(context, request(context, prefs, ruleOverride = ProfileCodec.normalRule()), hotSwap = true)
                    StatusReporter.success(cmd) { put("appliedRule", "normal") }
                } else {
                    StatusReporter.success(cmd) { put("note", "engine not running; nothing to reset") }
                }
            }

            ControlCommand.LOAD_PROFILE -> {
                val name = intent.getStringExtra(AutomationContract.EXTRA_PROFILE)
                    ?: return StatusReporter.failure(cmd, ControlError.INVALID_ARGS, "profile name required")
                when (val result = ProfileCodec.loadProfile(context, prefs, name)) {
                    is ProfileResult.Err -> StatusReporter.failure(cmd, result.error, result.message)
                    is ProfileResult.Ok -> StatusReporter.success(cmd) {
                        put("valid", true)
                        put("appliedRule", result.request.rule.id)
                        put("scope", result.request.scope.name.lowercase())
                    }
                }
            }
        }
    }

    /** Shared body for START (cold, re-establishes tunnel) and UPDATE (hot-swap on running tunnel). */
    private fun start(context: Context, intent: Intent, prefs: PakomoPreferences, hotSwap: Boolean): JSONObject {
        val cmd = if (hotSwap) ControlCommand.UPDATE else ControlCommand.START

        if (hotSwap) {
            PreconditionChecks.engineForwarding()?.let {
                return StatusReporter.failure(cmd, it, "update requires a running tunnel; use start")
            }
        } else {
            PreconditionChecks.consent(context)?.let {
                return StatusReporter.failure(cmd, it, "grant VPN consent first (appops ACTIVATE_VPN, §8)")
            }
        }

        val resolved = when (val r = resolveRequest(context, prefs, intent)) {
            is ProfileResult.Err -> return StatusReporter.failure(cmd, r.error, r.message)
            is ProfileResult.Ok -> r.request
        }

        PreconditionChecks.appsInstalled(context, resolved.scope, resolved.packages)?.let { missing ->
            return StatusReporter.failure(cmd, ControlError.APP_NOT_INSTALLED, "not installed: ${missing.packages}")
        }

        applyRunning(context, resolved, hotSwap)

        val waitMs = waitMillis(intent, if (hotSwap) 0L else DEFAULT_WAIT_MS)
        if (!hotSwap && waitMs > 0) {
            when (val stage = waitForStage(waitMs) { it == EngineStage.FORWARDING }) {
                EngineStage.FORWARDING -> Unit
                EngineStage.ERROR -> return StatusReporter.failure(
                    cmd, ControlError.ENGINE_ERROR,
                    VpnServiceController.runtime.value.message ?: "engine reported error",
                )
                else -> return StatusReporter.failure(
                    cmd,
                    if (stage == null) ControlError.TIMEOUT else ControlError.ENGINE_ERROR,
                    "did not reach forwarding within ${waitMs}ms",
                )
            }
        }
        return StatusReporter.success(cmd) {
            put("appliedRule", resolved.rule.id)
            put("scope", resolved.scope.name.lowercase())
        }
    }

    private fun applyRunning(context: Context, req: ControlRequest, hotSwap: Boolean) {
        if (hotSwap) {
            VpnServiceController.update(
                context, req.scope, req.packages, req.addressDomains, req.domainsByPackage, req.rule,
            )
        } else {
            VpnServiceController.start(
                context, req.scope, req.packages, req.addressDomains, req.domainsByPackage, req.rule,
            )
        }
    }

    private fun resolveRequest(context: Context, prefs: PakomoPreferences, intent: Intent): ProfileResult {
        val profile = intent.getStringExtra(AutomationContract.EXTRA_PROFILE)
        val ruleRef = intent.getStringExtra(AutomationContract.EXTRA_RULE)
        return if (profile != null) {
            ProfileCodec.loadProfile(context, prefs, profile)
        } else {
            ProfileCodec.fromPreferences(prefs, ruleRef)
        }
    }

    /** Preferences-based request with a forced rule (used by RESET). */
    private fun request(context: Context, prefs: PakomoPreferences, ruleOverride: com.pakomo.core.model.NetworkRule): ControlRequest {
        val packages = prefs.readSelectedPackages().toList()
        return ControlRequest(
            scope = prefs.readScope(),
            packages = packages,
            domainsByPackage = prefs.readDomainsByPackage().filterKeys(packages::contains),
            addressDomains = prefs.readAddressDomains(),
            rule = ruleOverride,
        )
    }

    // ---- helpers ----

    private fun waitMillis(intent: Intent, default: Long): Long {
        val raw = intent.getStringExtra(AutomationContract.EXTRA_WAIT) ?: return default
        return when {
            raw.equals("false", ignoreCase = true) || raw == "0" -> 0L
            raw.equals("true", ignoreCase = true) -> DEFAULT_WAIT_MS
            else -> raw.toLongOrNull()?.coerceAtLeast(0L) ?: default
        }
    }

    /** Polls the engine stage until [target] holds, an ERROR appears, or the timeout elapses. */
    private fun waitForStage(timeoutMs: Long, target: (EngineStage) -> Boolean): EngineStage? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val stage = VpnServiceController.runtime.value.stage
            if (target(stage)) return stage
            if (stage == EngineStage.ERROR) return stage
            Thread.sleep(POLL_MS)
        }
        return null
    }

    private fun emitSync(context: Context, payload: JSONObject) {
        val json = StatusReporter.persist(context, payload)
        if (isOrderedBroadcast) {
            setResultCode(resultCode(payload))
            setResultData(json)
        }
    }

    private fun emitAsync(context: Context, pending: PendingResult, ordered: Boolean, payload: JSONObject) {
        val json = StatusReporter.persist(context, payload)
        if (ordered) {
            pending.setResultCode(resultCode(payload))
            pending.setResultData(json)
        }
        pending.finish()
    }

    private fun resultCode(payload: JSONObject): Int =
        if (payload.optBoolean("ok", false)) AutomationContract.RESULT_OK else AutomationContract.RESULT_ERROR

    private companion object {
        const val DEFAULT_WAIT_MS = 10_000L
        const val POLL_MS = 100L
    }
}
