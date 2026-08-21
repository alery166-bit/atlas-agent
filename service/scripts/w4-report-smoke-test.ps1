param()

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot

Push-Location $projectRoot
try {
    & .\mvnw.cmd `
        -pl atlas-bootstrap `
        -am `
        "-Dtest=BeijingReportSmokeIT" `
        "-Dsurefire.failIfNoSpecifiedTests=false" `
        test
    if ($LASTEXITCODE -ne 0) {
        throw "W4 Beijing report smoke test failed with exit code $LASTEXITCODE."
    }
}
finally {
    Pop-Location
}

$result = Join-Path (Split-Path -Parent $projectRoot) "outputs\atlas-w4\w4-beijing-smoke-result.json"
if (-not (Test-Path -LiteralPath $result)) {
    throw "W4 smoke result was not created: $result"
}
Write-Output "W4 Beijing report smoke test passed. Result: $result"
