package com.alphynia.pakomo.automation

/**
 * Stable wire contract shared by Pakomo and external automation clients. It is intentionally
 * string-based so host-side clients can use Android's explicit ordered-broadcast transport without
 * linking against the app.
 */
internal object AutomationContract {
    /** Incremented when the wire contract makes an incompatible change. */
    const val PROTOCOL_VERSION = 1

    /** Broadcast action the [ControlReceiver] listens on. */
    const val ACTION_CONTROL = "com.pakomo.automation.CONTROL"

    /** logcat tag for the machine-readable status line (`adb logcat -s PAKOMO_AUTO:I`). */
    const val LOG_TAG = "PAKOMO_AUTO"

    /** Status file, written under getExternalFilesDir()/pakomo/ for `adb pull` / `adb shell cat`. */
    const val STATUS_DIR = "pakomo"
    const val STATUS_FILE = "status.json"

    // ---- String-valued Intent extras used by host clients ----
    const val EXTRA_CMD = "cmd"
    const val EXTRA_TOKEN = "token"
    const val EXTRA_PROFILE = "profile"
    const val EXTRA_RULE = "rule"
    const val EXTRA_SCOPE = "scope"
    const val EXTRA_APPS = "apps"
    const val EXTRA_DOMAINS = "domains"
    const val EXTRA_WAIT = "wait"

    /** Broadcast result codes: mirror the JSON `ok` field for callers that only read the code. */
    const val RESULT_OK = 0
    const val RESULT_ERROR = 1
}

/** Commands implemented by the external control surface. */
internal enum class ControlCommand(val wire: String) {
    STATUS("status"),
    START("start"),
    UPDATE("update"),
    STOP("stop"),
    LOAD_PROFILE("load_profile"),
    RESET("reset");

    companion object {
        fun fromWire(value: String?): ControlCommand? =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) }
    }
}

/** Stable error codes returned in the JSON `error` field and machine-readable status channels. */
internal enum class ControlError {
    UNAUTHORIZED_CALLER,
    BAD_TOKEN,
    INVALID_ARGS,
    PROFILE_NOT_FOUND,
    PROFILE_INVALID,
    NEED_VPN_CONSENT,
    APP_NOT_INSTALLED,
    WRONG_STATE,
    TIMEOUT,
    ENGINE_ERROR,
}
