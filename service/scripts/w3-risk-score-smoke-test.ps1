param(
    [int]$Port = 18086,
    [string]$CompanyQuery = "91110113MAK5DEJQ0W"
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$jarPath = Join-Path $projectRoot "atlas-bootstrap\target\atlas-bootstrap-0.1.0-SNAPSHOT.jar"
$resultPath = Join-Path $projectRoot "target\w3-risk-score-smoke-result.json"

if (-not (Test-Path -LiteralPath $jarPath)) {
    throw "Executable jar not found: $jarPath. Run .\mvnw.cmd clean verify first."
}

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $resultPath) | Out-Null

$databaseUrl = "jdbc:h2:mem:atlas-w3-smoke;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
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

    $taskBody = @{
        prompt = "Update the existing enterprise risk report and calculate a traceable score."
        company_query = $CompanyQuery
        previous_report_file_id = "offline-smoke-report-v1"
    } | ConvertTo-Json
    $created = Invoke-RestMethod `
        -Method Post `
        -Uri "$baseUri/api/tasks" `
        -Headers @{ "Idempotency-Key" = "w3-score-smoke-$([guid]::NewGuid().ToString('N'))" } `
        -ContentType "application/json" `
        -Body $taskBody
    $executed = Invoke-RestMethod `
        -Method Post `
        -Uri "$baseUri/api/tasks/$($created.task_id)/execute" `
        -TimeoutSec 240
    if ($executed.status -ne "SEARCHING_PUBLIC_INTELLIGENCE") {
        throw "Workflow did not freeze a structured snapshot. Status: $($executed.status)"
    }

    $scoreBody = @{
        confirmed_events = @(
            @{
                risk_type = "OUT_OF_CONTACT"
                reference_id = "smoke-finding-out-of-contact"
                title = "Confirmed out of contact"
                evidence_ids = @("smoke-evidence-1")
            },
            @{
                risk_type = "WAGE_ARREARS"
                reference_id = "smoke-finding-wage-arrears"
                title = "Confirmed wage arrears"
                evidence_ids = @("smoke-evidence-2")
            },
            @{
                risk_type = "STORE_CLOSURE"
                reference_id = "smoke-finding-store-closure"
                title = "Confirmed store closure"
                evidence_ids = @("smoke-evidence-3")
            }
        )
    } | ConvertTo-Json -Depth 5
    $firstScore = Invoke-RestMethod `
        -Method Post `
        -Uri "$baseUri/api/tasks/$($created.task_id)/risk-score/calculate" `
        -ContentType "application/json" `
        -Body $scoreBody
    $secondScore = Invoke-RestMethod `
        -Method Post `
        -Uri "$baseUri/api/tasks/$($created.task_id)/risk-score/calculate" `
        -ContentType "application/json" `
        -Body $scoreBody

    if ($firstScore.score_snapshot_id -ne $secondScore.score_snapshot_id) {
        throw "Repeated calculation was not idempotent."
    }
    if ([decimal]$firstScore.event_floor_score -ne [decimal]8) {
        throw "Expected maximum event floor 8, got $($firstScore.event_floor_score)."
    }
    if ([decimal]$firstScore.original_score -lt [decimal]8) {
        throw "Original score did not respect the store-closure floor."
    }

    $adjustmentBody = @{
        manual_score = 4.5
        reason_code = "RULE_LIMITATION"
        reason_text = "Smoke test verifies original and manual scores remain separate."
    } | ConvertTo-Json
    $adjustment = Invoke-RestMethod `
        -Method Post `
        -Uri "$baseUri/api/tasks/$($created.task_id)/risk-score/$($firstScore.score_snapshot_id)/adjustments" `
        -Headers @{ "X-Operator-Id" = "w3-smoke-operator" } `
        -ContentType "application/json" `
        -Body $adjustmentBody

    if ([decimal]$adjustment.score.original_score -ne [decimal]$firstScore.original_score) {
        throw "Manual adjustment overwrote the original score."
    }
    if ([decimal]$adjustment.score.manual_score -ne [decimal]4.5) {
        throw "Manual score was not persisted."
    }
    if (-not $adjustment.floor_override_warning) {
        throw "Expected floor override warning."
    }

    $decisions = Invoke-RestMethod `
        -Uri "$baseUri/api/tasks/$($created.task_id)/risk-score/decisions"
    @{
        health = $health.status
        taskId = $created.task_id
        companyQuery = $CompanyQuery
        scoreSnapshotId = $firstScore.score_snapshot_id
        idempotent = ($firstScore.score_snapshot_id -eq $secondScore.score_snapshot_id)
        legacyScore = $firstScore.legacy_score
        ruleCalculatedScore = $firstScore.rule_calculated_score
        eventFloorScore = $firstScore.event_floor_score
        originalScore = $adjustment.score.original_score
        manualScore = $adjustment.score.manual_score
        originalRiskLevel = $adjustment.score.original_risk_level
        manualRiskLevel = $adjustment.score.manual_risk_level
        floorOverrideWarning = $adjustment.floor_override_warning
        ruleHitCount = @($firstScore.rule_hits).Count
        decisionCount = @($decisions).Count
        ruleVersion = $firstScore.rule_version
        engineVersion = $firstScore.engine_version
        inputHash = $firstScore.input_hash
        checkedAt = [DateTimeOffset]::UtcNow.ToString("O")
    } |
        ConvertTo-Json |
        Set-Content -LiteralPath $resultPath -Encoding utf8

    Write-Output "W3 risk score smoke test passed. Result: $resultPath"
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
