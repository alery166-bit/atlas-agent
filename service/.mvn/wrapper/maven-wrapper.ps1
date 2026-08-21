$ErrorActionPreference = "Stop"

$projectRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$propertiesPath = Join-Path $PSScriptRoot "maven-wrapper.properties"
$properties = @{}

Get-Content -LiteralPath $propertiesPath -Encoding UTF8 | ForEach-Object {
    if ($_ -match "^\s*([^#][^=]*)=(.*)$") {
        $properties[$matches[1].Trim()] = $matches[2].Trim()
    }
}

$version = $properties["mavenVersion"]
$distributionUrl = $properties["distributionUrl"]
$checksumUrl = $properties["distributionSha512Url"]
$installRoot = Join-Path $projectRoot ".mvn-local"
$mavenHome = Join-Path $installRoot "apache-maven-$version"
$mavenCommand = Join-Path $mavenHome "bin\mvn.cmd"

if (-not (Test-Path -LiteralPath $mavenCommand)) {
    New-Item -ItemType Directory -Force -Path $installRoot | Out-Null
    $archivePath = Join-Path $installRoot "apache-maven-$version-bin.zip"
    $checksumPath = "$archivePath.sha512"

    Invoke-WebRequest -Uri $distributionUrl -OutFile $archivePath
    Invoke-WebRequest -Uri $checksumUrl -OutFile $checksumPath

    $expected = ((Get-Content -LiteralPath $checksumPath -Raw).Trim() -split "\s+")[0].ToLowerInvariant()
    $actual = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA512).Hash.ToLowerInvariant()
    if ($actual -ne $expected) {
        throw "Maven archive checksum mismatch. Expected $expected but got $actual."
    }

    Expand-Archive -LiteralPath $archivePath -DestinationPath $installRoot -Force
}

& $mavenCommand @args
exit $LASTEXITCODE
