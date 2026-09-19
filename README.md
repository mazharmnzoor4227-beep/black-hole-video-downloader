# BLACK HOLE

Minimal Android video downloader with a full jet-black interface and a single animated black-hole interaction.

## Product direction
- No login or signup
- Android 9+ (`minSdk 28`)
- Edge-to-edge pure black UI
- User-provided black-hole artwork is the launcher icon and central home visual
- Clipboard link detection: copy a supported public video link, open BLACK HOLE, then tap the black hole
- Local download history only

GitHub Actions builds a debug APK on every push. The Android app uses an on-device media extractor for supported public, non-DRM media sources. Individual platforms can change their pages or extraction behavior over time and may require extractor updates.

Only download media you have permission to save.
