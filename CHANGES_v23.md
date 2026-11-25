# v7.2 (code 72): floating orb size

- Settings > FLOAT & PHONE CONTROL > Orb and UI customiser: new **Floating orb size** slider under the "Open UI customiser" button (50% to 200%, steps of 5, default 100%) plus Reset.
- Live: while float mode is on, the bubble resizes as you drag, keeps its centre and stays on screen. The size is saved (Prefs `orb_scale`) and used the next time the bubble starts.
- Files: Prefs.kt, FloatService.kt (`orbPx`, `resizeBubble`, `applyOrbSize`), SettingsActivity.kt (`getOrbScale` / `setOrbScale`), assets/settings.html, app/build.gradle.kts.
- Not compiled here (no Android SDK). Send any `e:` lines from the GitHub build.
