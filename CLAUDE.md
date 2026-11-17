# Snap Overlay — notes for Claude

- The owner doesn't code: explain steps in plain words, numbered, with exact button names.
- The owner chose: Android, free only, GitHub Actions builds the app (no Android SDK license
  accepted in the cloud container). So the app is compiled and tested only on GitHub. After
  pushing, check the "Build app" workflow run and fix failures.
- Bump `versionCode` and `versionName` in `app/build.gradle.kts` for each change sent to the owner.
- The app must not read or touch the game. The player picks the deck and taps Won/Lost (owner
  chose one-tap results over screen watching).
- The overlay is the whole app (owner's request). `MainActivity` only asks for the overlay
  permission and starts `OverlayService`; all screens live in `OverlayService` (a back stack of
  `Screen`s). Opening from the app icon starts at the folders; tapping the S resumes where you were.
- Data: `DeckStore` keeps folders (Ladder id 1 always present) and decks with wins/losses as one
  JSON string in SharedPreferences; decks saved by 0.1.0 move into Ladder.
- Deck code format (current): base64 of comma-separated short keys. Key = card id with lowercase
  a e i o u y removed + id length in hex ("Lancelot" -> "Lnclt8"). Old format: base64 JSON with
  "CardDefId". See `DeckCode.kt`; the owner's real decks are in `DeckCodeTest.kt`.
- Card data: marvelsnapzone.com `getinfo/?searchtype=cards&searchcardstype=true` → `success.cards[]`
  with `carddefid`, `name`, `cost`, `power`, `status`. Slimmed into `app/src/main/assets/cards.json`.
- On hold (owner's call): automatic tracking by reading the game's files on the phone, the way
  Untapped.gg's Android tracker does (folder access to the game's data folder; blocked on many newer
  Android versions). Next step if resumed: ask phone model/Android version, then a test build with a
  "Connect game folder" button that lists the files found. Screen watching is the other option.
- Keep `README.md` up to date when features change.
