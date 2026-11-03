param(
    [string]$SdkPath = (Join-Path $PSScriptRoot '..\.tooling\android-sdk'),
    [string]$Serial = 'emulator-5554',
    [ValidatePattern('^[a-z-]+$')][string]$Profile = 'standard'
)
$ErrorActionPreference = 'Stop'
$project = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$adb = Join-Path $SdkPath 'platform-tools\adb.exe'
$destination = Join-Path $project "docs\screenshots\$Profile"
New-Item -ItemType Directory -Path $destination -Force | Out-Null
foreach ($theme in @('dark','light')) {
    foreach ($screen in @('today','apps','insights','insights-chart','settings','settings-pending','breathing','selection','setup')) {
        $name = "pause-$Profile-$screen-$theme.png"
        & $adb -s $Serial pull "/sdcard/Download/$name" (Join-Path $destination "$screen-$theme.png")
        if ($LASTEXITCODE -ne 0) { throw "Could not retrieve $name. Run VisualScreenshotsTest successfully first." }
    }
}
