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

