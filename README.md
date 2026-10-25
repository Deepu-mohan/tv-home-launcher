# TV Home: a glass, Apple TV-style launcher for Android TV and Google TV

A small native launcher (plain Java, about 350 KB, no Gradle, no libraries) that gives an Android TV or Google TV a clean,
Apple TV-inspired home screen with a Liquid Glass look, built and installed from your PC over ADB.

> **Designed and developed by Deepu Mohan.** MIT licensed. Tested on one TV only (see [Known limits](#known-limits)).
> You use this at your own risk; every step is reversible and the AI prompt below asks you before each change.

## What you get

- **Two-page home screen, like Apple TV:** page one has a tall see-through glass clock with AM/PM, the date, four glass keys
  (Inputs, Wi-Fi, Settings, Options), a weather card, the name of the focused app in big glass letters, and a dock of up to 6 apps. Page two is the app grid.
- **Wallpapers:** Near black, Warm sunset, Spheres (all drawn by the launcher at full resolution with film grain, no image files),
  or your own full-screen photo.
- **Control Centre panel:** Wi-Fi, Bluetooth, Inputs, Sound, Display, Options and All settings as rounded glass tiles. It opens from the Settings key on screen.
- **Inputs list with HDMI status:** green dot for a connected device, amber for standby, grey for no signal.
- **All apps list:** sorted by name by default or by install date, filter to the apps you installed, and open the system's uninstall prompt
  (nothing is removed until you confirm it there).
- **Dock editing:** hold OK on an app to add it to the dock, remove it, move it, or hide it. Hiding never uninstalls.
- **Weather:** Open-Meteo (no key), about once an hour, only while the home screen is shown.
- **Light:** about 40 to 75 MB of RAM, no background services, no polling, sharp icons read at native resolution.
- **Safe by design:** a "test" build that can never become your Home app, scripts that refuse to run without `-Approved`, and rollback scripts.

## Quick start with an AI assistant (recommended)

You need a Windows PC, an AI coding assistant with shell access (for example Claude Code), and a TV on the same Wi-Fi.

1. On the TV: Settings > System > About, tap **Build** 10 times; then Developer options > **Wireless debugging** on.
2. Give your assistant this repository link and paste the prompt below. It will connect to your TV, inspect it, back up its state, build the launcher,
   test it, and ask you before each change.

**Prompt (the same text is in [PROMPT.md](PROMPT.md); it already points at this repository, so just paste it):**

<details>
<summary>Show the full prompt</summary>

```text
You are an Android TV developer working directly on my real TV through ADB from my Windows PC. Start with the repository at
`https://github.com/Deepu-mohan/tv-home-launcher`: clone it, read `README.md` and `docs/TV-NOTES.md`, and then follow this workflow. Do not just explain; inspect my
real device, build, test, and document. Never do anything in the "approval required" list without my explicit yes in chat.

GOAL: build and install the glass, Apple-TV-style launcher from this repo on my TV, optionally make it my Home app, and optionally remove apps that I pick.

## 1. Safety rules (they override everything else)

Approval required, every time, one action at a time (tell me the exact command, the effect and how to undo it, then wait for my yes):
- installing anything on my PC or my TV (SDKs, JDKs, APKs, tools, fonts);
- installing the launcher APK; changing the default Home app; disabling the stock launcher or the setup wizard;
- uninstalling or disabling any app: show me the list with names, package names, purpose and risk, ask
  "Which applications do you want to uninstall or disable?", show the final list, and ask again;
- sending any request to the internet on my behalf, changing network or Wi-Fi settings, or touching accounts.

Never do: root, unlock the bootloader, flash firmware, factory reset, enter service or factory menus, change audio, Dolby, picture,
HDR, resolution, refresh rate, HDMI, accessibility or update settings, remap or intercept remote keys, or impersonate another company's app.
Prefer reversible actions: `pm disable-user` over `pm uninstall`, and hiding an app in the launcher over both.
If something goes wrong, stop, undo the last approved change, and tell me. The original TV functions and the way back come first.

## 2. Phase 1: find and connect to my TV

1. Check the tools you already have: `adb version`, the Android SDK (platform android-34 or newer, build-tools), and a JDK 17+.
   Do not install missing tools without asking me.
2. Ask me for: the TV's IP address, and, on the TV, Settings > System > About > tap Build 10 times, then Developer options >
   Wireless debugging (or USB debugging). With Wireless debugging I will give you the *pairing* IP:port and code
   (`adb pair`), then the *connection* port (`adb connect`). Ports change, so ask again each session.
3. Verify the device is mine before doing anything else.

## 3. Phase 2: read-only inspection, then backup

Run only read-only commands and report: manufacturer, model, Android version and SDK level, CPU ABI, RAM, free storage,
Google TV or Android TV, the current Home app (`cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME`
and `cmd role get-role-holders android.app.role.HOME`), the HOME handlers, the leanback-launchable apps, the enabled, disabled and
third-party package lists (`pm list packages -e / -d / -3 -i`), and who owns the saved Wi-Fi networks (`dumpsys wifi | grep cname=`).

Then write `backup_state.txt` (all of the above) in the project folder **before changing anything**, and keep
`approved_changes.txt` for every later change. Check that the launcher's minimum SDK (34) is not above the TV's SDK; if the TV is older, stop and tell me.

## 4. Phase 3: build and test the launcher without touching the Home key

1. Run `.\build.ps1`. It finds the SDK and JDK, creates a local signing key on first use, and writes `build\tvhome-test.apk`
   (an ordinary app "Home (test)", which can never become Home) and `build\tvhome-home.apk`.
2. Optional: ask me for a wallpaper photo that I own, and run `.\set_wallpaper.ps1 -Image <path>`. Without one the launcher uses its drawn wallpapers.
3. Ask me before installing. Then `.\install_launcher.ps1 -Target <ip:port> -Variant test -Approved`.
4. Open it (`am start -n com.tvhome.launcher/.HomeActivity`), take screenshots with `adb shell screencap` + `adb pull`
   (do not use shell redirection on Windows; it corrupts PNGs), and test: D-pad moves between the dock, the grid and the header keys, long-press
   on an app (dock/hide), Options, All apps (sort by name or install date; do **not** confirm any uninstall during tests), Inputs (do not switch
   inputs), the Control Centre panel, memory (`dumpsys meminfo com.tvhome.launcher`, expect 40 to 80 MB).
5. Before every OK key press, take a screenshot and check where the focus is. Do not send keys blindly; I may also be using the remote.
6. Report what you tested and what you did not. Never claim a feature works without testing it.

## 5. Phase 4: weather and personalisation (ask first)

The only network use is the weather card (Open-Meteo, no key). Ask me for my city, with a country code, for example `Dammam,SA`; it is sent
once to find coordinates and then only the coordinates are sent about once an hour while the home screen is shown. Set it in the launcher
under Options > Weather city. If I decline, tell me the card will show the default city and offer to remove it.

## 6. Phase 5: removing apps (only if I want to)

Show two separate lists: essential system components (never touch: Google TV home, Settings, Play Store, Play Services, voice assistant, Live TV
and tuner services, input and HDMI services, the OTA updater, remote-control updater, Cast and mirroring, and every factory, service and retail-demo
tool) and possible unwanted apps (apps I installed, manufacturer extras). For each: name, package, purpose, risk, and how to restore. Offer
**hide in the launcher** first. Only after my exact selection and a final yes: `pm uninstall -k --user 0 <pkg>` for system apps (undo with
`cmd package install-existing <pkg>`) or `pm uninstall --user 0 <pkg>` for apps I installed. Warn me that sideloaded apps cannot be
re-downloaded without their APK, and offer to back the APK up first. Record every removal.

## 7. Phase 6: make it the Home app (only if I want to, last)

Read `docs/TV-NOTES.md` first and explain the trade-offs to me: the Home key is hard-wired to the Google TV launcher, so on Google TV
this needs the setup wizard and the stock launcher to be **disabled** (not uninstalled); the remote's Settings and Netflix buttons stop working; saved Wi-Fi networks
owned by the wizard are forgotten; phone casting may need the wizard re-enabled; do not reboot while the recovery screen is in focus.
Check that ADB survives a reboot only if I agree to a reboot. Ask for my yes, then install the Home build
(`-Variant home`) and run `.\make_default_home.ps1 -Target <ip:port> -Approved`. It starts our launcher first, disables the wizard and then the stock launcher,
checks the **focused app** after pressing Home, and rolls back by itself if Home does not land on our launcher. Afterwards test Home from
Settings, a 20 second check for a Home loop, Wi-Fi still connected, and casting if I can try it with a phone. Write everything into `approved_changes.txt`.

If anything fails: stop, run `.\restore_original_launcher.ps1 -Target <ip:port>`, check that Google TV's home works, and report which functions you could not verify.

## 8. Final delivery

Give me: what was changed (exact packages), test results with the screenshots, the limits (see "Known limits" in README), the restore commands, and where
`backup_state.txt`, `approved_changes.txt` and the APKs are.
```

</details>

## Quick start by hand

Requirements: Windows with PowerShell 5.1, `adb`, the Android SDK (platform `android-34` or newer and build-tools; Android Studio installs both) and a JDK 17+
(Android Studio's bundled JDK works). Nothing else is downloaded.

```powershell
git clone https://github.com/Deepu-mohan/tv-home-launcher
cd tv-home-launcher
.\build.ps1                      # writes build\tvhome-test.apk and build\tvhome-home.apk

adb pair <tv-ip>:<pairing-port>  # enter the 6-digit code shown on the TV
adb connect <tv-ip>:<port>       # the port on the TV's Wireless debugging screen

.\install_launcher.ps1 -Target <tv-ip>:<port> -Variant test -Approved   # installs "Home (test)" as an ordinary app
```

Open **Home (test)** from the TV's app list. That is all most people need. To also use your own wallpaper photo:

```powershell
.\set_wallpaper.ps1 -Image "C:\path\to\your-photo.jpg"   # crops to 16:9, rebuilds
```

Set your weather city in the launcher under **Options > Weather city** (format `City,CC`, for example `London,GB`).

## Making it your Home app (optional, read first)

The Home key on a Google TV opens Google's launcher **by name**, so installing a launcher and choosing it as Home is not enough.
It takes disabling the TV's setup wizard and Google's launcher. That is reversible, but it has costs. Read [docs/TV-NOTES.md](docs/TV-NOTES.md), then:

```powershell
.\install_launcher.ps1 -Target <tv-ip>:<port> -Variant home -Approved
.\make_default_home.ps1 -Target <tv-ip>:<port> -Approved    # checks the focused app and rolls back by itself on failure
```

Undo: `.\restore_original_launcher.ps1 -Target <tv-ip>:<port>`. More in [docs/ROLLBACK.md](docs/ROLLBACK.md).

## Known limits

- **Tested on one TV only:** Haier MatrixTV EE (HR9676EU), Google TV on Android 14, 32-bit ARM, 1.8 GB RAM. Other TVs may behave differently.
- **Reboot was never tested** with the Home takeover. After a restart, check that Home still opens the launcher and re-enable the stock launcher if not.
- With the stock launcher and the setup wizard disabled: the remote's **Settings** and **Netflix** buttons stop working (they are handled inside the TV software and
  by Google's launcher, and cannot be re-mapped without changing system files), saved Wi-Fi networks owned by the wizard are forgotten, and phone casting may need
  the wizard re-enabled.
- Android does not tell apps the name of the device on an HDMI port, so the Inputs list shows status only.
- The Control Centre has no volume or brightness sliders: this project does not change audio or display settings.
- Windows PowerShell scripts only. Live TV playback, audio output and Dolby were not tested.

## Privacy

The launcher asks for: internet (weather only), network state (the Wi-Fi label), seeing all installed apps (to build the app grid), and the system's
uninstall prompt. The weather city is sent once to Open-Meteo to find coordinates; after that only the coordinates are sent, about once an hour.
Nothing else leaves the TV. Screenshots, `backup_state.txt`, `approved_changes.txt` and your signing key are excluded from git on purpose.

## Files

| File | Purpose |
|---|---|
| `app/` | The launcher source (`HomeActivity.java`), two manifests (test and home), resources |
| `build.ps1` | Builds both APKs with the SDK and JDK you already have |
| `install_launcher.ps1` | Installs an APK (needs `-Approved`; never changes the Home app) |
| `make_default_home.ps1` | The Home takeover with checks and automatic rollback (needs `-Approved`) |
| `restore_original_launcher.ps1`, `restore_disabled_apps.ps1` | Undo scripts |
| `set_wallpaper.ps1` | Bundles your own wallpaper photo |
| `PROMPT.md` | The full AI-assistant prompt |
| `docs/` | What was learned on a real TV, and the rollback guide |

## Credits

Inspired by [CleanYourTV](https://github.com/Gathin23/cleanyourtv), whose notes on the setup wizard and Home takeover on Google TV informed
`make_default_home.ps1`. No code was copied. The glass and Control Centre styles are an original interpretation of Apple's design language; this project is not affiliated with Apple, Google or any TV maker.



