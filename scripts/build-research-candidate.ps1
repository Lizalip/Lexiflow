#Requires -Version 7.0
param(
    [Parameter(Mandatory)][string]$Dictionary,
    [Parameter(Mandatory)][string]$Python,
    [string]$Sdk = "$env:USERPROFILE/AppData/Local/Android/Sdk",
    [string]$Jdk = 'C:/Program Files/Android/Android Studio/jbr'
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$buildTools = (Get-ChildItem -LiteralPath (Join-Path $Sdk 'build-tools') -Directory |
    Sort-Object { [version]($_.Name -replace '-.*$','') } -Descending |
    Where-Object { (Test-Path (Join-Path $_.FullName 'aapt.exe')) -and
        (Test-Path (Join-Path $_.FullName 'apksigner.bat')) } | Select-Object -First 1).FullName
if (!$buildTools) { throw 'AAPT and apksigner are required in the existing Android SDK.' }
$env:ANDROID_HOME = $Sdk
$env:JAVA_HOME = $Jdk
Push-Location -LiteralPath $root
try {
    $pair = (Resolve-Path -LiteralPath $Dictionary).Path
    # Force a complete APK write while retaining incremental preprocessing and compilation.
    $options = @('--offline','--gradle-user-home','.gradle/verification','--project-cache-dir',
        '.gradle/research-candidate-cache','-Plexiflow.research=true',"-Plexiflow.researchDictionary=$pair",
        "-Plexiflow.python=$Python",':app:packageDebug','--rerun',':app:assembleDebug')
    & ./gradlew.bat @options
    $apk = (Resolve-Path -LiteralPath '.gradle/research-build/app/outputs/apk/debug/app-debug.apk').Path
    $aapt = Join-Path $buildTools 'aapt.exe'
    $line = (& $aapt dump badging $apk | Select-String '^package: name=').Line
    if ($line -notlike "package: name='app.lexiflow.research'*") { throw 'Unexpected application ID.' }
    if ((& $aapt dump permissions $apk) -match 'android.permission.INTERNET') {
        throw 'Research APK requests INTERNET.'
    }
    & (Join-Path $buildTools 'apksigner.bat') verify --min-sdk-version 29 $apk
    $report = & $Python scripts/inspect_research_apk.py $apk --pair $pair --require-compact
    $out = New-Item -ItemType Directory -Path (Join-Path '.gradle/samsung-evidence' (
        'research-candidate-'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff')))
    Copy-Item -LiteralPath $apk -Destination (Join-Path $out.FullName 'research.apk')
    $report | Set-Content -LiteralPath (Join-Path $out.FullName 'archive.json') -Encoding utf8
    "Verified Research APK: $($out.FullName)/research.apk"
    "APK bytes: $((Get-Item -LiteralPath $apk).Length)"
    "APK SHA-256: $((Get-FileHash -LiteralPath $apk).Hash)"
} finally {
    Pop-Location
}
