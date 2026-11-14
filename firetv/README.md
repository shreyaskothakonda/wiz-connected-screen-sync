# Screen Sync for WiZ lights (Fire TV)

Makes Philips WiZ bulbs follow whatever is on your Fire TV screen. The app captures the screen on the Stick, picks a colour, and sends it straight to the bulbs over your LAN. No cloud, no phone, no PC. The store name is **Screen Sync for WiZ lights**; the in-app title is still **WiZ Sync**.

This is an independent project. It is not affiliated with, endorsed by or supported by Signify or WiZ. WiZ is a trademark of Signify Holding B.V., used only to state which bulbs the app works with.

Tested on a Fire TV Stick 4K Max (Fire OS 7.7.1.3).

## What it does

- Finds WiZ bulbs on your Wi-Fi, and lets you Identify (blink), Rename and Remove them.
- Five presets, per-preset brightness, and a Start/Stop button.
- Turns the bulbs off when the Stick goes to sleep.
- Works on YouTube, the home screen, and players such as Plex, Kodi and VLC.

**What it does not do:** apps that mark their video as protected (most subscription streaming apps) capture as black, so the bulbs fade to warm white after 1 s of black and resume when a picture returns. This is enforced by the system and cannot be worked around.

## Setup

**WiZ app:** Settings → Security → Allow local communication, ON.

**Fire TV Stick:**
1. Settings → My Fire TV → Developer options → ADB debugging ON, Apps from Unknown Sources ON (if Developer options is missing, press the select button 7 times on About → your device name).
2. Settings → My Fire TV → About → Network, and note the IP address.
3. Stick and bulbs must be on the same Wi-Fi network.

## Build and install

Prerequisites:
- `brew install openjdk@21`.
- The Android SDK with Platform 36 and platform-tools, for example via Android Studio's SDK Manager. Alternatively install the command-line tools and accept the licences with `sdkmanager --licenses`.
- `source firetv/jdk.sh` sets both `JAVA_HOME` (the Homebrew JDK) and `ANDROID_HOME` (default `~/Library/Android/sdk`). If you would rather not rely on the environment, run `echo "sdk.dir=$HOME/Library/Android/sdk" > firetv/local.properties` once instead.

```bash
source firetv/jdk.sh
cd firetv && ./gradlew assembleDebug
~/Library/Android/sdk/platform-tools/adb connect <stick-ip>:5555
~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Run the unit tests with `./gradlew test`. Then launch **Screen Sync for WiZ lights** from Your Apps & Channels.

## Release build and signing

Release builds are minified with R8 (`app/proguard-rules.pro`), which also strips `Log.i`/`Log.d` output, and are signed with a keystore that stays out of git:

```bash
source firetv/jdk.sh
cd firetv
keytool -genkeypair -v -keystore release.jks -alias screensync -keyalg RSA -keysize 2048 -validity 10000
cp keystore.properties.example keystore.properties   # then fill in the passwords
./gradlew assembleRelease
~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/release/app-release.apk
```

`release.jks`, `keystore.properties` and `*.keystore` are git-ignored. Without `keystore.properties` the release build still succeeds but produces `app-release-unsigned.apk`. Back the keystore up: Amazon re-signs the store build with its own certificate, but sideloaded updates over an already installed build must be signed by the same key, and a store build cannot be updated over a sideloaded one (uninstall first).

Appstore preparation lives in `docs/`: `appstore-checklist.md` (requirements with sources), `store-listing.md` (listing text, privacy policy, content rating answers) and `store/` (icons, icon generator, screenshot script).

## Using it

Pick a preset, adjust brightness if you like, and press **Start**. Fire OS shows a screen-capture consent dialog on each Start; choose **Start now** (ticking **Don't show again** skips it on later starts). Press **Stop** to end the sync. When the Stick sleeps, the bulbs are turned off.

## Presets

| Preset | Colour pick | Brightness | Updates/s |
| --- | --- | --- | --- |
| Instant (default) | Average, no smoothing, black bars ignored | Full brightness (floor 100%; lower it to let dark scenes dim) | 30 |
| Movie | Average, smoothed, black bars ignored | Follows the picture, floor 15% | 20 |
| Gaming | Dominant colour, light smoothing | Follows the picture, floor 20% | 30 |
| Ambient | Average, heavy smoothing, black bars ignored | Fixed at 60% | 10 |
| Party | Most saturated colour, no smoothing | Fixed at 100% | 30 |

The brightness slider changes the floor for "follows the picture" presets and the fixed level for fixed ones, remembered per preset.

## Troubleshooting

- **No bulbs found:** the Stick and bulbs must share a Wi-Fi network (multicast not blocked, no guest or isolated network), and Allow local communication must be on in the WiZ app. Press **Find bulbs** again.
- **"Not responding" badge on a bulb:** its IP has probably changed (router DHCP). While syncing, the app looks the bulb up again by itself within a few seconds and carries on; when not syncing, press **Find bulbs**. If the badge stays, the bulb is off or on another network.
- **Bulbs fade to warm white while playing:** the app is probably protected content (see above), so the capture is black. Try YouTube to confirm the setup works.

## Known limitations

- Protected-content apps capture as black.
- The bulb's own fade time bounds the latency; the app cannot make a bulb react faster than it can change colour.
- If the system kills the app process mid-sync, the bulb stays on its last colour.
