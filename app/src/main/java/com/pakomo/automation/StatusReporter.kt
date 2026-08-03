package com.pakomo.automation

import android.content.Context
import android.util.Log
import com.pakomo.BuildConfig
import com.pakomo.core.model.EngineRuntime
import com.pakomo.vpn.VpnServiceController
import org.json.JSONObject
import java.io.File

/**
 * Builds the machine-readable response and fans it out over all three read-back channels
 * through the ordered-broadcast result data, a logcat line, and a status file. The caller
 * ([ControlReceiver]) owns the broadcast result (setResultData must run inside onReceive); this
 * helper produces the JSON and handles the log + file channels.
 */
internal object StatusReporter {

    /** The current engine flavor, surfaced so dual-flavor 对拍 runs can tell kernel from hev. */
    private val flavor: String get() = if (BuildConfig.USE_KOTLIN_KERNEL) "kernel" else "hev"

    /** Success payload for [cmd], snapshotting the live [VpnServiceController.runtime]. */
    fun success(cmd: ControlCommand, extra: JSONObject.() -> Unit = {}): JSONObject =
        base(cmd).apply {
            put("ok", true)
            put("error", JSONObject.NULL)
            putRuntime(VpnServiceController.runtime.value)
            extra()
        }

    /** Error payload carrying a stable [ControlError] code and a human-readable [message]. */
    fun failure(cmd: ControlCommand?, error: ControlError, message: String? = null): JSONObject =
        base(cmd).apply {
            put("ok", false)
            put("error", error.name)
            if (message != null) put("message", message)
            putRuntime(VpnServiceController.runtime.value)
        }

    private fun base(cmd: ControlCommand?): JSONObject = JSONObject().apply {
        put("protocolVersion", AutomationContract.PROTOCOL_VERSION)
        put("cmd", cmd?.wire ?: JSONObject.NULL)
        put("flavor", flavor)
        put("ts", System.currentTimeMillis())
    }

    private fun JSONObject.putRuntime(runtime: EngineRuntime) {
        put("stage", runtime.stage.name.lowercase())
        put("active", runtime.stage.isActive)
        runtime.message?.let { put("engineMessage", it) }
        val s = runtime.stats
        put(
            "stats",
            JSONObject().apply {
                put("upBps", s.uploadBytesPerSecond)
                put("downBps", s.downloadBytesPerSecond)
                put("activeFlows", s.activeConnections)
                put("dropped", s.droppedTransfers)
                put("delayed", s.delayedTransfers)
                put("uptimeMs", s.uptimeMs)
            },
        )
    }

    /**
     * Emits [payload] to the log and status-file channels. The broadcast-result channel is set by
     * the receiver itself. Returns the compact JSON string so the caller can reuse it for the
     * broadcast result data.
     */
    fun persist(context: Context, payload: JSONObject): String {
        val json = payload.toString()
        Log.i(AutomationContract.LOG_TAG, json)
        runCatching {
            val dir = File(context.getExternalFilesDir(null), AutomationContract.STATUS_DIR)
            dir.mkdirs()
            File(dir, AutomationContract.STATUS_FILE).writeText(json, Charsets.UTF_8)
        }.onFailure { Log.w(AutomationContract.LOG_TAG, "status file write failed: ${it.message}") }
        return json
    }
}
