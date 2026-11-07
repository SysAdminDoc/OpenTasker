#Requires -Version 7

<#
.SYNOPSIS
    Launches an OpenTasker APK on the oldest supported Android releases, over the previous
    release's data, and fails on any crash.

.DESCRIPTION
    Every automated UI lane and both test phones run API 35 or newer, so v0.2.94 shipped a crash
    that took down Setup and Settings on every Android 8 to 12 phone with the companion-device
    feature (issue #20) without anything here ever opening those screens on an old release.

    For each API level this boots a headless emulator on an explicit port, gives the image the
    companion-device feature a real Galaxy has (the stock image lacks it, which is why #20 never
    reproduced on a plain emulator), installs the previous release and opens it so there is real
    data to upgrade, installs the candidate over it, and visits every destination: Profiles, Tasks,
    Run Log, Setup, then Variables, Flow, Scenes, Inspector, Diagnostics and Settings from More.
    Any crash-buffer line for the app fails the run.

    Run it before tagging a release. It never touches a phone: every adb call names the emulator
    serial it started, and it refuses to reuse a port something else already answers on.

.PARAMETER Candidate
    The APK to test, normally the signed release build.

.PARAMETER Previous
    The APK to upgrade from. Defaults to the asset of the latest GitHub release, downloaded with gh.

.PARAMETER ApiLevels
    Android API levels to run. Each needs system-images;android-<n>;google_apis;x86_64 installed.

.PARAMETER BasePort
    First emulator console port. Each API level takes the next even port.

.EXAMPLE
    pwsh -NoProfile -File tools/smoke-old-api.ps1 -Candidate app/build/outputs/apk/release/app-release.apk

.EXAMPLE
    # Seeded failure: v0.2.94 crashes on Setup here, so this must exit non-zero.
    pwsh -NoProfile -File tools/smoke-old-api.ps1 -Candidate OpenTasker-v0.2.94.apk -Previous OpenTasker-v0.2.94.apk -ApiLevels 29
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Candidate,
    [string]$Previous,
    # Strings, split below: `pwsh -File ... -ApiLevels 26,29` arrives as the one string "26,29",
    # which an [int[]] parameter reads as 2629.
    [string[]]$ApiLevels = @('26', '29'),
    [int]$BasePort = 5590
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$Package = 'com.opentasker.app'
$Sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$Adb = Join-Path $Sdk 'platform-tools\adb.exe'
$Emulator = Join-Path $Sdk 'emulator\emulator.exe'
$AvdManager = Join-Path $Sdk 'cmdline-tools\latest\bin\avdmanager.bat'
$CompanionFeature = 'android.software.companion_device_setup'
$Primary = @('Profiles', 'Tasks', 'Run Log', 'Setup')
$Secondary = @('Variables', 'Flow', 'Scenes', 'Inspector', 'Diagnostics', 'Settings')
$AdbTimeoutSeconds = 180
# Serial -> the emulator process this run started on it.
$script:Owned = @{}

function Write-Step([string]$Message) { Write-Host "[smoke] $Message" }

# A plain function on purpose: an advanced one binds adb's own switches (monkey -p, install -r)
# to PowerShell's common parameters and fails before adb runs. Every call gets a deadline, because
# `adb shell` against an emulator that is still coming up can hang with no output at all.
function Invoke-Adb {
    $info = [Diagnostics.ProcessStartInfo]::new($Adb)
    $info.ArgumentList.Add('-s')
    foreach ($argument in $args) { $info.ArgumentList.Add([string]$argument) }
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $process = [Diagnostics.Process]::Start($info)
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    if (-not $process.WaitForExit($AdbTimeoutSeconds * 1000)) {
        try { $process.Kill($true) } catch { }
        return [pscustomobject]@{ Code = -1; Text = "adb $($args -join ' ') gave no answer in $AdbTimeoutSeconds s" }
    }
    $process.WaitForExit()
    [pscustomobject]@{ Code = $process.ExitCode; Text = ($stdout.Result + $stderr.Result).Trim() }
}

# Polls instead of `adb wait-for-device`, which blocks forever on an emulator whose adbd never
# comes up (it sits "offline"), so a wedged boot fails the run instead of hanging it.
function Wait-Boot([string]$Serial, [int]$TimeoutSeconds = 600) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $process = $script:Owned[$Serial]
        if ($process -and $process.HasExited) { throw "The emulator on $Serial exited while booting (code $($process.ExitCode))" }
        if ((Invoke-Adb $Serial get-state).Text -eq 'device' -and
            (Invoke-Adb $Serial shell getprop sys.boot_completed).Text -eq '1') {
            Start-Sleep -Seconds 3
            return
        }
        Start-Sleep -Seconds 3
    }
    throw "$Serial did not finish booting in $TimeoutSeconds s"
}

# `adb reboot` returns while the old boot still answers boot_completed=1, so wait for it to go away
# first; otherwise Wait-Boot returns at once and the next step lands on a device mid-shutdown.
function Restart-Device([string]$Serial) {
    Invoke-Adb $Serial reboot | Out-Null
    $deadline = (Get-Date).AddSeconds(90)
    while ((Get-Date) -lt $deadline -and (Invoke-Adb $Serial shell getprop sys.boot_completed).Text -eq '1') {
        Start-Sleep -Seconds 2
    }
    Wait-Boot $Serial
}

# SO_EXCLUSIVEADDRUSE, because a plain bind on Windows can succeed on a port another process holds.
function Test-PortFree([int]$Port) {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $Port)
    $listener.ExclusiveAddressUse = $true
    try { $listener.Start(); return $true } catch { return $false } finally { $listener.Stop() }
}

function Resolve-PreviousApk {
    if ($Previous) { return (Resolve-Path -LiteralPath $Previous).Path }
    $directory = Join-Path ([IO.Path]::GetTempPath()) 'opentasker-smoke-previous'
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
    Get-ChildItem -LiteralPath $directory -Filter '*.apk' | Remove-Item -Force
    Write-Step 'Downloading the latest release APK with gh'
    & gh release download --repo SysAdminDoc/OpenTasker --pattern 'OpenTasker-v*.apk' --dir $directory | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'gh release download failed; pass -Previous with a local APK' }
    $apk = Get-ChildItem -LiteralPath $directory -Filter 'OpenTasker-v*.apk' | Select-Object -First 1
    if (-not $apk) { throw 'The latest release has no OpenTasker-v*.apk asset' }
    return $apk.FullName
}

function Ensure-Avd([int]$Api) {
    $name = "OpenTasker_API${Api}_Smoke"
    $known = & $Emulator -list-avds
    if ($known -notcontains $name) {
        $image = "system-images;android-$Api;google_apis;x86_64"
        if (-not (Test-Path -LiteralPath (Join-Path $Sdk "system-images\android-$Api\google_apis\x86_64"))) {
            throw "Missing $image; install it with sdkmanager first"
        }
        Write-Step "Creating AVD $name"
        'no' | & $AvdManager create avd --name $name --package $image --device pixel_2 --force | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "avdmanager could not create $name" }
    }
    return $name
}

function Start-SmokeEmulator([string]$Avd, [int]$Port) {
    $serial = "emulator-$Port"
    if ((& $Adb devices) -match "^$serial\s" -or -not (Test-PortFree $Port) -or -not (Test-PortFree ($Port + 1))) {
        throw "$serial or its ports are already in use. Pick another -BasePort; this script only drives emulators it started."
    }
    Write-Step "Booting $Avd headless on $serial"
    $arguments = @('-avd', $Avd, '-port', "$Port", '-no-window', '-no-audio', '-no-boot-anim', '-no-snapshot', '-writable-system', '-gpu', 'swiftshader_indirect')
    $process = Start-Process -FilePath $Emulator -ArgumentList $arguments -WindowStyle Hidden -PassThru
    $script:Owned[$serial] = $process
    $instance = [pscustomobject]@{ Serial = $serial; Process = $process; Verified = $false }
    try {
        Wait-Boot $serial
        # Whatever answers on this serial must be the AVD this run launched, not an emulator that
        # won the port race; every later adb call, including the final kill, trusts the serial.
        $name = ((Invoke-Adb $serial emu avd name).Text -split "`r?`n")[0].Trim()
        if ($name -ne $Avd) { throw "$serial answers as '$name', not $Avd" }
        $instance.Verified = $true
    } catch {
        Stop-SmokeEmulator $instance
        throw
    }
    return $instance
}

function Stop-SmokeEmulator($Instance) {
    # A console kill only for an emulator this run verified as its own; otherwise end just the
    # process tree it started (emulator.exe and its qemu child), whoever holds the serial.
    if ($Instance.Verified) { Invoke-Adb $Instance.Serial emu kill | Out-Null }
    if (-not $Instance.Process.WaitForExit(30000)) { $Instance.Process.Kill($true) }
    $script:Owned.Remove($Instance.Serial)
}

# The stock google_apis image lacks the companion-device feature every Galaxy has. Without it,
# CompanionDeviceManager answers from the client side and the Android 8 to 12 manifest check that
# crashed #20 never runs. Adding the feature needs a writable system: disable verity once, reboot,
# remount, drop the feature file in, reboot.
function Enable-SystemWrites([string]$Serial) {
    Invoke-Adb $Serial root | Out-Null
    Start-Sleep -Seconds 3
    Wait-Boot $Serial
    return Invoke-Adb $Serial remount
}

function Add-CompanionFeature([string]$Serial) {
    if ((Invoke-Adb $Serial shell pm list features).Text -match [regex]::Escape($CompanionFeature)) { return }
    Write-Step "Adding $CompanionFeature to $Serial"
    $file = Join-Path ([IO.Path]::GetTempPath()) "$CompanionFeature.xml"
    Set-Content -LiteralPath $file -Encoding utf8NoBOM -Value "<?xml version=`"1.0`" encoding=`"utf-8`"?>`n<permissions>`n    <feature name=`"$CompanionFeature`" />`n</permissions>"
    $target = "/system/etc/permissions/$CompanionFeature.xml"
    Enable-SystemWrites $Serial | Out-Null
    $push = Invoke-Adb $Serial push $file $target
    if ($push.Code -ne 0) {
        # A fresh image needs verity off and a reboot before /system takes writes. On some images
        # remount even reports success the first time and only asks for that reboot in its output,
        # so the push is the real test.
        Invoke-Adb $Serial shell avbctl disable-verification | Out-Null
        Invoke-Adb $Serial disable-verity | Out-Null
        Restart-Device $Serial
        $remount = Enable-SystemWrites $Serial
        $push = Invoke-Adb $Serial push $file $target
        if ($push.Code -ne 0) { throw "Could not write /system on ${Serial}: $($remount.Text) $($push.Text)" }
    }
    Restart-Device $Serial
    if (-not ((Invoke-Adb $Serial shell pm list features).Text -match [regex]::Escape($CompanionFeature))) {
        throw "$Serial still does not report $CompanionFeature"
    }
}

function Get-UiNodes([string]$Serial) {
    # A dump can fail while a screen is still settling, and the file from the last screen would then
    # read as this one, so it goes first and a failed dump gets two more tries.
    foreach ($attempt in 1..3) {
        Invoke-Adb $Serial shell rm -f /sdcard/ot-smoke.xml | Out-Null
        Invoke-Adb $Serial shell uiautomator dump /sdcard/ot-smoke.xml | Out-Null
        # exec-out, not shell: a dump is one long line, and a shell read cut it at 4,096 bytes on API 26.
        $raw = (Invoke-Adb $Serial exec-out cat /sdcard/ot-smoke.xml).Text
        if ($raw -match '<hierarchy') {
            return ([xml]$raw.Substring($raw.IndexOf('<?xml'))).SelectNodes('//node')
        }
        Start-Sleep -Seconds 2
    }
    return @()
}

function Invoke-TapLabel([string]$Serial, [string]$Label) {
    $node = Get-UiNodes $Serial | Where-Object { $_.text -eq $Label -or $_.'content-desc' -eq $Label } | Select-Object -First 1
    if (-not $node) { return $false }
    if ($node.bounds -notmatch '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') { return $false }
    $x = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
    $y = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
    Invoke-Adb $Serial shell input tap $x $y | Out-Null
    Start-Sleep -Seconds 2
    return $true
}

# Only this app's crashes count. A system app falling over on an emulator is not a verdict on the
# candidate, so the buffer has to name our process: "Process: <pkg>," for a Java crash, or the
# ">>> <pkg> <<<" tombstone header for a native one.
function Get-AppCrashes([string]$Serial) {
    $text = (Invoke-Adb $Serial logcat -d -b crash).Text
    $marker = "Process: $([regex]::Escape($Package)),|>>> $([regex]::Escape($Package)) <<<"
    if ($text -notmatch $marker) { return @() }
    $lines = $text -split "`n"
    $start = [Array]::FindIndex($lines, [Predicate[string]] { param($line) $line -match $marker })
    $lines[[Math]::Max(0, $start - 1)..[Math]::Min($lines.Count - 1, $start + 12)]
}

function Test-AppAlive([string]$Serial) {
    (Invoke-Adb $Serial shell pidof $Package).Text -match '^\d+'
}

function Open-App([string]$Serial) {
    Invoke-Adb $Serial shell monkey -p $Package -c android.intent.category.LAUNCHER 1 | Out-Null
    Start-Sleep -Seconds 6
}

# A first launch may show the template picker or another sheet over the shell. Back out of
# anything that is not the bottom bar, without leaving the app.
function Reset-ToShell([string]$Serial) {
    for ($i = 0; $i -lt 3; $i++) {
        $labels = Get-UiNodes $Serial | ForEach-Object { $_.text; $_.'content-desc' }
        if ($labels -contains 'Profiles' -and $labels -contains 'More') { return $true }
        Invoke-Adb $Serial shell input keyevent KEYCODE_BACK | Out-Null
        Start-Sleep -Seconds 1
        if (-not (Test-AppAlive $Serial)) { Open-App $Serial }
    }
    return $false
}

function Invoke-Smoke([int]$Api, [int]$Port, [string]$PreviousApk, [string]$CandidateApk) {
    $failures = [Collections.Generic.List[string]]::new()
    $avd = Ensure-Avd $Api
    $instance = Start-SmokeEmulator $avd $Port
    $serial = $instance.Serial
    try {
        Add-CompanionFeature $serial
        Invoke-Adb $serial uninstall $Package | Out-Null

        Write-Step "API ${Api}: installing the previous release"
        $install = Invoke-Adb $serial install $PreviousApk
        if ($install.Code -ne 0) { throw "Installing the previous release failed: $($install.Text)" }
        Open-App $serial
        Reset-ToShell $serial | Out-Null
        Invoke-Adb $serial shell am force-stop $Package | Out-Null

        Write-Step "API ${Api}: installing the candidate over it"
        Invoke-Adb $serial logcat -c | Out-Null
        Invoke-Adb $serial logcat -b crash -c | Out-Null
        $install = Invoke-Adb $serial install -r $CandidateApk
        if ($install.Code -ne 0) { throw "Installing the candidate over the previous release failed: $($install.Text)" }
        Open-App $serial
        if (-not (Reset-ToShell $serial)) { $failures.Add("API ${Api}: the app never showed its bottom bar") }

        foreach ($label in $Primary) {
            if (-not (Invoke-TapLabel $serial $label)) { $failures.Add("API ${Api}: no '$label' in the bottom bar") }
            Start-Sleep -Seconds 2
            if (-not (Test-AppAlive $serial)) { $failures.Add("API ${Api}: the app died opening $label"); Open-App $serial }
        }
        foreach ($label in $Secondary) {
            Reset-ToShell $serial | Out-Null
            if (-not (Invoke-TapLabel $serial 'More')) { $failures.Add("API ${Api}: no More button"); continue }
            if (-not (Invoke-TapLabel $serial $label)) { $failures.Add("API ${Api}: no '$label' under More") }
            Start-Sleep -Seconds 2
            if (-not (Test-AppAlive $serial)) { $failures.Add("API ${Api}: the app died opening $label"); Open-App $serial }
        }

        $crashes = @(Get-AppCrashes $serial)
        if ($crashes.Count -gt 0) {
            $failures.Add("API ${Api}: crash buffer:`n" + ($crashes -join "`n"))
        }
    } finally {
        Stop-SmokeEmulator $instance
    }
    return , $failures
}

$levels = @($ApiLevels -split ',' | Where-Object { $_.Trim() } | ForEach-Object { [int]$_.Trim() })
if ($levels.Count -eq 0) { throw 'No API levels to run; pass -ApiLevels, for example 26,29' }
$candidateApk = (Resolve-Path -LiteralPath $Candidate).Path
$previousApk = Resolve-PreviousApk
Write-Step "Candidate: $candidateApk"
Write-Step "Previous:  $previousApk"

$all = [Collections.Generic.List[string]]::new()
$port = $BasePort
foreach ($api in $levels) {
    $result = Invoke-Smoke -Api $api -Port $port -PreviousApk $previousApk -CandidateApk $candidateApk
    foreach ($failure in $result) { $all.Add($failure) }
    Write-Step ("API {0}: {1}" -f $api, $(if ($result.Count -eq 0) { 'PASS' } else { 'FAIL' }))
    $port += 2
}

if ($all.Count -gt 0) {
    Write-Host ''
    $all | ForEach-Object { Write-Host "[smoke] FAIL $_" }
    exit 1
}
Write-Step "PASS on API $($levels -join ', ')"
exit 0
