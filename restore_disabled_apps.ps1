# Re-enables / restores apps that were disabled or removed with USER APPROVAL.
# No app has been disabled or removed so far, so approved_changes.txt does not exist yet.
# Pre-existing disabled packages (disabled before this project, do NOT re-enable them):
#   com.haier.gamingbar, com.mediatek.dtv.tvinput.isdbtuner, it.hon.service,
#   com.mediatek.dtv.tvinput.atsctuner, ru.haier.evo, com.haier.apps.evoSmart,
#   com.mediatek.tv.oneworld.tvcenter, it.hon.onboarding
#
# approved_changes.txt format (one package per line, lines starting with # ignored):
#   com.example.app
# This script CHANGES the TV. Do not run it without approval.
#
# Usage:  .\restore_disabled_apps.ps1 -Target <tv-ip>:<port>
param(
    [Parameter(Mandatory = $true)][string]$Target,
    [string]$ListFile = (Join-Path $PSScriptRoot 'approved_changes.txt')
)
if (-not (Test-Path $ListFile)) { Write-Host "No $ListFile - nothing was ever disabled. Nothing to restore."; exit 0 }
adb connect $Target | Out-Null
Get-Content $ListFile | Where-Object { $_.Trim() -and -not $_.StartsWith('#') } | ForEach-Object {
    $pkg = $_.Trim()
    Write-Host "Restoring $pkg"
    adb -s $Target shell pm enable --user 0 $pkg
    # If the app was uninstalled for user 0 (pm uninstall -k --user 0), this brings it back:
    adb -s $Target shell cmd package install-existing --user 0 $pkg
}

