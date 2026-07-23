param(
    [string]$Root = (Join-Path $PSScriptRoot '..\third_party\hev-socks5-tunnel')
)

$resolvedRoot = [System.IO.Path]::GetFullPath($Root)
$placeholders = Get-ChildItem -LiteralPath $resolvedRoot -Recurse -File | Where-Object {
    $content = Get-Content -LiteralPath $_.FullName -Raw
    $content.Trim() -match '^\.\./'
}

foreach ($file in $placeholders) {
    $targetText = (Get-Content -LiteralPath $file.FullName -Raw).Trim()
    $targetPath = [System.IO.Path]::GetFullPath(
        (Join-Path $file.DirectoryName $targetText)
    )
    if (-not $targetPath.StartsWith($resolvedRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to resolve a link outside the third-party source tree: $($file.FullName)"
    }
    if (-not (Test-Path -LiteralPath $targetPath -PathType Leaf)) {
        throw "Missing link target: $targetPath"
    }
    Copy-Item -LiteralPath $targetPath -Destination $file.FullName -Force
}

Write-Output "Restored $($placeholders.Count) vendored link placeholders."
