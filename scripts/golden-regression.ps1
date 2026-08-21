param(
    [string]$Manifest = "data\golden\seed-risk-cases.json"
)

$ErrorActionPreference = "Stop"
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$serviceRoot = Join-Path $taskRoot "service"
$manifestPath = if ([System.IO.Path]::IsPathRooted($Manifest)) {
    $Manifest
} else {
    Join-Path $taskRoot $Manifest
}
$manifestPath = (Resolve-Path -LiteralPath $manifestPath).Path
$maven = Join-Path $serviceRoot "mvnw.cmd"

Push-Location $serviceRoot
try {
    & $maven `
        -pl atlas-domain `
        test `
        "-Dtest=GoldenRiskRegressionTest" `
        "-Datlas.golden.manifest=$manifestPath"
    if ($LASTEXITCODE -ne 0) {
        throw "Golden regression failed for $manifestPath"
    }
} finally {
    Pop-Location
}

Write-Output "Golden regression passed: $manifestPath"
