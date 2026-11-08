# WiZ Sync for Fire TV — design

Date: 2026-11-08. Status: draft for approval.

## Intent

A native Fire TV app, sideloaded as one APK, that makes Philips WiZ bulbs follow what is on the TV
while the Fire TV Stick itself is the video source. The owner sits on the couch, opens the app,
ticks a bulb, presses Start, switches to YouTube and watches music videos with the room lit to match.

This is the on-device sibling of the desktop/Pi app in this repo. It reuses the same colour
pipeline (`app/color.py`, `app/engine.py`) and the same WiZ UDP protocol (`app/bulb.py`),
rewritten in Kotlin, and runs with no server, no cloud and no account.

## Scope decision

The Stick reads its own screen through Android's standard MediaProjection API, which the user
authorises with the system consent dialog. Content that other apps mark as protected is delivered
to this API as black, by design of the platform, and that stays as it is. The app is for content
that captures normally: YouTube, the home screen, local media players (Plex, Kodi, VLC), screen
mirroring. When the frame is black the app falls back to a resting colour (see "Black frame
handling") so the room is never left dark.

## Goals

- One-press start from the couch: open app, Start, consent dialog, done. Last bulb selection is remembered.
- Lights visibly follow a YouTube music video with latency comparable to the desktop app (bulb's own
  100-150 ms plus capture).
- Zero setup beyond the WiZ app's "Allow local communication" toggle and sideloading the APK.
- Works on Fire TV Stick 4K Max (1st gen, Fire OS 7 / Android 9, 2 GB RAM) without making YouTube
  stutter.

## Non-goals (v1)

- Tuya / Wipro bulbs. WiZ only.
- Per-bulb screen zones. Whole screen only; every ticked bulb gets the same colour.
- A settings screen for smoothing, latency lead, saturation, rate or stop action. Presets carry
  those values; the only live controls are Start/Stop, preset and brightness.
- Amazon Appstore publishing. Sideload only.
- Talking to the Pi sync box or the FastAPI server. This app is standalone.

## Anti-goals

- Anything that degrades YouTube playback on the Stick. If capture cost forces a choice, lower
  capture resolution or rate, never fight the player.
- Being killed silently. If Fire OS stops the service, the bulb must not be left frozen on a
  random colour; the app restores the resting colour on any stop path it can observe.

## Target device and platform constraints

- Device: Fire TV Stick 4K Max, Fire OS 7.7.1.3 (Android 9, API 28). `minSdk 25` (covers Fire OS 6
  devices too), `targetSdk 28`. Do not raise `targetSdk` past 28 without revisiting the
  foreground-service type requirements introduced in Android 10+.
- Input: D-pad remote only. Every control must be reachable and operable with D-pad and Select.
  No touch, no mouse.
- Display: 1080p UI canvas (the Stick renders UI at 1920×1080 even on 4K panels). Use Leanback
  conventions: 48 dp safe margins, text ≥ 16 sp, focus highlighting that is obvious from 3 m.
- Capture: `MediaProjectionManager.createScreenCaptureIntent()` → user consent → `MediaProjection`
  → `VirtualDisplay` rendering into an `ImageReader` at a tiny size (default 96×54, RGBA_8888).
  The consent dialog appears on every Start; Android 9 offers no "don't ask again". Say so in the UI.
- Background: capture and bulb I/O run in a foreground service with a persistent notification
  (`FOREGROUND_SERVICE` permission). On Fire TV notifications are mostly invisible, which is fine;
  the service is what keeps the process alive while YouTube is in front.
- Network: `INTERNET` permission for UDP; `ACCESS_WIFI_STATE` + `CHANGE_WIFI_MULTICAST_STATE` and a
  `WifiManager.MulticastLock` held during discovery so broadcast replies are not filtered.
- No network calls other than LAN UDP to port 38899. No analytics, no crash reporting services.

## User flows

### First launch
1. App opens on the main screen with an empty bulb list and a focused **Find bulbs** button.
2. Find bulbs broadcasts `getPilot` to 255.255.255.255:38899 twice over ~2 s (same as
   `bulb.discover`), collects replies, shows each as a tile: name defaults to "WiZ <last 4 of MAC>",
   IP shown underneath. Found bulbs are stored and ticked by default.
3. User presses **Start**. System consent dialog appears; on accept the service starts and the
   status line reads "Syncing · 25 fps · ●" with the current colour swatch.
4. User presses Home, opens YouTube. Bulb follows.

### Every later launch
Main screen shows stored bulbs with their last tick state, the last preset highlighted, Start focused.
One press on Start → consent → syncing.

### Stop
From the app: **Stop** button. From outside the app: reopening the app shows Stop focused when
syncing. On stop the bulbs receive the resting colour (warm white, `r=255 g=214 b=170 dimming=80`,
the same `WARM_WHITE` as `engine.py`). This replaces the desktop "When I press Stop" choice; v1
has no option to leave or turn off.

### Black frame handling
If the extracted frame stays near-black (max channel of the average colour < 8/255) for 1.0 s
continuously, the bulbs fade to the resting colour over ~0.5 s and stay there. The first frame
that is not near-black resumes syncing immediately. This covers a sleeping source, a black
transition, or any app whose output does not capture. The status line shows "Paused · no picture".

### Stick sleeps or shows the screensaver
The Stick is the source, so "source asleep" means the Stick itself. The service registers for
`ACTION_SCREEN_OFF` / `ACTION_SCREEN_ON`. On screen off it sends `setState {state:false}` to the
ticked bulbs, releases the projection and stops itself; the next Start needs the consent dialog
again. This is deliberate: it is the only path in v1 that turns bulbs off, and it prevents warm
white being left on all night. The Fire TV screensaver (display still on, frames keep coming) is
simply content and syncs like anything else. A persistent-sync-across-sleep option is a later
feature.

### Consent declined or revoked
If the user cancels the consent dialog, nothing starts and the status line says "Capture
permission needed". If the projection stops (`MediaProjection.Callback.onStop`), the service
applies the resting colour and stops itself.

## Main screen (single screen, Leanback style)

```
┌──────────────────────────────────────────────────────────────────┐
│  WiZ Sync                                   Syncing · 25 fps  ■  │
│                                                                  │
│  Bulbs                                          [ Find bulbs ]   │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐              │
│  │ ☑ WiZ 3F2A   │ │ ☑ Floor lamp │ │ ☐ WiZ 91C0   │              │
│  │ 192.168.1.42 │ │ 192.168.1.43 │ │ 192.168.1.44 │              │
│  └──────────────┘ └──────────────┘ └──────────────┘              │
│                                                                  │
│  Preset     ( Movie )  ( Gaming )  ( Ambient )  ( Party )        │
│  Brightness ◄ ────────────●───── ►  80%                          │
│                                                                  │
│                      [      Start / Stop      ]                  │
│                                                                  │
│  Fire OS asks for screen-capture permission each time you start. │
└──────────────────────────────────────────────────────────────────┘
```

- Bulb tiles: Select toggles the tick. Long-press (or Menu button) opens a tiny dialog with
  **Rename**, **Identify** (blink the bulb twice via `setPilot`), **Remove**.
- Preset row: radio behaviour, one active. Selecting a preset applies its values live if syncing.
- Brightness: D-pad left/right in steps of 5, range 10–100. Semantics follow the preset's
  `brightness_mode`: when the preset is `follow`, this slider sets `min_brightness`; when
  `fixed`, it sets `brightness`. Label the slider "Min brightness" or "Brightness" accordingly.
  The value is stored **per preset**: selecting a preset restores that preset's last value
  (initially the value from `engine.PRESETS`), and moving the slider overwrites only the active
  preset's stored value. Switching presets never discards an adjustment made to another preset.
- Start/Stop: one button whose label flips. Focus lands here on launch when at least one bulb is ticked.
- Status line (top right): "Ready", "Syncing · N fps", "Paused · no picture", "Capture permission
  needed", or an error such as "Bulb 192.168.1.42 not responding" (see Health).
- Colour swatch beside the status shows the last colour sent.

## Colour pipeline (port of `app/color.py` + `_Pipeline` in `app/engine.py`)

Runs on a single worker thread in the service, driven by `ImageReader.OnImageAvailableListener`
with a rate limiter.

1. **Acquire** latest image from the `ImageReader`, copy the RGBA plane into a reusable
   `IntArray` (respect `rowStride`), close the image. Never hold more than one image.
2. **Crop black bars**: port `crop_black_bars` (threshold 14, min_keep 0.2) on the small frame.
3. **Extract** with the preset's mode. Port all four extractors (`average`, `dominant`,
   `brightest`, `saturated`) faithfully, including the `dominant` 8×8×8 bucket scoring with
   saturation weighting and the 5 % top-mean for `brightest`/`saturated`. Presets reference all four.
4. **Saturation boost**: port `boost_saturation` (HSV scale, clamp 1.0).
5. **Smooth**: `smooth(prev, new, smoothing ** (10 / rate))`, exactly as the engine.
6. **Lead prediction**: velocity estimate damped with factor 0.6, target = smoothed + vel × lead/1000, clamped 0–255.
7. **Normalise**: level = max channel / 255; if level < 0.02 keep last hue; else rgb = target / level.
   `dimming` = `brightness` (fixed) or `min_brightness + (100 − min_brightness) × level` (follow).
8. **Dead-band**: skip the send if max channel delta ≤ 2 and dimming delta ≤ 1, as in `_Pipeline.step`.
9. **Send** `setPilot {state:true, r,g,b, dimming}` to each ticked bulb (fire-and-forget UDP,
   one `DatagramSocket` reused for the whole run).

Presets are the four dicts in `engine.PRESETS`, copied verbatim (mode, smoothing, saturation,
brightness_mode, brightness/min_brightness, lead, rate, ignore_black_bars). Defaults on first
run: preset Movie, min brightness 15.

Rate: the preset's `rate` (10–30 fps) is the ceiling. The `VirtualDisplay` produces frames as the
compositor does; the worker drops frames that arrive faster than the rate, and sends nothing when
nothing changed.

Performance budget: a 96×54 frame is 5 184 pixels; every step above is a few microseconds in
Kotlin on this device. Expect < 2 % CPU in the service. If profiling shows the `VirtualDisplay`
itself costs the GPU more than expected, drop to 64×36 before anything else.

## WiZ protocol (port of `app/bulb.py`)

- Port 38899, UDP, JSON. Commands: `{"id":1,"method":"setPilot","params":{...}}`,
  `setState {state:bool}`, discovery via `getPilot` broadcast; reply `result.mac` identifies the bulb.
- Fire-and-forget for `setPilot` during sync (no reply wait).
- **Identify**: `setPilot` to pure white dimming 100, 300 ms later dimming 20, 300 ms later back to
  white 100, then restore the current sync colour or resting colour.
- **Health**: once every 60 sends (or every 3 s when idle), send `getPilot` to each ticked bulb
  and wait up to 500 ms for a reply. Two consecutive misses → that bulb shows "not responding" in
  the status line and a warning badge on its tile; sync continues for the others. A reply clears it.
- Bulbs are keyed by MAC, with IP updated on every discovery and on every health reply, so a DHCP
  change does not lose the bulb.

## Persistence

`SharedPreferences` (or DataStore) holding: bulbs `[ {mac, ip, name, enabled} ]`, active preset,
brightness value, nothing else. No secrets are involved (WiZ has none), so plain storage is fine.

## Project layout

```
firetv/
  settings.gradle.kts, build.gradle.kts, gradle/ (wrapper)
  app/
    build.gradle.kts            # applicationId com.shreyas.wizsync.tv, minSdk 25, targetSdk 28
    src/main/AndroidManifest.xml  # LEANBACK_LAUNCHER intent, touchscreen not required, permissions above
    src/main/java/.../
      ui/MainActivity.kt        # single Leanback-style screen
      ui/BulbTile.kt, PresetRow.kt, BrightnessBar.kt
      sync/SyncService.kt       # foreground service: projection, ImageReader, worker, bulbs
      sync/ColorPipeline.kt     # steps 2–8 above, pure functions + Pipeline state class
      sync/Presets.kt
      wiz/WizClient.kt          # UDP send, discovery, health
      store/Settings.kt
    src/main/res/               # banner 320×180 (required for Fire TV launcher), layouts, strings
    src/test/.../ColorPipelineTest.kt  # ports of tests/test_core.py colour cases + preset table check
  README.md                     # build + sideload instructions
```

UI toolkit is the builder's choice between classic Views with Leanback focus handling and
Jetpack Compose for TV; both are acceptable on API 28. Compose for TV is preferred if its
dependencies resolve for `targetSdk 28`; otherwise Views.

## Build and install

- Gradle wrapper committed; `./gradlew assembleDebug` produces `app/build/outputs/apk/debug/app-debug.apk`.
- Debug-signed APK is fine for sideloading; a release keystore is out of scope.
- README covers: on the Stick, Settings → My Fire TV → Developer options → ADB debugging ON and
  Apps from Unknown Sources ON; find the IP under About → Network; then on the Mac
  `adb connect <ip>:5555` and `adb install -r app-debug.apk`. Launch from "Your Apps & Channels".
- README also says that YouTube must be the app in front for sync to show anything, and lists the
  WiZ app toggle (Settings → Security → Allow local communication).

## Step zero: capture spike (do this before any other work)

The design assumes three things about Fire OS 7 that have not been observed on this Stick:
`createScreenCaptureIntent()` resolves to a consent activity, that dialog is D-pad operable, and
the Fire TV YouTube app does not render video to a surface that captures as black. Build a
throwaway APK first (consent → `VirtualDisplay` → `ImageReader` → log the average colour once a
second) and run it with a YouTube music video in front. Any of the three failing stops the
project for a scope rethink; the UI, pipeline and persistence work waits on this result. The
spike code is discarded, not kept.

**Result (2026-11-08, Fire TV Stick 4K Max, Fire OS 7.7.1.3): PASS.** All three held. The consent
dialog appeared and was accepted with the D-pad; YouTube video captured with real colour. Three
sample lines from `adb logcat -s WIZSPIKE` during a YouTube music video (dark scene, red hats):
`avg r=53 g=46 b=45`, `avg r=35 g=30 b=30`, `avg r=52 g=35 b=33`. A system `screencap` taken at
the same moment averaged `r=34 g=27 b=24`, matching the spike. Over ~60 s: 115 frame lines and
240 "no frame" lines (the sampler polls faster than new frames arrive; expected). Brief
`r=0 g=0 b=0` / `r=1 g=1 b=1` lines coincided with scene cuts and the YouTube overlay.

## Testing

- Unit tests for the colour pipeline with hand-built frames: solid colour, letterboxed colour,
  near-black, two-colour dominant case, smoothing convergence, dead-band. Mirror
  `tests/test_core.py` where it tests colour maths.
- A `WizClient` test against a fake UDP listener on localhost: discovery collects replies, health
  marks misses.
- Manual acceptance on the Stick: Find bulbs finds the real bulb; Start → consent → bulb follows a
  YouTube music video; Home screen keeps syncing; Stop restores warm white; killing the app via
  Settings → Applications restores warm white (via `onStop`/`onDestroy`) or at worst leaves the last
  colour, which is noted as a known limitation if it happens.

## Left to the builder

- Views vs Compose for TV, and exact Leanback styling.
- Threading primitives (HandlerThread vs coroutines) for the service worker.
- Exact ImageReader buffer count and how frames are dropped under rate limiting.
- Wording of strings, banner artwork.
- Whether health checks use a second socket or the send socket.

## Open items settled by the designer (my calls, not the owner's)

- Resting colour on stop and on black frames is warm white, no user option in v1.
- Brightness slider doubles as min-brightness in follow presets.
- Bulbs keyed by MAC, not IP.
- `targetSdk 28`, debug signing, no store release.
- Brightness is stored per preset (see Main screen).
- Stick screen off → bulbs turned off and sync stopped; no persistence across sleep in v1.
- Capture spike is a mandatory first step; the project is conditional on its result.
- Ticked state changes mid-sync take effect on the next send; a bulb unticked while syncing
  receives the resting colour once.
- Step 2 (crop black bars) honours the preset's `ignore_black_bars`.
- Health-check waits run on their own thread and never block the send loop; Identify pauses
  sends to that bulb for its 1 s sequence.
- Builder environment note: the Mac has the Android SDK at `~/Library/Android/sdk` with
  platform-tools (adb not on PATH) and only JDK 26 on PATH; the Gradle build must pin a JDK 17 or
  21 toolchain.
