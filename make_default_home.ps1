# Makes com.tvhome.launcher (HOME build) the launcher the Home key opens on this TV.
# CHANGES THE TV. Requires explicit owner approval, hence the mandatory -Approved switch. NOT RUN YET.
#
# Why two packages are disabled (verified on this TV, see rollback_readme.md):
#   - The Home key starts com.google.android.apps.tv.launcherx by name; the Home role is ignored.
#   - With launcherx disabled, the system falls back to com.google.android.tungsten.setupwraith/.RecoveryActivity
#     (priority 1 beats ours, priority 0), which loops. So setupwraith (the setup wizard) must be disabled FIRST.
# Nothing is uninstalled: both are 'pm disable-user' and fully reversible with 'pm enable'.
#
# Known costs (also in the repo notes this approach is based on):
#   - Wi-Fi networks that were saved by the setup wizard (check: dumpsys wifi | grep cname=) are forgotten when it is disabled.
#   - Phones may report "Untrusted device" when casting until setupwraith is re-enabled.
#   - Re-enable setupwraith before any factory reset. Do NOT reboot while .RecoveryActivity is in focus.
#
# Usage:  .\make_default_home.ps1 -Target <tv-ip>:<port> -Approved
param(
    [Parameter(Mandatory = $true)][string]$Target,
    [switch]$Approved
)
if (-not $Approved) { Write-Host "Refusing: pass -Approved after the owner has approved this exact change."; exit 1 }

$ours    = 'com.tvhome.launcher'
$stock   = 'com.google.android.apps.tv.launcherx'
$wizard  = 'com.google.android.tungsten.setupwraith'

function Adb { & adb -s $Target @args }
function Focused { ((Adb shell dumpsys activity activities) | Select-String 'mFocusedApp' | Select-Object -First 1).Line }

function Rollback {
    Write-Host "ROLLING BACK: re-enabling the stock launcher and setup wizard"
    Adb shell pm enable --user 0 $stock
    Adb shell pm enable --user 0 $wizard
    Start-Sleep -Seconds 2
    Adb shell input keyevent KEYCODE_HOME
    Start-Sleep -Seconds 3
    Write-Host ("Focused after rollback: " + (Focused))
}

adb connect $Target | Out-Null

# 1. our HOME build must be installed and must start on its own
if (-not ((Adb shell pm list packages $ours) -match $ours)) { Write-Host "Install the home build first (install_launcher.ps1 -Variant home -Approved)"; exit 1 }
$start = (Adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.HOME -n "$ours/.HomeActivity") -join "`n"
if ($start -notmatch 'Status: ok' -or $start -match 'Error') { Write-Host "Our launcher failed to start; nothing was changed.`n$start"; exit 1 }
Start-Sleep -Seconds 3
if ((Focused) -notmatch $ours) { Write-Host "Our launcher is not in focus; nothing was changed."; exit 1 }

# 2. role + preferred activity point at us (does not affect the Home key by itself)
Adb shell cmd role add-role-holder --user 0 android.app.role.HOME $ours | Out-Null
Adb shell cmd package set-home-activity --user 0 "$ours/.HomeActivity" | Out-Null

# 3. the switch: wizard first, then the stock launcher
Adb shell pm disable-user --user 0 $wizard
Adb shell pm disable-user --user 0 $stock
Start-Sleep -Seconds 2

# 4. verify with the focused app, not with resolve-activity
Adb shell input keyevent KEYCODE_HOME
Start-Sleep -Seconds 4
$f = Focused
Write-Host "Focused after Home: $f"
if ($f -match $ours) {
    Write-Host "OK: the Home key opens $ours. Undo any time with restore_original_launcher.ps1."
} else {
    Write-Host "Home did not land on our launcher."
    Rollback
    exit 2
}


