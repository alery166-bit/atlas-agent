param(
    [int]$BackendPort = 18080,
    [int]$FrontendPort = 13000
)

$ErrorActionPreference = "Stop"
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$serviceRoot = Join-Path $taskRoot "service"
$consoleRoot = Join-Path $taskRoot "console"
$workRoot = Join-Path $taskRoot "work\e2e"
$runId = Get-Date -Format "yyyyMMdd-HHmmss"
$runRoot = Join-Path $workRoot $runId
$backendFiles = Join-Path $runRoot "backend-files"
$backendLog = Join-Path $runRoot "backend.log"
$backendErrorLog = Join-Path $runRoot "backend-error.log"
$frontendLog = Join-Path $runRoot "frontend.log"
$frontendErrorLog = Join-Path $runRoot "frontend-error.log"
$e2eLog = Join-Path $runRoot "browser-test.log"
$webBase = "http://127.0.0.1:$FrontendPort"
$apiBase = "http://127.0.0.1:$BackendPort"

New-Item -ItemType Directory -Force -Path $backendFiles | Out-Null

$occupiedPorts = Get-NetTCPConnection `
    -LocalPort $BackendPort, $FrontendPort `
    -State Listen `
    -ErrorAction SilentlyContinue
if ($occupiedPorts) {
    $owners = $occupiedPorts |
        ForEach-Object { "$($_.LocalPort):pid=$($_.OwningProcess)" }
    throw "E2E ports are already occupied: $($owners -join ', ')"
}

function Resolve-NodeExecutable {
    if ($env:ATLAS_NODE_EXE -and (Test-Path -LiteralPath $env:ATLAS_NODE_EXE)) {
        return (Resolve-Path -LiteralPath $env:ATLAS_NODE_EXE).Path
    }
    $command = Get-Command node.exe -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }
    $fallback = Join-Path $env:USERPROFILE `
        ".cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe"
    if (Test-Path -LiteralPath $fallback) {
        return (Resolve-Path -LiteralPath $fallback).Path
    }
    throw "Node.js 22+ was not found. Set ATLAS_NODE_EXE and retry."
}

function Wait-HttpEndpoint([string]$Uri, [string]$Name) {
    $deadline = (Get-Date).AddSeconds(60)
    do {
        try {
            $response = Invoke-WebRequest -Uri $Uri -UseBasicParsing -TimeoutSec 2
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 500) {
                return
            }
        } catch {
            Start-Sleep -Milliseconds 500
        }
    } while ((Get-Date) -lt $deadline)
    throw "$Name did not become ready at $Uri"
}

$node = Resolve-NodeExecutable
$java = (Get-Command java.exe -ErrorAction Stop).Source
$maven = Join-Path $serviceRoot "mvnw.cmd"
$template = Get-ChildItem -LiteralPath (Join-Path $taskRoot "data\templates") `
    -Filter *.docx | Select-Object -First 1
if (-not $template) {
    throw "No DOCX template was found under data/templates."
}

$fixtureData = Join-Path $serviceRoot `
    "atlas-bootstrap\src\test\resources\fixtures\company-data"
$fixtureJson = Join-Path $serviceRoot `
    "atlas-bootstrap\src\test\resources\fixtures\company-json\sample-company.json"

Push-Location $serviceRoot
try {
    & $maven -pl atlas-bootstrap -am package "-DskipTests"
    if ($LASTEXITCODE -ne 0) {
        throw "Backend packaging failed."
    }
} finally {
    Pop-Location
}

$jar = Get-ChildItem -LiteralPath (Join-Path $serviceRoot "atlas-bootstrap\target") `
    -Filter "atlas-bootstrap-*.jar" |
    Where-Object { $_.Name -notlike "*.original" } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if (-not $jar) {
    throw "Packaged backend jar was not found."
}

$previousApiBase = $env:NEXT_PUBLIC_ATLAS_API_BASE
$env:NEXT_PUBLIC_ATLAS_API_BASE = $apiBase
Push-Location $consoleRoot
try {
    & $node "node_modules\vinext\dist\cli.js" build
    if ($LASTEXITCODE -ne 0) {
        throw "Frontend build failed."
    }
} finally {
    Pop-Location
    $env:NEXT_PUBLIC_ATLAS_API_BASE = $previousApiBase
}

$backend = $null
$frontend = $null
try {
    $backendArguments = @(
        "-jar",
        $jar.FullName,
        "--spring.profiles.active=test",
        "--server.port=$BackendPort",
        "--spring.datasource.url=jdbc:h2:mem:atlas-e2e-$runId;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "--spring.datasource.username=sa",
        "--spring.datasource.password=",
        "--atlas.storage.root=$backendFiles",
        "--atlas.report.template-path=$($template.FullName)",
        "--atlas.report.previous-root=$taskRoot",
        "--atlas.data.offline.root=$fixtureData",
        "--atlas.data.offline.json-files[0]=$fixtureJson",
        "--atlas.web.allowed-origins=$webBase",
        "--management.endpoints.web.cors.allowed-origins=$webBase",
        "--management.endpoints.web.cors.allowed-methods=GET,OPTIONS"
    )
    $backend = Start-Process -FilePath $java `
        -ArgumentList $backendArguments `
        -WorkingDirectory $serviceRoot `
        -WindowStyle Hidden `
        -RedirectStandardOutput $backendLog `
        -RedirectStandardError $backendErrorLog `
        -PassThru

    $frontend = Start-Process -FilePath $node `
        -ArgumentList @(
            "node_modules\vinext\dist\cli.js",
            "dev",
            "--hostname",
            "127.0.0.1",
            "--port",
            $FrontendPort
        ) `
        -WorkingDirectory $consoleRoot `
        -WindowStyle Hidden `
        -RedirectStandardOutput $frontendLog `
        -RedirectStandardError $frontendErrorLog `
        -PassThru

    Wait-HttpEndpoint "$apiBase/actuator/health" "Atlas backend"
    Wait-HttpEndpoint $webBase "Atlas console"
    $backend.Refresh()
    $frontend.Refresh()
    if ($backend.HasExited) {
        throw "Atlas backend exited during startup. See $backendLog"
    }
    if ($frontend.HasExited) {
        throw "Atlas console exited during startup. See $frontendLog"
    }

    $env:ATLAS_E2E_API_BASE = $apiBase
    $env:ATLAS_E2E_WEB_BASE = $webBase
    $env:ATLAS_E2E_OPERATOR = "e2e-operator-$runId"
    Push-Location $consoleRoot
    try {
        & $node --test "tests\operator-flow.e2e.mjs" *>&1 |
            Tee-Object -FilePath $e2eLog
        $browserTestExitCode = $LASTEXITCODE
        if ($browserTestExitCode -ne 0) {
            throw "Browser end-to-end test failed. Logs: $runRoot"
        }
    } finally {
        Pop-Location
    }

    Write-Output "W13 browser end-to-end flow passed. Artifacts: $runRoot"
} finally {
    if ($frontend) {
        Stop-Process -Id $frontend.Id -Force -ErrorAction SilentlyContinue
    }
    if ($backend) {
        Stop-Process -Id $backend.Id -Force -ErrorAction SilentlyContinue
    }
    $remainingListeners = Get-NetTCPConnection `
        -LocalPort $BackendPort, $FrontendPort `
        -State Listen `
        -ErrorAction SilentlyContinue
    foreach ($listener in $remainingListeners) {
        $ownedProcess = Get-Process `
            -Id $listener.OwningProcess `
            -ErrorAction SilentlyContinue
        if ($ownedProcess -and @("java", "node").Contains($ownedProcess.ProcessName)) {
            Stop-Process -Id $ownedProcess.Id -Force -ErrorAction SilentlyContinue
        }
    }
}
