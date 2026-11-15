# Snap Overlay — notes for Claude

- The owner doesn't code: explain steps in plain words, numbered, with exact button names.
- The owner chose: Android, free only, GitHub Actions builds the app (no Android SDK license
  accepted in the cloud container). So the app is compiled and tested only on GitHub. After
  pushing, check the "Build app" workflow run and fix failures.
- Bump `versionCode` and `versionName` in `app/build.gradle.kts` for each change sent to the owner.
- The app must not read or touch the game. The player picks the deck; nothing is automatic.
- Deck code format (current): base64 of comma-separated short keys. Key = card id with lowercase
  a e i o u y removed + id length in hex ("Lancelot" -> "Lnclt8"). Old format: base64 JSON with
  "CardDefId". See `DeckCode.kt`; the owner's real decks are in `DeckCodeTest.kt`.
- Card data: marvelsnapzone.com `getinfo/?searchtype=cards&searchcardstype=true` → `success.cards[]`
  with `carddefid`, `name`, `cost`, `power`, `status`. Slimmed into `app/src/main/assets/cards.json`.
- Ideas not built yet: build a deck from scratch in the app (`DeckCode.encode` already exists),
  automatic deck detection from the screen.
- Keep `README.md` up to date when features change.
