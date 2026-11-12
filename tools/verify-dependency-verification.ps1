[CmdletBinding()]
param(
    [switch]$UpdateOrigins
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$Root = Split-Path -Parent $PSScriptRoot
$MetadataPath = Join-Path $Root "gradle\verification-metadata.xml"
$VerificationNamespace = "https://schema.gradle.org/dependency-verification"
$HttpClient = [System.Net.Http.HttpClient]::new()
$HttpClient.Timeout = [TimeSpan]::FromSeconds(30)
$HttpClient.DefaultRequestHeaders.UserAgent.ParseAdd("OpenTasker-dependency-verification/1")
$RemoteCache = @{}

function Get-RemoteBytes {
    param([Parameter(Mandatory = $true)][string]$Url)

    if ($RemoteCache.ContainsKey($Url)) {
        return $RemoteCache[$Url]
    }

    try {
        $response = $HttpClient.GetAsync($Url).GetAwaiter().GetResult()
        if (-not $response.IsSuccessStatusCode) {
            $RemoteCache[$Url] = $null
            $response.Dispose()
            return $null
        }
        $bytes = $response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
        $response.Dispose()
        $RemoteCache[$Url] = $bytes
        return $bytes
    } catch {
        $RemoteCache[$Url] = $null
        return $null
    }
}

function Get-Text {
    param([byte[]]$Bytes)

    if ($null -eq $Bytes) { return $null }
    return [Text.Encoding]::UTF8.GetString($Bytes).Trim()
}

function Get-ArtifactHash {
    param([Parameter(Mandatory = $true)][byte[]]$Bytes)

    $sha256 = [Security.Cryptography.SHA256]::Create()
    try {
        return (($sha256.ComputeHash($Bytes) | ForEach-Object { $_.ToString("x2") }) -join "")
    } finally {
        $sha256.Dispose()
    }
}

function Get-PublishedHash {
    param([byte[]]$Bytes)

    $text = Get-Text $Bytes
    if ($null -eq $text) { return $null }
    $match = [Regex]::Match($text, "(?i)\b([0-9a-f]{64})\b")
    if (-not $match.Success) { return $null }
    return $match.Groups[1].Value.ToLowerInvariant()
}

function Get-ArtifactNames {
    param(
        [Parameter(Mandatory = $true)]$Component,
        [Parameter(Mandatory = $true)]$Artifact
    )

    $names = [Collections.Generic.List[string]]::new()
    $names.Add($Artifact.name)
    $extension = [IO.Path]::GetExtension($Artifact.name)
    if ($extension -eq ".aar" -and $Artifact.name -notmatch [Regex]::Escape($Component.version)) {
        $names.Add("$($Component.name)-$($Component.version)$extension")
    }
    return @($names | Select-Object -Unique)
}

function Get-RepositoryCandidates {
    param([Parameter(Mandatory = $true)][string]$Group)

    $googleGroup = $Group.StartsWith("androidx.") -or
        $Group.StartsWith("com.android.") -or
        $Group.StartsWith("com.google.android.")
    $google = [pscustomobject]@{ Name = "Google Maven"; Base = "https://dl.google.com/dl/android/maven2/" }
    $central = [pscustomobject]@{ Name = "Maven Central"; Base = "https://repo.maven.apache.org/maven2/" }
    $pluginPortal = [pscustomobject]@{ Name = "Gradle Plugin Portal"; Base = "https://plugins.gradle.org/m2/" }
    if ($googleGroup) {
        return @($google, $central, $pluginPortal)
    }
    return @($central, $pluginPortal, $google)
}

function Get-ArtifactPath {
    param(
        [Parameter(Mandatory = $true)]$Component,
        [Parameter(Mandatory = $true)][string]$ArtifactName
    )

    $groupPath = $Component.group.Replace(".", "/")
    return "$groupPath/$($Component.name)/$($Component.version)/$ArtifactName"
}

function Get-ModuleDeclaredUrls {
    param(
        [Parameter(Mandatory = $true)]$Component,
        [Parameter(Mandatory = $true)]$Artifact,
        [Parameter(Mandatory = $true)]$Repository
    )

    # Gradle module metadata can serve a variant's file from another version's directory:
    # guava's -android module points its JVM variant at ../<version>-jre/guava-<version>-jre.jar.
    if ($Artifact.name -match [Regex]::Escape($Component.version)) { return @() }
    $versionUrl = "$($Repository.Base)$($Component.group.Replace('.', '/'))/$($Component.name)/$($Component.version)/"
    $text = Get-Text (Get-RemoteBytes "$versionUrl$($Component.name)-$($Component.version).module")
    if ($null -eq $text) { return @() }
    $module = $text | ConvertFrom-Json
    if ($module.PSObject.Properties.Name -notcontains "variants") { return @() }
    $urls = foreach ($variant in @($module.variants)) {
        if ($variant.PSObject.Properties.Name -notcontains "files") { continue }
        foreach ($file in @($variant.files)) {
            if ($file.name -eq $Artifact.name -and $file.url -ne $file.name) {
                [Uri]::new([Uri]$versionUrl, [string]$file.url).AbsoluteUri
            }
        }
    }
    return @($urls | Select-Object -Unique)
}

function Get-CandidateUrls {
    param(
        [Parameter(Mandatory = $true)]$Component,
        [Parameter(Mandatory = $true)]$Artifact,
        [Parameter(Mandatory = $true)]$Repository
    )

    $urls = [Collections.Generic.List[string]]::new()
    foreach ($artifactName in (Get-ArtifactNames $Component $Artifact)) {
        $urls.Add("$($Repository.Base)$(Get-ArtifactPath $Component $artifactName)")
    }
    foreach ($url in (Get-ModuleDeclaredUrls $Component $Artifact $Repository)) {
        $urls.Add($url)
    }
    return @($urls | Select-Object -Unique)
}

function Get-OriginRepository {
    param([Parameter(Mandatory = $true)][string]$Origin)

    if ($Origin -match "^OpenTasker: (Google Maven|Maven Central|Gradle Plugin Portal) ") {
        switch ($Matches[1]) {
            "Google Maven" { return [pscustomobject]@{ Name = "Google Maven"; Base = "https://dl.google.com/dl/android/maven2/" } }
            "Maven Central" { return [pscustomobject]@{ Name = "Maven Central"; Base = "https://repo.maven.apache.org/maven2/" } }
            "Gradle Plugin Portal" { return [pscustomobject]@{ Name = "Gradle Plugin Portal"; Base = "https://plugins.gradle.org/m2/" } }
        }
    }
    return $null
}

function Test-PgpSignatureFile {
    param([byte[]]$Bytes)

    $text = Get-Text $Bytes
    return $null -ne $text -and $text.StartsWith("-----BEGIN PGP SIGNATURE-----") -and
        $text.Contains("-----END PGP SIGNATURE-----")
}

function Resolve-Evidence {
    param(
        [Parameter(Mandatory = $true)]$Component,
        [Parameter(Mandatory = $true)]$Artifact,
        [Parameter(Mandatory = $true)][string]$ExpectedHash,
        [string]$ExistingOrigin
    )

    $repositories = if ([string]::IsNullOrWhiteSpace($ExistingOrigin)) {
        Get-RepositoryCandidates $Component.group
    } else {
        $repository = Get-OriginRepository $ExistingOrigin
        if ($null -eq $repository) {
            throw "Unknown dependency verification origin '$ExistingOrigin'."
        }
        @($repository)
    }
    $originMode = if ($ExistingOrigin -match "published \.sha256") { "sha256" } elseif ($ExistingOrigin -match "published \.asc") { "asc" } else { "direct" }

    foreach ($repository in $repositories) {
        foreach ($artifactUrl in (Get-CandidateUrls $Component $Artifact $repository)) {
            if ($originMode -in @("sha256", "direct") -and [string]::IsNullOrWhiteSpace($ExistingOrigin)) {
                $publishedHash = Get-PublishedHash (Get-RemoteBytes "$artifactUrl.sha256")
                if ($null -ne $publishedHash) {
                    if ($publishedHash -ne $ExpectedHash) {
                        throw "Upstream SHA-256 mismatch for $($Component.group):$($Component.name):$($Component.version) at $artifactUrl."
                    }
                    return "OpenTasker: $($repository.Name) published .sha256"
                }
            } elseif ($originMode -eq "sha256") {
                $publishedHash = Get-PublishedHash (Get-RemoteBytes "$artifactUrl.sha256")
                if ($null -eq $publishedHash) {
                    continue
                }
                if ($publishedHash -ne $ExpectedHash) {
                    throw "Upstream SHA-256 mismatch for $artifactUrl."
                }
                return $ExistingOrigin
            }

            if ($originMode -in @("asc", "direct") -and [string]::IsNullOrWhiteSpace($ExistingOrigin)) {
                $signature = Get-RemoteBytes "$artifactUrl.asc"
                if (Test-PgpSignatureFile $signature) {
                    $actualHash = Get-ArtifactHash (Get-RemoteBytes $artifactUrl)
                    if ($actualHash -eq $ExpectedHash) {
                        return "OpenTasker: $($repository.Name) published .asc + artifact SHA-256"
                    }
                    throw "Artifact SHA-256 mismatch for the upstream-signed $artifactUrl."
                }
            } elseif ($originMode -eq "asc") {
                $signature = Get-RemoteBytes "$artifactUrl.asc"
                if (-not (Test-PgpSignatureFile $signature)) {
                    continue
                }
                $actualHash = Get-ArtifactHash (Get-RemoteBytes $artifactUrl)
                if ($actualHash -ne $ExpectedHash) {
                    throw "Artifact SHA-256 mismatch for the upstream-signed $artifactUrl."
                }
                return $ExistingOrigin
            }
        }
    }

    if (-not [string]::IsNullOrWhiteSpace($ExistingOrigin) -and $originMode -ne "direct") {
        throw "Recorded dependency verification evidence is no longer available for $($Component.group):$($Component.name):$($Component.version) $($Artifact.name)."
    }

    foreach ($repository in $repositories) {
        foreach ($artifactUrl in (Get-CandidateUrls $Component $Artifact $repository)) {
            Write-Verbose "Checking direct upstream artifact $artifactUrl"
            $bytes = Get-RemoteBytes $artifactUrl
            if ($null -eq $bytes) {
                Write-Verbose "Upstream artifact unavailable at $artifactUrl"
                continue
            }
            $actualHash = Get-ArtifactHash $bytes
            Write-Verbose "Upstream artifact hash $actualHash"
            if ($actualHash -eq $ExpectedHash) {
                return "OpenTasker: $($repository.Name) direct artifact SHA-256 (no upstream .sha256/.asc)"
            }
            throw "Upstream artifact SHA-256 mismatch for $artifactUrl; expected $ExpectedHash, actual $actualHash."
        }
    }

    throw "No upstream verification evidence found for $($Component.group):$($Component.name):$($Component.version) $($Artifact.name)."
}

if (-not (Test-Path -LiteralPath $MetadataPath -PathType Leaf)) {
    throw "Dependency verification metadata not found at $MetadataPath"
}

$metadata = [xml](Get-Content -LiteralPath $MetadataPath -Raw)
$namespace = New-Object System.Xml.XmlNamespaceManager($metadata.NameTable)
$namespace.AddNamespace("v", $VerificationNamespace)
$verifyMetadata = $metadata.SelectSingleNode("//v:configuration/v:verify-metadata", $namespace)
$verifySignatures = $metadata.SelectSingleNode("//v:configuration/v:verify-signatures", $namespace)
if ($null -eq $verifyMetadata -or $verifyMetadata.InnerText -ne "true") {
    throw "Dependency verification must enforce metadata verification."
}
if ($null -eq $verifySignatures -or $verifySignatures.InnerText -ne "true") {
    throw "Dependency verification must enforce signature verification."
}
if (@($metadata.SelectNodes("//v:trusted-artifacts", $namespace)).Count -gt 0) {
    throw "Blanket trusted-artifacts entries are forbidden."
}
if (@($metadata.SelectNodes("//v:trusted-key", $namespace)).Count -eq 0) {
    throw "Dependency verification must contain an explicit trusted-key set."
}

$shaNodes = @($metadata.SelectNodes("//v:sha256", $namespace))
if ($shaNodes.Count -eq 0) { throw "Dependency verification contains no SHA-256 entries." }
$generated = @($shaNodes | Where-Object { $_.origin -like "Generated by Gradle*" })
if (-not $UpdateOrigins -and $generated.Count -gt 0) {
    throw "Dependency verification contains $($generated.Count) Gradle-generated origin(s); review each upstream source before committing."
}

$verified = 0
$provenanceCounts = @{}
foreach ($component in @($metadata.SelectNodes("//v:component", $namespace))) {
    foreach ($artifact in @($component.artifact)) {
        $sha = $artifact.SelectSingleNode("v:sha256", $namespace)
        if ($null -eq $sha -or $sha.value -notmatch "^[0-9a-f]{64}$") {
            throw "Missing or malformed SHA-256 for $($component.group):$($component.name):$($component.version) $($artifact.name)."
        }
        if (-not $UpdateOrigins -and [string]::IsNullOrWhiteSpace($sha.origin)) {
            throw "Checksum provenance is missing for $($component.group):$($component.name):$($component.version) $($artifact.name)."
        }
        $origin = Resolve-Evidence $component $artifact $sha.value $(if ($UpdateOrigins) { $null } else { $sha.origin })
        if ($UpdateOrigins) { $sha.SetAttribute("origin", $origin) }
        $provenanceCounts[$origin] = 1 + ($provenanceCounts[$origin] | ForEach-Object { $_ })
        $verified++
    }
}

if ($UpdateOrigins) {
    $settings = New-Object System.Xml.XmlWriterSettings
    $settings.Encoding = New-Object System.Text.UTF8Encoding($false)
    $settings.Indent = $true
    $settings.IndentChars = "   "
    $writer = [System.Xml.XmlWriter]::Create($MetadataPath, $settings)
    try { $metadata.Save($writer) } finally { $writer.Dispose() }
}

Write-Host "Dependency verification passed for $verified checksum entries."
foreach ($entry in $provenanceCounts.GetEnumerator() | Sort-Object Name) {
    Write-Host ("  {0}: {1}" -f $entry.Name, $entry.Value)
}
