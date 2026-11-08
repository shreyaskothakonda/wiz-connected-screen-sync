# Store listing: Screen Sync for WiZ lights

Text for the Amazon Developer Console (Appstore details and Target your app screens).
Everything is plain text; the console rejects HTML. Field limits are in
`appstore-checklist.md`, section 4.

## Display title

Screen Sync for WiZ lights

## Short description

Fire TV shows only the first 200 characters, so the limitation is stated inside them.

> Makes your Philips WiZ colour bulbs follow the picture on your Fire TV, over your own Wi-Fi. Works with YouTube, the home screen and most players; apps that protect their video capture as black, so the lights rest on warm white there. No cloud, no account, nothing leaves your home network.

(First 200 characters: "Makes your Philips WiZ colour bulbs follow the picture on your Fire TV, over your own Wi-Fi. Works with YouTube, the home screen and most players; apps that protect their video capture as black, so t" - the limitation is visible.)

## Long description

> Screen Sync turns your WiZ colour bulbs into ambient lighting for your Fire TV. It looks at what is on screen, picks a colour, and sends it straight to the bulbs on your Wi-Fi up to 30 times a second. There is no cloud service, no account and no phone app in the loop: the Fire TV talks to the bulbs directly.
>
> HOW IT WORKS
> 1. In the WiZ app, turn on Settings > Security > Allow local communication.
> 2. Open Screen Sync, press Find bulbs and tick the ones you want to use. Identify blinks a bulb so you know which is which; Rename and Remove keep the list tidy.
> 3. Pick a preset, set the brightness and press Start. Fire TV asks for screen-capture permission; choose Start now.
>
> PRESETS
> Instant: average colour, no smoothing, full brightness. Movie: smoothed colour that follows the picture's brightness. Gaming: dominant colour, fast. Ambient: slow, steady colour at a fixed level. Party: the most saturated colour, maximum punch. Brightness is remembered per preset. The bulbs switch off when the Fire TV goes to sleep.
>
> PLEASE READ BEFORE BUYING BULBS OR INSTALLING
> Fire OS only lets apps capture video that is not copy-protected. Most subscription streaming apps protect their video, and the capture arrives as pure black. Screen Sync cannot see that picture and does not try to work around the protection: after a second of black the bulbs fade to a warm white and resume when a picture comes back. YouTube, the Fire TV home screen, photo and media players such as Plex, Kodi and VLC, games and most free apps work. Try the app with YouTube first.
>
> REQUIREMENTS
> Philips WiZ colour bulbs (the Wi-Fi ones controlled by the WiZ app) on the same Wi-Fi network as the Fire TV, with local communication enabled in the WiZ app. Guest or isolated networks that block devices from seeing each other will not work. The bulb's own fade time limits how fast the light can change.
>
> PRIVACY
> Screen frames are processed in the Fire TV's memory to compute a colour and are discarded immediately. Nothing is recorded, stored or sent anywhere except the colour commands to your bulbs. The app has no analytics, no advertising and no account.
>
> Screen Sync is an independent project and is not affiliated with, endorsed by or supported by Signify or WiZ. WiZ is a trademark of Signify Holding B.V. and is used only to state which bulbs the app works with.

## Product feature bullets

- Bulbs follow the picture up to 30 times a second, directly over your Wi-Fi
- Five presets (Instant, Movie, Gaming, Ambient, Party) with per-preset brightness
- Finds WiZ bulbs automatically; Identify, Rename and Remove from the remote
- Lights turn off when the Fire TV sleeps
- Nothing leaves your home network: no cloud, no account, no analytics

## Keywords

ambient lighting, bias lighting, screen sync, smart bulbs, wiz, colour sync, immersive light

## Category

Utilities (or Lifestyle > Home if Utilities is not offered for Fire TV).

## Release notes (first version)

> First release. Finds WiZ bulbs on your network, five presets, per-preset brightness, bulbs off on sleep. Protected video (most subscription apps) captures as black and cannot be followed.

## Testing instructions for Amazon's reviewers

> The app needs at least one Philips WiZ colour bulb on the same Wi-Fi network as the Fire TV, with "Allow local communication" enabled in the WiZ phone app. Without a bulb: Find bulbs reports "No bulbs found", Start stays disabled, and the rest of the UI can still be navigated. With a bulb: press Find bulbs, tick the bulb, press Start, accept the screen-capture dialog, then open YouTube; the bulb follows the video. Press Stop in the app to end. Subscription streaming apps are expected to show "No picture" and warm-white bulbs; this is Fire OS protecting their video, not a bug.

## Content rating answers

Target your app > Content rating. Expected result: **All ages**.

| Question area | Answer | Note |
| --- | --- | --- |
| Violence | None | |
| Cartoon violence | None | |
| Drugs | None | |
| Nudity | None | |
| Sex | None | |
| Intolerance | None | |
| Profanity | None | |
| Academic | None | |
| Account creation or other personal information collected | No | No account, no sign-in, no personal data |
| Advertisements | No | |
| Gambling | No | |
| Location detection or location-based services | No | Uses Wi-Fi multicast on the LAN only; no location permission |
| User-generated content or user-to-user communication | No | Bulb names are stored locally only |

Target audience: not designed for children; select **16 and over** (the general audience
option), which keeps the app out of the Child-Directed App policy.

## User data privacy answers

Question: "Does your app collect user data or transfer user data to third-parties?"
Answer: **No**. (This confirms that no data is collected or transferred, and the
per-category questionnaire is then skipped.)

If a privacy policy URL field is shown, paste the hosted copy of the text below.

## Privacy policy (host this as a page; also the in-repo copy)

> **Screen Sync for WiZ lights: privacy policy**
>
> Last updated 8 November 2026.
>
> Screen Sync for WiZ lights ("the app") is a Fire TV application that changes the colour of Philips WiZ bulbs on your local network to match the picture on your television.
>
> **What the app does with your screen.** When you press Start, Fire OS asks for your permission to capture the screen. With that permission the app receives a small, low-resolution copy of the picture many times a second, calculates a single colour and brightness from it, and then discards the frame. Frames exist only in the device's working memory for the fraction of a second needed to compute that colour. The app never writes a frame to storage, never encodes video, and never transmits any image or any description of what you are watching to anyone. Video that an app marks as protected is delivered to the app by Fire OS as black and is not visible to it at all.
>
> **What leaves the Fire TV.** The only network traffic the app produces is on your own local network: discovery broadcasts to find WiZ bulbs, and colour commands and health checks sent to the bulbs you selected, using the bulbs' local control protocol (UDP port 38899). The app does not connect to any internet server, including servers run by the developer, Amazon, Signify or WiZ. It contains no analytics, crash reporting, advertising or tracking code.
>
> **What the app stores.** On the Fire TV only: the list of bulbs you found (their IP and hardware address), the names you gave them, which ones are enabled, your chosen preset and brightness per preset. This is ordinary app data; uninstalling the app deletes it. Nothing is backed up to the cloud (the app opts out of Android backup).
>
> **Permissions.** Internet and Wi-Fi state: to talk to bulbs on your local network. Foreground service and media projection: to keep capturing the screen while you watch, which is the app's purpose. The screen-capture permission is granted by you through the Fire OS dialog each time you start a sync (unless you tick "Don't show again") and is released when you press Stop, when the Fire TV sleeps or when the app is closed.
>
> **Children.** The app is not directed at children and collects no personal information from anyone.
>
> **Changes and contact.** If a future version ever sends data off the device, this policy and the store listing will say so before that version is released. Questions: open an issue on the project's GitHub repository (link on the store page).
>
> Screen Sync is not affiliated with Signify or WiZ. WiZ is a trademark of Signify Holding B.V.

## Export compliance

The app uses no encryption of its own (plain UDP to the bulbs; standard Android platform
TLS is not used at all). If the console asks about encryption, answer **No**.
