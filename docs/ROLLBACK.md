# Rollback guide

Every change this project makes to a TV is reversible. Nothing is uninstalled except apps **you** choose, and system apps are
only disabled (`pm disable-user`), never deleted.

| Change | Undo |
|---|---|
| Installed the launcher | `adb shell pm uninstall --user 0 com.tvhome.launcher` |
| Made it the Home role holder | `adb shell cmd role add-role-holder --user 0 android.app.role.HOME <stock-launcher>` |
| Disabled the setup wizard | `adb shell pm enable --user 0 com.google.android.tungsten.setupwraith` |
| Disabled the stock launcher | `adb shell pm enable --user 0 com.google.android.apps.tv.launcherx` (other TVs: `com.google.android.tvlauncher`) |
| Removed a system app for your user | `adb shell cmd package install-existing <package>` |
| Disabled another app | `adb shell pm enable --user 0 <package>` |
| Uninstalled a Play Store app | Re-install it from the Play Store (its data is lost) |
| Uninstalled a sideloaded app | Only possible with its original APK, so **back it up first** if you may want it again |

One script does the first four: `.\restore_original_launcher.ps1 -Target <tv-ip>:<port> [-RemoveCustom]`.

## If the screen is black or Home loops and ADB is unavailable

1. If our launcher is showing, open its Settings icon, choose **All settings**, then Apps, See all apps, Show system apps, and
   enable the Google TV home and the setup wizard.
2. Otherwise use a USB keyboard or the TV's own remote to reach Settings, Apps, and enable them there.
3. Do **not** factory-reset, flash firmware or open a service menu as a routine recovery. If you must reset, re-enable the setup
   wizard first.

## Keep a record

The prompt in `PROMPT.md` tells your AI assistant to write `backup_state.txt` (properties, enabled and disabled packages, the original
Home app) **before** changing anything, and to log every approved change in `approved_changes.txt`. Keep both files; they are
the exact list `restore_disabled_apps.ps1` reads.
