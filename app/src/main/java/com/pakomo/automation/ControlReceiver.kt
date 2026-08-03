package com.pakomo.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.pakomo.core.model.EngineStage
import com.pakomo.data.PakomoPreferences
import com.pakomo.vpn.VpnServiceController
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Exported entry point for external automation. It ships in debug and release builds; release
 * commands are denied until device provisioning installs a token. The receiver parses the wire protocol, runs the precondition asserts
 * ([PreconditionChecks], §14), and delegates every mutation to
 * [VpnServiceController][com.pakomo.vpn.VpnServiceController] — no engine or policy logic lives here.
 *
 * Command → rule resolution:
 * - `profile=<name>`: load `profiles/<name>.json` (hermetic, fully specifies scope/apps/rule).
 * - `rule=<name>`: keep the app's persisted scope/apps, override just the rule by preset or
 *   saved-rule name.
 * - neither: use the app's persisted active rule.
 */
class ControlReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
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

        // Release requires a provisioned token; debug enforces it whenever one is configured.
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
                awaitOutcome(cmd, waitMs) { it == EngineStage.FORWARDING } ?: StatusReporter.success(cmd)
            }

            // State-mutating command handling is serialized. A waited command keeps the lock until
            // its config is confirmed; a fire-and-forget command may be superseded after it returns.
            // Concurrent UI-driven reconfiguration remains out of scope for a headless test run.
            ControlCommand.START -> synchronized(MUTATION_LOCK) { start(context, intent, prefs, hotSwap = false) }
            ControlCommand.UPDATE -> synchronized(MUTATION_LOCK) { start(context, intent, prefs, hotSwap = true) }

            ControlCommand.STOP -> synchronized(MUTATION_LOCK) {
                VpnServiceController.stop(context)
                val waitMs = waitMillis(intent, DEFAULT_WAIT_MS)
                awaitOutcome(cmd, waitMs) { !it.isActive } ?: StatusReporter.success(cmd)
            }

            ControlCommand.RESET -> synchronized(MUTATION_LOCK) {
                if (VpnServiceController.runtime.value.stage != EngineStage.FORWARDING) {
                    StatusReporter.success(cmd) { put("note", "engine not running; nothing to reset") }
                } else {
                    val configId = applyRunning(
                        context, request(context, prefs, ruleOverride = ProfileCodec.normalRule()), hotSwap = true,
                    )
                    // Reset waits by default so the response only claims success once normal is applied.
                    val waitMs = waitMillis(intent, DEFAULT_WAIT_MS)
                    awaitConfigApplied(cmd, waitMs, configId)
                        ?: StatusReporter.success(cmd) { put("appliedRule", "normal"); put("confirmed", waitMs > 0) }
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

        val configId = applyRunning(context, resolved, hotSwap)

        // Honour an explicit wait for UPDATE too; cold START defaults to waiting. Waiting on the
        // applied config id (not merely FORWARDING) confirms the background start/reconfigure really
        // took effect — for an update the stage stays FORWARDING throughout, so stage proves nothing.
        val waitMs = waitMillis(intent, if (hotSwap) 0L else DEFAULT_WAIT_MS)
        awaitConfigApplied(cmd, waitMs, configId)?.let { return it }
        // confirmed=false means "accepted but not waited for" — appliedRule is the requested rule,
        // not a guarantee it has taken effect. With a wait, reaching here means it was confirmed.
        return StatusReporter.success(cmd) {
            put("appliedRule", resolved.rule.id)
            put("scope", resolved.scope.name.lowercase())
            put("confirmed", waitMs > 0)
        }
    }

    /** Applies the request and returns the config id to confirm against [awaitConfigApplied]. */
    private fun applyRunning(context: Context, req: ControlRequest, hotSwap: Boolean): Long =
        if (hotSwap) {
            VpnServiceController.update(
                context, req.scope, req.packages, req.addressDomains, req.domainsByPackage, req.rule,
            )
        } else {
            VpnServiceController.start(
                context, req.scope, req.packages, req.addressDomains, req.domainsByPackage, req.rule,
            )
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

    /**
     * Blocks up to [waitMs] for [target]. Returns null when no wait was requested (waitMs <= 0) or
     * the condition was met (caller then builds the success payload); otherwise a typed failure:
     * TIMEOUT if the deadline passed, ENGINE_ERROR if the engine reached ERROR first.
     */
    private fun awaitOutcome(
        cmd: ControlCommand,
        waitMs: Long,
        target: (EngineStage) -> Boolean,
    ): JSONObject? {
        if (waitMs <= 0) return null
        val stage = waitForStage(waitMs, target)
        return when {
            stage == null ->
                StatusReporter.failure(cmd, ControlError.TIMEOUT, "condition not met within ${waitMs}ms")
            target(stage) -> null
            stage == EngineStage.ERROR -> StatusReporter.failure(
                cmd,
                ControlError.ENGINE_ERROR,
                VpnServiceController.runtime.value.message ?: "engine reported error",
            )
            else -> StatusReporter.failure(cmd, ControlError.TIMEOUT, "condition not met within ${waitMs}ms")
        }
    }

    /**
     * Like [awaitOutcome] but confirms the specific [configId] was applied — the service bumps
     * [VpnServiceController.appliedConfigId] only after the pipeline/reconfigure actually finishes.
     * For an update the stage stays FORWARDING throughout, so waiting on stage alone would return
     * prematurely (or miss a later failure). Returns null on success / no wait; else a typed failure.
     */
    private fun awaitConfigApplied(cmd: ControlCommand, waitMs: Long, configId: Long): JSONObject? {
        if (waitMs <= 0) return null
        val deadline = SystemClock.elapsedRealtime() + waitMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val stage = VpnServiceController.runtime.value.stage
            if (stage == EngineStage.ERROR) {
                return StatusReporter.failure(
                    cmd,
                    ControlError.ENGINE_ERROR,
                    VpnServiceController.runtime.value.message ?: "engine reported error",
                )
            }
            // A hot reconfigure failure reports its config id here (the tunnel stays FORWARDING with
            // the previous config, so there is no ERROR stage to observe). Under the mutation lock a
            // failedConfigId >= ours means our config specifically failed.
            if (VpnServiceController.failedConfigId.value >= configId) {
                return StatusReporter.failure(cmd, ControlError.ENGINE_ERROR, "config $configId failed to apply")
            }
            if (VpnServiceController.appliedConfigId.value >= configId && stage == EngineStage.FORWARDING) {
                return null
            }
            Thread.sleep(POLL_MS)
        }
        return StatusReporter.failure(cmd, ControlError.TIMEOUT, "config $configId not applied within ${waitMs}ms")
    }

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

        /** Serializes state-mutating commands across concurrent broadcasts (each runs on its own
         *  goAsync thread). Shared across receiver instances since a new one is created per broadcast. */
        val MUTATION_LOCK = Any()
    }
}
