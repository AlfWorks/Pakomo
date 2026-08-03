package com.pakomo.automation

/**
 * Wire contract shared by every automation-control component. Debug-only: these sources live in
 * src/debug and are never compiled into a release APK (see app/build.gradle.kts and the design
 * doc's §9 security note). The contract is intentionally string-based so external drivers can speak
 * it over `adb shell am broadcast` with no client library.
 */
internal object AutomationContract {
    /** Broadcast action the [ControlReceiver] listens on. */
    const val ACTION_CONTROL = "com.pakomo.automation.CONTROL"

    /** logcat tag for the machine-readable status line (`adb logcat -s PAKOMO_AUTO:I`). */
    const val LOG_TAG = "PAKOMO_AUTO"

    /** Status file, written under getExternalFilesDir()/pakomo/ for `adb pull` / `adb shell cat`. */
    const val STATUS_DIR = "pakomo"
    const val STATUS_FILE = "status.json"

    // ---- Intent extras (all string-keyed for `am broadcast --es`) ----
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

/** Supported commands. P0 implements [STATUS]; the rest are scaffolded for P1/P2. */
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

/**
 * Stable error codes returned in the JSON `error` field and logged for CI to branch on. The full
 * set is defined up front (design §14 precondition checklist) even though P0 only emits a few, so
 * the vocabulary never churns as later phases wire in the remaining assertions.
 */
internal enum class ControlError {
    AUTOMATION_DISABLED,
    UNAUTHORIZED_CALLER,
    BAD_TOKEN,
    INVALID_ARGS,
    PROFILE_NOT_FOUND,
    PROFILE_INVALID,
    NEED_VPN_CONSENT,
    APP_NOT_INSTALLED,
    FGS_START_BLOCKED,
    WRONG_STATE,
    TIMEOUT,
    ENGINE_ERROR,
    NOT_IMPLEMENTED,
}
