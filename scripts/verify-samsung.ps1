#Requires -Version 7.0
param(
    [string]$Sdk = $env:ANDROID_HOME,
    [string]$Jdk = $env:JAVA_HOME,
    [string]$TestClass,
    [switch]$Capacity,
    [switch]$DesignPreview,
    [switch]$Research,
    [switch]$LeaveResearchApp,
    [string]$Python,
    [switch]$Evidence,
    [switch]$RequireAll
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
if (($Research -and ($DesignPreview -or $Capacity -or $TestClass)) -or ($LeaveResearchApp -and !$Research)) {
    throw 'The real dictionary workflow uses a separate research profile and test sequence.'
}
if ($Research) {
    $TestClass = 'app.lexiflow.ui.RealResearchFlowTest#usesRealDictionaryAndPersistsPersonalData'
}
if ($DesignPreview) {
    $previewClass = 'app.lexiflow.ui.DesignPreviewTest'
    if ($TestClass -and $TestClass -ne $previewClass) { throw 'Design preview requires its own test class.' }
    $TestClass = $previewClass
}
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
    $app = if ($Research) { 'app.lexiflow.research' } else { 'app.lexiflow.runtimecheck' }
    $packages = @($app,"$app.test")
    foreach ($package in $packages) {
        if ((& $adb -s $serial shell pm path $package) -match '^package:') {
            throw 'An existing isolated profile is installed. It must be preserved.'
        }
    }
    $env:ANDROID_HOME = $Sdk
    $env:JAVA_HOME = $Jdk
    $env:ANDROID_SERIAL = $serial
    $tasks = @('--gradle-user-home','.gradle/verification','--offline','--project-cache-dir')
    if ($Research) {
        $tasks += @('.gradle/samsung-research','-Plexiflow.research=true')
        if ($Python) { $tasks += "-Plexiflow.python=$Python" }
    } else {
        $tasks += @('.gradle/samsung-isolated','-Plexiflow.isolatedRuntimeCheck=true')
    }
    $tasks += @(':app:assembleDebug',':app:assembleDebugAndroidTest')
    if ($Capacity) { $tasks += '-Plexiflow.capacityFixture=true' }
    if ($DesignPreview) { $tasks += '-Plexiflow.designPreview=true' }
    & ./gradlew.bat @tasks
    if ($LASTEXITCODE) { throw 'Isolated APK build failed.' }

    $aapt = Get-ChildItem (Join-Path $Sdk 'build-tools') -Filter aapt.exe -File -Recurse |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (!$aapt) { throw 'Installed Android build-tools do not provide aapt.' }
    $apk = if ($Research) { '.gradle/research-build/app/outputs/apk' } else { '.gradle/runtimecheck-build/app/outputs/apk' }
    $main = Join-Path $apk 'debug/app-debug.apk'
    $tests = Join-Path $apk 'androidTest/debug/app-debug-androidTest.apk'
    foreach ($pair in @(@($main,$app),@($tests,"$app.test"))) {
        $packageLine = (& $aapt.FullName dump badging $pair[0] | Select-String '^package: name=').Line
        if ($packageLine -notlike "package: name='$($pair[1])'*") { throw 'Unexpected APK identity.' }
    }
    $policy = (& $adb -s $serial shell dumpsys window policy | Select-String 'showing=|mShowingLockscreen|mKeyguardShowing') -join ' '
    if ($policy -match '(showing|mShowingLockscreen|mKeyguardShowing)=true') { throw 'Samsung is locked.' }

    $installTime = [Diagnostics.Stopwatch]::StartNew()
    & $adb -s $serial install-multi-package -t $main $tests | ForEach-Object { $_.Replace($serial,'[tablet]') }
    if ($LASTEXITCODE) { throw 'Atomic isolated install failed.' }
    if ($Research) { "Research APK and test APK installation ms: $($installTime.ElapsedMilliseconds)" }
    try {
        $start = [long](& $adb -s $serial shell date '+%s').Trim()
        $arguments = @()
        if ($TestClass) { $arguments = @('-e','class',$TestClass) }
        else { $arguments = @('-e','notClass','app.lexiflow.ui.RealResearchFlowTest') }
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
        $appProcess = 'Process: ' + [regex]::Escape($app) + '(?:\.test)?,'
        $crashes = @(& $adb -s $serial logcat -d -b crash -v epoch | Where-Object {
            $_ -match '^([0-9]+)\.' -and [long]$Matches[1] -ge $start -and
                $_ -match $appProcess
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
        if ($Research) {
            & $adb -s $serial shell am force-stop $app
            if ($LASTEXITCODE) { throw 'Could not stop the temporary research process.' }
            $restartArgs = @('-e','class','app.lexiflow.ui.RealResearchFlowTest#readsPersonalDataAfterNewProcess')
            $restarted=@(& $adb -s $serial shell am instrument -w -r @restartArgs "$app.test/androidx.test.runner.AndroidJUnitRunner")
            $restartFile = Join-Path $folder.FullName ("restart-"+(Get-Date -Format 'yyyyMMdd-HHmmss-fff')+".txt")
            $restarted | ForEach-Object { $_.Replace($serial,'[tablet]') } | Set-Content -LiteralPath $restartFile -Encoding utf8
            if (($restarted -join ' ') -notmatch 'OK \(1 test\)' -or
                'INSTRUMENTATION_STATUS_CODE: 0' -notin $restarted) {
                throw "Research process persistence check failed. Report: $restartFile"
            }
            $restartCrashes=@(& $adb -s $serial logcat -d -b crash -v epoch | Where-Object {
                $_ -match '^([0-9]+)\.' -and [long]$Matches[1] -ge $start -and $_ -match $appProcess
            }).Count
            if ($restartCrashes) { throw 'Research crash log contains a new entry.' }
            "Research process persistence verified: $restartFile"
        }
    } finally {
        foreach ($package in $packages) { & $adb -s $serial uninstall $package }
    }
    if ($LeaveResearchApp) {
        & $adb -s $serial install -t $main
        if ($LASTEXITCODE) { throw 'Clean research installation failed.' }
        & $adb -s $serial shell am start -W -n "$app/app.lexiflow.ui.MainActivity"
        if ($LASTEXITCODE) { throw 'Research activity launch failed.' }
    }
} finally {
    Pop-Location
}
