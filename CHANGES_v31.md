# v8.1 (code 81): all languages are back

v7.7 (code 77) had locked Pragon to English while the English offline models were added. This build undoes the lock and keeps every English addition (speech pack, Kokoro voice, Gemma chat model, Typer punctuation, vision tools, Unload buttons).

## Restored
- **Listening language**: Settings > Voice and language now lists the phone's language, English (India/US/UK), Tamil, Hindi, Telugu, Kannada, Malayalam, Marathi, Bengali, Gujarati, Punjabi, Urdu, Spanish, French, German, Italian, Portuguese, Russian, Arabic, Turkish, Indonesian, Thai, Japanese, Korean and Chinese. The saved choice is used again (`Prefs.speechLang`).
- **Pragon replies in**: "Follow the language I speak" (default) or a locked language (`Prefs.replyLang`).
- **Voice switching by script**: `LangDetect.ENGLISH_ONLY = false`, so Tamil / Hindi / Telugu ... replies are spoken in that language's voice again.
- **Speech model link**: `Prefs.sttModelUrl` honours a custom link again. New box under Offline > "Speech model for another language" with **Use this model** and **Back to English**. Blank = the English model. With a non-English listening language and no link, Android's own recogniser listens (the existing `languageOk` rule).
- **Translated strings**: removed `androidResources { localeFilters += "en" }` from `app/build.gradle.kts`.

## Kept English-only on purpose
- **Typer punctuation** rules are English, so they now run only when the listening language is English. Other languages are typed exactly as heard.
- **Kokoro voice and the default speech pack** are English models. For other languages the phone's own text to speech speaks, and Android's recogniser (or a speech model link) listens.

## Files
`LangDetect.kt`, `Prefs.kt`, `PragonAccessibilityService.kt` (dictate), `SettingsActivity.kt` (useSpeechModel), `assets/settings.html`, `app/build.gradle.kts` (81 / 8.1).

Not compiled here (no Android SDK). Send any `e:` lines from the GitHub build.

## Priority order
Language lists and script detection put English first, then Tamil, Telugu, Hindi, then the rest.
