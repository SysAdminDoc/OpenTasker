Param(
    [switch]$BuildRelease,
    [switch]$RunFdroidLint,
    [switch]$RunFdroidBuild,
    [string]$UpstreamApk,
    [switch]$SkipTagCheck,
    [string]$FdroidCommand,
    [string]$AndroidHome,
    [string]$JavaHome
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$AppBuild = Join-Path $Root "app\build.gradle.kts"
$MetadataFile = Join-Path $Root "fdroid\metadata\com.opentasker.app.yml"
$UnsignedReleaseApk = Join-Path $Root "app\build\outputs\apk\release\app-release-unsigned.apk"

function Read-RequiredText {
    param([string]$Path)

    if (-not (Test-Path -LiteralPath $Path)) {
        throw "Required file is missing: $Path"
    }
    return Get-Content -LiteralPath $Path -Raw
}

function Get-FirstMatch {
    param(
        [string]$Text,
        [string]$Pattern,
        [string]$Description
    )

    $match = [regex]::Match($Text, $Pattern, [System.Text.RegularExpressions.RegexOptions]::Multiline)
    if (-not $match.Success) {
        throw "Could not read $Description"
    }
    return $match.Groups[1].Value.Trim()
}

function Get-MetadataValues {
    param(
        [string]$Text,
        [string]$Key
    )

    $pattern = "(?m)^\s*(?:-\s*)?$([regex]::Escape($Key)):\s*(.+?)\s*$"
    return [regex]::Matches($Text, $pattern) | ForEach-Object {
        $_.Groups[1].Value.Trim().Trim('"', "'")
    }
}

function Require-MetadataValue {
    param(
        [string]$Text,
        [string]$Key,
        [string]$Expected
    )

    $values = @(Get-MetadataValues -Text $Text -Key $Key)
    if ($values -notcontains $Expected) {
        $found = if ($values.Count -eq 0) { "<missing>" } else { $values -join ", " }
        throw "F-Droid metadata key '$Key' expected '$Expected' but found $found"
    }
}

function ConvertTo-ProcessArgument {
    param([string]$Argument)

    if ($Argument -notmatch '[\s"]') {
        return $Argument
    }
    return '"' + ($Argument -replace '"', '\"') + '"'
}

function Invoke-Checked {
    param(
        [string]$FilePath,
        [string[]]$ArgumentList,
        [string]$WorkingDirectory = $Root,
        [hashtable]$Environment = @{},
        [switch]$TreatFdroidOutputAsAuthoritative
    )

    Write-Host "> $FilePath $($ArgumentList -join ' ')"
    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = $FilePath
    $psi.Arguments = (($ArgumentList | ForEach-Object { ConvertTo-ProcessArgument -Argument $_ }) -join " ")
    $psi.WorkingDirectory = $WorkingDirectory
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    foreach ($key in $Environment.Keys) {
        $psi.EnvironmentVariables[$key] = [string]$Environment[$key]
    }

    $process = [System.Diagnostics.Process]::Start($psi)
    $stdout = $process.StandardOutput.ReadToEnd()
    $stderr = $process.StandardError.ReadToEnd()
    $process.WaitForExit()

    if ($stdout.Trim().Length -gt 0) {
        Write-Host $stdout.TrimEnd()
    }
    if ($stderr.Trim().Length -gt 0) {
        Write-Host $stderr.TrimEnd()
    }

    $combined = "$stdout`n$stderr"
    if ($process.ExitCode -ne 0) {
        throw "Command failed with exit code $($process.ExitCode): $FilePath $($ArgumentList -join ' ')"
    }
    if ($TreatFdroidOutputAsAuthoritative -and $combined -match "(?m)(Could not build app|Build for app .* failed|[0-9]+ build failed)") {
        throw "fdroidserver reported a failed build despite process exit code 0"
    }
}

function Find-FdroidCommand {
    if (-not [string]::IsNullOrWhiteSpace($FdroidCommand)) {
        return $FdroidCommand
    }

    $pathCommand = Get-Command "fdroid" -ErrorAction SilentlyContinue
    if ($null -ne $pathCommand) {
        return $pathCommand.Source
    }

    $localWindows = Join-Path $Root "build\fdroid-venv\Scripts\fdroid.exe"
    if (Test-Path -LiteralPath $localWindows) {
        return $localWindows
    }

    throw "fdroidserver was not found. Install fdroidserver or pass -FdroidCommand."
}

function Get-NormalizedApkDigest {
    param([string]$Path)

    if (-not (Test-Path -LiteralPath $Path)) {
        throw "APK does not exist: $Path"
    }

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $sha = [System.Security.Cryptography.SHA256]::Create()
    $encoding = [System.Text.Encoding]::UTF8
    $zip = [System.IO.Compression.ZipFile]::OpenRead($Path)
    try {
        $entries = $zip.Entries |
            Where-Object { $_.FullName -and $_.FullName -notmatch "^META-INF/" } |
            Sort-Object FullName
        $buffer = New-Object byte[] 65536

        foreach ($entry in $entries) {
            $header = $encoding.GetBytes("entry:$($entry.FullName)`nlength:$($entry.Length)`n")
            [void]$sha.TransformBlock($header, 0, $header.Length, $null, 0)
            $stream = $entry.Open()
            try {
                while (($read = $stream.Read($buffer, 0, $buffer.Length)) -gt 0) {
                    [void]$sha.TransformBlock($buffer, 0, $read, $null, 0)
                }
            } finally {
                $stream.Dispose()
            }
            $separator = $encoding.GetBytes("`n")
            [void]$sha.TransformBlock($separator, 0, $separator.Length, $null, 0)
        }

        [void]$sha.TransformFinalBlock([byte[]]::new(0), 0, 0)
        return ($sha.Hash | ForEach-Object { $_.ToString("x2") }) -join ""
    } finally {
        $zip.Dispose()
        $sha.Dispose()
    }
}

$gradleText = Read-RequiredText -Path $AppBuild
$metadataText = Read-RequiredText -Path $MetadataFile

$versionName = Get-FirstMatch -Text $gradleText -Pattern 'val\s+appVersionName\s*=\s*"([^"]+)"' -Description "Gradle appVersionName"
$versionCode = Get-FirstMatch -Text $gradleText -Pattern 'val\s+appVersionCode\s*=\s*(\d+)' -Description "Gradle appVersionCode"

Require-MetadataValue -Text $metadataText -Key "versionName" -Expected $versionName
Require-MetadataValue -Text $metadataText -Key "versionCode" -Expected $versionCode
Require-MetadataValue -Text $metadataText -Key "CurrentVersion" -Expected $versionName
Require-MetadataValue -Text $metadataText -Key "CurrentVersionCode" -Expected $versionCode
Require-MetadataValue -Text $metadataText -Key "Changelog" -Expected "https://github.com/SysAdminDoc/OpenTasker/releases"

$metadataCommits = @(Get-MetadataValues -Text $metadataText -Key "commit")
if ($metadataCommits.Count -ne 1) {
    throw "Expected exactly one metadata commit, found $($metadataCommits.Count)"
}
$metadataCommit = $metadataCommits[0]
$tagName = "v$versionName"
if ($metadataCommit -cne $tagName) {
    throw "Metadata commit must be the release tag $tagName, found $metadataCommit"
}
if ($metadataText -notmatch "openTaskerDistribution=fdroid") {
    throw "Metadata must include gradleprops openTaskerDistribution=fdroid"
}
if ($metadataText -notmatch [regex]::Escape(":app:verifyFdroidReadiness")) {
    throw "Metadata must run :app:verifyFdroidReadiness"
}

if (-not $SkipTagCheck) {
    Invoke-Checked -FilePath "git" -ArgumentList @("rev-parse", "--verify", "$tagName^{commit}")
    $tagCommit = (& git -C $Root rev-list -n 1 $tagName).Trim()
    # F-Droid builds the tag, but the signed APKs are built at the version-bump commit recorded in
    # tools/release-truth.json, and the tag lands on the metadata sync commit after it. The two
    # builds only match if nothing the APK is built from changed in between.
    $truth = Read-RequiredText -Path (Join-Path $Root "tools\release-truth.json") | ConvertFrom-Json
    $artifactCommit = [string]$truth.requiredArtifactCommit
    if ($artifactCommit -cnotmatch "^[0-9a-f]{40}$") {
        throw "tools/release-truth.json requiredArtifactCommit must be a full SHA: $artifactCommit"
    }
    $metadataCommit = $artifactCommit
    if ($tagCommit -ne $metadataCommit) {
        & git -C $Root merge-base --is-ancestor $metadataCommit $tagCommit
        if ($LASTEXITCODE -ne 0) {
            throw "Tag $tagName points to $tagCommit, which does not descend from build commit $metadataCommit"
        }
        $allowedSyncPaths = @("tools/release-truth.json")
        $changedPaths = (& git -C $Root diff --name-only $metadataCommit $tagCommit) |
            Where-Object { $_ -ne "" }
        $unexpected = $changedPaths | Where-Object {
            ($_ -notlike "fdroid/metadata/*.yml") -and ($allowedSyncPaths -notcontains $_)
        }
        if ($unexpected) {
            throw ("Tag $tagName adds non-metadata changes after the build commit $metadataCommit`: " +
                ($unexpected -join ", "))
        }
    }
}

if ($BuildRelease) {
    $gradlew = Join-Path $Root "gradlew.bat"
    Invoke-Checked -FilePath $gradlew -ArgumentList @(
        "-PopenTaskerDistribution=fdroid",
        ":app:assembleRelease",
        ":app:verifyFdroidReadiness",
        ":app:verifyFdroidMetadata",
        "--console=plain"
    )
}

if ($RunFdroidLint -or $RunFdroidBuild) {
    $fdroid = Find-FdroidCommand
    $fdroidDir = Join-Path $Root "fdroid"
    $env = @{}
    if (-not [string]::IsNullOrWhiteSpace($AndroidHome)) {
        $env["ANDROID_HOME"] = $AndroidHome
        $env["ANDROID_SDK_ROOT"] = $AndroidHome
    }
    if (-not [string]::IsNullOrWhiteSpace($JavaHome)) {
        $env["JAVA_HOME"] = $JavaHome
    }

    if ($RunFdroidLint) {
        Invoke-Checked -FilePath $fdroid -ArgumentList @("lint", "com.opentasker.app") -WorkingDirectory $fdroidDir -Environment $env
    }
    if ($RunFdroidBuild) {
        Invoke-Checked -FilePath $fdroid -ArgumentList @("build", "--no-tarball", "com.opentasker.app:$versionCode") -WorkingDirectory $fdroidDir -Environment $env -TreatFdroidOutputAsAuthoritative
    }
}

if (-not [string]::IsNullOrWhiteSpace($UpstreamApk)) {
    if (-not (Test-Path -LiteralPath $UnsignedReleaseApk)) {
        throw "Local unsigned release APK is missing. Run with -BuildRelease first."
    }
    $localDigest = Get-NormalizedApkDigest -Path $UnsignedReleaseApk
    $upstreamDigest = Get-NormalizedApkDigest -Path $UpstreamApk
    if ($localDigest -ne $upstreamDigest) {
        throw "APK payload comparison failed. Local=$localDigest Upstream=$upstreamDigest"
    }
    Write-Host "APK payload comparison passed: $localDigest"
}

Write-Host "F-Droid release verification passed for v$versionName ($versionCode), metadata commit $metadataCommit"
