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

### QR codes instead of a sticker

A QR code containing the same `content.json` share link works exactly like a card: hold it in front of the
**front camera**. Grant the camera permission once on first launch. Scanning is invisible (no preview), runs only
while the app is on screen with the screen on, uses low resolution and the camera's lowest frame rate, and stops
for 10 seconds after each code so a code left in view doesn't restart the song. Print codes large enough for the
front camera's fixed focus (roughly 5 cm or more, held 15-30 cm away).

## Behaviour

- Same card while it is playing: ignored. A different card: switches immediately.
- Tap the screen: pause / resume. Audio and video play once, then the app returns to the idle screen.
- Anything that goes wrong (unknown tag, no internet, bad JSON, missing/unsupported media...) returns to the idle screen with a small wordless wobble. Details are in Logcat (tags `NfcReader`, `QrReader`, `ContentResolver`, `MediaCache`, `MainViewModel`).
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
- Lock the toddler in: open the app, then use Android **screen pinning** (Settings -> Security -> Pin windows, then pin from the recents screen). The app also hides the system bars and ignores Back.
- The phone will sit on a charger forever: enable battery protection (Samsung: Battery -> Battery protection -> Maximum 85%).

## Testing without NFC cards

- **Simulate a tap over adb** (debug builds only), with the phone connected by USB and USB debugging on:
  ```
  adb shell am start -n com.omrihak.nfccards/.MainActivity -a android.intent.action.VIEW -d "https://drive.google.com/file/d/<ID>/view"
  ```
  Run it again with a different URL to simulate a different card. Watch what happens with
  `adb logcat -s NfcReader ContentResolver MediaCache MainViewModel`.
- **Test the NFC read itself** with a second Android phone running an NDEF tag-emulator app, or buy a couple of NTAG215 stickers (a few dollars)
  and write the URL with an app such as NFC Tools.

## Build

Open the folder in Android Studio (Ladybug or newer) and run on the phone, or `./gradlew assembleDebug`.
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
MainViewModel            card URI in, UI state out; NFC knows nothing about media
```
