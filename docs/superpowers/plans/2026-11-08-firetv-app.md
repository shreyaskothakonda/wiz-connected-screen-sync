# WiZ Sync for Fire TV Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A sideloadable Fire TV APK that captures the Stick's own screen and drives WiZ bulbs over LAN UDP, with a one-screen D-pad UI.

**Architecture:** A foreground `SyncService` owns the MediaProjection → `VirtualDisplay` → `ImageReader` chain and a worker that runs a pure-Kotlin `ColorPipeline` (port of `app/color.py` + `_Pipeline`) and a `WizClient` (port of `app/bulb.py`). `MainActivity` is a single Views-based Leanback-style screen bound to the service. Everything testable (pipeline, engine loop, UDP client, settings) is kept Android-free behind small interfaces so JVM unit tests cover it; the Android glue is thin and tested manually on the Stick.

**Tech Stack:** Kotlin 2.0, AGP 8.11, Gradle 8.14 wrapper, Temurin JDK 21, `minSdk 25 / targetSdk 28 / compileSdk 36`, classic Views (no Compose), JUnit 4 + kotlin-test, no third-party runtime libraries.

**Spec:** `docs/superpowers/specs/2026-11-08-firetv-app-design.md`

## Global Constraints

- `applicationId com.shreyas.wizsync.tv`, `minSdk 25`, `targetSdk 28`, `compileSdk 36`. Never raise `targetSdk`.
- D-pad only: every control focusable and operable with D-pad + Select; `android.hardware.touchscreen` required=false; `LEANBACK_LAUNCHER` category; 320×180 banner.
- Permissions exactly: `INTERNET`, `FOREGROUND_SERVICE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`. No others.
- Network traffic is LAN UDP to port 38899 only. No analytics or crash reporters.
- Capture size default 96×54 RGBA_8888, fallback 64×36. Never hold more than one `Image`.
- Resting colour `WARM_WHITE = (r=255, g=214, b=170, dimming=80)`. Dimming clamp 10–100.
- Presets copied verbatim from `app/engine.py` `PRESETS`. Default preset Movie.
- Black frame: average colour max channel < 8 for 1.0 s → fade to resting over 0.5 s; first non-black frame resumes.
- Screen off → `setState false` to ticked bulbs, release projection, stop service.
- Build runs with `JAVA_HOME=$(/usr/libexec/java_home -v 21)`; adb is `~/Library/Android/sdk/platform-tools/adb`.
- Commit after every task with a conventional message; spike code is deleted in Task 9.

## Review Focus

1. **Start with zero bulbs ticked** → Start must be disabled with status "Tick at least one bulb", never a crash or a consent dialog. (Test in Task 8.)
2. **Discovery run twice** → the same MAC must not produce a second tile; IP updates in place. (Test in Task 5.)
3. **ImageReader rowStride padding** → a frame whose `rowStride` ≠ `width*4` must decode to the right colours, not a skewed/garbage image. (Test in Task 6.)
4. **Projection stopped by the system mid-sync** (`MediaProjection.Callback.onStop`) → bulbs receive resting colour and service stops; the UI shows "Ready". (Test in Task 6 via engine `stop(reason)`; manual in Task 7.)
5. **Preset switch while brightness was adjusted** → switching Movie→Party→Movie restores Movie's adjusted value, Party runs at its own value. (Test in Task 5.)

---

### Task 0: Toolchain and project scaffold

**Files:**
- Create: `firetv/settings.gradle.kts`, `firetv/build.gradle.kts`, `firetv/gradle.properties`, `firetv/gradle/wrapper/*`, `firetv/gradlew`, `firetv/.gitignore`
- Create: `firetv/app/build.gradle.kts`, `firetv/app/src/main/AndroidManifest.xml`, `firetv/app/src/main/java/com/shreyas/wizsync/tv/ui/MainActivity.kt`, `firetv/app/src/main/res/values/strings.xml`, `firetv/app/src/main/res/values/themes.xml`, `firetv/app/src/main/res/drawable/banner.png` (320×180 placeholder), `firetv/app/src/main/res/layout/activity_main.xml` (single `TextView` "WiZ Sync")
- Test: `firetv/app/src/test/java/com/shreyas/wizsync/tv/SmokeTest.kt`

**Interfaces:**
- Produces: package `com.shreyas.wizsync.tv` with subpackages `ui`, `sync`, `wiz`, `store` used by every later task.

- [ ] **Step 1: Install JDK 21**

Run: `brew install --cask temurin@21 && /usr/libexec/java_home -v 21`
Expected: prints a `/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home` path.

- [ ] **Step 2: Create the Gradle project**

`settings.gradle.kts`: `pluginManagement` with google/mavenCentral/gradlePluginPortal, `rootProject.name = "wizsync-tv"`, `include(":app")`.
`build.gradle.kts` (root): plugins `com.android.application` 8.11.0 and `org.jetbrains.kotlin.android` 2.0.21, both `apply false`.
`gradle.properties`: `android.useAndroidX=true`, `org.gradle.jvmargs=-Xmx2g`.
`app/build.gradle.kts`: the Global Constraints SDK values, `kotlin { jvmToolchain(21) }`, `buildFeatures { viewBinding = true }`, dependencies `androidx.appcompat:appcompat:1.7.0`, `androidx.core:core-ktx:1.13.1`, `testImplementation("junit:junit:4.13.2")`, `testImplementation(kotlin("test"))`.
Generate the wrapper with a one-off Gradle 8.14 download: `cd firetv && curl -sL https://services.gradle.org/distributions/gradle-8.14-bin.zip -o /tmp/g.zip && unzip -q /tmp/g.zip -d /tmp/g && /tmp/g/gradle-8.14/bin/gradle wrapper --gradle-version 8.14` (use the scratchpad directory rather than `/tmp` if the session has one).

- [ ] **Step 3: Write the manifest**

`<uses-feature android:name="android.software.leanback" android:required="true"/>`, `<uses-feature android:name="android.hardware.touchscreen" android:required="false"/>`, the four permissions, `<application android:banner="@drawable/banner" android:theme="@style/Theme.WizSync">`, `MainActivity` exported with `MAIN` + `LEANBACK_LAUNCHER`. Theme parent `Theme.AppCompat.NoActionBar`, dark background `#101418`.

- [ ] **Step 4: Write the smoke test**

```kotlin
class SmokeTest { @Test fun `math works`() = assertEquals(4, 2 + 2) }
```

- [ ] **Step 5: Build and test**

Run: `cd firetv && JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test assembleDebug`
Expected: BUILD SUCCESSFUL; `app/build/outputs/apk/debug/app-debug.apk` exists.

- [ ] **Step 6: Commit**

```bash
git add firetv && git commit -m "feat(firetv): scaffold Fire TV app project"
```

---

### Task 1: Capture spike (throwaway, gates the project)

**Files:**
- Create: `firetv/app/src/main/java/com/shreyas/wizsync/tv/spike/SpikeActivity.kt`, `firetv/app/src/main/java/com/shreyas/wizsync/tv/spike/SpikeService.kt`
- Modify: `firetv/app/src/main/AndroidManifest.xml` (SpikeActivity gets the launcher intents temporarily; MainActivity keeps none)

**Interfaces:**
- Produces: nothing kept. Deleted in Task 9.

- [ ] **Step 1: Write SpikeActivity**

On create: `MediaProjectionManager.createScreenCaptureIntent()` via `startActivityForResult(…, 1)`. On result OK: start `SpikeService` as a foreground service passing `resultCode` and `data`, then `finish()`. On cancelled: `Log.w("WIZSPIKE", "consent declined")`.

- [ ] **Step 2: Write SpikeService**

Foreground service (notification channel "wizsync", low importance). `getMediaProjection(resultCode, data)`, `ImageReader.newInstance(96, 54, PixelFormat.RGBA_8888, 2)`, `createVirtualDisplay("wizspike", 96, 54, 160, VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, null)`. Every second: acquire latest image, compute mean R,G,B over the first plane honouring `rowStride`, `Log.i("WIZSPIKE", "avg r=%d g=%d b=%d")`, close image. Self-stop after 10 minutes.

- [ ] **Step 3: Install on the Stick**

Run (Stick on same Wi-Fi, ADB debugging and Unknown Sources on):
```bash
ADB=~/Library/Android/sdk/platform-tools/adb; $ADB connect <stick-ip>:5555 && cd firetv && JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew installDebug && $ADB shell am start -n com.shreyas.wizsync.tv/.spike.SpikeActivity && $ADB logcat -s WIZSPIKE
```
Expected: consent dialog visible on TV and operable with D-pad (ask the user to accept it, since the agent cannot press the remote).

- [ ] **Step 4: Verify with YouTube**

Ask the user to open YouTube and play a colourful music video. Expected in logcat: `avg` values that change scene to scene and are not all < 8. Record the result (three sample lines) in the plan's commit message.
**If the dialog never appears, is not D-pad operable, or YouTube logs only near-black: STOP. Report to the user; the spec's scope needs rethinking before Task 2.**

- [ ] **Step 5: Commit**

```bash
git add firetv && git commit -m "spike(firetv): verify MediaProjection capture of YouTube on Fire OS 7 — <result>"
```

---

### Task 2: Colour extraction functions

**Files:**
- Create: `firetv/app/src/main/java/com/shreyas/wizsync/tv/sync/Frame.kt`, `firetv/app/src/main/java/com/shreyas/wizsync/tv/sync/ColorMath.kt`
- Test: `firetv/app/src/test/java/com/shreyas/wizsync/tv/sync/ColorMathTest.kt`

**Interfaces:**
- Produces:
  - `class Frame(val width: Int, val height: Int, val px: IntArray)` — `px[y*width+x]` is `0xRRGGBB`.
  - `typealias Rgb = FloatArray` (size 3, 0–255 floats).
  - `object ColorMath { fun average(f: Frame): Rgb; fun dominant(f: Frame): Rgb; fun brightest(f: Frame): Rgb; fun saturated(f: Frame): Rgb; fun cropBlackBars(f: Frame, threshold: Float = 14f, minKeep: Float = 0.2f): Frame; fun boostSaturation(c: Rgb, amount: Float): Rgb; fun smooth(prev: Rgb?, new: Rgb, factor: Float): Rgb; fun brightnessOf(c: Rgb): Float; fun distance(a: Rgb, b: Rgb): Float }`
  - `enum class Mode(val extract: (Frame) -> Rgb) { AVERAGE, DOMINANT, BRIGHTEST, SATURATED }`

- [ ] **Step 1: Write the failing tests** (port of `tests/test_core.py` colour cases)

```kotlin
private fun solid(r:Int,g:Int,b:Int,h:Int=9,w:Int=16) = Frame(w,h,IntArray(w*h){ (r shl 16) or (g shl 8) or b })
private fun Frame.fill(x0:Int,y0:Int,x1:Int,y1:Int,r:Int,g:Int,b:Int) { for(y in y0 until y1) for(x in x0 until x1) px[y*width+x]=(r shl 16) or (g shl 8) or b }

@Test fun `average of halves`() { val f=solid(0,0,0); f.fill(8,0,16,9,200,100,0); assertArrayEquals(floatArrayOf(100f,50f,0f), ColorMath.average(f), 0.01f) }
@Test fun `dominant ignores black and prefers saturated`() { val f=solid(0,0,0); f.fill(0,0,4,2,250,10,10); f.fill(0,4,16,9,120,120,120); val c=ColorMath.dominant(f); assertTrue(c[0]>200 && c[1]<40) }
@Test fun `dominant all black`() = assertArrayEquals(floatArrayOf(0f,0f,0f), ColorMath.dominant(solid(0,0,0)), 0f)
@Test fun `smoothing`() { assertArrayEquals(floatArrayOf(10f,10f,10f), ColorMath.smooth(null, floatArrayOf(10f,10f,10f), 0.5f), 0f); assertArrayEquals(floatArrayOf(25f,25f,25f), ColorMath.smooth(floatArrayOf(0f,0f,0f), floatArrayOf(100f,100f,100f), 0.75f), 0.001f) }
@Test fun `saturation boost keeps grey`() = assertArrayEquals(floatArrayOf(128f,128f,128f), ColorMath.boostSaturation(floatArrayOf(128f,128f,128f), 2f), 0.01f)
@Test fun `brightest and saturated pick different colours`() { val f=solid(30,30,30); f.fill(0,0,16,3,240,240,235); f.fill(0,3,4,5,200,0,20); val br=ColorMath.brightest(f); val sat=ColorMath.saturated(f); assertTrue(br.min()>200); assertTrue(sat[0]>150 && sat[1]<60) }
@Test fun `all extractors handle black`() { for (m in Mode.values()) assertEquals(0f, m.extract(solid(0,0,0)).max()) }
@Test fun `crop black bars trims letterbox`() { val f=solid(0,0,0,h=10,w=10); f.fill(0,2,10,8,200,200,200); val c=ColorMath.cropBlackBars(f); assertEquals(10,c.width); assertEquals(6,c.height) }
@Test fun `crop black bars keeps full frame when almost all dark`() { val f=solid(0,0,0,h=10,w=10); f.fill(0,0,1,1,200,200,200); val c=ColorMath.cropBlackBars(f); assertEquals(10,c.height) }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd firetv && JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test --tests '*ColorMathTest*'`
Expected: compilation failure (unresolved `Frame`, `ColorMath`).

- [ ] **Step 3: Implement `Frame`, `ColorMath`, `Mode`**

Port each Python function line for line. `dominant`: ignore pixels with max channel ≤ 24; weight `0.05 + sat²`; bucket key `(r/32)*64 + (g/32)*8 + b/32`; return mean of the winning bucket's members. `brightest`/`saturated`: top 5 % (`max(1, n*0.05)`) by luminance `0.2126R+0.7152G+0.0722B` and by `sat*(0.5+max/510)` over pixels with max > 40 respectively. `cropBlackBars`: rows/cols whose mean over all channels > threshold; fall back to full frame when none or when result < `minKeep` of either dimension. `boostSaturation`: RGB→HSV, `s = min(1, s*amount)`, back. `smooth`: `prev*factor + new*(1-factor)`, `prev == null → new`.

- [ ] **Step 4: Run tests to verify they pass**

Same command. Expected: all 9 PASS.

- [ ] **Step 5: Commit**

```bash
git add firetv/app/src && git commit -m "feat(firetv): port colour extraction maths"
```

---

### Task 3: Presets and per-zone pipeline state

**Files:**
- Create: `firetv/app/src/main/java/com/shreyas/wizsync/tv/sync/Presets.kt`, `firetv/app/src/main/java/com/shreyas/wizsync/tv/sync/Pipeline.kt`
- Test: `firetv/app/src/test/java/com/shreyas/wizsync/tv/sync/PipelineTest.kt`

**Interfaces:**
- Consumes: `Frame`, `Rgb`, `ColorMath`, `Mode` (Task 2).
- Produces:
  - `enum class BrightnessMode { FOLLOW, FIXED }`
  - `data class Preset(val id: String, val label: String, val mode: Mode, val smoothing: Float, val saturation: Float, val brightnessMode: BrightnessMode, val defaultBrightness: Int, val leadMs: Int, val rate: Float, val ignoreBlackBars: Boolean)`; `object Presets { val MOVIE, GAMING, AMBIENT, PARTY: Preset; val ALL: List<Preset>; fun byId(id: String): Preset }`. `defaultBrightness` is `min_brightness` for FOLLOW presets and `brightness` for FIXED ones (15, 20, 60, 100).
  - `data class BulbCommand(val r: Int, val g: Int, val b: Int, val dimming: Int)`
  - `class Pipeline(val clock: () -> Long = System::nanoTime) { fun step(frame: Frame, preset: Preset, brightness: Int): BulbCommand? }` — returns null when the dead-band says nothing changed. `brightness` is the per-preset slider value (min brightness for FOLLOW, fixed level for FIXED).
  - `val WARM_WHITE = BulbCommand(255, 214, 170, 80)` (top-level in `Pipeline.kt`)

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `presets match engine py`() {
  assertEquals(Mode.AVERAGE, Presets.MOVIE.mode); assertEquals(0.45f, Presets.MOVIE.smoothing); assertEquals(1.4f, Presets.MOVIE.saturation); assertEquals(BrightnessMode.FOLLOW, Presets.MOVIE.brightnessMode); assertEquals(15, Presets.MOVIE.defaultBrightness); assertEquals(80, Presets.MOVIE.leadMs); assertEquals(20f, Presets.MOVIE.rate); assertTrue(Presets.MOVIE.ignoreBlackBars)
  assertEquals(Mode.DOMINANT, Presets.GAMING.mode); assertEquals(0.05f, Presets.GAMING.smoothing); assertEquals(20, Presets.GAMING.defaultBrightness); assertFalse(Presets.GAMING.ignoreBlackBars)
  assertEquals(BrightnessMode.FIXED, Presets.AMBIENT.brightnessMode); assertEquals(60, Presets.AMBIENT.defaultBrightness); assertEquals(0, Presets.AMBIENT.leadMs); assertEquals(10f, Presets.AMBIENT.rate)
  assertEquals(Mode.SATURATED, Presets.PARTY.mode); assertEquals(2.5f, Presets.PARTY.saturation); assertEquals(100, Presets.PARTY.defaultBrightness); assertEquals(0f, Presets.PARTY.smoothing)
}
@Test fun `solid red with no smoothing sends red at full dimming in fixed mode`() {
  val p = Pipeline(); val c = p.step(solid(255,0,0), Presets.PARTY.copy(saturation=1f), 100)!!
  assertEquals(255, c.r); assertEquals(0, c.g); assertEquals(100, c.dimming)
}
@Test fun `follow mode maps level onto min brightness range`() {
  val c = Pipeline().step(solid(128,128,128), Presets.MOVIE.copy(smoothing=0f, saturation=1f, leadMs=0), 20)!!
  assertTrue(abs(c.dimming - 60) <= 1)   // 20 + 80 * 128/255 ≈ 60.2
}
@Test fun `dead band suppresses tiny changes`() {
  val p = Pipeline(); val pr = Presets.PARTY.copy(saturation=1f)
  assertNotNull(p.step(solid(200,100,50), pr, 100)); assertNull(p.step(solid(201,101,51), pr, 100))
}
@Test fun `near black keeps last hue and drops dimming`() {
  val p = Pipeline(); val pr = Presets.MOVIE.copy(smoothing=0f, saturation=1f, leadMs=0)
  p.step(solid(0,0,255), pr, 10); val c = p.step(solid(1,1,1), pr, 10)!!
  assertEquals(255, c.b); assertEquals(10, c.dimming)
}
@Test fun `dimming is always clamped 10 to 100`() {
  for (b in listOf(10, 55, 100)) { val c = Pipeline().step(solid(255,255,255), Presets.AMBIENT, b)!!; assertTrue(c.dimming in 10..100) }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests '*PipelineTest*'` (with `JAVA_HOME` as above). Expected: compilation failure.

- [ ] **Step 3: Implement `Presets` and `Pipeline.step`**

`step` is `_Pipeline.step` from `engine.py`: crop bars if `preset.ignoreBlackBars`, extract with `preset.mode`, boost saturation, `smooth(prev, raw, smoothing.pow(10/rate))`, velocity damped with 0.6 using `clock()` seconds, `target = smoothed + vel*leadMs/1000` clamped 0–255, `level = brightnessOf(target)`; `level < 0.02 → rgb = lastSent.rgb ?: white` else `rgb = target/level`; `dim = brightness` (FIXED) or `brightness + (100-brightness)*level` (FOLLOW); clamp 10–100; dead-band `distance ≤ 2 && |Δdim| ≤ 1 → null`.

- [ ] **Step 4: Run tests to verify they pass**

Expected: 6 PASS.

- [ ] **Step 5: Commit**

```bash
git add firetv/app/src && git commit -m "feat(firetv): presets and colour pipeline state"
```

---

### Task 4: WiZ UDP client

**Files:**
- Create: `firetv/app/src/main/java/com/shreyas/wizsync/tv/wiz/WizClient.kt`, `firetv/app/src/main/java/com/shreyas/wizsync/tv/wiz/WizJson.kt`
- Test: `firetv/app/src/test/java/com/shreyas/wizsync/tv/wiz/WizClientTest.kt`

**Interfaces:**
- Consumes: `BulbCommand` (Task 3).
- Produces:
  - `data class Discovered(val ip: String, val mac: String)`
  - `class WizClient(val port: Int = 38899) : Closeable { fun send(ip: String, cmd: BulbCommand); fun setState(ip: String, on: Boolean); fun discover(broadcast: String = "255.255.255.255", timeoutMs: Long = 2000): List<Discovered>; fun ping(ip: String, timeoutMs: Long = 500): Boolean; fun identify(ip: String) }` — `send`/`setState` are fire-and-forget on one reused `DatagramSocket`; `discover` and `ping` block the calling thread; `identify` runs its 3-step blink on the calling thread (300 ms gaps) and does not restore (caller does).
  - `object WizJson { fun setPilot(cmd: BulbCommand): String; fun setState(on: Boolean): String; val getPilot: String; fun parseMac(reply: String): String? }` — hand-written JSON via `org.json` (in Android) *and* tests: add `testImplementation("org.json:json:20240303")`.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `setPilot json matches python client`() {
  val j = JSONObject(WizJson.setPilot(BulbCommand(10,20,30,55)))
  assertEquals("setPilot", j.getString("method")); val p = j.getJSONObject("params")
  assertTrue(p.getBoolean("state")); assertEquals(10, p.getInt("r")); assertEquals(30, p.getInt("b")); assertEquals(55, p.getInt("dimming"))
}
@Test fun `setPilot clamps dimming`() { assertEquals(10, JSONObject(WizJson.setPilot(BulbCommand(0,0,0,3))).getJSONObject("params").getInt("dimming")) }
@Test fun `parseMac reads result mac`() = assertEquals("a8bb50123456", WizJson.parseMac("""{"method":"getPilot","result":{"mac":"a8bb50123456","state":true}}"""))

/** Fake bulb: a DatagramSocket on 127.0.0.1 that records datagrams and answers getPilot with a mac. */
class FakeBulb(val mac: String) : Closeable { val socket = DatagramSocket(0, InetAddress.getLoopbackAddress()); val received = CopyOnWriteArrayList<String>(); var answer = true; /* thread: loop receive, add to received, if contains getPilot && answer -> reply {"result":{"mac":mac}} */ }

@Test fun `send delivers setPilot datagram`() { FakeBulb("aa").use { b -> WizClient(b.socket.localPort).use { it.send("127.0.0.1", BulbCommand(1,2,3,50)) }; await { b.received.any { it.contains("setPilot") } } } }
@Test fun `ping true when bulb answers, false when silent`() { FakeBulb("aa").use { b -> WizClient(b.socket.localPort).use { c -> assertTrue(c.ping("127.0.0.1", 500)); b.answer = false; assertFalse(c.ping("127.0.0.1", 300)) } } }
@Test fun `discover collects replies by ip`() { FakeBulb("abc123").use { b -> WizClient(b.socket.localPort).use { c -> val found = c.discover(broadcast = "127.0.0.1", timeoutMs = 600); assertEquals(listOf(Discovered("127.0.0.1","abc123")), found) } } }
```
`await` is a 2 s polling helper in the test file.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests '*WizClientTest*'`. Expected: compilation failure.

- [ ] **Step 3: Implement `WizJson` and `WizClient`**

`discover`: open a second broadcast-enabled socket on port 0, send `getPilot` twice `timeoutMs/2` apart, collect replies until the timeout, dedupe by IP. `ping`: send `getPilot` from the main socket with `soTimeout`, return whether a reply with a mac arrived from that IP (drain unrelated packets). `identify`: white dimming 100 → 300 ms → dimming 20 → 300 ms → white 100.

- [ ] **Step 4: Run tests to verify they pass**

Expected: 6 PASS.

- [ ] **Step 5: Commit**

```bash
git add firetv/app && git commit -m "feat(firetv): WiZ UDP client with discovery and health ping"
```

---

### Task 5: Settings store

**Files:**
- Create: `firetv/app/src/main/java/com/shreyas/wizsync/tv/store/KeyValueStore.kt`, `firetv/app/src/main/java/com/shreyas/wizsync/tv/store/Settings.kt`, `firetv/app/src/main/java/com/shreyas/wizsync/tv/store/PrefsStore.kt`
- Test: `firetv/app/src/test/java/com/shreyas/wizsync/tv/store/SettingsTest.kt`

**Interfaces:**
- Consumes: `Presets`, `Preset` (Task 3), `Discovered` (Task 4).
- Produces:
  - `interface KeyValueStore { fun get(key: String): String?; fun put(key: String, value: String) }`; `class MapStore : KeyValueStore` (tests); `class PrefsStore(ctx: Context) : KeyValueStore` over `SharedPreferences("wizsync")`.
  - `data class Bulb(val mac: String, val ip: String, val name: String, val enabled: Boolean)`
  - `class Settings(store: KeyValueStore) { val bulbs: List<Bulb>; val presetId: String; fun brightnessFor(presetId: String): Int; fun setBrightness(presetId: String, value: Int); fun setPreset(id: String); fun upsertFromDiscovery(found: List<Discovered>); fun setEnabled(mac: String, enabled: Boolean); fun rename(mac: String, name: String); fun remove(mac: String); fun updateIp(mac: String, ip: String); fun enabledBulbs(): List<Bulb> }` — persists to the store on every mutation as one JSON document under key `"settings"`.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `defaults`() { val s = Settings(MapStore()); assertEquals("movie", s.presetId); assertEquals(15, s.brightnessFor("movie")); assertEquals(100, s.brightnessFor("party")); assertTrue(s.bulbs.isEmpty()) }
@Test fun `discovery adds ticked bulbs named by mac tail and dedupes by mac`() {
  val s = Settings(MapStore()); s.upsertFromDiscovery(listOf(Discovered("10.0.0.5","a8bb50003f2a")))
  s.upsertFromDiscovery(listOf(Discovered("10.0.0.9","a8bb50003f2a")))
  assertEquals(1, s.bulbs.size); assertEquals("WiZ 3F2A", s.bulbs[0].name); assertEquals("10.0.0.9", s.bulbs[0].ip); assertTrue(s.bulbs[0].enabled)
}
@Test fun `rediscovery keeps rename and tick`() { val s = Settings(MapStore()); s.upsertFromDiscovery(listOf(Discovered("1.1.1.1","aa"))); s.rename("aa","Floor lamp"); s.setEnabled("aa",false); s.upsertFromDiscovery(listOf(Discovered("1.1.1.2","aa"))); assertEquals("Floor lamp", s.bulbs[0].name); assertFalse(s.bulbs[0].enabled) }
@Test fun `brightness is stored per preset and survives switching`() {
  val s = Settings(MapStore()); s.setPreset("movie"); s.setBrightness("movie", 40); s.setPreset("party"); assertEquals(100, s.brightnessFor("party")); s.setPreset("movie"); assertEquals(40, s.brightnessFor("movie"))
}
@Test fun `brightness clamps 10 to 100 and persists across instances`() {
  val m = MapStore(); Settings(m).setBrightness("ambient", 300); assertEquals(100, Settings(m).brightnessFor("ambient")); Settings(m).setBrightness("ambient", 1); assertEquals(10, Settings(m).brightnessFor("ambient"))
}
@Test fun `remove and enabledBulbs`() { val s = Settings(MapStore()); s.upsertFromDiscovery(listOf(Discovered("1.1.1.1","aa"), Discovered("1.1.1.2","bb"))); s.setEnabled("bb", false); assertEquals(listOf("aa"), s.enabledBulbs().map { it.mac }); s.remove("aa"); assertEquals(1, s.bulbs.size) }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests '*SettingsTest*'`. Expected: compilation failure.

- [ ] **Step 3: Implement the store classes**

JSON shape: `{"preset":"movie","brightness":{"movie":40},"bulbs":[{"mac":..,"ip":..,"name":..,"enabled":..}]}`. `brightnessFor` falls back to `Presets.byId(id).defaultBrightness`. MAC compared case-insensitively; default name `"WiZ " + mac.takeLast(4).uppercase()`.

- [ ] **Step 4: Run tests to verify they pass**

Expected: 6 PASS.

- [ ] **Step 5: Commit**

```bash
git add firetv/app/src && git commit -m "feat(firetv): persisted settings with per-preset brightness"
```

---

### Task 6: Sync engine (Android-free loop)

**Files:**
- Create: `firetv/app/src/main/java/com/shreyas/wizsync/tv/sync/FrameSource.kt`, `firetv/app/src/main/java/com/shreyas/wizsync/tv/sync/BulbSink.kt`, `firetv/app/src/main/java/com/shreyas/wizsync/tv/sync/SyncEngine.kt`, `firetv/app/src/main/java/com/shreyas/wizsync/tv/sync/RgbaDecoder.kt`
- Test: `firetv/app/src/test/java/com/shreyas/wizsync/tv/sync/SyncEngineTest.kt`, `firetv/app/src/test/java/com/shreyas/wizsync/tv/sync/RgbaDecoderTest.kt`

**Interfaces:**
- Consumes: `Frame`, `Pipeline`, `Preset`, `BulbCommand`, `WARM_WHITE` (Tasks 2–3), `Bulb`, `Settings` (Task 5).
- Produces:
  - `interface FrameSource { fun latest(): Frame? }` — non-blocking, returns the newest frame or null.
  - `interface BulbSink { fun send(ip: String, cmd: BulbCommand); fun setState(ip: String, on: Boolean); fun ping(ip: String): Boolean }` — `WizClient` will implement this in Task 7 via a thin adapter.
  - `object RgbaDecoder { fun decode(buf: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int, into: IntArray): Frame }`
  - `sealed class SyncState { object Ready; data class Syncing(val fps: Float, val color: BulbCommand); object PausedNoPicture; data class Error(val message: String) }`
  - `class SyncEngine(val source: FrameSource, val sink: BulbSink, val settings: Settings, val clock: () -> Long = System::nanoTime, val sleep: (Long) -> Unit = Thread::sleep) { @Volatile var state: SyncState; var onState: ((SyncState) -> Unit)?; val unhealthy: Set<String>; val muted: MutableSet<String>; fun runLoop(stop: () -> Boolean); fun applyResting(); fun applyOff(); fun tick(nowNs: Long) }` — `runLoop` calls `tick` at the active preset's rate until `stop()` is true, then `applyResting()`. `tick` is public so tests drive it with a fake clock without threads. `unhealthy` and `muted` are keyed by IP; a bulb whose IP is in `muted` receives no sends (used by Identify in Task 8).

- [ ] **Step 1: Write the failing decoder test**

```kotlin
@Test fun `decode honours row stride padding`() {
  val w=2; val h=2; val stride=12 /* 2 px * 4 + 4 bytes padding */
  val bytes = ByteArray(stride*h); fun put(x:Int,y:Int,r:Int,g:Int,b:Int){ val o=y*stride+x*4; bytes[o]=r.toByte(); bytes[o+1]=g.toByte(); bytes[o+2]=b.toByte(); bytes[o+3]=-1 }
  put(0,0,255,0,0); put(1,0,0,255,0); put(0,1,0,0,255); put(1,1,10,20,30)
  val f = RgbaDecoder.decode(ByteBuffer.wrap(bytes), w, h, stride, 4, IntArray(w*h))
  assertEquals(0xFF0000, f.px[0]); assertEquals(0x00FF00, f.px[1]); assertEquals(0x0000FF, f.px[2]); assertEquals(0x0A141E, f.px[3])
}
```

- [ ] **Step 2: Write the failing engine tests**

```kotlin
class FakeSource : FrameSource { @Volatile var frame: Frame? = null; override fun latest() = frame }
class FakeSink : BulbSink { val sent = mutableListOf<Pair<String,BulbCommand>>(); val states = mutableListOf<Pair<String,Boolean>>(); var alive = true
  override fun send(ip:String, cmd:BulbCommand){ sent += ip to cmd }; override fun setState(ip:String,on:Boolean){ states += ip to on }; override fun ping(ip:String)=alive }
private fun engine(src: FakeSource, sink: FakeSink, vararg ips: String): Pair<SyncEngine, Settings> {
  val s = Settings(MapStore()); s.upsertFromDiscovery(ips.mapIndexed { i, ip -> Discovered(ip, "mac$i") }); s.setPreset("party")
  var now = 0L; return SyncEngine(src, sink, s, clock = { now }, sleep = { now += it*1_000_000 }) to s
}
@Test fun `sends colour to every ticked bulb and switches with the frame`() {
  val src = FakeSource(); val sink = FakeSink(); val (e, s) = engine(src, sink, "1.1.1.1", "1.1.1.2"); s.setEnabled("mac1", false)
  src.frame = solid(255,0,0); e.tick(0); src.frame = solid(0,0,255); e.tick(100_000_000)
  assertTrue(sink.sent.all { it.first == "1.1.1.1" }); assertEquals(255, sink.sent.first().second.r); assertEquals(255, sink.sent.last().second.b)
}
@Test fun `black for one second fades to resting and resumes on picture`() {
  val src = FakeSource(); val sink = FakeSink(); val (e, _) = engine(src, sink, "1.1.1.1")
  src.frame = solid(0,0,200); e.tick(0); src.frame = solid(2,2,2)
  var t = 0L; while (t < 1_600_000_000) { t += 50_000_000; e.tick(t) }
  assertEquals(SyncState.PausedNoPicture, e.state); assertEquals(WARM_WHITE, sink.sent.last().second)
  src.frame = solid(0,200,0); e.tick(t + 50_000_000); assertTrue(e.state is SyncState.Syncing); assertEquals(200, sink.sent.last().second.g)
}
@Test fun `applyResting sends warm white and applyOff sends state false`() {
  val sink = FakeSink(); val (e, _) = engine(FakeSource(), sink, "1.1.1.1"); e.applyResting(); assertEquals(WARM_WHITE, sink.sent.last().second); e.applyOff(); assertEquals("1.1.1.1" to false, sink.states.last())
}
@Test fun `health marks a bulb unhealthy after two missed pings and clears on reply`() {
  val src = FakeSource(); val sink = FakeSink(); val (e, _) = engine(src, sink, "1.1.1.1"); src.frame = solid(200,0,0); sink.alive = false
  var t = 0L; repeat(130) { i -> t += 40_000_000; src.frame = solid(40 + (i * 37) % 200, 0, 0); e.tick(t) }   // jumps > dead-band → 130 sends → ≥2 health checks
  assertTrue("1.1.1.1" in e.unhealthy); sink.alive = true; repeat(70) { i -> t += 40_000_000; src.frame = solid(40 + (i * 37) % 200, 0, 0); e.tick(t) }; assertFalse("1.1.1.1" in e.unhealthy)
}
@Test fun `muted bulb receives nothing`() {
  val src = FakeSource(); val sink = FakeSink(); val (e, _) = engine(src, sink, "1.1.1.1", "1.1.1.2"); e.muted += "1.1.1.2"; src.frame = solid(200,0,0); e.tick(0)
  assertTrue(sink.sent.isNotEmpty()); assertTrue(sink.sent.none { it.first == "1.1.1.2" })
}
@Test fun `runLoop stops and applies resting`() {
  val src = FakeSource(); val sink = FakeSink(); val (e, _) = engine(src, sink, "1.1.1.1"); src.frame = solid(0,200,0); var n = 0
  e.runLoop(stop = { ++n > 5 }); assertEquals(WARM_WHITE, sink.sent.last().second); assertEquals(SyncState.Ready, e.state)
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew test --tests '*SyncEngineTest*' --tests '*RgbaDecoderTest*'`. Expected: compilation failure.

- [ ] **Step 4: Implement `RgbaDecoder`, `SyncEngine`**

`tick(nowNs)`: take `source.latest()` (null → return); run one `Pipeline` with the active preset and `settings.brightnessFor(presetId)`; compute `avgMax = ColorMath.brightnessOf(ColorMath.average(frame))*255`; if `avgMax < 8` start/continue a black timer, at ≥ 1.0 s switch to `PausedNoPicture` and send `WARM_WHITE` once (the 0.5 s fade is 5 intermediate `BulbCommand`s interpolating from last sent to `WARM_WHITE` spread over the next ticks); any frame with `avgMax ≥ 8` resets the timer and resumes `Syncing`. Send the pipeline's command to every `settings.enabledBulbs()`; a bulb that becomes unticked between ticks receives `WARM_WHITE` once. Every 60 sends per bulb run `sink.ping(ip)` synchronously in tests (in Task 7 the adapter runs pings on their own thread and returns the cached last result); two consecutive misses add to `unhealthy`, a hit removes; while in `PausedNoPicture` (no sends) ping every 3 s of clock time instead. Skip sends to IPs in `muted`. `state = Syncing(fps, lastColor)` with fps recomputed each second from the clock. `runLoop`: `while(!stop()) { tick(clock()); sleep(1000/rate - elapsed) }` then `applyResting()`, `state = Ready`.

- [ ] **Step 5: Run tests to verify they pass**

Expected: 7 PASS.

- [ ] **Step 6: Commit**

```bash
git add firetv/app/src && git commit -m "feat(firetv): sync engine loop with black-frame fallback and health"
```

---

### Task 7: Foreground service and projection glue

**Files:**
- Create: `firetv/app/src/main/java/com/shreyas/wizsync/tv/sync/SyncService.kt`, `firetv/app/src/main/java/com/shreyas/wizsync/tv/sync/ProjectionFrameSource.kt`, `firetv/app/src/main/java/com/shreyas/wizsync/tv/wiz/WizSink.kt`
- Modify: `firetv/app/src/main/AndroidManifest.xml` (declare `SyncService`, `android:foregroundServiceType` omitted for API 28)

**Interfaces:**
- Consumes: `SyncEngine`, `FrameSource`, `BulbSink`, `RgbaDecoder` (Task 6), `WizClient` (Task 4), `PrefsStore`, `Settings` (Task 5).
- Produces:
  - `class ProjectionFrameSource(projection: MediaProjection, width: Int = 96, height: Int = 54) : FrameSource, Closeable` — `ImageReader` with 2 buffers, `VirtualDisplay` with `VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR`, decodes into a double-buffered `IntArray` on the reader's `HandlerThread`, `latest()` returns the most recent decoded `Frame` (same instance until a new one lands).
  - `class WizSink(client: WizClient) : BulbSink` — `send`/`setState` pass through; `ping` returns the last result of a ping run on a dedicated single thread (fires a new one if none in flight), initial value `true`.
  - `class SyncService : Service()` with companion `fun start(ctx, resultCode: Int, data: Intent)`, `fun stop(ctx)`, `LocalBinder` exposing `val engine: SyncEngine?` and `fun addListener((SyncState) -> Unit)`. Actions: `ACTION_START` (extras `resultCode`, `data`), `ACTION_STOP`. Registers `ACTION_SCREEN_OFF` receiver → `engine.applyOff()`, release projection, `stopSelf()`. `MediaProjection.Callback.onStop` → `engine.applyResting()`, `stopSelf()`. Notification channel `wizsync`, id 1, text "WiZ Sync is syncing your lights".
  - Settings changes from the UI (preset, brightness, ticks) are read by the engine on each tick through the shared `Settings` instance (one `Settings` per process, created in a tiny `App : Application` and exposed as `App.settings`).

- [ ] **Step 1: Implement the three classes and the `App` class**

Worker: a `Thread("wizsync-loop")` running `engine.runLoop(stop = { stopping })`. Acquire a `WifiManager.MulticastLock("wizsync")` only around discovery (that lives in the Activity, Task 8), not in the service.

- [ ] **Step 2: Build**

Run: `./gradlew assembleDebug`. Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Manual check on the Stick (temporarily reuse `SpikeActivity`)**

Point `SpikeActivity`'s result handler at `SyncService.start(...)` instead of `SpikeService`, with one bulb IP hard-coded into `App.settings` for this check only. Install, Start, accept consent, open YouTube: bulb follows. Press the remote's sleep button (hold Home → Sleep): bulb turns off and `adb shell dumpsys activity services com.shreyas.wizsync.tv` shows no `SyncService`. Revert the hard-coded IP.

- [ ] **Step 4: Commit**

```bash
git add firetv/app && git commit -m "feat(firetv): foreground sync service with projection frame source"
```

---

### Task 8: Main screen

**Files:**
- Create: `firetv/app/src/main/res/layout/activity_main.xml` (replace), `firetv/app/src/main/res/layout/item_bulb.xml`, `firetv/app/src/main/res/drawable/tile_bg.xml` (focus-state selector), `firetv/app/src/main/java/com/shreyas/wizsync/tv/ui/MainViewModel.kt`, `firetv/app/src/main/java/com/shreyas/wizsync/tv/ui/BulbAdapter.kt`, `firetv/app/src/main/java/com/shreyas/wizsync/tv/ui/BulbMenuDialog.kt`
- Modify: `firetv/app/src/main/java/com/shreyas/wizsync/tv/ui/MainActivity.kt`, `firetv/app/src/main/res/values/strings.xml`
- Test: `firetv/app/src/test/java/com/shreyas/wizsync/tv/ui/MainViewModelTest.kt`

**Interfaces:**
- Consumes: `Settings`, `Bulb` (Task 5), `Presets` (Task 3), `WizClient.discover/identify` (Task 4), `SyncService`, `SyncState` (Task 7).
- Produces: `class MainViewModel(val settings: Settings) { val canStart: Boolean; val statusText: (SyncState) -> String; fun startLabel(state: SyncState): String; fun brightnessLabel(): String; fun adjustBrightness(delta: Int) }` — pure presentation logic, Android-free so it is unit-testable; the Activity owns views, the service binding and the consent `startActivityForResult`.

- [ ] **Step 1: Write the failing view-model tests**

```kotlin
@Test fun `cannot start with no ticked bulbs`() { val vm = MainViewModel(Settings(MapStore())); assertFalse(vm.canStart); assertEquals("Tick at least one bulb", vm.statusText(SyncState.Ready)) }
@Test fun `status strings`() { val s = Settings(MapStore()); s.upsertFromDiscovery(listOf(Discovered("1.1.1.1","aa"))); val vm = MainViewModel(s)
  assertEquals("Ready", vm.statusText(SyncState.Ready)); assertEquals("Syncing · 25 fps", vm.statusText(SyncState.Syncing(25.2f, WARM_WHITE))); assertEquals("Paused · no picture", vm.statusText(SyncState.PausedNoPicture)); assertEquals("Bulb 1.1.1.1 not responding", vm.statusText(SyncState.Error("Bulb 1.1.1.1 not responding")))
  assertEquals("Start", vm.startLabel(SyncState.Ready)); assertEquals("Stop", vm.startLabel(SyncState.Syncing(1f, WARM_WHITE))); assertEquals("Stop", vm.startLabel(SyncState.PausedNoPicture)) }
@Test fun `brightness label follows preset mode and steps by 5`() { val s = Settings(MapStore()); val vm = MainViewModel(s); s.setPreset("movie"); assertEquals("Min brightness 15%", vm.brightnessLabel()); vm.adjustBrightness(+1); assertEquals(20, s.brightnessFor("movie")); s.setPreset("ambient"); assertEquals("Brightness 60%", vm.brightnessLabel()); vm.adjustBrightness(-1); assertEquals(55, s.brightnessFor("ambient")) }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests '*MainViewModelTest*'`. Expected: compilation failure.

- [ ] **Step 3: Implement `MainViewModel`**

- [ ] **Step 4: Run tests to verify they pass**

Expected: 3 PASS.

- [ ] **Step 5: Build the layout and Activity**

Layout per the spec's ASCII mock: title + status + 24 dp colour swatch top row; "Bulbs" header with **Find bulbs** button; horizontal `RecyclerView` of tiles (`item_bulb.xml`: checkbox glyph, name 20 sp, IP 14 sp, warning badge view hidden by default; focused state scales 1.08 and shows a 3 dp accent border via `tile_bg.xml`); preset row of four `ToggleButton`-style buttons in a radio group; brightness row where the focused bar consumes `KEYCODE_DPAD_LEFT/RIGHT` to call `adjustBrightness`; Start/Stop button 320×64 dp; footer text `@string/consent_note` = "Fire OS asks for screen-capture permission each time you start." 48 dp margins, 1920×1080 design.
Activity: binds to `SyncService` on start; Start → `createScreenCaptureIntent()` → on OK `SyncService.start(resultCode, data)`; on cancel status "Capture permission needed"; Stop → `SyncService.stop`. Find bulbs → hold `MulticastLock`, run `WizClient.discover()` on a background thread, `settings.upsertFromDiscovery`, release lock, refresh adapter; status "No bulbs found. Enable local communication in the WiZ app." when empty. Tile Select → toggle tick; tile long-press or `KEYCODE_MENU` → `BulbMenuDialog` with Rename (uses `EditText` with on-screen keyboard), Identify (add the IP to `engine.muted` if syncing, run `WizClient.identify` on a background thread, then remove it from `muted` so the next tick restores the sync colour; when not syncing send `WARM_WHITE` after the blink), Remove. Initial focus: Start if `canStart`, else Find bulbs. Unhealthy MACs (from `engine.unhealthy`) show the badge.

- [ ] **Step 6: Manual check on the Stick**

`./gradlew installDebug`, launch from "Your Apps & Channels" (give MainActivity the launcher intents back; Spike keeps a plain `MAIN` action without launcher category for now). Walk every control with the remote: Find bulbs finds the real bulb; tick/untick; preset change while syncing changes behaviour; brightness label switches wording between Movie and Ambient; Start → consent → syncing with fps; Home → YouTube → bulb follows; return → Stop focused → Stop → warm white. Rename and Identify work.

- [ ] **Step 7: Commit**

```bash
git add firetv/app && git commit -m "feat(firetv): main screen with bulb tiles, presets, brightness and start/stop"
```

---

### Task 9: Remove spike, README, acceptance

**Files:**
- Delete: `firetv/app/src/main/java/com/shreyas/wizsync/tv/spike/`
- Modify: `firetv/app/src/main/AndroidManifest.xml`, `firetv/app/src/main/res/drawable/banner.png` (real 320×180 banner: dark background, "WiZ Sync" wordmark, a bulb glyph)
- Create: `firetv/README.md`
- Modify: `README.md` (repo root: one "Fire TV app" section linking to `firetv/README.md`)

- [ ] **Step 1: Delete the spike and its manifest entry; build**

Run: `./gradlew test assembleDebug`. Expected: BUILD SUCCESSFUL, all unit tests pass (count ≥ 36).

- [ ] **Step 2: Write `firetv/README.md`**

Sections: what it does and what it does not (content that other apps protect does not capture; YouTube, home screen, Plex/Kodi/VLC do); WiZ app toggle Settings → Security → Allow local communication; Stick setup (Settings → My Fire TV → Developer options → ADB debugging ON, Apps from Unknown Sources ON; IP under About → Network); build and install:
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
cd firetv && ./gradlew assembleDebug
~/Library/Android/sdk/platform-tools/adb connect <stick-ip>:5555
~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```
then "Launch from Your Apps & Channels"; the consent-each-start note; sleep turns bulbs off; troubleshooting (no bulbs found → multicast / same Wi-Fi; "not responding" badge → bulb IP changed, press Find bulbs).

- [ ] **Step 3: Final acceptance on the Stick**

Fresh install (`adb uninstall com.shreyas.wizsync.tv` first). Run the manual list from Task 8 Step 6 plus: kill the app from Settings → Applications while syncing and note whether the bulb returns to warm white or stays (record either outcome in the README's known-limitations line).

- [ ] **Step 4: Commit**

```bash
git add -A firetv README.md && git commit -m "feat(firetv): remove spike, add banner and sideload README"
```
