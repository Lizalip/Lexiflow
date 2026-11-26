#Requires -Version 7.0
param(
    [Parameter(Mandatory)][string]$Python,
    [string]$Sdk = "$env:USERPROFILE/AppData/Local/Android/Sdk",
    [string]$Jdk = 'C:/Program Files/Android/Android Studio/jbr',
    [switch]$UpdateResearch,
    [switch]$Measure30000,
    [switch]$CheckRollback,
    [ValidateSet('full','bilingual','bilingual-expansion','content','morphology')][string]$Advanced,
    [string]$DirectWord,
    [string]$AddedWord,
    [switch]$LexicalScale,
    [switch]$ProfileOnly,
    [ValidateSet('10000','20000')][string]$RussianSize = '20000'
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
if ($Advanced -and ($UpdateResearch -or $Measure30000 -or $CheckRollback)) {
    throw 'Advanced experiments must stay in the temporary profile.'
}
if ($Advanced -like 'bilingual*' -and !$DirectWord) {
    throw 'Supply an exact direct Russian headword for the instrumented article scenario.'
}
if ($Advanced -eq 'bilingual-expansion' -and !$AddedWord) {
    throw 'Supply an exact Russian headword available only after expansion.'
}
if (($LexicalScale -or $ProfileOnly) -and $Advanced -ne 'morphology') {
    throw 'LexicalScale and ProfileOnly are available in the isolated morphology workflow.'
}
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$adb = Join-Path $Sdk 'platform-tools/adb.exe'
$aapt = (Get-ChildItem "$Sdk/build-tools" -Recurse -File -Filter aapt.exe |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
$tempApp = 'app.lexiflow.researchcheck'
$researchApp = 'app.lexiflow.research'
$test = 'app.lexiflow.ui.RealResearchFlowTest'
$env:ANDROID_HOME = $Sdk
$env:JAVA_HOME = $Jdk
Push-Location $root
try {
    $devices = @(& $adb devices -l | ForEach-Object {
        if ($_ -match '^(\S+)\s+device\b.*\bmodel:SM_X516B\b') { $Matches[1] }
    })
    if ($devices.Count -ne 1) { throw 'Expected exactly one authorized Samsung SM-X516B.' }
    $serial = $devices[0]
    if ((& $adb -s $serial shell getprop ro.product.manufacturer).Trim() -ne 'samsung') {
        throw 'Unexpected manufacturer.'
    }
    Write-Host ("Samsung API "+(& $adb -s $serial shell getprop ro.build.version.sdk))
    $policy = (& $adb -s $serial shell dumpsys window policy |
        Select-String 'mShowingLockscreen=|mKeyguardShowing=|showing=') -join ' '
    if ($policy -match '(mShowingLockscreen|mKeyguardShowing|showing)=true') { throw 'Samsung is locked.' }
    $storage = (& $adb -s $serial shell df -k /data | Select-Object -Last 1).Trim() -split '\s+'
    if ($storage.Count -lt 6 -or [long]$storage[-3] -lt 1048576) { throw 'Need 1 GiB free on /data.' }
    Write-Host ("Free KiB on /data: "+$storage[-3])

    function Package-Present($package) {
        $PSNativeCommandUseErrorActionPreference = $false
        return [bool]((& $adb -s $serial shell pm path $package) -match '^package:')
    }
    foreach ($package in @($tempApp,"$tempApp.test")) {
        if (Package-Present $package) { throw 'Existing researchcheck installation must be preserved.' }
    }
    $runName = 'research-upgrade-'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff')
    $run = New-Item -ItemType Directory (Join-Path '.gradle/samsung-evidence' $runName)
    Write-Host "Local report: $($run.FullName)"
    $artifacts = 'research/data/processed'
    $old = "$artifacts/research-app/dictionary-research"
    $new = "$artifacts/research-scaling/20000"
    if (!$Advanced) {
        & $Python research/prototypes/sqlite_poc/research_upgrade.py $old $new |
            Tee-Object -FilePath (Join-Path $run.FullName 'identity.json')
    }

    function Build-Apk($dictionary,$label,[switch]$PersonalProfile,[switch]$Tests) {
        $profile = if ($PersonalProfile) {'research'} else {'researchcheck'}
        $buildFolder = ".gradle/$profile-build/app/outputs/apk"
        $arguments = @('--gradle-user-home','.gradle/verification','--offline','--project-cache-dir',
            ".gradle/overnight-$profile",'-Plexiflow.research=true',"-Plexiflow.python=$Python",
            "-Plexiflow.researchDictionary=$dictionary",':app:assembleDebug')
        if (!$PersonalProfile) { $arguments += '-Plexiflow.researchCheck=true' }
        if ($Tests) { $arguments += ':app:assembleDebugAndroidTest' }
        & ./gradlew.bat @arguments | Out-Host
        $file = Join-Path $run.FullName "$label.apk"
        Copy-Item "$buildFolder/debug/app-debug.apk" $file
        $expected = if ($PersonalProfile) { $researchApp } else { $tempApp }
        $identity = (& $aapt dump badging $file | Select-String '^package: name=').Line
        if ($identity -notlike "package: name='$expected'*") { throw 'Unexpected APK application ID.' }
        $zip = [IO.Compression.ZipFile]::OpenRead($file)
        try {
            $relevant = @($zip.Entries | Where-Object { $_.FullName.StartsWith('assets/dictionary') })
            if ($relevant.Count -ne 2) { throw 'The APK must contain one dictionary pair.' }
            foreach ($name in @('manifest.json','dictionary.sqlite')) {
                $entry = $zip.GetEntry("assets/dictionary-research/$name")
                if (!$entry) { throw 'Missing staged dictionary file.' }
                $stream = $entry.Open()
                try { $hash = (Get-FileHash -InputStream $stream).Hash }
                finally { $stream.Dispose() }
                if ($hash -ne (Get-FileHash "$dictionary/$name").Hash) { throw 'APK dictionary hash mismatch.' }
            }
        } finally { $zip.Dispose() }
        if ($Tests) {
            Copy-Item "$buildFolder/androidTest/debug/app-debug-androidTest.apk" (Join-Path $run.FullName 'test.apk')
        }
        Write-Host "$label bytes=$((Get-Item $file).Length)"
        return $file
    }

    function Run-Test($method,$label,[string[]]$extras=@(),[string]$testClass=$test) {
        & $adb -s $serial shell am force-stop $tempApp
        $instrument = @('-s',$serial,'shell','am','instrument','-w','-r','-e','class',"$testClass#$method")
        $instrument += $extras
        $instrument += "$tempApp.test/androidx.test.runner.AndroidJUnitRunner"
        $lines = @(& $adb @instrument)
        $lines | Set-Content -Encoding utf8 (Join-Path $run.FullName "$label.txt")
        if ('INSTRUMENTATION_STATUS_CODE: 0' -notin $lines -or
            ($lines -join ' ') -notmatch 'OK \(1 test\)') { throw "Research upgrade check failed: $label" }
        Write-Host "$label passed"
    }

    function Save-AppFrame([string]$name) {
        $info = [Diagnostics.ProcessStartInfo]::new($adb)
        $info.UseShellExecute = $false
        $info.RedirectStandardOutput = $true
        foreach ($arg in @('-s',$serial,'exec-out','run-as',$tempApp,'cat',"cache/lexiflow-evidence/$name.png")) {
            $info.ArgumentList.Add($arg)
        }
        $process = [Diagnostics.Process]::Start($info)
        try {
            $stream = [IO.File]::Open((Join-Path $run.FullName "$name.png"),[IO.FileMode]::CreateNew)
            try { $process.StandardOutput.BaseStream.CopyTo($stream) }
            finally { $stream.Dispose() }
            $process.WaitForExit()
            if ($process.ExitCode) { throw "Could not save app frame: $name" }
        } finally { $process.Dispose() }
    }

    if ($Advanced -eq 'full') {
        $initial = Build-Apk "$artifacts/research-scaling/full-v3" 'full-isolated' -Tests
    } elseif ($Advanced -eq 'bilingual') {
        $initial = Build-Apk $new '20000-isolated' -Tests
        $expanded = Build-Apk "$artifacts/research-scaling/bilingual-20000-5000" 'bilingual-isolated'
    } elseif ($Advanced -eq 'bilingual-expansion') {
        $baseline = "$artifacts/research-scaling/bilingual-cefr-attributed"
        $candidate = "$artifacts/research-scaling/bilingual-cefr-$RussianSize"
        & $Python research/prototypes/sqlite_poc/research_upgrade.py $baseline $candidate |
            Tee-Object -FilePath (Join-Path $run.FullName 'identity.json')
        $initial = Build-Apk $baseline 'bilingual-5000-isolated' -Tests
        $expanded = Build-Apk $candidate "bilingual-$RussianSize-isolated"
    } elseif ($Advanced -eq 'content') {
        $initial = Build-Apk "$artifacts/research-scaling/bilingual-usage-pilot" 'usage-v1-isolated' -Tests
        $expanded = Build-Apk "$artifacts/research-scaling/bilingual-cefr-20000-content" 'content-isolated'
    } elseif ($Advanced -eq 'morphology') {
        $baseline = "$artifacts/research-scaling/bilingual-cefr-20000-content"
        $candidateName = if ($LexicalScale) {'lexical-scale'} else {'ud-expanded'}
        $candidate = "$artifacts/research-scaling/bilingual-cefr-20000-$candidateName"
        & $Python research/prototypes/sqlite_poc/research_upgrade.py $baseline $candidate --allow-enrichment |
            Tee-Object -FilePath (Join-Path $run.FullName 'identity.json')
        $initial = Build-Apk $baseline 'morphology-before' -Tests
        $expanded = Build-Apk $candidate 'morphology-expanded'
    } else {
        $initial = Build-Apk $old '5000-isolated' -Tests
        $expanded = Build-Apk $new '20000-isolated'
    }
    $start = [long](& $adb -s $serial shell date '+%s').Trim()
    $installTime = [Diagnostics.Stopwatch]::StartNew()
    & $adb -s $serial install-multi-package -t $initial (Join-Path $run.FullName 'test.apk') |
        ForEach-Object { $_.Replace($serial,'[tablet]') }
    Write-Host "Initial APK and test APK installation ms=$($installTime.ElapsedMilliseconds)"
    try {
        if ($Advanced -eq 'full') {
            Run-Test 'opensAdvancedCorpusAndMeasuresSearch' 'full-open' @('-e','lexiflowCorpus','research-freedict-2025.11.23-all')
        } elseif ($Advanced -eq 'bilingual') {
            Run-Test 'usesRealDictionaryAndPersistsPersonalData' 'english-seed'
            & $adb -s $serial install -r -t $expanded
            Run-Test 'learnsAnIndependentRussianSenseAfterUpdatingEnglishCorpus' 'bilingual-learns' @('-e','lexiflowDirectWord',$DirectWord)
            Run-Test 'readsBothLanguagesAfterRestartOrRollback' 'bilingual-restart' @('-e','lexiflowDirectWord',$DirectWord)
            & $adb -s $serial install -r -t $initial
            Run-Test 'readsBothLanguagesAfterRestartOrRollback' 'bilingual-rollback' @('-e','lexiflowDirectWord',$DirectWord,'-e','lexiflowRollback','true')
        } elseif ($Advanced -eq 'bilingual-expansion') {
            $existing = @('-e','lexiflowDirectWord',$DirectWord)
            $added = @('-e','lexiflowAddedWord',$AddedWord)
            $version = @('-e','lexiflowCorpus',"research-bilingual-freedict-2025.11.23-$RussianSize-cefr-j")
            Run-Test 'usesRealDictionaryAndPersistsPersonalData' 'expansion-english-seed' @('-e','lexiflowEvidence','true')
            Run-Test 'learnsAnIndependentRussianSenseAfterUpdatingEnglishCorpus' 'expansion-russian-seed' $existing
            & $adb -s $serial install -r -t $expanded
            Run-Test 'savesRussianSenseAfterBilingualExpansion' 'expansion-upgrade' ($added+$version+@('-e','lexiflowEvidence','true'))
            Run-Test 'readsBothLanguagesAfterRestartOrRollback' 'expansion-restart' ($existing+$added+$version)
            & $adb -s $serial install -r -t $initial
            Run-Test 'readsBothLanguagesAfterRestartOrRollback' 'expansion-rollback' ($existing+$added+
                @('-e','lexiflowCorpus','research-bilingual-freedict-2025.11.23-5000-cefr-j','-e','lexiflowRollback','true'))
        } elseif ($Advanced -eq 'content') {
            Run-Test 'displaysUsageExampleWithSeparateSentenceAndTranslationCredits' 'content-old-reader'
            & $adb -s $serial install -r -t $expanded
            Run-Test 'opensSeveralAttributedExamplesAndSearchesInflectedForms' 'content-usage-and-forms' @('-e','lexiflowEvidence','true')
            Save-AppFrame '13-attributed-usage'
            Save-AppFrame '14-grammatical-forms'
        } elseif ($Advanced -eq 'morphology') {
            $beforeVersion = 'research-bilingual-freedict-2025.11.23-20000-cefr-j-usage-v2-ud-forms'
            $afterVersion = "$beforeVersion-ud-expanded"
            if ($LexicalScale) { $afterVersion += '-usage-expanded' }
            $existing = @('-e','lexiflowDirectWord','дом')
            $profileClass = 'app.lexiflow.data.ResearchActivationProfileTest'
            if ($ProfileOnly) {
                Run-Test 'measuresInitialActivationAndReopenSeparately' 'morphology-baseline-activation' @(
                    '-e','lexiflowCorpus',$beforeVersion) $profileClass
            } else {
                Run-Test 'usesRealDictionaryAndPersistsPersonalData' 'morphology-english-seed'
                Run-Test 'learnsAnIndependentRussianSenseAfterUpdatingEnglishCorpus' 'morphology-russian-seed' $existing
            }
            & $adb -s $serial install -r -t $expanded
            Run-Test 'measuresInitialActivationAndReopenSeparately' 'morphology-activation' @(
                '-e','lexiflowCorpus',$afterVersion) $profileClass
            if ($ProfileOnly) {
                Run-Test 'scannedMetadataRejectsMissingAndWrongLanguageOwners' 'morphology-attribution-rejection' @() 'app.lexiflow.data.AdvancedManifestTest'
            } else {
                $formArgs = @('-e','lexiflowCorpus',$afterVersion,'-e','lexiflowEvidence','true')
                if ($LexicalScale) { $formArgs += @('-e','lexiflowUsageExpanded','true') }
                Run-Test 'opensSeveralAttributedExamplesAndSearchesInflectedForms' 'morphology-forms' $formArgs
                Save-AppFrame '13-attributed-usage'
                Save-AppFrame '14-grammatical-forms'
                Run-Test 'readsBothLanguagesAfterRestartOrRollback' 'morphology-restart' ($existing+
                    @('-e','lexiflowCorpus',$afterVersion))
                & $adb -s $serial install -r -t $initial
                Run-Test 'readsBothLanguagesAfterRestartOrRollback' 'morphology-rollback' ($existing+
                    @('-e','lexiflowCorpus',$beforeVersion))
            }
        } else {
        Run-Test 'usesRealDictionaryAndPersistsPersonalData' '5000-seed'
        Run-Test 'seedAdditionalUpgradeSaves' '5000-extra-saves'
        & $adb -s $serial install -r -t $expanded
        Run-Test 'readsExpandedDictionaryAndPreservesLearning' '20000-verify'
        Run-Test 'readsExpandedDictionaryAndPreservesLearning' '20000-restart'
        if ($Measure30000) {
            $larger = Build-Apk "$artifacts/research-scaling/30000" '30000-isolated'
            & $adb -s $serial install -r -t $larger
            Run-Test 'readsExpandedDictionaryAndPreservesLearning' '30000-verify'
        }
        if ($CheckRollback) {
            Run-Test 'seedMeaningAvailableOnlyInExpandedCorpus' 'expanded-seed-rollback'
            & $adb -s $serial install -r -t $initial
            Run-Test 'preservesLearningWhenAnEntryDisappearsOnRollback' '5000-rollback'
        }
        }
        $crashes = @(& $adb -s $serial logcat -d -b crash -v epoch | Where-Object {
            $_ -match '^\s*([0-9]+)\.' -and [long]$Matches[1] -ge $start -and
            $_ -match 'Process: app\.lexiflow\.researchcheck(?:\.test)?,'
        })
        if ($crashes.Count) { throw 'New isolated LexiFlow crash detected.' }
    } finally {
        & $adb -s $serial logcat -d -v epoch -s 'LexiFlowResearch:I' '*:S' | Where-Object {
            $_ -match '^\s*([0-9]+)\.' -and [long]$Matches[1] -ge $start
        } | Set-Content -Encoding utf8 (Join-Path $run.FullName 'performance.txt')
        & $adb -s $serial uninstall "$tempApp.test"
        & $adb -s $serial uninstall $tempApp
    }

    if ($Advanced) { return }
    $apk = Build-Apk $new '20000-research' -PersonalProfile
    if ($UpdateResearch) {
        if (!(Package-Present $researchApp)) {
            throw 'The existing research installation is unavailable for in-place update.'
        }
        # Package Manager checks signing compatibility and keeps application data for -r.
        & $adb -s $serial install -r -t $apk
        & $adb -s $serial shell am start -W -n "$researchApp/app.lexiflow.ui.MainActivity" |
            Tee-Object -FilePath (Join-Path $run.FullName 'research-launch.txt')
    }
} finally {
    Pop-Location
}
