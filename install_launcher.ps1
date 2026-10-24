# Installs the custom launcher APK on the TV. CHANGES the TV - requires explicit approval, so
# the -Approved switch is mandatory.
#
# -Variant test : launcher WITHOUT the HOME category. It shows up as an ordinary app
#                 ("Home (test)") and can never become the Home app. Use this first.
# -Variant home : launcher WITH the HOME category (can be chosen as Home app).
# This script NEVER changes the default Home app and never disables the original launcher.
#
# Usage:  .\install_launcher.ps1 -Target <tv-ip>:<port> -Variant test -Approved
param(
    [Parameter(Mandatory = $true)][string]$Target,
    [ValidateSet('test', 'home')][string]$Variant = 'test',
    [switch]$Approved
)
if (-not $Approved) { Write-Host "Refusing to install: pass -Approved after the owner has approved the installation."; exit 1 }
$apk = Join-Path $PSScriptRoot "build\tvhome-$Variant.apk"
if (-not (Test-Path $apk)) { Write-Host "Missing $apk - run build.ps1 first."; exit 1 }
adb connect $Target | Out-Null
adb -s $Target install -r $apk
Write-Host "Installed $Variant build. Default Home app was NOT changed."

