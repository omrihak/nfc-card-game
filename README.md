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

   `type` and `media` are required, `image` is optional (artwork shown while a song plays, and the front of a printed card; the player
   ignores it for videos). Unknown extra
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

## Card manager (web page)

`manager/` is a small web page that does steps 1-2 above for you, and on Android also step 3. (The [companion app](#companion-app-for-the-parent)
does the same from an Android phone.)
Pick a song or a video (+ an optional picture, used as the song's artwork and on the printed card) and give the card a name. The page then:

1. creates `Tap and Play/<name>/` in your Google Drive and uploads the files into it,
2. sets them to *Anyone with the link -> Viewer*,
3. writes `content.json` there (plus `name`/`createdAt`, which the player ignores),
4. adds the card to `Tap and Play/cards.json`, the list the page shows.

Each card in the list has **Write to sticker** (Chrome on Android with NFC, via Web NFC: hold an
NTAG215 to the back of the phone), **Copy link** (the `content.json` link, for any NFC app) and **Folder**.
**Remove** moves the card's Drive folder to the trash (restorable there for 30 days) and takes it off the list.
Its stickers and printed cards then stop working, except on phones that have already played it (they keep it cached).

**Printing cards.** Tick cards in the list and press **Make PDF**. Each A4 sheet holds 6 cards (85 x 85 mm, 2 x 3)
and is two pages: the fronts (the card's picture, fitted whole inside the square on white, or a coloured card with the name if it has none), then the backs
(four copies of the QR code of the `content.json` link in a 2 x 2 grid, so a hand holding the card still leaves one
visible, with the name between them). Print double-sided,
**flip on long edge**, at **Actual size / 100%**. Cards are 6 mm apart; the crop marks point at the middle of each
gap, so cutting there leaves every card a 3 mm white border. The back page is mirrored, so each back lands behind
its own front. If your
printer shifts the second side, measure the shift on one test sheet and enter it under *Back side doesn't line up?*
(remembered in the browser). Card size and margins are constants at the top of `manager/print.js`. The PDF is
written directly (no PDF library); QR codes come from `manager/vendor/qrcode.js` (qrcode-generator 1.4.4, MIT).

**Hosting.** Settings -> Pages -> *Deploy from a branch*, default branch, `/ (root)`. The page is then at
`https://omrihak.github.io/nfc-card-game/manager/`. To try it locally: `cd manager && python3 -m http.server 8000`
and open `http://localhost:8000`.

**Google Drive access.** The page signs you in with your own Google account (OAuth, Google Identity
Services in the browser). One-time setup in [Google Cloud Console](https://console.cloud.google.com/):

1. Create a project and enable the **Google Drive API**.
2. *OAuth consent screen*: External, keep it in **Testing**, add your Gmail as a test user.
3. *Credentials -> Create OAuth client ID -> Web application*. Authorized JavaScript origins:
   `https://omrihak.github.io` and `http://localhost:8000`. No redirect URI is needed.
4. Put the client ID in `manager/config.js`. It is public by design; there is no client secret.

The only scope is `drive.file`: the page can see and change just the files and folders it created, not the
rest of your Drive, and Google needs no app review for it. Sign-in lasts about an hour; after that the page
asks again. A service account is not used: its key cannot be kept secret in a web page, and it has no Drive
storage of its own on a personal Gmail account.

## Companion app (for the parent)

`companion/` is a second Android app, *Tap and Play Parent*, for the parent's own phone. It has two tabs:

- **Player**: a remote control for the player phone: what is playing (name, paused or playing, position),
  **Pause / Resume**, **Stop**, **volume** (- / slider / +) and **Play** for any of your cards. It only works while the
  player phone is on Wi-Fi with the app open, and your phone is on the **same Wi-Fi**. Without Wi-Fi the player
  works exactly as before; the companion just says it can't reach it.
- **Cards**: the web manager's features: sign in with Google, **make a new card** (name, song or video, optional
  picture), and the list of cards with **Play on player**, **Write to sticker** (with your phone's own NFC; no
  Chrome needed), **Copy link** and **Remove**. It uses the same `Tap and Play/` folder and `cards.json` as the web
  page, so cards made in either show up in both. Printing still happens on the web page (the app links to it).

Build and install: `./gradlew :companion:installDebug` (or open the project in Android Studio and run `companion`).
It is signed with the same checked-in `app/debug.keystore` as the player.

**Finding the player.** The player announces itself on the Wi-Fi (mDNS / NSD, `_tapandplay._tcp`), so the companion
finds it by itself and remembers it. Some routers block that (guest networks, "AP/client isolation"); then tap
*Enter the player's address instead* and type the player phone's IP address (player phone: Settings -> Wi-Fi ->
your network -> IP address). Giving the player a fixed IP in your router's DHCP settings keeps that working.

**Google sign-in, one-time setup** (in addition to the web manager's setup below, in the **same** Google Cloud
project, so both see the same files with the `drive.file` scope): *Credentials -> Create OAuth client ID ->
Android*, package name `com.omrihak.nfccards.companion`, SHA-1
`E8:BC:C9:F8:85:B5:19:3D:C1:33:F8:79:14:39:8A:FA:50:06:3A:67` (the checked-in debug key; check with
`keytool -list -v -keystore app/debug.keystore -storepass android`). Nothing goes in the code: Android identifies
the app by package name and signature. Your Gmail must be a test user on the consent screen, as for the web page.

**Remote control API** (what the companion uses; the player serves it on port **8765**, local network only):

| Request                                   | Does                                                         |
|-------------------------------------------|--------------------------------------------------------------|
| `GET /api/status`                         | `{"app":"tapandplay","state":"idle\|loading\|audio\|video","paused":false,"title":"...","card":"...","positionMs":0,"durationMs":0,"volume":7,"maxVolume":15}` |
| `POST /api/play` `{"url":"<card link>"}`  | Plays a card's `content.json` link, exactly like tapping it  |
| `POST /api/pause`, `/api/resume`, `/api/stop` | Pause, resume, stop (back to the idle screen)            |
| `POST /api/volume` `{"level":7}` or `{"step":1}` / `{"step":-1}` | Sets the media volume, or moves it one step |

Every call answers with the status. Try it from a computer on the same Wi-Fi:
`curl http://<player-ip>:8765/api/status`. The player refuses requests from outside the local network, but there is
no password: anyone on your Wi-Fi can pause the song. Use it on a home network you trust.

## Behaviour

- Same card while it is playing: ignored. A different card: switches immediately.
- Double-tap the screen: pause / resume (a single tap does nothing). Hold for 4 seconds: stop and return to the idle screen. Audio and video play once, then the app returns to the idle screen.
- QR codes work like cards: hold one up to the front camera (live view in the small circle, top-left). A code
  containing the `content.json` link plays it; it fires once, and again only after being out of view for 2 seconds.
  Admin card URIs work as QR codes too. In kiosk mode the camera permission is granted automatically.
- Battery level is shown small in the top-right corner (the system status bar is hidden).
- Anything that goes wrong (unknown tag, no internet, bad JSON, missing/unsupported media...) returns to the idle screen with a small wordless wobble. Details are in Logcat (tags `NfcReader`, `ContentResolver`, `MediaCache`, `MainViewModel`).
- Volume follows the phone's volume (which the companion app can change).
- The companion app can pause, resume, stop and play cards over Wi-Fi; to the player that is the same as a double-tap,
  a hold or a card tap.

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
  `adb logcat -s NfcReader ContentResolver MediaCache MainViewModel Remote RemoteServer`.
- **Simulate the companion** from a computer on the same Wi-Fi:
  `curl -X POST -d '{"url":"<card link>"}' http://<player-ip>:8765/api/play`.
- **Test the NFC read itself** with a second Android phone running an NDEF tag-emulator app, or buy a couple of NTAG215 stickers (a few dollars)
  and write the URL with an app such as NFC Tools.

## Build

Open the folder in Android Studio (Ladybug or newer) and run on the phone, or `./gradlew assembleDebug`.
Install a ready APK with `adb install -r tap-and-play.apk`. Debug builds are signed with the checked-in
`app/debug.keystore`, so a build from any computer can update the installed app (important in kiosk mode,
where the app can't be uninstalled).
Pure-logic unit tests (`GoogleDriveUrlResolver`, `ContentMetadata`): `./gradlew testDebugUnitTest`.
CI (`.github/workflows/ci.yml`) runs the tests and a debug build on every pull request and push to `master`.

### Releases

Parents install release builds from GitHub Releases: `tap-and-play.apk` (player) and `tap-and-play-parent.apk`
(companion), always at `https://github.com/omrihak/nfc-card-game/releases/latest/download/<name>`. Both are signed
with one release key (provisioning and self-update rely on that).

1. Once: `scripts/make-release-key.sh` creates `release.jks` (gitignored) and prints everything below. Back up the
   keystore and password: without them installed phones can never be updated.
2. Add repository secrets (Settings -> Secrets and variables -> Actions): `TAP_KEYSTORE_BASE64`,
   `TAP_KEYSTORE_PASSWORD`, `TAP_KEY_ALIAS`, `TAP_KEY_PASSWORD`.
3. Google Cloud Console, same project: add another *Android* OAuth client for `com.omrihak.nfccards.companion` with the
   release key's SHA-1, or the companion's Google sign-in fails in release builds.
4. Put the printed signature checksum in `manager/config.js` as `releaseSignatureChecksum` (QR provisioning).

Cut a release: `git tag v0.2.0 && git push origin v0.2.0`. `.github/workflows/release.yml` builds both apps with
versionName `0.2.0` and versionCode `200` (`vX.Y.Z` -> X*10000 + Y*100 + Z; tags must only go up) and publishes them.
Locally: `TAP_KEYSTORE_FILE=release.jks TAP_KEYSTORE_PASSWORD=... TAP_KEY_ALIAS=tap-and-play TAP_KEY_PASSWORD=...
./gradlew assembleRelease -PtapVersion=0.2.0`; without `TAP_KEYSTORE_FILE` release APKs are built unsigned.

Migrating from debug builds: a phone running the debug-signed player can't be updated to a release build (signature
mismatch). In device-owner mode tap the `tapandplay://release` admin card, uninstall the app, then install the release
build and set it up again. Same for the companion (uninstall, reinstall).

## Layout

```
nfc/NfcReader            read-only NDEF URI reader (reader mode)
qr/QrScanner             front-camera QR reader (CameraX + ZXing)
content/ContentMetadata  parses content.json leniently
content/GoogleDriveUrlResolver
content/ContentResolver  card URL -> metadata -> local files (offline-first)
media/MediaCache         download-to-disk cache, atomic writes
media/PlayerController   the one ExoPlayer (Media3), audio and video
ui/                      IdleScreen, AudioScreen, VideoScreen
kiosk/Kiosk              device-owner lock task, admin cards
MainViewModel            card URI in, UI state out; NFC knows nothing about media
manager/                 web page for making cards in Google Drive (not part of the app)
```
