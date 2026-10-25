# Tap and Play

An old Android phone mounted in a toddler-safe enclosure becomes a card-driven media toy:
tap an NFC card on the phone, and a song (with artwork) or a video plays. The app only **reads**
NFC tags; there is no writing or card management. New cards never need an app update.

```
NFC card (URL) -> content.json -> audio/video (+ cover) -> cached on the phone -> plays
```

## Making a card

1. Prepare files, upload them to Google Drive and set each to **Anyone with the link -> Viewer**.
2. Write a `content.json`, upload it the same way:

   ```json
   { "type": "audio", "media": "<share link to song.mp3>", "image": "<share link to cover.jpg>" }
   ```
   ```json
   { "type": "video", "media": "<share link to movie.mp4>" }
   ```

   `type` and `media` are required, `image` is optional (artwork for audio). Unknown extra
   fields are ignored, so the format can grow. Use ordinary Drive share links
   (`https://drive.google.com/file/d/<ID>/view?...`); the app turns them into direct downloads.
3. With any external NFC app, write the **`content.json` share link as a URL/URI record** to an NTAG215
   sticker. A Drive link is ~70 bytes; NTAG215 holds ~500.
4. Stick it in a card. Tap.

Tips
- To change a song later, use Drive's *Manage versions -> Upload new version* on the same file, so the
  link on the sticker stays valid. Metadata is refreshed in the background whenever the phone is online
  (the new version is used on the next tap). Media is cached forever by its URL, so a *different* song
  needs a *different* file (new link).
- Tap each card once while online; from then on it works offline and starts instantly.

## Behaviour

- Same card while it is playing: ignored. A different card: switches immediately.
- Tap the screen: pause / resume. Long-press: stop and return to the idle screen. Audio and video play once, then the app returns to the idle screen.
- Battery level is shown small in the top-right corner (the system status bar is hidden).
- Anything that goes wrong (unknown tag, no internet, bad JSON, missing/unsupported media...) returns to the idle screen with a small wordless wobble. Details are in Logcat (tags `NfcReader`, `ContentResolver`, `MediaCache`, `MainViewModel`).
- Volume follows the phone's volume.

## Drive notes

`GoogleDriveUrlResolver` is the only Drive-specific code. Public files need no sign-in. It rewrites share links to
`https://drive.usercontent.google.com/download?id=<ID>&export=download`, and if Drive answers a large file
with an HTML "virus scan" page it retries with `&confirm=t`. Other hosts' URLs pass through unchanged, so moving to
GitHub Pages, R2, etc. later needs no app change. Public Drive links are unlisted, not private, and Google can
rate-limit them; that is a risk mainly for the first download.

## Setting up the phone

- Settings -> NFC: on. (Samsung: also check "Contactless payments" doesn't grab taps.) The NFC antenna is behind the phone, near the camera; put the card flat against the back.
- Screen timeout is handled by the app (screen stays on while it is open).
- Lock the toddler in: see *Kiosk mode* below. The app always hides the system bars and ignores Back.
- The phone will sit on a charger forever: enable battery protection (Samsung: Battery -> Battery protection -> Maximum 85%).

## Kiosk mode (the app is the only thing on the phone)

The app can be the phone's home screen (launcher), so it starts on boot and Home always comes back to it.
Pick one level:

**Simple: launcher only.** Press Home, choose *Tap and Play*, *Always* (or Settings -> Apps -> Default apps -> Home app).
Set Settings -> Security -> Screen lock -> **None**, so the phone boots and wakes straight into the app
(the app also shows over a swipe lock screen). A determined toddler can still pull down the notification shade
or open recents, so combine it with **screen pinning** (Settings -> Security -> Pin windows, then pin from recents).

**Full: device owner.** The app becomes the permanent home screen, runs in lock task mode (no home, recents,
notification shade or power menu), the lock screen is disabled and the screen stays on while charging.
Survives reboots. Setup, once, over adb:

1. Remove every account from the phone (Settings -> Accounts), or factory reset it and skip sign-in.
   Android refuses a device owner while accounts exist.
2. Install the app, then:
   ```
   adb shell dpm set-device-owner com.omrihak.nfccards/.kiosk.AdminReceiver
   ```
3. Open the app. It locks itself in.

Getting out uses **admin cards**: write one of these as a URI record on a spare sticker (NFC Tools: *Custom URL/URI*)
and keep it away from the toddler.

| Card URI                 | Does                                                                     |
|--------------------------|--------------------------------------------------------------------------|
| `tapandplay://settings`  | Leaves kiosk and opens Settings. Press Home (or open the app) to lock again. |
| `tapandplay://release`   | Gives up device owner for good and opens Settings, so the app can be uninstalled. |

Make the admin cards *before* enabling device owner: without a release card, the only other ways out of
device owner are a new app build that calls `clearDeviceOwnerApp` or a factory reset.

## Testing without NFC cards

- **Simulate a tap over adb** (debug builds only), with the phone connected by USB and USB debugging on:
  ```
  adb shell am start -n com.omrihak.nfccards/.MainActivity -a android.intent.action.VIEW -d "https://drive.google.com/file/d/<ID>/view"
  ```
  Run it again with a different URL to simulate a different card (admin card URIs work too). Watch what happens with
  `adb logcat -s NfcReader ContentResolver MediaCache MainViewModel`.
- **Test the NFC read itself** with a second Android phone running an NDEF tag-emulator app, or buy a couple of NTAG215 stickers (a few dollars)
  and write the URL with an app such as NFC Tools.

## Build

Open the folder in Android Studio (Ladybug or newer) and run on the phone, or `./gradlew assembleDebug`.
Install a ready APK with `adb install -r tap-and-play.apk`. Debug builds are signed with the checked-in
`app/debug.keystore`, so a build from any computer can update the installed app (important in kiosk mode,
where the app can't be uninstalled).
Pure-logic unit tests (`GoogleDriveUrlResolver`, `ContentMetadata`): `./gradlew testDebugUnitTest`.

## Layout

```
nfc/NfcReader            read-only NDEF URI reader (reader mode)
content/ContentMetadata  parses content.json leniently
content/GoogleDriveUrlResolver
content/ContentResolver  card URL -> metadata -> local files (offline-first)
media/MediaCache         download-to-disk cache, atomic writes
media/PlayerController   the one ExoPlayer (Media3), audio and video
ui/                      IdleScreen, AudioScreen, VideoScreen
kiosk/Kiosk              device-owner lock task, admin cards
MainViewModel            card URI in, UI state out; NFC knows nothing about media
```
