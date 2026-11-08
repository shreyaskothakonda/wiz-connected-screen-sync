# Amazon Appstore checklist (Fire TV)

Researched 2026-11-08 against Amazon's developer documentation. Each item quotes the
page it comes from and the "Last updated" date shown on that page, so it can be
re-checked quickly before the actual submission. Items marked **[owner]** need the
owner's account or device; everything else is done in this repo.

## 1. Target API level

| Fact | Source |
| --- | --- |
| "New submissions must target at least API level 34." Amazon "recommends you set targetSdkVersion to 36." | [Fire OS 16 page](https://developer.amazon.com/docs/fire-tv/fire-os-16.html), last updated 2026-06-30 |
| "You cannot submit new apps or update existing apps that do not meet the minimum API levels for targetSdkVersion" (enforced since 2022-04-01). Minimums are enforced on `targetSdkVersion`, not `minSdkVersion`. | [Device filtering and compatibility](https://developer.amazon.com/docs/app-submission/device-filtering-and-compatibility.html), last updated 2026-05-27; [Release notes](https://developer.amazon.com/docs/app-submission/release-notes.html) entry 2023-01-25 |
| Fire OS 14 page: "Amazon requires setting targetSdkVersion to 34." Apps targeting Android 14 "must declare a specific permission based on the foreground service type." | [Fire OS 14 page](https://developer.amazon.com/docs/fire-tv/fire-os-14.html), last updated 2026-06-30 |
| Fire OS to API mapping: Fire OS 5 = 22, 6 = 25, 7 = 28, 8 = 30 (29/30), 14 = 34 (31 to 34), 16 = 36 (35/36). | [Fire OS overview](https://developer.amazon.com/docs/fire-tv/fire-os-overview.html), last updated 2026-10-16 |

No separate calendar deadline is published; the minimum is enforced at upload time.

**Decision taken in this repo:** `targetSdk` raised from 28 to **34** (the published
minimum). `compileSdk` stays 36, `minSdk` stays 25 (Fire OS 6+). Going to 36 is
Amazon's recommendation but brings Android 15/16 behaviour changes (edge-to-edge,
tighter foreground-service rules) that cannot be tested on the owner's Fire OS 7 Stick;
revisit once a Fire OS 14/16 device is available.

Because the Stick runs Android 9 (API 28), none of the API 29+ behaviour changes gated
on `targetSdk` apply on it, so behaviour on the owner's device is unchanged.

### Manifest changes the bump needs (done)

- `<service android:foregroundServiceType="mediaProjection">` on `SyncService`.
  On API 29+ a MediaProjection may only be used from a foreground service declared with
  this type; API 34+ also throws if a started foreground service has no type.
- `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />`
  (API 34+: "you must declare the service type in your app manifest, and also request the
  appropriate foreground service permission for that type").
- Call order already correct: `startForeground()` runs in `onStartCommand` before
  `getMediaProjection()`, and `MediaProjection.Callback` is registered before
  `createVirtualDisplay()` (both required on API 34+).
- Source: [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types) (Android docs), and the Fire OS 8 page, which lists `mediaProjection` as a supported type: [Fire OS 8](https://developer.amazon.com/docs/fire-tv/fire-os-8.html).

Both attributes are ignored by Android 9 and compile against SDK 36, so the same APK
installs on Fire OS 7 and Fire OS 8/14.

- `registerReceiver` for `ACTION_SCREEN_OFF` needs no `RECEIVER_EXPORTED` flag on API 34:
  system (protected) broadcasts are exempt from that rule.

**Not done, to check on a Fire OS 14+ device [owner]:** `POST_NOTIFICATIONS` is a
runtime permission on API 33+. Without it the foreground service still runs but its
notification is hidden. Fire TV shows no notification shade, so this is cosmetic.

## 2. APK or App Bundle

- "The Amazon Appstore supports the Android App Bundle file format for app submission."
  "App bundles are optional. You can continue submitting apps using the existing APK
  format." Amazon converts bundles with its own bundletool 1.11; only install-time
  delivery is supported. [App bundles](https://developer.amazon.com/docs/app-submission/app-bundles.html), last updated 2025-09-30.
- "Amazon removes the signature you used to sign your app" and re-signs with an
  account-wide certificate. The APK must still be signed and zip-aligned to upload;
  `versionCode` must increase every release, `versionName` is required and under
  50 characters. Binaries up to 2.5 GB; keep under 50 MB to avoid warnings.
  [Understanding submission](https://developer.amazon.com/docs/app-submission/understanding-submission.html), last updated 2026-02-17.

**Decision:** submit a signed **release APK** (`./gradlew assembleRelease`). The app
has one ABI-independent artefact and no asset packs, so a bundle buys nothing. Keep
the upload keystore anyway: the Developer Console shows the Appstore certificate
hashes it will re-sign with, and the same keystore is needed for sideload updates
over the store build (different signer = uninstall first).

## 3. Listing images

[Appstore details, Images and videos](https://developer.amazon.com/docs/app-submission/appstore-details.html#images-and-videos), last updated 2026-01-06.

| Asset | Size | Format | Count | Status |
| --- | --- | --- | --- | --- |
| Small icon | 114 x 114 | PNG, transparency allowed | 1, required | generated: `docs/store/icon-114.png` |
| Large icon | 512 x 512 | PNG, transparency allowed | 1, required | generated: `docs/store/icon-512.png` |
| Fire TV app icon | 1280 x 720 | PNG, **no** transparency, subject inside 882 x 448 safe area | 1, required | generated: `docs/store/firetv-icon-1280x720.png` |
| Fire TV background | 1920 x 1080 | JPG or 24-bit PNG, no transparency, subject inside 1214 x 830 safe area | 1, required | generated: `docs/store/firetv-background-1920x1080.png` |
| Fire TV screenshots | 1920 x 1080 landscape | JPG or 24-bit PNG, no transparency | 3 to 10, required | **[owner]** take on device with `docs/store/screenshots.sh` |
| Tablet screenshots | 1920 x 1080 is an accepted size | PNG/JPEG | 3 to 10, required even for TV-only apps | reuse the Fire TV screenshots |
| Promotional image | 1024 x 500 | PNG/JPEG | optional | skip |
| Video | 720 x 1080 | various | optional | skip |

The 320 x 180 leanback banner in `res/drawable/banner.png` is the Android TV launcher
banner inside the APK; it is not uploaded separately and stays as it is.

Icons are regenerated from `res/drawable/ic_bulb.xml` with `docs/store/make-icons.sh`.

## 4. Listing text

Same page as above. Plain text only; HTML fails testing.

| Field | Limit |
| --- | --- |
| Display title | no limit, keep brief |
| Short description | 2,000 bytes; Fire TV shows only the first 200 characters |
| Long description | 4,000 characters; shown on the website and tablets, not on Fire TV |
| Product feature bullets | 3 to 5, one per line |
| Keywords | optional, comma separated |

Draft text is in `docs/store-listing.md`.

## 5. Content rating

Answered on the **Target Your App** screen. Amazon lists the categories rather than
the exact wording: *Subject matter* (Violence, Cartoon violence, Drugs, Nudity, Sex,
Intolerance, Profanity, Academic) and *Additional information* (Account creation or
other personal information collected, Advertisements, Gambling, Location detection,
User-generated content or user-to-user communication). Ratings can only change with a
new binary. Required for every submission.
[Target your app](https://developer.amazon.com/docs/app-submission/target-app.html), last updated 2026-07-17;
[Navigating app submission and compliance](https://developer.amazon.com/apps-and-games/blogs/2025/02/navigating-app-submission-and-compliance) (blog, 2025-02).

Proposed answers are in `docs/store-listing.md`; expected rating: **All ages**.

## 6. Privacy

- App-level question: "Does your app collect user data or transfer user data to
  third-parties?" Answer **No**: the app sends nothing off the device. Answering No
  means "you are confirming that you do not collect or transfer user data from your app"
  and no privacy policy URL is mandatory.
  [Appstore privacy labels](https://developer.amazon.com/docs/app-submission/appstore-privacy-labels.html), last updated 2026-02-17.
- A privacy policy is still worth hosting **[owner]**: screen capture is sensitive, and
  reviewers look for one. The text is in `docs/store-listing.md`; publish it at any
  stable URL (GitHub Pages or the repo README anchor both work) and paste the URL into
  the privacy policy field if the console offers it.

## 7. Device targeting

- Manifest must declare `android.hardware.touchscreen` as not required for the
  Appstore to classify the app as Fire TV compatible (already present), and
  `android.software.leanback` required (already present, keeps tablets out).
  "This classification isn't automatic": after upload, open **Edit supported devices**
  and tick the Fire TV devices manually **[owner]**.
  [Device filtering and compatibility](https://developer.amazon.com/docs/app-submission/device-filtering-and-compatibility.html), last updated 2026-05-27.
- `minSdk` 25 includes Fire OS 6 (Fire TV Stick 4K 2018, Cube 1st gen). Verified
  device: Fire TV Stick 4K Max 1st gen (Fire OS 7). Fire OS 8 devices (Stick 4K Max 2nd
  gen, Stick 4K 2nd gen, 4K Plus) are untested; MediaProjection behaviour should match
  Android 11.
- Vega OS devices (Stick 4K Select 2025, Stick 4K 3rd gen and Stick HD 2026) do not run
  Android APKs at all; they need a VPKG. Deselect them.
  [Fire OS overview](https://developer.amazon.com/docs/fire-tv/fire-os-overview.html), last updated 2026-10-16;
  [Device specifications comparison table](https://developer.amazon.com/docs/device-specs/device-specifications-comparison-table.html).
- Install location: Amazon recommends `preferExternal` for most apps; this app is a few
  hundred kilobytes and runs a service, so internal (default) is kept.

## 8. Trademark and naming

- Apps "must not infringe on the intellectual property rights" of third parties; an
  example violation is an app that "uses brand names, logos or promotional images from a
  third party" without permission, in the app or its metadata. Disclaimers do not cure
  unauthorised use "regardless of any disclosure or disclaimer."
  [Intellectual property policy](https://www.developer.amazon.com/docs/policy-center/intellectual-property.html), last updated 2024-07-10;
  [Policy FAQ](https://developer.amazon.com/docs/policy-center/faq-policy.html), last updated 2023-04-05.
- Decision: store name **Screen Sync for WiZ lights**. The product is named for what it
  does; "WiZ" appears only descriptively, after "for", to state compatibility, which is
  the conventional nominative use. No WiZ or Signify logo is used anywhere. A
  non-affiliation line is in the README, the listing and the in-app footer. If Amazon
  still objects, the fallback name is **Screen Sync for smart bulbs** (listing text only;
  the package name does not change).

## 9. Pre-submission checklist (Amazon's)

[Presubmission checklist](https://developer.amazon.com/docs/app-submission/presubmission-checklist.html), last updated 2025-10-08.

- [x] Content policy reviewed (utility app, no user content, no ads, no IAP).
- [x] Binary built per "Understanding submission" (signed, zip-aligned release APK).
- [x] Privacy labels answer prepared (No data collected).
- [x] Images created (icons, TV icon, background); screenshots **[owner]**.
- [x] Third-party IP: none used beyond the descriptive "WiZ" compatibility reference.
- [ ] **[owner]** Tested on a physical device: re-test the *release* build on the Stick
      (Start, Stop, sleep, Find bulbs, Rename) because R8 is new.
- [x] Device targeting declared in the manifest.
- [x] Install location: default internal (service app).
- [x] No Amazon APIs, so no security profile.
- [x] Well under the 65k method limit (R8 shrinks further).

## 10. What only the owner can do

1. Create the Amazon developer account (free) at developer.amazon.com.
2. Generate the upload keystore and fill in `firetv/keystore.properties` (see README).
3. Run `./gradlew assembleRelease`, install the release APK on the Stick, re-test.
4. Take the screenshots with `docs/store/screenshots.sh` (list of screens inside).
5. Host the privacy policy text and note its URL.
6. In the Developer Console: new app → Fire TV, upload the APK, pick supported devices
   (Fire OS devices only), fill the listing from `docs/store-listing.md`, answer the
   content rating and privacy questions, submit.
