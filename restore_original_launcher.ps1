# Restores the ORIGINAL Google TV home screen: re-enables the stock launcher and the setup wizard,
# makes the stock launcher the Home role holder again, and can remove our launcher.
#   Stock launcher : com.google.android.apps.tv.launcherx   (other TVs: com.google.android.tvlauncher)
#   Setup wizard   : com.google.android.tungsten.setupwraith
# Check these package names on YOUR TV first (see backup_state.txt written by the prompt in PROMPT.md).
# This script CHANGES the TV. Do not run it without approval.
#
# Usage:  .\restore_original_launcher.ps1 -Target <tv-ip>:<port> [-RemoveCustom]
#   PORT is the current "IP address & Port" on the TV's Wireless debugging screen (it changes).
param(
    [Parameter(Mandatory = $true)][string]$Target,
    [switch]$RemoveCustom
)
$ErrorActionPreference = 'Stop'
$orig   = 'com.google.android.apps.tv.launcherx'
$custom = 'com.tvhome.launcher'
$wizard = 'com.google.android.tungsten.setupwraith'   # also re-enabled: disabling it was part of make_default_home.ps1

adb connect $Target | Out-Null
adb -s $Target shell pm enable --user 0 $wizard
adb -s $Target shell pm enable --user 0 $orig
adb -s $Target shell cmd role add-role-holder --user 0 android.app.role.HOME $orig
Write-Host "HOME role holder now:"
adb -s $Target shell cmd role get-role-holders android.app.role.HOME

if ($RemoveCustom) {
    # Removes only the custom launcher this project installed.
    adb -s $Target shell pm uninstall --user 0 $custom
}
adb -s $Target shell am start -a android.intent.action.MAIN -c android.intent.category.HOME
Write-Host "Done. Press Home on the remote to confirm the Google TV home screen appears."

