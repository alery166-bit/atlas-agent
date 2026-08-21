param(
    [switch]$AllowHttpForPrivateNetwork
)

$ErrorActionPreference = "Stop"
$failures = [System.Collections.Generic.List[string]]::new()
$warnings = [System.Collections.Generic.List[string]]::new()

function Required([string]$Name) {
    $value = [Environment]::GetEnvironmentVariable($Name)
    if ([string]::IsNullOrWhiteSpace($value)) {
        $failures.Add("$Name is required")
    }
    return $value
}

function Secret([string]$Name, [int]$MinimumLength) {
    $value = Required $Name
    if ($value -and $value.Length -lt $MinimumLength) {
        $failures.Add("$Name must contain at least $MinimumLength characters")
    }
    return $value
}

$securityMode = Required "ATLAS_SECURITY_MODE"
if ($securityMode -and $securityMode.ToUpperInvariant() -ne "PROXY") {
    $failures.Add("ATLAS_SECURITY_MODE must be PROXY")
}
[void](Secret "ATLAS_SECURITY_PROXY_SECRET" 32)

$publicApi = Required "ATLAS_PUBLIC_API_BASE"
$allowedOrigins = Required "ATLAS_ALLOWED_ORIGINS"
if (-not $AllowHttpForPrivateNetwork) {
    if ($publicApi -and -not $publicApi.StartsWith("https://")) {
        $failures.Add("ATLAS_PUBLIC_API_BASE must use HTTPS")
    }
    foreach ($origin in ($allowedOrigins -split ',')) {
        if ($origin.Trim() -and -not $origin.Trim().StartsWith("https://")) {
            $failures.Add("Every ATLAS_ALLOWED_ORIGINS entry must use HTTPS")
        }
    }
}

$databasePassword = Secret "ATLAS_DB_PASSWORD" 16
if ($databasePassword -in @("atlas", "atlas-local", "atlas-local-only", "password")) {
    $failures.Add("ATLAS_DB_PASSWORD still uses a known development value")
}

$esApiKey = [Environment]::GetEnvironmentVariable("ATLAS_ES_API_KEY")
$esUsername = [Environment]::GetEnvironmentVariable("ATLAS_ES_USERNAME")
$esPassword = [Environment]::GetEnvironmentVariable("ATLAS_ES_PASSWORD")
if ([string]::IsNullOrWhiteSpace($esApiKey) -and
    ([string]::IsNullOrWhiteSpace($esUsername) -or
     [string]::IsNullOrWhiteSpace($esPassword))) {
    $failures.Add("Configure ATLAS_ES_API_KEY or both ATLAS_ES_USERNAME and ATLAS_ES_PASSWORD")
}

if ([Environment]::GetEnvironmentVariable("ATLAS_SEARCH_PRIMARY_ENABLED") -eq "true") {
    [void](Required "TAVILY_API_KEY")
}
if ([Environment]::GetEnvironmentVariable("ATLAS_SEARCH_LLM_ENABLED") -eq "true") {
    [void](Required "ATLAS_SEARCH_LLM_API_KEY")
}
[void](Required "ATLAS_LLM_API_KEY")

if ([Environment]::GetEnvironmentVariable("ATLAS_DATA_PROVIDER") -ne "es") {
    $warnings.Add("ATLAS_DATA_PROVIDER is not es; confirm this is intentional")
}

$result = [ordered]@{
    status = if ($failures.Count -eq 0) { "PASS" } else { "FAIL" }
    checked_at = (Get-Date).ToUniversalTime().ToString("o")
    failures = @($failures)
    warnings = @($warnings)
}
$result | ConvertTo-Json -Depth 3
if ($failures.Count -gt 0) { exit 1 }
