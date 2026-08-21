param(
    [int]$Port = 18085,
    [string]$CompanyQuery = "91110113MAK5DEJQ0W"
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$jarPath = Join-Path $projectRoot "atlas-bootstrap\target\atlas-bootstrap-0.1.0-SNAPSHOT.jar"
$resultPath = Join-Path $projectRoot "target\w2-offline-smoke-result.json"

if (-not (Test-Path -LiteralPath $jarPath)) {
    throw "Executable jar not found: $jarPath. Run .\mvnw.cmd clean verify first."
}

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $resultPath) | Out-Null

$databaseUrl = "jdbc:h2:mem:atlas-w2-smoke;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
$processInfo = [System.Diagnostics.ProcessStartInfo]::new()
$processInfo.FileName = "java"
$processInfo.Arguments = "-jar `"$jarPath`" --server.port=$Port `"--spring.datasource.url=$databaseUrl`" --spring.datasource.username=atlas --spring.datasource.password=atlas-smoke"
$processInfo.UseShellExecute = $false
$processInfo.CreateNoWindow = $true

$process = [System.Diagnostics.Process]::new()
$process.StartInfo = $processInfo
$null = $process.Start()

try {
    $baseUri = "http://127.0.0.1:$Port"
    $ready = $false

    for ($attempt = 0; $attempt -lt 80; $attempt++) {
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
        throw "Application did not become ready within 40 seconds."
    }

    $idempotencyKey = "w2-offline-smoke-" + [guid]::NewGuid().ToString("N")
    $body = @{
        prompt = "Update the existing enterprise risk report from offline data."
        company_query = $CompanyQuery
        previous_report_file_id = "offline-smoke-report-v1"
    } | ConvertTo-Json

    $created = Invoke-RestMethod `
        -Method Post `
        -Uri "$baseUri/api/tasks" `
        -Headers @{ "Idempotency-Key" = $idempotencyKey } `
        -ContentType "application/json" `
        -Body $body

    $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
    $executed = Invoke-RestMethod `
        -Method Post `
        -Uri "$baseUri/api/tasks/$($created.task_id)/execute" `
        -TimeoutSec 240
    $stopwatch.Stop()

    if ($executed.status -ne "SEARCHING_PUBLIC_INTELLIGENCE") {
        throw "Workflow did not reach the W2 boundary. Status: $($executed.status)"
    }

    $snapshotResponse = Invoke-WebRequest `
        -Uri "$baseUri/api/tasks/$($created.task_id)/snapshot" `
        -UseBasicParsing `
        -TimeoutSec 30
    $snapshotResponse.RawContentStream.Position = 0
    $snapshotReader = [System.IO.StreamReader]::new(
        $snapshotResponse.RawContentStream,
        [System.Text.Encoding]::UTF8,
        $true
    )
    try {
        $snapshot = ($snapshotReader.ReadToEnd() | ConvertFrom-Json)
    }
    finally {
        $snapshotReader.Dispose()
    }

    if ($snapshot.company_facts.unified_credit_code -ne $CompanyQuery) {
        throw "Resolved company credit code does not match the query."
    }

    $failedSources = @(
        $snapshot.source_statuses |
        Where-Object { $_.query_status -eq "FAILED" }
    )

    @{
        health = $health.status
        taskId = $created.task_id
        status = $executed.status
        atlasCompanyId = $executed.atlas_company_id
        companyName = $snapshot.company_facts.canonical_name
        unifiedCreditCode = $snapshot.company_facts.unified_credit_code
        snapshotId = $snapshot.snapshot_id
        snapshotHash = $snapshot.content_hash
        companyChangeCount = @($snapshot.company_changes).Count
        riskEventCount = @($snapshot.risk_events).Count
        sourceCount = @($snapshot.source_statuses).Count
        failedSourceCount = $failedSources.Count
        executionMilliseconds = $stopwatch.ElapsedMilliseconds
        checkedAt = [DateTimeOffset]::UtcNow.ToString("O")
    } |
        ConvertTo-Json |
        Set-Content -LiteralPath $resultPath -Encoding utf8

    Write-Output "W2 offline smoke test passed. Result: $resultPath"
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
