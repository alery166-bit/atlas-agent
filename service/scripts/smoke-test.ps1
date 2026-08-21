param(
    [int]$Port = 18080
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$jarPath = Join-Path $projectRoot "atlas-bootstrap\target\atlas-bootstrap-0.1.0-SNAPSHOT.jar"
$resultPath = Join-Path $projectRoot "target\smoke-result.json"

if (-not (Test-Path -LiteralPath $jarPath)) {
    throw "Executable jar not found: $jarPath. Run .\mvnw.cmd clean verify first."
}

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $resultPath) | Out-Null

$processInfo = [System.Diagnostics.ProcessStartInfo]::new()
$processInfo.FileName = "java"
$processInfo.Arguments = "-jar `"$jarPath`" --server.port=$Port --spring.profiles.active=test"
$processInfo.UseShellExecute = $false
$processInfo.CreateNoWindow = $true

$process = [System.Diagnostics.Process]::new()
$process.StartInfo = $processInfo
$null = $process.Start()

try {
    $baseUri = "http://127.0.0.1:$Port"
    $ready = $false

    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        if ($process.HasExited) {
            throw "Application exited before becoming ready with code $($process.ExitCode)."
        }

        try {
            $health = Invoke-RestMethod -Uri "$baseUri/actuator/health" -TimeoutSec 1
            if ($health.status -eq "UP") {
                $ready = $true
                break
            }
        }
        catch {
            Start-Sleep -Milliseconds 500
        }
    }

    if (-not $ready) {
        throw "Application did not become ready within 30 seconds."
    }

    $idempotencyKey = "smoke-" + [guid]::NewGuid().ToString("N")
    $body = @{
        prompt = "Update the risk report for Smoke Test Company Ltd."
        company_query = "Smoke Test Company Ltd."
        previous_report_file_id = "smoke-report-v1"
    } | ConvertTo-Json

    $created = Invoke-RestMethod `
        -Method Post `
        -Uri "$baseUri/api/tasks" `
        -Headers @{ "Idempotency-Key" = $idempotencyKey } `
        -ContentType "application/json" `
        -Body $body

    $loaded = Invoke-RestMethod -Uri "$baseUri/api/tasks/$($created.task_id)"

    if ($created.task_id -ne $loaded.task_id -or $loaded.status -ne "CREATED") {
        throw "Task create/read smoke assertion failed."
    }

    @{
        health = $health.status
        taskId = $loaded.task_id
        status = $loaded.status
        companyQuery = $loaded.company_query
        checkedAt = [DateTimeOffset]::UtcNow.ToString("O")
    } |
        ConvertTo-Json |
        Set-Content -LiteralPath $resultPath -Encoding utf8

    Write-Output "Smoke test passed. Result: $resultPath"
}
finally {
    $listenerProcessIds = @(
        Get-NetTCPConnection `
            -LocalPort $Port `
            -State Listen `
            -ErrorAction SilentlyContinue |
            Select-Object -ExpandProperty OwningProcess -Unique
    )

    foreach ($ownerProcessId in $listenerProcessIds) {
        Stop-Process -Id $ownerProcessId -Force -ErrorAction SilentlyContinue
    }

    if (-not $process.HasExited) {
        $process.Kill()
        $process.WaitForExit()
    }
}
