# Gurbani (Gurbani Live) — Android APK

Listens to Gurbani being recited and follows the lines of Sri Guru Granth Sahib, with transliteration and English translation.

This repo builds the Android app. The page, the full scripture (1430 angs) and the fonts are packed inside the APK, so the app does not load anything from a website.

ਐਪ ਨਿਰਮਾਤਾ : ਦਾਸ ਵਰੁਣ ਦੁੱਗਲ

## Download

Open **Releases → latest** and download `Gurbani.apk`.

Each build is signed with a new debug key, so uninstall the old app before installing a newer APK.

## What works offline

| Part | Offline? |
|---|---|
| Scripture, translation, transliteration | Yes, bundled in the APK |
| Fonts | Yes, bundled in the APK |
| Spoken meaning (Punjabi, English) | Yes, if that voice is installed on the phone |
| Listening (speech to text) | Uses the phone's Google speech engine: needs internet, or Google's Punjabi offline speech pack on the phone |

If a pack is missing and the phone can download it, a **Download for offline use** button appears on the start screen. If listening fails for lack of the Punjabi pack, the message that appears has a download button too.

## Modes

- **Self-reading** — shows the line you are reading; can speak its meaning, then move on.
- **Gurdwara** — reads one line ahead of the recitation.
- **Kirtan** — follows repeats and returns to the rahao.

## How the build works

Every push to `main` runs `.github/workflows/build.yml`, which:

1. Downloads the scripture once from the GurbaniNow API (`scripts/fetch-corpus.mjs`) and caches it for later builds.
2. Downloads the fonts (`scripts/fetch-fonts.mjs`).
3. Builds the debug APK with Gradle.
4. Publishes it under **Releases → latest**.

**Actions → Emulator check → Run workflow** is an optional test: it boots the app in an Android emulator and confirms it starts and loads the scripture.

The first build is slower because of the scripture download. It stops with an error rather than ship an APK with missing angs.

To rebuild without changing a file: **Actions → Build APK → Run workflow**.

## Files

| Path | Purpose |
|---|---|
| `src/main/assets/index.html` | The app itself (single page) |
| `src/main/java/com/gurbani/app/MainActivity.java` | Android shell: speech recognition, voices, offline-pack prompts |
| `src/main/AndroidManifest.xml` | Permissions (microphone, internet) |
| `src/main/res/` | App icon |
| `scripts/fetch-corpus.mjs` | Build step: scripture download |
| `scripts/fetch-fonts.mjs` | Build step: font download |
| `scripts/ci-smoke.sh`, `.github/workflows/smoke.yml` | Optional emulator check |
| `build.gradle`, `settings.gradle`, `gradle.properties` | Gradle build settings |
| `.github/workflows/build.yml` | GitHub Actions build |

## Changing the app

Edit `src/main/assets/index.html` and commit. A new APK appears under Releases when the build finishes.

## Requirements

Android 8.0 or newer. Microphone permission is needed for listening.

## Credits

Scripture text and translations come from the [GurbaniNow API](https://api.gurbaninow.com).
