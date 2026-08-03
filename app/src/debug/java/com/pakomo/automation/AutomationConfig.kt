package com.pakomo.automation

import android.content.Context
import java.io.File

/**
 * Optional shared-secret gating for the control surface (design §9, layer 3). The primary security
 * boundary is that this whole package ships only in debug builds; the token is defence-in-depth
 * against another app on the same device forging a broadcast.
 *
 * Enforcement is opt-in: if `getExternalFilesDir()/pakomo/automation.token` exists and is
 * non-empty, every command must present a matching `--es token`. If the file is absent, the token
 * is not enforced (local-dev convenience). CI turns enforcement on by pushing a token file during
 * device setup and reading it back:
 *
 * `adb push token.txt /sdcard/Android/data/<pkg>/files/pakomo/automation.token`
 *
 * Note: [ControlError.UNAUTHORIZED_CALLER] is intentionally *not* implemented via calling-uid —
 * a manifest BroadcastReceiver cannot reliably read the sender's uid in onReceive (it is not a
 * Binder transaction), so a uid check would be security theatre. The debug-only surface + token
 * are the real boundaries.
 */
internal object AutomationConfig {

    private const val TOKEN_FILE = "pakomo/automation.token"

    /** The configured token, or null when enforcement is off (no/empty token file). */
    private fun storedToken(context: Context): String? {
        val file = File(context.getExternalFilesDir(null), TOKEN_FILE)
        if (!file.isFile) return null
        return runCatching { file.readText(Charsets.UTF_8).trim() }.getOrNull()?.ifBlank { null }
    }

    /** Returns [ControlError.BAD_TOKEN] when a token is configured and [provided] doesn't match. */
    fun verifyToken(context: Context, provided: String?): ControlError? {
        val expected = storedToken(context) ?: return null
        return if (provided != null && constantTimeEquals(expected, provided)) {
            null
        } else {
            ControlError.BAD_TOKEN
        }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }
}
