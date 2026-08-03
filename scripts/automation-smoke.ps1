<#
.SYNOPSIS
  Turn-key smoke test for the Pakomo automation control surface (P0-P3), PowerShell port of
  automation-smoke.sh for native Windows runs. Asserts every command's JSON response against one
  installed debug flavor. Setup foregrounds the app once (am start), so `start` runs from the
  `stopped` state - not a pure background FGS cold start (modern Android blocks that).

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

# Send a control broadcast (explicit component - implicit broadcasts are dropped on Android 8+) and
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
    Write-Host "  FAIL  $Label  - expected /$Pattern/ in: $Json" -ForegroundColor Red
    $script:fail++
  }
}

# Cleanup: on ANY exit (incl. Ctrl-C) stop the tunnel, remove local temps, and restore the device's
# original automation token so a real token is never clobbered by the token test.
$script:tokenTestRan = $false
$script:hadDeviceToken = $false
$script:deviceTokenBackup = ""
$script:originalTokenValue = ""
$script:testTokenInstalled = $false
$script:tokenRestored = $false
$script:teardownDone = $false
$script:tmpLocal = @()
# Send a command with whichever token is currently installed on the device.
function Send-CurrentControl {
  param([string[]]$ExtraArgs)
  if ($script:testTokenInstalled -and -not $script:tokenRestored) {
    return Send-Control ($ExtraArgs + @("--es","token","smoke-secret"))
  }
  if ($script:hadDeviceToken -and $script:originalTokenValue -ne "") {
    return Send-Control ($ExtraArgs + @("--es","token",$script:originalTokenValue))
  }
  return Send-Control $ExtraArgs
}
# Restore (or clear) the device token. Mark success only after adb confirms it; on failure retain the
# raw backup for manual recovery and let the smoke run fail.
function Restore-Token {
  if (-not $script:tokenTestRan -or $script:tokenRestored) { return }
  if ($script:hadDeviceToken -and $script:deviceTokenBackup -and (Test-Path $script:deviceTokenBackup -PathType Leaf)) {
    & adb push $script:deviceTokenBackup "$Files/automation.token" 2>$null | Out-Null
  } else {
    & adb shell rm -f "$Files/automation.token" 2>$null | Out-Null
  }
  if ($LASTEXITCODE -ne 0) {
    Write-Host "  FAIL  unable to restore original automation token; backup retained at $($script:deviceTokenBackup)" -ForegroundColor Red
    $script:fail++
    return
  }
  $script:tokenRestored = $true
}
function Cleanup {
  # Stop while the currently installed token is still known, then restore the original token last.
  if (-not $script:teardownDone) {
    Send-CurrentControl @("--es","cmd","stop","--es","wait","false") | Out-Null
  }
  Restore-Token
  if ($script:tokenRestored -and $script:deviceTokenBackup -and (Test-Path $script:deviceTokenBackup)) {
    Remove-Item $script:deviceTokenBackup -Force -ErrorAction SilentlyContinue
  }
  foreach ($f in $script:tmpLocal) { if (Test-Path $f) { Remove-Item $f -Force -ErrorAction SilentlyContinue } }
}

try {

Write-Host "== automation smoke: $Pkg =="
& adb shell appops set $Pkg ACTIVATE_VPN allow 2>$null | Out-Null
& adb shell appops set $Pkg SYSTEM_ALERT_WINDOW allow 2>$null | Out-Null
# Reliable headless cold start: modern Android blocks background foreground-service starts even with
# appops exemptions. Bring the app to the foreground once (scripted, no human) so `start` has the
# foreground privilege it needs - the standard way to drive VPN apps in automation.
& adb shell am start -n "$Pkg/com.pakomo.MainActivity" 2>$null | Out-Null
Start-Sleep -Seconds 1

Write-Host "-- teardown to a known 'stopped' baseline"
Send-Control @("--es","cmd","stop","--es","wait","true") | Out-Null

Write-Host "-- P0: status (stopped)"
Assert "status ok + stopped" (Send-Control @("--es","cmd","status")) '"stage":"stopped"'

Write-Host "-- P1: start from stopped (app foregrounded in setup - not a pure background FGS start)"
$started = Send-Control @("--es","cmd","start","--es","rule","medium","--es","wait","true")
Assert "start-from-stopped reaches forwarding" $started '"stage":"forwarding"'
Assert "start-from-stopped applied medium"     $started '"appliedRule":"medium"'
Assert "start-from-stopped ok"                 $started '"ok":true'

Write-Host "-- P1: hot update (running tunnel)"
Assert "update -> severe" (Send-Control @("--es","cmd","update","--es","rule","severe","--es","wait","true")) '"appliedRule":"severe"'

Write-Host "-- P1: reset -> normal"
Assert "reset ok" (Send-Control @("--es","cmd","reset")) '"ok":true'

# P2: use the caller-supplied profile if given (they own targeting installed apps / global scope),
# otherwise auto-generate a device-independent global-scope profile.
Write-Host "-- P2: profile file"
if ($ProfileFile -ne "") {
  $profName = [IO.Path]::GetFileNameWithoutExtension($ProfileFile) -replace '\.example$',''
  $profSrc = $ProfileFile
} else {
  $profName = "smoke_auto"
  $profSrc = Join-Path $env:TEMP "pakomo-smoke-profile.json"
  $script:tmpLocal += $profSrc
  '{"scope":"global","apps":[],"domainsByApp":{},"domains":[],"rule":{"id":"smoke","name":"smoke","latencyMs":150,"jitterMs":30,"packetLossPercent":2,"downloadKbps":1024,"uploadKbps":512}}' | Set-Content -Path $profSrc -NoNewline -Encoding ascii
}
if (-not (Test-Path $profSrc)) {
  Write-Host "  FAIL  profile source missing: $profSrc" -ForegroundColor Red
  $script:fail++
} else {
  & adb shell mkdir -p "$Files/profiles" 2>$null | Out-Null
  # Delete any stale same-name profile first, then require the push to succeed, else load/start
  # could silently pass against a leftover file on the device.
  & adb shell rm -f "$Files/profiles/$profName.json" 2>$null | Out-Null
  & adb push $profSrc "$Files/profiles/$profName.json" | Out-Null
  if ($LASTEXITCODE -ne 0) {
    Write-Host "  FAIL  adb push failed for $profName" -ForegroundColor Red
    $script:fail++
  } else {
    Assert "load_profile valid" (Send-Control @("--es","cmd","load_profile","--es","profile",$profName)) '"valid":true'
    Assert "start via profile"  (Send-Control @("--es","cmd","start","--es","profile",$profName,"--es","wait","true")) '"ok":true'
  }
}

if ($env:TEST_TOKEN -eq "1") {
  Write-Host "-- P3: token gate"
  $script:tokenTestRan = $true
  # Pull a raw, uniquely named backup so UTF-8/non-ASCII token bytes are preserved exactly.
  $backupCandidate = Join-Path $env:TEMP ("pakomo-token-backup-{0}.txt" -f [guid]::NewGuid().ToString("N"))
  & adb pull "$Files/automation.token" $backupCandidate 2>$null | Out-Null
  if ($LASTEXITCODE -eq 0 -and (Test-Path $backupCandidate -PathType Leaf)) {
    $script:hadDeviceToken = $true
    $script:deviceTokenBackup = $backupCandidate
    $script:originalTokenValue = (Get-Content -LiteralPath $backupCandidate -Raw -Encoding UTF8).Trim()
  } else {
    Remove-Item $backupCandidate -Force -ErrorAction SilentlyContinue
  }
  $tokenFile = Join-Path $env:TEMP "pakomo.token"
  $script:tmpLocal += $tokenFile
  Set-Content -Path $tokenFile -Value "smoke-secret" -NoNewline -Encoding ascii
  & adb push $tokenFile "$Files/automation.token" | Out-Null
  if ($LASTEXITCODE -ne 0) {
    Write-Host "  FAIL  adb push failed for token" -ForegroundColor Red; $script:fail++
  } else {
    $script:testTokenInstalled = $true
    Assert "no token -> BAD_TOKEN" (Send-Control @("--es","cmd","status")) '"error":"BAD_TOKEN"'
    Assert "good token -> ok"      (Send-Control @("--es","cmd","status","--es","token","smoke-secret")) '"ok":true'
  }
}

Write-Host "-- teardown"
Send-CurrentControl @("--es","cmd","stop","--es","wait","true") | Out-Null
Assert "final stopped" (Send-CurrentControl @("--es","cmd","status")) '"stage":"stopped"'
$script:teardownDone = $true
# Restoring the device's original credential is deliberately the final device mutation.
Restore-Token

} finally {
  Cleanup
}

Write-Host "== $($script:pass) passed, $($script:fail) failed =="
if ($script:fail -gt 0) { exit 1 }
