# Snap Overlay

An Android overlay for Marvel Snap. The overlay is the whole app: tap the app icon and a round
purple **S** button appears over the game. Tap the **S** to open the panel.

- **Folders.** The panel opens on your folders: **Ladder**, plus one per game mode that you add
  with **New folder** (rename or delete them inside each folder; Ladder can't be deleted).
- **+ (new deck).** Always at the top of the panel. Choose **Paste a deck code** (after **Copy Deck
  Code** in the game) or **Pick cards** (search and tap 12 cards; the app makes the deck code).
  Pick the deck's name and folder, then **Save deck**.
- **Decks.** Each deck shows its win/loss record. Inside a deck: **Won** / **Lost** after each
  match (with **Undo**), the 12 cards with cost and power (tap one when you draw it to grey it out),
  **Reset cards**, **Copy code**, **Rename**, **Move** (to another folder) and **Delete**.
- **Closing.** **Quit** at the top of the panel, or drag the **S** onto the **✕** that appears at
  the bottom of the screen. **✕** in the panel just hides the panel.
- **Card list** updates by itself each time the overlay starts.

The app never reads or changes the game. You record results with one tap.

## Install on your phone

1. On your phone, open the repo's **Releases** page (on GitHub: the repo, then **Releases** on the
   right, or **⋯ → Releases** on mobile) and tap **SnapOverlay.apk** under **latest**.
2. When it finishes downloading, tap it. If Android says installs from this source aren't allowed,
   tap **Settings**, turn on **Allow from this source**, then go back and tap **Install**.
3. Open **Snap Overlay**. The first time, Android opens **Display over other apps**: turn
   **Snap Overlay** on, then press back.

To update, download and install the new **SnapOverlay.apk** the same way. Your decks and records stay.

## Card details

Card names, costs and power come from [marvelsnapzone.com](https://marvelsnapzone.com)'s card list.
A copy is built into the app, refreshed each time GitHub builds it, and the app downloads the newest one each
time the overlay starts. Card art is not included.

## How it's built

- Kotlin Android app, no layout files or extra libraries. Code is in `app/src/main/java/app/snapoverlay/`.
- GitHub Actions (`.github/workflows/build.yml`) refreshes the card list, runs the tests, builds the
  app, and publishes it as the **latest** release, on every push. Free within GitHub's monthly
  allowance.
- `app/snap-overlay.keystore` is a fixed signing key kept in the repo on purpose, so new builds
  install over old ones. It's for this personal app only, not the Play Store.
- `node tools/update-cards.js` refreshes `app/src/main/assets/cards.json` by hand.
