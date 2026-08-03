#!/usr/bin/env bash
#
# Dual-flavor 对拍 driver (DRAFT — wire into GitLab CI per your runner setup).
#
# Runs the SAME automation profile against the kernel and hev debug builds, captures each
# flavor's status JSON, then diffs the key fields so the hev flavor guards the kernel flavor as a
# trusted baseline. See docs/automation-control.md.
#
# Prereqs on the test device (environment provisioning, NOT this script's job):
#   - both debug APKs installed (com.pakomo.kernel / com.pakomo.hev)
#   - VPN consent granted per flavor:  adb shell appops set <pkg> ACTIVATE_VPN allow
#
# Usage:
#   scripts/automation-compare.sh <profile-name> [path/to/profile.json] [-- <sut-test-cmd...>]
#
# Example:
#   scripts/automation-compare.sh checkout_flow docs/automation/profiles/checkout_flow.example.json \
#       -- ./run-sut-tests.sh
set -euo pipefail

ACTION="com.pakomo.automation.CONTROL"
FLAVORS=("kernel" "hev")
OUT_DIR="${OUT_DIR:-build/automation-compare}"

PROFILE_NAME="${1:?profile name required}"
PROFILE_FILE="${2:-}"
SUT_CMD=()
if [[ "${3:-}" == "--" ]]; then shift 3; SUT_CMD=("$@"); fi

mkdir -p "$OUT_DIR"

broadcast() { # <pkg> <extras...>
  local pkg="$1"; shift
  adb shell am broadcast -a "$ACTION" -n "$pkg/com.pakomo.automation.ControlReceiver" "$@"
}

run_flavor() {
  local flavor="$1" pkg="com.pakomo.$flavor"
  echo "== $flavor ($pkg) =="

  if [[ -n "$PROFILE_FILE" ]]; then
    adb push "$PROFILE_FILE" \
      "/sdcard/Android/data/$pkg/files/pakomo/profiles/$PROFILE_NAME.json" >/dev/null
  fi

  broadcast "$pkg" --es cmd start --es profile "$PROFILE_NAME" --es wait true

  # Hook: run the system-under-test's own automation while the fault is active.
  if [[ ${#SUT_CMD[@]} -gt 0 ]]; then "${SUT_CMD[@]}" || true; fi

  # Capture the machine-readable status, then restore.
  adb shell cat "/sdcard/Android/data/$pkg/files/pakomo/status.json" > "$OUT_DIR/$flavor-status.json"
  broadcast "$pkg" --es cmd stop --es wait true
}

for f in "${FLAVORS[@]}"; do run_flavor "$f"; done

echo "== compare (kernel vs hev) =="
# Compare the stable, behaviour-relevant fields; ignore ts/uptime which always differ.
filter='{ok,error,stage,appliedRule,scope,flags:.stats|{activeFlows,dropped,delayed}}'
if command -v jq >/dev/null 2>&1; then
  diff <(jq -S "$filter" "$OUT_DIR/kernel-status.json") \
       <(jq -S "$filter" "$OUT_DIR/hev-status.json") \
    && echo "MATCH: kernel and hev agree on $PROFILE_NAME" \
    || { echo "DIVERGENCE on $PROFILE_NAME (see $OUT_DIR/*.json)"; exit 1; }
else
  echo "jq not found; raw outputs in $OUT_DIR/*.json"
fi
