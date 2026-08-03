<#
.SYNOPSIS
  Turn-key smoke test for the Pakomo automation control surface (P0-P3), PowerShell port of
  automation-smoke.sh for native Windows runs. Asserts every command's JSON response against one
  installed debug flavor, including a real cold start from `stopped` (the FGS-from-background path).

.EXAMPLE
  .\scripts\automation-smoke.ps1
  .\scripts\automation-smoke.ps1 -Pkg com.pakomo.hev
  .\scripts\automation-smoke.ps1 -Pkg com.pakomo.kernel -ProfileFile docs\automation\profiles\checkout_flow.example.json
  $env:TEST_TOKEN=1; .\scripts\automation-smoke.ps1        # also exercise the token gate

.NOTES
  Prereqs (environment provisioning): debug APK installed, device connected (`adb devices`).
#>
param(
  [string]$Pkg = "com.pakomo.kernel",
  [string]$ProfileFile = ""
)

$Action = "com.pakomo.automation.CONTROL"
$Comp   = "$Pkg/com.pakomo.automation.ControlReceiver"
$Files  = "/sdcard/Android/data/$Pkg/files/pakomo"
$script:pass = 0
$script:fail = 0

# Send a control broadcast (explicit component — implicit broadcasts are dropped on Android 8+) and
# return the response JSON extracted from `am`'s `data="..."`.
function Send-Control {
  param([string[]]$ExtraArgs)
  $out = (& adb shell am broadcast -a $Action -n $Comp @ExtraArgs 2>$null) -join "`n"
  if ($out -match 'data="(.*)"') { return $Matches[1] }
  return ""
}

function Assert {
  param([string]$Label, [string]$Json, [string]$Pattern)
  if ($Json -match $Pattern) {
    Write-Host "  PASS  $Label" -ForegroundColor Green
    $script:pass++
  } else {
    Write-Host "  FAIL  $Label  — expected /$Pattern/ in: $Json" -ForegroundColor Red
    $script:fail++
  }
}

Write-Host "== automation smoke: $Pkg =="
& adb shell appops set $Pkg ACTIVATE_VPN allow 2>$null | Out-Null
# Headless cold start: SYSTEM_ALERT_WINDOW exempts the app from the Android 12+ background
# foreground-service-start restriction, so `start` works even when the app was never opened.
& adb shell appops set $Pkg SYSTEM_ALERT_WINDOW allow 2>$null | Out-Null

Write-Host "-- teardown to a known 'stopped' baseline"
Send-Control @("--es","cmd","stop","--es","wait","true") | Out-Null

Write-Host "-- P0: status (stopped)"
Assert "status ok + stopped" (Send-Control @("--es","cmd","status")) '"stage":"stopped"'

Write-Host "-- P1: COLD start from stopped (FGS-from-background path)"
$cold = Send-Control @("--es","cmd","start","--es","rule","medium","--es","wait","true")
Assert "cold start reaches forwarding" $cold '"stage":"forwarding"'
Assert "cold start applied medium"     $cold '"appliedRule":"medium"'
Assert "cold start ok"                 $cold '"ok":true'

Write-Host "-- P1: hot update (running tunnel)"
Assert "update -> severe" (Send-Control @("--es","cmd","update","--es","rule","severe","--es","wait","true")) '"appliedRule":"severe"'

Write-Host "-- P1: reset -> normal"
Assert "reset ok" (Send-Control @("--es","cmd","reset")) '"ok":true'

# Auto-generate a global-scope profile so P2 is device-independent (an app-targeting profile like
# the doc example would — correctly — fail APP_NOT_INSTALLED unless that exact app is installed).
Write-Host "-- P2: profile file (auto-generated, global scope)"
$prof = Join-Path $env:TEMP "pakomo-smoke-profile.json"
'{"scope":"global","apps":[],"domainsByApp":{},"domains":[],"rule":{"id":"smoke","name":"smoke","latencyMs":150,"jitterMs":30,"packetLossPercent":2,"downloadKbps":1024,"uploadKbps":512}}' | Set-Content -Path $prof -NoNewline -Encoding ascii
& adb shell mkdir -p "$Files/profiles" 2>$null | Out-Null
& adb push $prof "$Files/profiles/smoke_auto.json" | Out-Null
Assert "load_profile valid" (Send-Control @("--es","cmd","load_profile","--es","profile","smoke_auto")) '"valid":true'
Assert "start via profile"  (Send-Control @("--es","cmd","start","--es","profile","smoke_auto","--es","wait","true")) '"ok":true'

if ($env:TEST_TOKEN -eq "1") {
  Write-Host "-- P3: token gate"
  $tokenFile = Join-Path $env:TEMP "pakomo.token"
  Set-Content -Path $tokenFile -Value "smoke-secret" -NoNewline -Encoding ascii
  & adb push $tokenFile "$Files/automation.token" | Out-Null
  Assert "no token -> BAD_TOKEN" (Send-Control @("--es","cmd","status")) '"error":"BAD_TOKEN"'
  Assert "good token -> ok"      (Send-Control @("--es","cmd","status","--es","token","smoke-secret")) '"ok":true'
  & adb shell rm "$Files/automation.token" 2>$null | Out-Null   # restore open access
}

Write-Host "-- teardown"
Send-Control @("--es","cmd","stop","--es","wait","true") | Out-Null
Assert "final stopped" (Send-Control @("--es","cmd","status")) '"stage":"stopped"'

Write-Host "== $($script:pass) passed, $($script:fail) failed =="
if ($script:fail -gt 0) { exit 1 }
