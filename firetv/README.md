# WiZ Sync for Fire TV

Makes Philips WiZ bulbs follow whatever is on your Fire TV screen. The app captures the screen on the Stick, picks a colour, and sends it straight to the bulbs over your LAN. No cloud, no phone, no PC.

Tested on a Fire TV Stick 4K Max (Fire OS 7.7.1.3).

## What it does

- Finds WiZ bulbs on your Wi-Fi, and lets you Identify (blink), Rename and Remove them.
- Five presets, per-preset brightness, and a Start/Stop button.
- Turns the bulbs off when the Stick goes to sleep.
- Works on YouTube, the home screen, and players such as Plex, Kodi and VLC.

**What it does not do:** apps that mark their video as protected (most subscription streaming apps) capture as black, so the bulbs stay dark or dim. This is enforced by the system and cannot be worked around.

## Setup

**WiZ app:** Settings → Security → Allow local communication, ON.

**Fire TV Stick:**
1. Settings → My Fire TV → Developer options → ADB debugging ON, Apps from Unknown Sources ON (if Developer options is missing, press the select button 7 times on About → your device name).
2. Settings → My Fire TV → About → Network, and note the IP address.
3. Stick and bulbs must be on the same Wi-Fi network.

## Build and install

Prerequisite: `brew install openjdk@21`. `firetv/jdk.sh` exports `JAVA_HOME` for the Homebrew JDK (Apple Silicon path).

```bash
source firetv/jdk.sh
cd firetv && ./gradlew assembleDebug
~/Library/Android/sdk/platform-tools/adb connect <stick-ip>:5555
~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Run the unit tests with `./gradlew test`. Then launch **WiZ Sync** from Your Apps & Channels.

## Using it

Pick a preset, adjust brightness if you like, and press **Start**. Fire OS shows a screen-capture consent dialog on each Start; choose **Start now** (ticking **Don't show again** skips it on later starts). Press **Stop** to end the sync. When the Stick sleeps, the bulbs are turned off.

## Presets

| Preset | Colour pick | Brightness | Updates/s |
| --- | --- | --- | --- |
| Instant (default) | Average, no smoothing, black bars ignored | Follows the picture, floor 15% | 30 |
| Movie | Average, smoothed, black bars ignored | Follows the picture, floor 15% | 20 |
| Gaming | Dominant colour, light smoothing | Follows the picture, floor 20% | 30 |
| Ambient | Average, heavy smoothing, black bars ignored | Fixed at 60% | 10 |
| Party | Most saturated colour, no smoothing | Fixed at 100% | 30 |

The brightness slider changes the floor for "follows the picture" presets and the fixed level for fixed ones, remembered per preset.

## Troubleshooting

- **No bulbs found:** the Stick and bulbs must share a Wi-Fi network (multicast not blocked, no guest or isolated network), and Allow local communication must be on in the WiZ app. Press **Find bulbs** again.
- **"Not responding" badge on a bulb:** its IP has probably changed (router DHCP). Press **Find bulbs**.
- **Bulbs stay dark while playing:** the app is probably protected content (see above). Try YouTube to confirm the setup works.

## Known limitations

- Protected-content apps capture as black.
- The bulb's own fade time bounds the latency; the app cannot make a bulb react faster than it can change colour.
- If the system kills the app process mid-sync, the bulb stays on its last colour.
