param(
    [Parameter(Mandatory = $true)][string]$Apk,
    [string]$DeviceSerial
)
$ErrorActionPreference = 'Stop'
$apkPath = (Resolve-Path $Apk).Path
$sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$adbPath = Join-Path $sdk 'platform-tools\adb.exe'
$signer = Get-ChildItem (Join-Path $sdk 'build-tools') -Filter apksigner.bat -Recurse |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
$key = Join-Path $env:USERPROFILE '.android\debug.keystore'
if (!(Test-Path $adbPath) -or !$signer -or !(Test-Path $key)) {
    throw 'Android SDK tools or your existing debug.keystore are missing. Build/run the CPU app once in Android Studio first.'
}
# Re-sign with the local development key so adb can update the installed POC
# without uninstalling it or deleting its external model files.
$signed = Join-Path (Split-Path $apkPath) 'voice-poc-vulkan-local.apk'
if ($apkPath -eq $signed) { throw 'Choose the original downloaded app-debug.apk as input.' }
& $signer.FullName sign --ks $key --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out $signed $apkPath
if ($LASTEXITCODE -ne 0) { throw 'APK signing failed; nothing was installed.' }
& $signer.FullName verify $signed
if ($LASTEXITCODE -ne 0) { throw 'Signed APK verification failed; nothing was installed.' }
$deviceArgs = @()
if ($DeviceSerial) { $deviceArgs = @('-s', $DeviceSerial) }
& $adbPath @deviceArgs install -r $signed
if ($LASTEXITCODE -ne 0) { throw 'APK update failed. Keep the installed app and its models; do not uninstall.' }
& $adbPath @deviceArgs shell am force-stop life.mosaic.fit.voicepoc
& $adbPath @deviceArgs shell am start -n life.mosaic.fit.voicepoc/life.mosaic.fit.voicepoc.VoicePocActivity
if ($LASTEXITCODE -ne 0) { throw 'APK installed, but launching failed.' }
