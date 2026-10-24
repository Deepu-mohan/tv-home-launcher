# What we learned on a real TV

Everything here was measured on one TV, a **Haier MatrixTV EE (HR9676EU)**: Google TV on Android 14, MediaTek MT9676,
32-bit ARM, about 1.8 GB RAM, 4K panel with the UI drawn at 1080p. Your TV may differ. Check before you copy a step.

## The Home key and the Home role

- `cmd role add-role-holder ... android.app.role.HOME` and `cmd package set-home-activity` both report success, but
  the Home key still opens the Google TV launcher (`com.google.android.apps.tv.launcherx`). The TV firmware starts
  that launcher by exact name.
- If you only disable the Google TV launcher, the system falls back to the setup wizard's recovery screen
  (`com.google.android.tungsten.setupwraith/.RecoveryActivity`) and starts it in a rapid loop. Nothing usable appears.
  The wizard's HOME entry has priority 1; a sideloaded launcher has priority 0 and cannot beat it.
- What works: install the launcher, start it by hand and check that it is in focus, disable the **setup wizard first**,
  then the Google TV launcher, then press Home and check the **focused app**
  (`dumpsys activity activities | grep mFocusedApp`). `cmd package resolve-activity` shows the resolver's winner,
  which is not always the activity that gets focus. `make_default_home.ps1` does exactly this and rolls back
  automatically if Home does not land on our launcher.
- This approach is the same one used by [CleanYourTV](https://github.com/Gathin23/cleanyourtv). Read its
  `docs/TROUBLESHOOTING.md` too; it documents more TVs than this repo does.

## What you give up when the setup wizard and the Google TV launcher are disabled

| Thing | Effect |
|---|---|
| Wi-Fi networks saved by the setup wizard | Forgotten. Check first: `dumpsys wifi | grep cname=` (networks owned by `android.uid.system` are safe). |
| Phone casting | May show "Untrusted device" after the Cast receiver restarts, because it takes its credentials from the wizard. It still worked after the change in our test, but we did not test a reboot. |
| The remote's Settings button | Stops working: it makes the firmware open a panel inside the Google TV launcher. |
| Google TV recommendations, profiles, Watch Next | Gone with the launcher. |
| Factory reset | Re-enable the wizard first. Never reboot while `.RecoveryActivity` is in focus. |

## Remote buttons that cannot be re-mapped

- The Settings button sends raw HID usage `0x9F`, which Android reports as `KEY_UNKNOWN` (scan code 240) and drops before
  any app sees it. Our launcher cannot receive it. The Control Centre panel opens from the on-screen Settings key instead.
- The Netflix button is handled by the TV software (`com.mediatek.tv.agent`, a `NetflixKeyReceiver`) and starts Netflix by
  name. It cannot be pointed at another app without changing the system key table. If you remove Netflix, the button does nothing.
- Special keys such as these are listed in `dumpsys window policy` (look for `KEYCODE_...=package/receiver`).

## Other measured facts

- The launcher uses about 40 to 75 MB of RAM. Google's own launcher uses about 130 MB.
- Input status comes from `TvInputManager.getInputState()`. Android does not give apps the name of the device on an HDMI port.
- Quick-settings rows open the TV's own screens: Sound `DisplaySoundActivity`, Display `mediatek ... PictureActivity`, Wi-Fi
  `NetworkActivity`. There is no Bluetooth settings action on this TV, so the Bluetooth tile opens the accessory pairing screen.
- Android keeps `adb` ports for Wireless debugging random. Re-read the port from the TV each session.
