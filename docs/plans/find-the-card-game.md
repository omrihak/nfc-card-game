# Plan: games, starting with "Find the card"

Status: **designed, not started.** Decisions below were made with the parent; the open ones are marked.

Goal: let the child learn words (Hebrew and English) by playing with the cards, with almost no screen time. A new
kind of card starts a **game**; the first game is **Find the card**: the phone asks "Where is the dog?" (in English or
Hebrew), she taps the dog card, the phone cheers.

## Decisions

| Topic | Decision |
|-------|----------|
| Game cards | One generic card type, `"type": "game"`, with `"game": "<engine id>"`. Everything else in the JSON is that game's settings. Later games reuse it. |
| Speech | **Text-to-speech on the player, driven by text in the card JSON.** No recording is needed. The player uses Android's `TextToSpeech` with the **Google** engine (the parent checked that it has a Hebrew voice on the Galaxy S20 FE and that it sounds acceptable). |
| Recorded override | A phrase may optionally carry an audio URL; if it does, that plays instead of TTS. Useful for the few words where a real voice matters. Not needed for v1. |
| Languages | `lang` is `"en"`, `"he"` or `"he>en"` (ask in Hebrew, say the English word when she is right). The parent prints one game card per mode. |
| Item cards | Ordinary audio cards plus an optional `words` field. Outside a game they play as today. |
| Wrong tap | Gentle "That's the cat. Try again!", question repeats, no penalty. A card outside the deck counts as wrong. |
| Screen | Audio first. Stars on the idle screen; the target's picture appears only as a hint after the second wrong tap. |
| Offline | Tapping the game card downloads the whole deck (loading animation as today); afterwards it works offline. |

## Card format

Item card (a normal audio card; `media` is what plays outside a game):

```json
{
  "type": "audio", "name": "Dog", "media": "<share link>", "image": "<share link>",
  "words": {
    "en": { "text": "dog" },
    "he": { "text": "כלב", "ask": "הכלב" }
  }
}
```

`words.<lang>.text` is the word as spoken when revealing or teaching it. `ask` (optional) is the form used inside the
question, which fixes Hebrew definite articles ("איפה הכלב?"). `text` may contain Hebrew vowel marks for words the
voice mispronounces. Either field may be replaced by an `{ "text": "...", "audio": "<url>" }` recorded override.

Game card:

```json
{
  "type": "game", "game": "find-card", "name": "Animals (English)",
  "lang": "en", "rounds": 5, "choices": 3,
  "deck": ["<dog content.json link>", "<cat content.json link>"],
  "say": {
    "intro": "Let's play!",
    "ask": "Where is the {word}?",
    "correct": "Yes! {word}!",
    "wrong": "That's the {word}. Try again!",
    "finish": "Great job!"
  }
}
```

`{word}` is replaced per card (using `ask` for the question, `text` otherwise). `say` has built-in defaults per
language, so a game card can be as small as `type`, `game`, `lang` and `deck`. The deck is in the JSON, so the sticker
still holds one short URL.

## A round

1. Game card tapped: download the deck, say `intro`, pick a target at random (not repeating until the deck is used up),
   say `ask`.
2. A card is tapped (the tapped URL is compared with the target):
   - **target** -> `correct`, a star, next round (or `finish` after the last round, then idle);
   - **other** -> `wrong` (naming the tapped card), then repeat the question; after the second miss on one target,
     show its picture as a hint;
   - **nothing for ~10 s** -> repeat the question.
3. Tapping the game card again, or the existing 4 s hold, ends the game.

## Code structure

- `content/ContentMetadata`: accept `type: "game"`; add `words` parsing for item cards.
- `game/` (new, pure Kotlin, unit-tested like the other logic): a `Game` interface that receives events
  (`started`, `cardTapped(url)`, `timeElapsed`) and returns actions (`say(text, lang)`, `showHint(card)`,
  `award star`, `finish`). `FindCardGame` is the first implementation; a registry maps the `game` id to it.
- `speech/` (new): a `Speaker` interface (`say(text, lang)`, with queueing and a completion callback) and an Android
  implementation over `TextToSpeech`. The manifest needs `<queries>` for the `android.intent.action.TTS_SERVICE`
  intent (Android 11+). If a language has no usable voice, the game says nothing and the problem wobble plays, as for
  other errors. Tests use a fake `Speaker`.
- `MainViewModel`: new `UiState.Game`; while a game is active, scanned cards go to the game instead of being played.
  `status()` for the companion reports the game state.
- `ui/`: idle screen shows collected stars; hint picture view.
- Manager and companion: a "Game" form (choose deck from existing cards, language, rounds) and a "words" section in
  the card form. After this the parent never edits JSON by hand.

## Steps

1. Format and parsing (`type: "game"`, `words`) with tests.
2. `Game` interface, `FindCardGame`, `Speaker` fake, tests for the round logic.
3. Android `Speaker` over TextToSpeech; view-model wiring; stars on the idle screen.
4. Manager and companion forms.
5. README: card format, game cards, setup note below.

## Setup note

The player phone must have **Google** as its preferred text-to-speech engine with the English and Hebrew voice data
downloaded (Settings -> General management -> Text-to-speech). With kiosk mode (factory-reset provisioning) this is a
manual step after setup unless we find a way to automate it; to investigate. If a phone has no Hebrew voice, bundling
an offline engine (e.g. sherpa-onnx with a Hebrew model) is the fallback; not planned.

## Open questions

- Best default number of choices per round for her age (2 vs 3).
- Whether to show anything on screen at all during the question (currently: no).
- A `he>en` mode needs both languages on every card in the deck; the manager should warn when one is missing.
