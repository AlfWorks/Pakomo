# Prepares the vendored hev-socks5-tunnel sources for a Windows build after a fresh
# checkout or `git submodule update`: materializes the git symlink placeholders and
# applies the Pakomo native attribution patch. Idempotent — safe to re-run.
param(
    [string]$Root = (Join-Path $PSScriptRoot '..')
)

$root = [System.IO.Path]::GetFullPath($Root)
$submodule = Join-Path $root 'third_party\hev-socks5-tunnel'
$patch = Join-Path $root 'patches\hev-attribution-preamble.patch'

if (-not (Test-Path -LiteralPath $submodule)) {
    throw "Submodule not found: $submodule (run 'git submodule update --init --recursive' first)"
}
if (-not (Test-Path -LiteralPath $patch)) {
    throw "Native patch not found: $patch"
}

Write-Output "Restoring vendored symlink placeholders..."
& (Join-Path $PSScriptRoot 'restore-third-party-links.ps1') -Root $submodule

# `git apply --check` writes to stderr on the expected "not yet applied" path. In Windows
# PowerShell 5.1 a redirected native stderr is wrapped as a terminating NativeCommandError, so
# relax the preference for these probes and drive control flow off the exit code only.
$ErrorActionPreference = 'SilentlyContinue'

& git -C $submodule apply --reverse --check $patch 2>$null
$alreadyApplied = ($LASTEXITCODE -eq 0)

if ($alreadyApplied) {
    $ErrorActionPreference = 'Continue'
    Write-Output "HEV attribution patch already applied; third-party sources are ready."
    return
}

& git -C $submodule apply $patch 2>$null
$applied = ($LASTEXITCODE -eq 0)
$ErrorActionPreference = 'Continue'

if (-not $applied) {
    throw "HEV attribution patch did not apply cleanly to '$submodule'. Inspect the submodule and '$patch'."
}
Write-Output "Applied HEV attribution patch. Third-party sources are ready to build."
