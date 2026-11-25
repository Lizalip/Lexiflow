#Requires -Version 7.0
param(
    [string]$Sdk = $env:ANDROID_HOME,
    [string]$Jdk = $env:JAVA_HOME,
    [string]$TestClass,
    [switch]$Capacity,
    [switch]$Evidence,
    [switch]$RequireAll
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
if (!$Sdk) { $Sdk = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
if (!$Jdk) { $Jdk = 'C:/Program Files/Android/Android Studio/jbr' }
$adb = Join-Path $Sdk 'platform-tools/adb.exe'
if (!(Test-Path -LiteralPath $adb) -or !(Test-Path -LiteralPath (Join-Path $Jdk 'bin/java.exe'))) {
    throw 'Set -Sdk and -Jdk to the existing Android SDK and JDK.'
}
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
Push-Location -LiteralPath $root
try {
    $devices = @(& $adb devices -l)
    if ($LASTEXITCODE) { throw 'ADB discovery failed.' }
    $serials = @($devices | ForEach-Object {
        if ($_ -match '^(\S+)\s+device\b.*\bmodel:(SM_\S+|Galaxy_\S+)') { $Matches[1] }
    })
    if ($serials.Count -ne 1) { throw 'Expected one authorized Samsung device.' }
    $serial = $serials[0]
    if ((& $adb -s $serial shell getprop ro.product.manufacturer).Trim() -ne 'samsung') {
        throw 'Samsung manufacturer check failed.'
    }
    $app = 'app.lexiflow.runtimecheck'
    $packages = @($app,"$app.test")
    foreach ($package in $packages) {
        if ((& $adb -s $serial shell pm path $package) -match '^package:') {
            throw 'An existing isolated profile is installed. It must be preserved.'
        }
    }
    $env:ANDROID_HOME = $Sdk
    $env:JAVA_HOME = $Jdk
    $env:ANDROID_SERIAL = $serial
    $tasks = @('--gradle-user-home','.gradle/verification','--offline','--project-cache-dir',
        '.gradle/samsung-isolated','-Plexiflow.isolatedRuntimeCheck=true',':app:assembleDebug',':app:assembleDebugAndroidTest')
    if ($Capacity) { $tasks += '-Plexiflow.capacityFixture=true' }
    & ./gradlew.bat @tasks
    if ($LASTEXITCODE) { throw 'Isolated APK build failed.' }

    $aapt = Get-ChildItem (Join-Path $Sdk 'build-tools') -Filter aapt.exe -File -Recurse |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (!$aapt) { throw 'Installed Android build-tools do not provide aapt.' }
    $apk = '.gradle/runtimecheck-build/app/outputs/apk'
    $main = Join-Path $apk 'debug/app-debug.apk'
    $tests = Join-Path $apk 'androidTest/debug/app-debug-androidTest.apk'
    foreach ($pair in @(@($main,$app),@($tests,"$app.test"))) {
        $packageLine = (& $aapt.FullName dump badging $pair[0] | Select-String '^package: name=').Line
        if ($packageLine -notlike "package: name='$($pair[1])'*") { throw 'Unexpected APK identity.' }
    }
    $policy = (& $adb -s $serial shell dumpsys window policy | Select-String 'showing=|mShowingLockscreen|mKeyguardShowing') -join ' '
    if ($policy -match '(showing|mShowingLockscreen|mKeyguardShowing)=true') { throw 'Samsung is locked.' }

    & $adb -s $serial install-multi-package -t $main $tests | ForEach-Object { $_.Replace($serial,'[tablet]') }
    if ($LASTEXITCODE) { throw 'Atomic isolated install failed.' }
    try {
        $start = [long](& $adb -s $serial shell date '+%s').Trim()
        $arguments = @()
        if ($TestClass) { $arguments = @('-e','class',$TestClass) }
        if ($Evidence) { $arguments += @('-e','lexiflowEvidence','true') }
        $result = @(& $adb -s $serial shell am instrument -w -r @arguments "$app.test/androidx.test.runner.AndroidJUnitRunner")
        $folder = New-Item -ItemType Directory -Path '.gradle/samsung-evidence' -Force
        $file = Join-Path $folder.FullName ("instrumentation-"+(Get-Date -Format 'yyyyMMdd-HHmmss-fff')+".txt")
        $result | ForEach-Object { $_.Replace($serial,'[tablet]') } | Set-Content -LiteralPath $file -Encoding utf8
        $counts = @{ passed=0; failed=0; skipped=0; started=0 }
        foreach ($line in $result) {
            switch -Regex ($line) {
                '^INSTRUMENTATION_STATUS_CODE: 1$' { $counts.started++ }
                '^INSTRUMENTATION_STATUS_CODE: 0$' { $counts.passed++ }
                '^INSTRUMENTATION_STATUS_CODE: -[12]$' { $counts.failed++ }
                '^INSTRUMENTATION_STATUS_CODE: -[34]$' { $counts.skipped++ }
            }
        }
        $crashes = @(& $adb -s $serial logcat -d -b crash -v epoch | Where-Object {
            $_ -match '^([0-9]+)\.' -and [long]$Matches[1] -ge $start -and
                $_ -match 'Process: app\.lexiflow\.runtimecheck(?:\.test)?,'
        }).Count
        $counts.crash_process_lines = $crashes
        $counts.report = $file
        $counts | ConvertTo-Json
        if (!$counts.started -or ($result -join ' ') -notmatch 'OK \([0-9]+ tests?\)' -or
            $counts.failed -or $crashes -or ($RequireAll -and $counts.skipped)) {
            throw 'Not all required Samsung checks completed successfully. See the local aggregate report.'
        }
        if ($Evidence) {
            $frames = New-Item -ItemType Directory -Path (Join-Path $folder.FullName ("frames-"+(Get-Date -Format 'yyyyMMdd-HHmmss-fff')))
            foreach ($name in @('01-dictionary','02-results','03-entry','04-vocabulary','05-collection',
                '06-review-front','07-review-answer','08-progress','09-settings')) {
                $png = "$name.png"
                $processInfo = [Diagnostics.ProcessStartInfo]::new($adb)
                $processInfo.UseShellExecute = $false
                $processInfo.RedirectStandardOutput = $true
                foreach ($argument in @('-s',$serial,'exec-out','run-as',$app,'cat',"cache/lexiflow-evidence/$png")) {
                    $processInfo.ArgumentList.Add($argument)
                }
                $process = [Diagnostics.Process]::Start($processInfo)
                try {
                    $destination = [IO.File]::Open((Join-Path $frames.FullName $png),[IO.FileMode]::CreateNew)
                    try { $process.StandardOutput.BaseStream.CopyTo($destination) }
                    finally { $destination.Dispose() }
                    $process.WaitForExit()
                    if ($process.ExitCode) { throw "App frame export failed for $png." }
                } finally { $process.Dispose() }
            }
            "App-only screenshots: $($frames.FullName)"
        }
    } finally {
        foreach ($package in $packages) { & $adb -s $serial uninstall $package }
    }
} finally {
    Pop-Location
}
