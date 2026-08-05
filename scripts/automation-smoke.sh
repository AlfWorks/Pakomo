#!/usr/bin/env bash
#
# Turn-key smoke test for the Pakomo automation control surface (P0–P3).
# Runs against a single installed debug flavor and asserts each command's JSON response. Setup
# foregrounds the app once (am start), so `start` runs from the `stopped` engine state — this is
# NOT a pure background FGS cold start, which modern Android blocks (see docs/automation-control.md
# §冷启动).
#
# Prereqs (environment provisioning, not this script's job):
#   - the debug APK is installed (default com.alphynia.pakomo.kernel)
#   - a device is connected (`adb devices`)
#
# Usage:
#   scripts/automation-smoke.sh [pkg] [profile.json]
#   TEST_TOKEN=1 scripts/automation-smoke.sh          # also exercise the token gate
#
# With no profile.json the P2 step uses an auto-generated global-scope profile (device-independent).
# A supplied profile.json is tested as-is — it must target installed apps or use global scope, else
# the `start via profile` step will (correctly) fail APP_NOT_INSTALLED.
#
# Examples:
#   scripts/automation-smoke.sh                       # kernel flavor, auto profile
#   scripts/automation-smoke.sh com.alphynia.pakomo.hev        # hev flavor
set -uo pipefail

# Git Bash (MSYS) rewrites Unix-looking args such as `/sdcard/...` into Windows paths, which breaks
# `adb push`/`adb shell` device paths. Disable that conversion; harmless on Linux/macOS.
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

PKG="${1:-com.alphynia.pakomo.kernel}"
PROFILE_FILE="${2:-}"
ACTION="com.pakomo.automation.CONTROL"
COMP="$PKG/com.alphynia.pakomo.automation.ControlReceiver"
FILES="/sdcard/Android/data/$PKG/files/pakomo"

pass=0; fail=0
green() { printf '\033[32m%s\033[0m\n' "$*"; }
red() { printf '\033[31m%s\033[0m\n' "$*"; }

# Send a control broadcast (explicit component — implicit broadcasts are dropped on Android 8+),
# echo the response JSON extracted from `am`'s `data="..."`.
send() {
  adb shell am broadcast -a "$ACTION" -n "$COMP" "$@" 2>/dev/null \
    | sed -n 's/^Broadcast completed: result=[0-9-]*, data="\(.*\)"$/\1/p'
}

# assert <label> <json> <grep-pattern>
assert() {
  local label="$1" json="$2" pat="$3"
  if grep -q "$pat" <<<"$json"; then
    green "  PASS  $label"; pass=$((pass + 1))
  else
    red   "  FAIL  $label  — expected /$pat/ in: $json"; fail=$((fail + 1))
  fi
}

# --- cleanup: on ANY exit (incl. Ctrl-C) stop the tunnel, remove local temps, and restore the
# device's original automation token so a real token is never clobbered by the token test.
TMP_LOCAL=()
TOKEN_TEST_RAN=0
HAD_DEVICE_TOKEN=0
DEVICE_TOKEN_BACKUP=""
ORIGINAL_TOKEN_VALUE=""
TEST_TOKEN_INSTALLED=0
TOKEN_RESTORED=0
TEARDOWN_DONE=0
send_current() {
  if [[ "$TEST_TOKEN_INSTALLED" == "1" && "$TOKEN_RESTORED" == "0" ]]; then
    send "$@" --es token smoke-secret
  elif [[ "$HAD_DEVICE_TOKEN" == "1" && -n "$ORIGINAL_TOKEN_VALUE" ]]; then
    send "$@" --es token "$ORIGINAL_TOKEN_VALUE"
  else
    send "$@"
  fi
}
# Restore (or clear) the device token. Only mark success after adb confirms it; otherwise retain the
# raw backup for manual recovery and fail the run.
restore_token() {
  [[ "$TOKEN_TEST_RAN" == "1" && "$TOKEN_RESTORED" == "0" ]] || return 0
  local restored=0
  if [[ "$HAD_DEVICE_TOKEN" == "1" && -n "$DEVICE_TOKEN_BACKUP" ]]; then
    adb push "$DEVICE_TOKEN_BACKUP" "$FILES/automation.token" >/dev/null 2>&1 && restored=1
  else
    adb shell rm -f "$FILES/automation.token" 2>/dev/null && restored=1
  fi
  if [[ "$restored" != "1" ]]; then
    red "  FAIL  unable to restore original automation token; backup retained at $DEVICE_TOKEN_BACKUP"
    fail=$((fail + 1))
    return 1
  fi
  TOKEN_RESTORED=1
}
cleanup() {
  # Stop while the currently installed token is still known, then restore the original token last.
  if [[ "$TEARDOWN_DONE" != "1" ]]; then
    send_current --es cmd stop --es wait false >/dev/null 2>&1 || true
  fi
  restore_token || true
  if [[ "$TOKEN_RESTORED" == "1" && -n "$DEVICE_TOKEN_BACKUP" ]]; then
    rm -f "$DEVICE_TOKEN_BACKUP" 2>/dev/null
  fi
  if [[ ${#TMP_LOCAL[@]} -gt 0 ]]; then rm -f "${TMP_LOCAL[@]}" 2>/dev/null; fi
}
trap cleanup EXIT INT TERM

echo "== automation smoke: $PKG =="
adb shell appops set "$PKG" ACTIVATE_VPN allow 2>/dev/null || true
adb shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow 2>/dev/null || true
# Reliable headless cold start: modern Android (targetSdk 34/36) blocks starting a foreground
# service from the background even with appops exemptions. Bringing the app to the foreground once
# (scripted, no human) gives `start` the foreground privilege it needs. This is the standard way to
# drive VPN apps in automation; it stays headless (no interaction), just not fully background.
adb shell am start -n "$PKG/com.alphynia.pakomo.MainActivity" >/dev/null 2>&1 || true
sleep 1

echo "-- teardown to a known 'stopped' baseline"
send --es cmd stop --es wait true >/dev/null

echo "-- P0: status (stopped)"
assert "status ok + stopped" "$(send --es cmd status)" '"stage":"stopped"'

echo "-- P1: start from stopped (app foregrounded in setup — not a pure background FGS start)"
started="$(send --es cmd start --es rule medium --es wait true)"
assert "start-from-stopped reaches forwarding" "$started" '"stage":"forwarding"'
assert "start-from-stopped applied medium"     "$started" '"appliedRule":"medium"'
assert "start-from-stopped ok"                 "$started" '"ok":true'

echo "-- P1: hot update (running tunnel)"
assert "update -> severe" "$(send --es cmd update --es rule severe --es wait true)" '"appliedRule":"severe"'

echo "-- P1: update precondition (WRONG_STATE only when stopped) — informational"
echo "-- P1: reset -> normal"
assert "reset ok" "$(send --es cmd reset)" '"ok":true'

# P2: use the caller-supplied profile if given (they own targeting installed apps / global scope),
# otherwise auto-generate a device-independent global-scope profile.
echo "-- P2: profile file"
if [[ -n "$PROFILE_FILE" ]]; then
  prof_name="$(basename "${PROFILE_FILE%.json}" | sed 's/\.example$//')"
  prof_src="$PROFILE_FILE"
else
  prof_name="smoke_auto"
  prof_src="./.pakomo-smoke-profile.json"
  TMP_LOCAL+=("$prof_src")
  printf '%s' '{"scope":"global","apps":[],"domainsByApp":{},"domains":[],"rule":{"id":"smoke","name":"smoke","latencyMs":150,"jitterMs":30,"packetLossPercent":2,"downloadKbps":1024,"uploadKbps":512}}' > "$prof_src"
fi
if [[ ! -f "$prof_src" ]]; then
  red "  FAIL  profile source missing: $prof_src"; fail=$((fail + 1))
else
  adb shell mkdir -p "$FILES/profiles" 2>/dev/null || true
  # Delete any stale same-name profile first, then require the push to actually succeed — otherwise
  # load_profile/start could silently pass against a leftover file on the device.
  adb shell rm -f "$FILES/profiles/$prof_name.json" 2>/dev/null || true
  if adb push "$prof_src" "$FILES/profiles/$prof_name.json" >/dev/null; then
    assert "load_profile valid" "$(send --es cmd load_profile --es profile "$prof_name")" '"valid":true'
    assert "start via profile"  "$(send --es cmd start --es profile "$prof_name" --es wait true)" '"ok":true'
  else
    red "  FAIL  adb push failed for $prof_name"; fail=$((fail + 1))
  fi
fi
[[ -n "$PROFILE_FILE" ]] || rm -f "$prof_src"

if [[ "${TEST_TOKEN:-0}" == "1" ]]; then
  echo "-- P3: token gate"
  TOKEN_TEST_RAN=1
  # Pull a raw backup so UTF-8/non-ASCII token bytes are preserved exactly.
  backup_candidate="$(mktemp)"
  if adb pull "$FILES/automation.token" "$backup_candidate" >/dev/null 2>&1; then
    HAD_DEVICE_TOKEN=1
    DEVICE_TOKEN_BACKUP="$backup_candidate"
    ORIGINAL_TOKEN_VALUE="$(tr -d '\r' < "$DEVICE_TOKEN_BACKUP")"
  else
    rm -f "$backup_candidate"
  fi
  # Relative local path: native adb can't resolve MSYS `/tmp/...` once path conversion is disabled.
  tok="./.pakomo-smoke.token"; TMP_LOCAL+=("$tok")
  printf '%s' "smoke-secret" > "$tok"
  if adb push "$tok" "$FILES/automation.token" >/dev/null; then
    TEST_TOKEN_INSTALLED=1
    assert "no token -> BAD_TOKEN" "$(send --es cmd status)" '"error":"BAD_TOKEN"'
    assert "good token -> ok"      "$(send --es cmd status --es token smoke-secret)" '"ok":true'
  else
    red "  FAIL  adb push failed for token"; fail=$((fail + 1))
  fi
  rm -f "$tok"
fi

echo "-- teardown"
send_current --es cmd stop --es wait true >/dev/null
assert "final stopped" "$(send_current --es cmd status)" '"stage":"stopped"'
TEARDOWN_DONE=1
# Restoring the device's original credential is deliberately the final device mutation.
restore_token || true

echo "== $pass passed, $fail failed =="
[[ "$fail" -eq 0 ]]
