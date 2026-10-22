# Install "Дом" on an Android TV over network ADB and give it its config (Home Assistant address and token).
#
#   .\install.ps1 -TvHost 192.168.1.50 -Config C:\path\config.json              # build, install, configure
#   .\install.ps1 -TvHost 192.168.1.50 -Config config.json -Apk tv-smart-home-1.0.apk   # a downloaded APK
#   .\install.ps1 -TvHost 192.168.1.50 -Config config.json -ConfigOnly           # new address or token only
#
# Needs adb (Android SDK platform-tools, or on PATH) and "USB debugging" on the TV; accept the prompt on the TV the
# first time. Building needs JDK 17 and the Android SDK (platform 34). The config is written through the app's
# provider, which only the adb shell may call, into the app's private files; it is never left in /data/local/tmp.
param(
    [Parameter(Mandatory = $true)][string]$TvHost,
    [Parameter(Mandatory = $true)][string]$Config,
    [string]$Apk = '',
    [switch]$ConfigOnly
)
$ErrorActionPreference = 'Stop'
$pkg = 'io.github.fabutnm.homepanel'
$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb) { $adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" }
if (-not (Test-Path $adb)) { throw 'adb not found: install Android SDK platform-tools or put adb on PATH' }
if (-not (Test-Path $Config)) { throw "no config file $Config (start from config.example.json)" }
try { Get-Content $Config -Raw -Encoding UTF8 | ConvertFrom-Json | Out-Null } catch { throw "$Config is not valid JSON" }
$target = if ($TvHost -match ':') { $TvHost } else { "${TvHost}:5555" }
# adb prints progress ("daemon not running; starting now") on stderr, which Windows PowerShell 5.1 turns into a
# terminating error under 'Stop'; native calls are checked by their output and exit code instead.
$ErrorActionPreference = 'Continue'
$said = (& $adb connect $target 2>&1) -join ' '
Write-Host $said
if ($said -notmatch 'connected') { throw "cannot reach $target over adb (USB debugging on? accept the prompt on the TV)" }
function Shell([string]$cmd) { (& $adb -s $target shell $cmd 2>&1) -join "`n" }

if (-not $ConfigOnly) {
    if (-not $Apk) {
        Push-Location $PSScriptRoot
        try { & .\gradlew.bat --no-daemon assembleRelease -q } finally { Pop-Location }
        if ($LASTEXITCODE -ne 0) { throw 'build failed' }
        $Apk = Join-Path $PSScriptRoot 'app\build\outputs\apk\release\app-release.apk'
    }
    & $adb -s $target push $Apk /data/local/tmp/homepanel.apk 2>&1 | Out-Null
    # pm install over the shell: adb install can wait forever for an on-screen confirmation on some sets.
    $out = Shell 'pm install -r /data/local/tmp/homepanel.apk; rm -f /data/local/tmp/homepanel.apk'
    Write-Host $out
    if ($out -notmatch 'Success') { throw 'install failed (a build signed with another key? uninstall the old app first)' }
}

# Some firmware (TCL) does not start an app's process just for its provider: open the panel first.
Shell "am start -W -n $pkg/.PanelActivity" | Out-Null
& $adb -s $target push $Config /data/local/tmp/hp-config.json 2>&1 | Out-Null
$out = Shell "content write --uri content://$pkg.config/config.json < /data/local/tmp/hp-config.json; echo rc=`$?; rm -f /data/local/tmp/hp-config.json"
if ($out -notmatch 'rc=0' -or $out -match 'Exception|Error') { throw "config write failed: $out" }
Shell "am force-stop $pkg; am start -n $pkg/.PanelActivity" | Out-Null
Write-Host 'Done: the panel is open on the TV.'
