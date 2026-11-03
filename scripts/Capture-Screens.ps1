param(
    [string]$SdkPath = (Join-Path $PSScriptRoot '..\.tooling\android-sdk'),
    [string]$Serial = 'emulator-5554'
)
$ErrorActionPreference = 'Stop'
$project = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$adb = Join-Path $SdkPath 'platform-tools\adb.exe'
$apk = Join-Path $project 'feature-intervention\build\outputs\apk\androidTest\debug\feature-intervention-debug-androidTest.apk'
if (-not (Test-Path -LiteralPath $apk)) { throw 'Build :feature-intervention:assembleDebugAndroidTest first.' }
& $adb -s $Serial install -t -r $apk
if ($LASTEXITCODE -ne 0) { throw 'Could not install the screenshot test APK.' }
$result = & $adb -s $Serial shell am instrument -w -e class com.pause.feature.intervention.InterventionScreenshotsTest com.pause.feature.intervention.test/androidx.test.runner.AndroidJUnitRunner
$result | Write-Output
if (($result -join "`n") -notmatch 'OK \(3 tests\)') { throw 'Screenshot capture tests did not all pass.' }
$destination = Join-Path $project 'docs\screenshots'
New-Item -ItemType Directory -Path $destination -Force | Out-Null
foreach ($screen in @('pause-breathing.png', 'pause-selection.png', 'pause-breathing-no-quotes.png')) {
    & $adb -s $Serial pull "/sdcard/Android/data/com.pause.feature.intervention.test/files/screenshots/$screen" (Join-Path $destination $screen)
    if ($LASTEXITCODE -ne 0) { throw "Could not retrieve $screen" }
}
