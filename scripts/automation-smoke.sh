#!/usr/bin/env bash
#
# Turn-key smoke test for the Pakomo automation control surface (P0–P3).
# Runs against a single installed debug flavor and asserts each command's JSON response, INCLUDING
# a true cold start from `stopped` — the FGS-start-from-background path that the ad-hoc manual run
# did not exercise (the engine was already forwarding). See docs/automation-control.md.
#
# Prereqs (environment provisioning, not this script's job):
#   - the debug APK is installed (default com.pakomo.kernel)
#   - a device is connected (`adb devices`)
#
# Usage:
#   scripts/automation-smoke.sh [pkg] [profile.json]
#   TEST_TOKEN=1 scripts/automation-smoke.sh          # also exercise the token gate
#
# Examples:
#   scripts/automation-smoke.sh                                   # kernel flavor, no profile
#   scripts/automation-smoke.sh com.pakomo.hev                    # hev flavor
#   scripts/automation-smoke.sh com.pakomo.kernel docs/automation/profiles/checkout_flow.example.json
set -uo pipefail

# Git Bash (MSYS) rewrites Unix-looking args such as `/sdcard/...` into Windows paths, which breaks
# `adb push`/`adb shell` device paths. Disable that conversion; harmless on Linux/macOS.
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

PKG="${1:-com.pakomo.kernel}"
PROFILE_FILE="${2:-}"
ACTION="com.pakomo.automation.CONTROL"
COMP="$PKG/com.pakomo.automation.ControlReceiver"
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

echo "== automation smoke: $PKG =="
adb shell appops set "$PKG" ACTIVATE_VPN allow 2>/dev/null || true

echo "-- teardown to a known 'stopped' baseline"
send --es cmd stop --es wait true >/dev/null

echo "-- P0: status (stopped)"
assert "status ok + stopped" "$(send --es cmd status)" '"stage":"stopped"'

echo "-- P1: COLD start from stopped (FGS-from-background path)"
cold="$(send --es cmd start --es rule medium --es wait true)"
assert "cold start reaches forwarding" "$cold" '"stage":"forwarding"'
assert "cold start applied medium"     "$cold" '"appliedRule":"medium"'
assert "cold start ok"                 "$cold" '"ok":true'

echo "-- P1: hot update (running tunnel)"
assert "update -> severe" "$(send --es cmd update --es rule severe --es wait true)" '"appliedRule":"severe"'

echo "-- P1: update precondition (WRONG_STATE only when stopped) — informational"
echo "-- P1: reset -> normal"
assert "reset ok" "$(send --es cmd reset)" '"ok":true'

# Auto-generate a global-scope profile so P2 is device-independent (an app-targeting profile like
# the doc example would — correctly — fail APP_NOT_INSTALLED unless that exact app is installed).
echo "-- P2: profile file (auto-generated, global scope)"
prof="./.pakomo-smoke-profile.json"
printf '%s' '{"scope":"global","apps":[],"domainsByApp":{},"domains":[],"rule":{"id":"smoke","name":"smoke","latencyMs":150,"jitterMs":30,"packetLossPercent":2,"downloadKbps":1024,"uploadKbps":512}}' > "$prof"
adb shell mkdir -p "$FILES/profiles" 2>/dev/null || true
adb push "$prof" "$FILES/profiles/smoke_auto.json" >/dev/null
rm -f "$prof"
assert "load_profile valid" "$(send --es cmd load_profile --es profile smoke_auto)" '"valid":true'
assert "start via profile"  "$(send --es cmd start --es profile smoke_auto --es wait true)" '"ok":true'

if [[ "${TEST_TOKEN:-0}" == "1" ]]; then
  echo "-- P3: token gate"
  # Relative local path: native adb can't resolve MSYS `/tmp/...` once path conversion is disabled.
  tok="./.pakomo-smoke.token"
  printf '%s' "smoke-secret" > "$tok"
  adb push "$tok" "$FILES/automation.token" >/dev/null
  rm -f "$tok"
  assert "no token -> BAD_TOKEN"   "$(send --es cmd status)" '"error":"BAD_TOKEN"'
  assert "good token -> ok"        "$(send --es cmd status --es token smoke-secret)" '"ok":true'
  adb shell rm "$FILES/automation.token" 2>/dev/null || true  # restore open access
fi

echo "-- teardown"
send --es cmd stop --es wait true >/dev/null
assert "final stopped" "$(send --es cmd status)" '"stage":"stopped"'

echo "== $pass passed, $fail failed =="
[[ "$fail" -eq 0 ]]
