# Prepares the vendored hev-socks5-tunnel sources for a Windows build after a fresh
# checkout or `git submodule update`: materializes the git symlink placeholders and
# applies the Pakomo native attribution patch. Idempotent — safe to re-run.
param(
    [string]$Root = (Join-Path $PSScriptRoot '..')
)

$ErrorActionPreference = 'Stop'
$root = [System.IO.Path]::GetFullPath($Root)
$submodule = Join-Path $root 'third_party\hev-socks5-tunnel'
$patch = Join-Path $root 'patches\hev-attribution-preamble.patch'

if (-not (Test-Path -LiteralPath $submodule)) {
    throw "Submodule not found: $submodule (run 'git submodule update --init --recursive' first)"
}

Write-Output "Restoring vendored symlink placeholders..."
& (Join-Path $PSScriptRoot 'restore-third-party-links.ps1') -Root $submodule

if (-not (Test-Path -LiteralPath $patch)) {
    throw "Native patch not found: $patch"
}

# Already applied? A successful reverse-check means the changes are present.
& git -C $submodule apply --reverse --check $patch 2>$null
if ($LASTEXITCODE -eq 0) {
    Write-Output "HEV attribution patch already applied; third-party sources are ready."
    return
}

# Not applied yet: verify it applies cleanly, then apply.
& git -C $submodule apply --check $patch 2>$null
if ($LASTEXITCODE -ne 0) {
    throw "HEV attribution patch does not apply cleanly to '$submodule'. Inspect the submodule state and '$patch'."
}

& git -C $submodule apply $patch
Write-Output "Applied HEV attribution patch. Third-party sources are ready to build."
