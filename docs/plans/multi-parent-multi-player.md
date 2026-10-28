# Plan: several parents, several players

Status: **not started**. Written down for later; nothing here is needed while one family has one player and one
Google account.

Goal: a family can have more than one parent (each with their own Google account) sharing one card library, and more
than one player phone. **One parent with one player stays the default and must look and work exactly as today.**

## Where things stand

| Area | Today |
|------|-------|
| Remote control, several parents | Already works. The player keeps the last 4 pairing tokens (`PairingTokens.MAX_TOKENS`); each companion pairs once. |
| Playing cards | Works everywhere: a card is a public `content.json` link, so any player plays any card, whoever made it. |
| Card library, several parents | **Doesn't work.** Cards and `cards.json` live in the signed-in account's Drive, and the `drive.file` scope only shows an app the files it made for *that* account. A second parent gets their own empty `Tap and Play/`; sharing the folder doesn't help. |
| Several players | Player side works (kiosk QR setup, pairing per player). **The companion handles one player**: it stores one `address`/`name`, and `RemoteViewModel.onFound` switches to whatever it finds unless the current player answers, so with two on the Wi-Fi it picks one at random and can flip between them. Players of the same model announce near-identical names. |

Workaround until this is built: both parents sign in with one shared family Google account.

## Part 1: shared card library ("family library")

### Design

The tenant is the library index file, `cards.json`, not a database. There is still no server.

- The first parent's app creates `Tap and Play/cards.json` as it does now. That file **is** the family library;
  its Drive file ID identifies it.
- Media and `content.json` of a new card go to the Drive of whoever makes the card (`Tap and Play/<name>/`, as now).
  Only the index entry goes into the shared `cards.json`.
- A parent who joined someone else's library remembers its file ID ("library ID") and reads and writes that file
  instead of looking up `cards.json` in their own `Tap and Play/` folder.

Why a file and not a database (Firebase etc.): no backend to run, secure or pay for, no new accounts, and the
narrow `drive.file` scope is kept. A file shared with a user and then picked by them in the Google Picker is the
documented way to grant `drive.file` access to it.

### Invite flow

1. Parent A: *Invite a parent* (web page and companion) asks for B's Gmail address. The app adds a Drive permission
   on `cards.json`: `writer` for that email (the app may share files it created). It shows/sends an invite link:
   `https://omrihak.github.io/nfc-card-game/manager/join.html#<libraryId>`.
2. Parent B opens the link (on any device; it's the web page), signs in with Google, and the **Google Picker** opens
   with that one file preselected (`DocsView.setFileIds(libraryId)`). B confirms. That grants the app `drive.file`
   access to it for B's account.
3. The page checks it can read the file, saves the library ID (see *Remembering the library*), and goes to the normal
   card list, now showing the family's cards.
4. The companion has no native Picker. On its Cards tab, *Join a family library* opens the join page in a Custom Tab;
   after that the companion can use the file too, **if** the grant applies across OAuth clients of the same Cloud
   project (see *Open questions*). The library ID is entered/pasted from the invite link (or read from a deep link
   `tapandplay://join?library=<id>` that the join page offers at the end).

Leaving: *Leave family library* forgets the library ID and goes back to the parent's own `cards.json`. A can remove B by
deleting the permission (a *Members* list read from the file's permissions).

### Remembering the library

The library ID must survive reinstalls and be the same on the web page and companion. Store it in Drive, in the
joining parent's own `Tap and Play/library.json` (`{"libraryId": "<id>"}`), which both apps can read under
`drive.file` since they created it. Lookup order on sign-in: `library.json` → else own `cards.json` (today's behaviour).

### Code changes

Web manager (`manager/`):
- `drive.js`: `share(fileId, email, role)` (permissions.create), `listPermissions`, `removePermission`.
- `app.js`: `readIndex()` / `writeIndex` / `removeFromIndex` (around line 216) use the library ID when set, instead of
  `Drive.find(INDEX_NAME, …)`. Re-read the index immediately before every write (already the pattern; keep it).
- New `join.html` / `join.js`: sign in, load the Picker API (`https://apis.google.com/js/api.js`, `picker`), preselect
  the ID from the URL fragment, write `library.json`.
- *Invite a parent* and *Members* UI, shown only to the owner of the library.
- Cloud Console: enable the **Google Picker API**; the Picker needs an API key (public, restricted to the Pages origin)
  and the project number as app ID. Add both to `config.js`.

Companion (`companion/…/cards/`):
- `Drive.kt`: same three permission calls.
- `CardStore.readIndex` / `writeIndex` (`CardStore.kt:97`): use the library ID from `library.json` when present.
- `CardsViewModel` / `CardsScreen`: *Invite a parent*, *Join a family library*, *Leave*; a line saying whose library
  this is.

Card removal:
- A card made by someone else can't be trashed by you (it's in their Drive). *Remove* then only takes it off the
  shared list, and says so; the card's files stay in the creator's Drive. Entries get `owner` (email) so the UI knows.
  Remove by the creator trashes the folder as today.

### Edge cases

- **Two parents saving at once**: read-modify-write of `cards.json` can lose an entry in a rare race. With 2-3 parents,
  re-reading just before writing is enough. If it ever matters: compare `modifiedTime`/version before writing and
  retry.
- **Owner deletes `cards.json` or their account**: the library is gone for everyone; joined parents fall back to their
  own library (detect 404 on the library ID, tell the user). Cards whose media was in a deleted account break; that's
  true in any design that keeps media in each parent's Drive.
- **Invited with a non-Gmail address**: Drive sharing needs a Google account; say so on the invite form.
- **OAuth consent screen in Testing**: every parent must be added as a test user in the Cloud project. Document this.

### Open questions (spike first)

1. Does a Picker grant made on the web client give the **Android** client (same Cloud project) `drive.file` access to
   that file? The README already relies on project-wide `drive.file` for files the apps create; confirm it for
   picked files too. If not, the companion needs its own picker step, e.g. a WebView hosting the Picker with the
   Android client's token (a home-made chooser can't work: `drive.file` can't list files shared with the user).
2. Does the Picker accept a preselected file ID that is only shared with the user (not in their My Drive)? Expected
   yes; verify.

Spike: a throwaway `join.html` that does steps 1-3 above against a test file, then a companion debug build reading
that file ID. Half a day. If question 1 fails badly, revisit (see *Alternative*).

### Alternative, if a real backend is ever wanted

Firebase (Google sign-in + Firestore, free tier): `families/{id}` with `members` and `cards`, invite tokens, security
rules. Worth it only for things a shared file can't do: invite links without email, live updates across phones,
controlling a player away from home Wi-Fi, a family-wide list of players. Moving there later is easy because a
library is just a list of card entries.

## Part 2: several players in the companion

### Design

- **Single player is unchanged**: with one known player, the Player tab is exactly today's screen.
- With two or more, a small switcher (chips or a dropdown) at the top of the Player tab chooses the **selected
  player**. Only the selected one is polled.
- Players are identified by a stable ID, not by IP address (DHCP changes it).

### Player app (`app/`)

- `remote/`: generate a random player ID once (SharedPreferences) and a display name, default
  `Tap and Play (<Build.MODEL>)`.
- `/api/status` gains `"id"` and `"name"`. Unpaired 401 answers include `"id"` too, so a new player can be told apart
  before pairing.
- `POST /api/name` `{"name":"Kids' room"}` sets the name (paired only); `RemoteApi` + tests.
- `Remote.start`: announce the name as the NSD service name and the ID as a TXT attribute (`setAttribute("id", …)`).
  Re-register when the name changes.
- README: API table rows.

### Companion (`companion/…/player/`)

- `PlayerFinder`: read the `id` TXT attribute into `FoundPlayer`.
- `RemoteViewModel`:
  - Store a list of known players `{id, name, address}` (JSON in the `remote` prefs) plus the selected ID. Migrate the
    old single `address`/`name` as the first entry (ID filled in from the first status answer).
  - `onFound`: update the address of a known player by ID; never switch the selection. An unknown player is offered,
    not used: `Connection` / a new `newPlayers` flow → "New player found: *name*. Pair it?".
  - `select(id)`, `rename(name)`, `forget(id)`.
  - Manual IP entry adds a player (ID learned from its status).
- `PlayerScreen`: the switcher (hidden with one player), *Rename player* and *Forget this player* under Parent tools,
  and *Pair another player* (shows the pairing QR again; the token is the same per companion, so it's one QR for all
  players).
- `CardsScreen`: *Play on player* plays on the selected player; with several, a small menu to pick one.
- Tests: list migration, `onFound` by ID, selection kept when another player appears.

### Edge cases

- Players without the new version (no `id`): fall back to address as the key, as today, and ask to update.
- Two players paired but only one on the current Wi-Fi (e.g. one at grandma's): the other just shows *Can't reach*.
- Same-named players: the switcher shows the name plus the last part of the IP when names collide.

## Order of work

1. Part 2 (multi-player): self-contained, no Google Cloud changes, no unknowns. Ship the player version first (it's
   backward compatible), then the companion.
2. Part 1 spike (open questions above).
3. Part 1 web manager (invite, join, shared index), then companion.
4. README: *Several parents* and *Several players* sections; keep the shared-account workaround as the simple option.
