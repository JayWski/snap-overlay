# Snap Overlay

An Android app for Marvel Snap:

- **Deck library.** Paste a deck code from the game (**Copy Deck Code**), give it a name, and it's saved.
  Each deck shows its 12 cards with cost and power. **Copy code** puts it back on the clipboard so
  you can import it in the game.
- **Floating button.** A round **S** button floats over the game. Tap it to see the deck you're
  playing; tap a card when you draw it to grey it out and see how many are left. Drag the button to
  move it. Tap **Change** to switch decks.

The app never reads or changes the game. You choose your deck yourself, and it only shows what you tap.

## Install on your phone

1. On your phone, open the repo's **Releases** page (on GitHub: the repo, then **Releases** on the
   right, or **⋯ → Releases** on mobile) and tap **SnapOverlay.apk** under **latest**.
2. When it finishes downloading, tap it. If Android says installs from this source aren't allowed,
   tap **Settings**, turn on **Allow from this source**, then go back and tap **Install**.
3. Open **Snap Overlay**. Tap **Turn on floating button**. The first time, Android opens
   **Display over other apps**: turn **Snap Overlay** on, then press back.

To update, download and install the new **SnapOverlay.apk** the same way. Your saved decks stay.

## Card details

Card names, costs and power come from [marvelsnapzone.com](https://marvelsnapzone.com)'s card list.
A copy is built into the app, refreshed each time GitHub builds it. **Update card list** in the app
downloads the newest one. Card art is not included.

## How it's built

- Kotlin Android app, no layout files or extra libraries. Code is in `app/src/main/java/app/snapoverlay/`.
- GitHub Actions (`.github/workflows/build.yml`) refreshes the card list, runs the tests, builds the
  app, and publishes it as the **latest** release, on every push. Free within GitHub's monthly
  allowance.
- `app/snap-overlay.keystore` is a fixed signing key kept in the repo on purpose, so new builds
  install over old ones. It's for this personal app only, not the Play Store.
- `node tools/update-cards.js` refreshes `app/src/main/assets/cards.json` by hand.
