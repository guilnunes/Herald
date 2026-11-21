# Herald

Personal Android app that reads Notion pages aloud. Fully free: it uses the Notion API with a personal integration token and Android's built-in text-to-speech.

## Install

Download `Herald.apk` from the latest release on your phone and open it:
https://github.com/guilnunes/Herald/releases/latest/download/Herald.apk

Every push to `main` builds, tests, signs and publishes a new release with GitHub Actions.

## First run

1. Create an internal connection named Herald at https://www.notion.so/profile/integrations (Notion now calls integrations "connections") and copy its secret (Internal Integration Secret or Installation access token).
2. In Notion, on each top-level page you want to hear: ••• › Connections › Herald. Pages inside it are included.
3. Open Herald and paste the secret.

## Features

- Search any connected page, or share a page from the Notion app to Herald.
- Plays with the screen off, with controls on the lock screen, notification, headphones and Bluetooth.
- Speed control, skip by paragraph or by section, tap any paragraph to jump there.
- Own reading view: page title, current section and completion percentage always on top; current sentence highlighted and current word underlined.
- Tap the current section to pause and pick another section.
- Pauses and asks before:
  - tables: Summarize or Skip (spoken outline of size, columns and rows; tap a row to hear it in full);
  - child pages: Read or Skip, with estimated listening time; after a child page it announces that it is going back to the parent page and section;
  - code blocks: Read or Skip.
- Offline copies of every opened page, refreshed when the page changed in Notion; remembers where you stopped.
- English voice by default, PT-BR toggle per page.

## Code map

- `data/NotionApi.kt`: REST client (search, page, block children, retries on rate limit).
- `data/ScriptBuilder.kt`: turns Notion blocks into a flat reading script with sections; table summaries.
- `data/Repository.kt`: recursive fetch, offline cache, saved pages, preferences.
- `player/Engine.kt`: sentence-by-sentence TTS engine, gates, child page stack, progress.
- `player/PlaybackService.kt`: foreground media service, media session, lock screen controls.
- `ui/`: Compose screens (setup, home, reader).

## Build locally

```
./gradlew testReleaseUnitTest assembleRelease
```

Release signing reads `HERALD_KEYSTORE_PATH`, `HERALD_KEYSTORE_PASSWORD`, `HERALD_KEY_ALIAS` and `HERALD_KEY_PASSWORD`; without them the build is signed with the debug key.
