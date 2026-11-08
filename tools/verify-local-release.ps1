[CmdletBinding()]
param(
    [switch]$SeedFailure,
    [switch]$BootstrapOnly,
    # The device the gate's connectedDebugAndroidTest runs on. Without it the gate needs exactly
    # one attached device or an ANDROID_SERIAL already set.
    [string]$Serial
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$Root = Split-Path -Parent $PSScriptRoot
$Gradlew = Join-Path $Root "gradlew.bat"
$GradleWrapperProperties = Join-Path $Root "gradle\wrapper\gradle-wrapper.properties"
$GradleWrapperJar = Join-Path $Root "gradle\wrapper\gradle-wrapper.jar"
$DependencyVerificationScript = Join-Path $Root "tools\verify-dependency-verification.ps1"
$ReportDirectory = Join-Path $Root "build\reports\opentasker"
$SbomPath = Join-Path $ReportDirectory "sbom.cdx.json"
$OsvReportPath = Join-Path $ReportDirectory "osv-advisories.json"
$SummaryPath = Join-Path $ReportDirectory "local-release-gate.json"
$JvmTestReportPath = Join-Path $ReportDirectory "jvm-test-count.json"
$ExpectedGradleVersion = "9.7.1"
$ExpectedGradleDistributionSha256 = "acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a"
$ExpectedGradleWrapperJarSha256 = "7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d"

function Resolve-GitSafeDirectory {
    $rootPath = [IO.Path]::GetFullPath($Root)
    $pathRoot = [IO.Path]::GetPathRoot($rootPath)
    if ($pathRoot -match "^[A-Za-z]:\\$") {
        $driveName = $pathRoot.Substring(0, 1)
        $drive = Get-PSDrive -Name $driveName -ErrorAction SilentlyContinue
        if ($null -ne $drive -and -not [string]::IsNullOrWhiteSpace($drive.DisplayRoot)) {
            $relative = $rootPath.Substring($pathRoot.Length)
            return (Join-Path $drive.DisplayRoot $relative).Replace("\", "/")
        }
    }
    return $rootPath.Replace("\", "/")
}

$GitSafeDirectory = Resolve-GitSafeDirectory
# Gradle release tasks also launch Git. Propagate this one exact repository identity only to child
# processes; the caller's global Git trust configuration remains untouched.
$env:GIT_CONFIG_COUNT = "1"
$env:GIT_CONFIG_KEY_0 = "safe.directory"
$env:GIT_CONFIG_VALUE_0 = $GitSafeDirectory

if (-not (Test-Path -LiteralPath $Gradlew -PathType Leaf)) {
    throw "Gradle wrapper not found at $Gradlew"
}

function Assert-GradleBootstrapIntegrity {
    foreach ($path in @($GradleWrapperProperties, $GradleWrapperJar)) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            throw "Gradle bootstrap file not found at $path"
        }
    }

    $properties = @{}
    foreach ($line in Get-Content -LiteralPath $GradleWrapperProperties) {
        $trimmed = $line.Trim()
        if ($trimmed.Length -eq 0 -or $trimmed.StartsWith("#")) { continue }
        $separator = $trimmed.IndexOf("=")
        if ($separator -le 0) { throw "Malformed Gradle wrapper property: $line" }
        $key = $trimmed.Substring(0, $separator)
        $value = $trimmed.Substring($separator + 1)
        if ($properties.ContainsKey($key)) { throw "Duplicate Gradle wrapper property: $key" }
        $properties[$key] = $value
    }

    $expectedDistributionUrl = "https\://services.gradle.org/distributions/gradle-$ExpectedGradleVersion-bin.zip"
    if ($properties["distributionUrl"] -ne $expectedDistributionUrl) {
        throw "Gradle distribution URL is not the pinned $ExpectedGradleVersion binary distribution."
    }
    if ($properties["distributionSha256Sum"] -ne $ExpectedGradleDistributionSha256) {
        throw "Gradle distribution SHA-256 is absent or does not match the pinned official checksum."
    }

    $actualWrapperHash = (Get-FileHash -LiteralPath $GradleWrapperJar -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualWrapperHash -ne $ExpectedGradleWrapperJarSha256) {
        throw "Gradle wrapper JAR SHA-256 mismatch. Expected $ExpectedGradleWrapperJarSha256; actual $actualWrapperHash."
    }
    Write-Host "Gradle bootstrap verified: $ExpectedGradleVersion distribution and wrapper JAR checksums match."
}

# The wrapper JAR is executable, so verify it and its distribution pin before invoking Gradle.
Assert-GradleBootstrapIntegrity
if ($BootstrapOnly) {
    Write-Host "Gradle bootstrap-only verification passed."
    return
}

function Select-ConnectedTestDevice {
    # localQualityGate depends on connectedDebugAndroidTest, which installs, instruments and then
    # uninstalls on every device adb can see, phones and other people's emulators included. AGP
    # honors ANDROID_SERIAL, so name one device before Gradle starts.
    if (-not [string]::IsNullOrWhiteSpace($Serial)) { $env:ANDROID_SERIAL = $Serial }
    $sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $env:LOCALAPPDATA "Android\Sdk" }
    $adb = Join-Path $sdk "platform-tools\adb.exe"
    if (-not (Test-Path -LiteralPath $adb -PathType Leaf)) { throw "adb not found at $adb" }
    $attached = @(& $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "^(\S+)\s+device$" } | ForEach-Object { ($_ -split "\s+")[0] })
    if (-not [string]::IsNullOrWhiteSpace($env:ANDROID_SERIAL)) {
        if ($attached -notcontains $env:ANDROID_SERIAL) {
            throw "ANDROID_SERIAL $($env:ANDROID_SERIAL) is not an attached device. Attached: $($attached -join ', ')"
        }
        Write-Host "Connected tests run on $($env:ANDROID_SERIAL) only."
        return
    }
    if ($attached.Count -ne 1) {
        throw "adb sees $($attached.Count) devices ($($attached -join ', ')). The gate's connected tests would run on all of them; pass -Serial <device> for the one this run may use."
    }
    Write-Host "Connected tests run on $($attached[0]), the only attached device."
}

# A seeded-failure run never reaches the connected tests, so it needs no device.
if (-not $SeedFailure) { Select-ConnectedTestDevice }

if (-not (Test-Path -LiteralPath $DependencyVerificationScript -PathType Leaf)) {
    throw "Dependency verification script not found at $DependencyVerificationScript"
}
& $DependencyVerificationScript
$dependencyVerificationExitCode = if (Test-Path variable:LASTEXITCODE) { [int]$LASTEXITCODE } else { 0 }
if ($dependencyVerificationExitCode -ne 0) {
    throw "Independent dependency verification failed with exit code $dependencyVerificationExitCode"
}

function Invoke-Gradle {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments,
        [switch]$CaptureOutput
    )

    Write-Host "gradlew $($Arguments -join ' ')"
    Push-Location $Root
    try {
        if ($CaptureOutput) {
            $lines = @(& $Gradlew @Arguments 2>&1)
            $exitCode = $LASTEXITCODE
            $lines | ForEach-Object { Write-Host $_ }
            if ($exitCode -ne 0) {
                throw "Gradle exited with code $exitCode"
            }
            return @($lines | ForEach-Object { $_.ToString() })
        }

        & $Gradlew @Arguments
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle exited with code $LASTEXITCODE"
        }
    } finally {
        Pop-Location
    }
}

# The gate forks one single-use daemon that also hosts the Kotlin compiler, lint and the
# connected-test platform, so it sizes that JVM itself. A user-home gradle.properties beats the
# repo's jvmargs, and only the command line beats that; a 512 MB metaspace cap there ran out
# three tests into connectedDebugAndroidTest.
$CommonGradleArguments = @(
    "--no-daemon",
    "--console=plain",
    "--project-prop=kotlin.compiler.execution.strategy=in-process",
    "-Dorg.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=1g -XX:+UseG1GC -Dfile.encoding=UTF-8"
)

if ($SeedFailure) {
    Invoke-Gradle -Arguments (@(
        ":app:verifyQualityGateSeed",
        "-PopenTaskerQualityGateSeedFailure=true"
    ) + $CommonGradleArguments)
    throw "Seeded failure unexpectedly passed."
}

$QualityArguments = @(
    ":app:localQualityGate",
    "--configuration-cache"
) + $CommonGradleArguments

Invoke-Gradle -Arguments $QualityArguments
$reuseOutput = Invoke-Gradle -Arguments $QualityArguments -CaptureOutput
if (-not ($reuseOutput -match "Reusing configuration cache")) {
    throw "The second local quality-gate invocation did not reuse the Gradle configuration cache."
}

if (-not (Test-Path -LiteralPath $JvmTestReportPath -PathType Leaf)) {
    throw "JVM test report was not produced at $JvmTestReportPath"
}
$jvmTestReport = Get-Content -LiteralPath $JvmTestReportPath -Raw | ConvertFrom-Json
$observedJvmTests = [int]$jvmTestReport.observedTests
$jvmTestFloor = [int]$jvmTestReport.configuredFloor
if ($jvmTestReport.status -ne "passed" -or $jvmTestReport.failures -ne 0 -or $jvmTestReport.errors -ne 0) {
    throw "JVM test report is not passing: observed $observedJvmTests, configured floor $jvmTestFloor, failures $($jvmTestReport.failures), errors $($jvmTestReport.errors)."
}
if ($observedJvmTests -lt $jvmTestFloor) {
    throw "JVM test report is below its configured floor: observed $observedJvmTests, configured floor $jvmTestFloor."
}

& git -c "safe.directory=$GitSafeDirectory" -C $Root diff --quiet -- app/schemas
$schemaDiffExit = $LASTEXITCODE
if ($schemaDiffExit -eq 1) {
    throw "Room schema generation changed tracked files. Review and commit the schema update."
}
if ($schemaDiffExit -ne 0) {
    throw "Unable to verify tracked Room schema state (git exit $schemaDiffExit)."
}
$untrackedSchemas = @(
    & git -c "safe.directory=$GitSafeDirectory" -C $Root ls-files --others --exclude-standard -- app/schemas
)
if ($LASTEXITCODE -ne 0) {
    throw "Unable to verify untracked Room schema state."
}
if ($untrackedSchemas.Count -gt 0) {
    throw "Room schema generation created untracked files: $($untrackedSchemas -join ', ')"
}

# This lane exists to check the merged Play manifest, and its APK is discarded, so it opts out of
# the packaging-time signing guard rather than requiring the release keystore on every machine that
# runs the gate. Nothing is staged or published from here.
Invoke-Gradle -Arguments (@(
    "-PopenTaskerDistribution=play",
    "-PopenTaskerAllowUnsignedRelease=true",
    ":app:assembleRelease",
    ":app:verifyPlayManifestPolicy"
) + $CommonGradleArguments)

Invoke-Gradle -Arguments (@(
    "-PopenTaskerDistribution=fdroid",
    ":app:assembleRelease",
    ":app:verifyFdroidReadiness",
    ":app:verifyFdroidMetadata"
) + $CommonGradleArguments)

if (-not (Test-Path -LiteralPath $SbomPath -PathType Leaf)) {
    throw "CycloneDX SBOM was not produced at $SbomPath"
}

$sbom = Get-Content -LiteralPath $SbomPath -Raw | ConvertFrom-Json
$components = @($sbom.components | Where-Object { -not [string]::IsNullOrWhiteSpace($_.purl) })
if ($components.Count -eq 0) {
    throw "CycloneDX SBOM contains no queryable release components."
}

$queries = @($components | ForEach-Object {
    [ordered]@{
        package = [ordered]@{ purl = $_.purl }
    }
})
$requestBody = [ordered]@{ queries = $queries } | ConvertTo-Json -Depth 6 -Compress

# OSV guarantees querybatch response ordering matches the request ordering:
# https://google.github.io/osv.dev/post-v1-querybatch/
try {
    $osvResponse = Invoke-RestMethod `
        -Method Post `
        -Uri "https://api.osv.dev/v1/querybatch" `
        -ContentType "application/json" `
        -Body $requestBody `
        -TimeoutSec 120
} catch {
    throw "OSV advisory query failed; no incomplete security report will be accepted. $($_.Exception.Message)"
}

$results = @($osvResponse.results)
if ($results.Count -ne $components.Count) {
    throw "OSV returned $($results.Count) result(s) for $($components.Count) component(s)."
}

$findings = @()
for ($index = 0; $index -lt $results.Count; $index++) {
    $result = $results[$index]
    if ($null -ne $result.PSObject.Properties["next_page_token"]) {
        throw "OSV paginated the result for $($components[$index].purl); refusing an incomplete advisory report."
    }
    $vulnerabilitiesProperty = $result.PSObject.Properties["vulns"]
    if ($null -eq $vulnerabilitiesProperty) { continue }
    foreach ($vulnerability in @($vulnerabilitiesProperty.Value)) {
        if ($null -eq $vulnerability) { continue }
        $findings += [ordered]@{
            purl = $components[$index].purl
            id = $vulnerability.id
            modified = $vulnerability.modified
        }
    }
}

$AllowedAdvisoryIds = @()
$unapprovedFindings = @($findings | Where-Object { $_.id -notin $AllowedAdvisoryIds })
New-Item -ItemType Directory -Path $ReportDirectory -Force | Out-Null
$osvReport = [ordered]@{
    schemaVersion = 1
    generatedAtUtc = [DateTime]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ")
    endpoint = "https://api.osv.dev/v1/querybatch"
    componentCount = $components.Count
    advisoryCount = $findings.Count
    allowedAdvisoryIds = $AllowedAdvisoryIds
    findings = $findings
}
[System.IO.File]::WriteAllText(
    $OsvReportPath,
    ($osvReport | ConvertTo-Json -Depth 8) + [Environment]::NewLine,
    [System.Text.UTF8Encoding]::new($false)
)
if ($unapprovedFindings.Count -gt 0) {
    $ids = @($unapprovedFindings | ForEach-Object { $_.id } | Sort-Object -Unique)
    throw "OSV found unapproved advisories: $($ids -join ', '). See $OsvReportPath"
}

$summary = [ordered]@{
    schemaVersion = 1
    generatedAtUtc = [DateTime]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ")
    status = "passed"
    gradleBootstrapVerified = $true
    gradleDistributionSha256 = $ExpectedGradleDistributionSha256
    gradleWrapperJarSha256 = $ExpectedGradleWrapperJarSha256
    jvmTests = [ordered]@{
        observed = $observedJvmTests
        floor = $jvmTestFloor
        failures = [int]$jvmTestReport.failures
        errors = [int]$jvmTestReport.errors
        report = "build/reports/opentasker/jvm-test-count.json"
    }
    configurationCacheReused = $true
    resolvedComponentCount = $components.Count
    advisoryCount = $findings.Count
    reports = @(
        "build/reports/opentasker/sbom.cdx.json",
        "build/reports/opentasker/osv-advisories.json",
        "build/reports/opentasker/jvm-test-count.json",
        "app/build/reports/lint-results-debug.html"
    )
}
[System.IO.File]::WriteAllText(
    $SummaryPath,
    ($summary | ConvertTo-Json -Depth 5) + [Environment]::NewLine,
    [System.Text.UTF8Encoding]::new($false)
)

Write-Host "Local release gate passed. Reports: $ReportDirectory"
