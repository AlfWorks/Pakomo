package com.alphynia.pakomo.automation

import android.content.Context
import java.io.File

/**
 * Shared-secret gating for the control surface. Release builds require a non-empty token;
 * debug builds keep token provisioning optional for local diagnostics.
 *
 * A configured token is always enforced. In release builds a missing/empty token denies every
 * command, so installing the production APK never exposes an unauthenticated control surface.
 * The test environment writes the secret to the app-specific `pakomo/automation.token` file
 * during device provisioning and includes the same value in each protocol request.
 *
 * Note: [ControlError.UNAUTHORIZED_CALLER] is intentionally *not* implemented via calling-uid —
 * a manifest BroadcastReceiver cannot reliably read the sender's uid in onReceive (it is not a
 * Binder transaction), so a uid check would be security theatre. The token is the real boundary.
 */
internal object AutomationConfig {

    private const val TOKEN_FILE = "pakomo/automation.token"

    /** The configured token, or null when the token file is absent/empty. */
    private fun storedToken(context: Context): String? {
        val file = File(context.getExternalFilesDir(null), TOKEN_FILE)
        if (!file.isFile) return null
        return runCatching { file.readText(Charsets.UTF_8).trim() }.getOrNull()?.ifBlank { null }
    }

    /** Release requires a configured matching token; debug only checks it when one is configured. */
    fun verifyToken(context: Context, provided: String?): ControlError? {
        val expected = storedToken(context)
        if (expected == null) return if (com.alphynia.pakomo.BuildConfig.DEBUG) null else ControlError.BAD_TOKEN
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
