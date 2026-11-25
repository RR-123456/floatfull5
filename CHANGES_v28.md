# v7.8 (code 78): Piper offline voice, with the phone's own voice as the fallback

## Piper (new)
- Pragon can now speak with **Piper**, an offline neural voice (English "amy", low quality: small and quick). It runs inside the sherpa-onnx library that is already in the app, so no new library was added.
- **Fallback:** Piper is used only when it is downloaded AND loaded in memory. In every other case (not downloaded, still loading, switched off, or an error while speaking) the phone's own text to speech speaks, exactly as before. If Piper fails on one sentence, that sentence is said by the phone's voice, so Pragon never goes silent.
- The voice is chosen once per reply, so a reply is never split between two voices. The first reply after start-up may use the phone's voice while Piper finishes loading.
- **Download:** Settings > Offline > new **Piper voice** row (Download and Load buttons, pill MISSING / INSTALLED / LOADING / LOADED). **Download and load all models** now includes it (about 73 MB: voice 67 MB + word list 6 MB; the whole set is about 1 GB).
- **Switch:** Settings > Voice > "Use the Piper voice when it is loaded" (on by default). Speed follows the existing Speed slider. Pitch and the voice list apply to the phone's voice only.
- **Why a word list:** sherpa-onnx 2.x removed espeak-ng, which old Piper archives relied on. The voice therefore uses `lexicon-en-us.txt` next to the model, which is the combination sherpa-onnx's own example uses (vits-piper-en_US-amy-low + lexicon-en-us.txt). The espeak-ng-data folder in the archive is skipped. The name "Pragon" is added to the word list so it is pronounced.
- Numbers are spelled out before speaking ("5 minutes" -> "five minutes", 3.5, 1,000, 50%), because the word list has no digits.
- Files: `PiperTts.kt` (new), `Speaker.kt`, `ModelPack.kt`, `Offline.kt`, `Prefs.kt`, `Guard.kt`, `SettingsActivity.kt`, `settings.html`.
- Not built or run on a phone. If Piper stays silent, check the Piper pill: an `error:` state means the voice would not load, and the phone's voice keeps speaking.

# v7.7 (code 77): Typer punctuation, English only, all models in one tap

## Typer punctuation (new)
- Typer now adds the punctuation you would not say out loud: full stop, question mark or exclamation mark at the end, a comma after "hello" / "okay" / "however", a comma before a mid-sentence "but", and a capital **I** ("i'm" becomes "I'm").
- Rule-based, English, on the phone. **No model file, nothing to download.** Spoken punctuation ("comma", "question mark") still works and wins: text you already punctuated is left alone.
- One-line boxes (search bars) get no end mark, so "best pizza near me" is not turned into "best pizza near me." Messages and notes (multi-line boxes) do.
- Emails, links and code-like text are never touched. One-word pieces are left alone.
- Settings > Float and typer: **Typer adds punctuation by itself** (on by default).
- Limits: it guesses from the first words. "When you arrive call me" gets a full stop, not a comma. Say "comma" when you need an exact one.

## English only
- Speech language is always an English locale (the phone's English, or en-US). A non-English choice saved by an older version is ignored.
- Replies are locked to English. The voice no longer switches language by script. The speech model link is fixed to the English model, and the other-language pickers are gone from Settings.
- Build: `androidResources { localeFilters += "en" }` drops translated strings that libraries bring along, which makes the APK a little smaller. ML Kit text recognition stays Latin only.
- Not done: R8 shrinking. It could break the native speech and LiteRT libraries, and this build has not been compiled here.

## All models in one tap (Settings > Offline, top card)
- **Download and load all models** fetches whatever is missing, one after another: speech pack (about 70 MB), cut-out model (Google Play services, a few MB), command model (FunctionGemma, about 283 MB), then the Gemma 3 1B chat model (about 529 MB). Then it loads the speech, command and chat models into memory (command first, then chat, so a low-memory phone is not asked to do both at once). Already-installed ones are skipped. A failure never stops the rest, and the summary says what is still missing.
- **Each row has its own Download button** (and a **Load** button for speech, command and chat), so one model can be fetched or loaded alone. The cut-out model has Download only, because Google Play services holds it.
- Status pills show MISSING, INSTALLED, LOADING or LOADED. Text reading, picture labels and object detection are inside the app. The offline voice is the phone's own text to speech, so it is not in this list.
- **The Gemma chat model is gated on Hugging Face.** Accept the licence once on the Gemma3-1B-IT page, make a free read token, and paste it in the box. It is saved only in this app's private storage; **Forget token** removes it. Without a token the other models still download.
- **Removed from Settings because the card covers them:** the old Download buttons for the speech, command and chat models, the chat link box, the second token box, the long chat-model hint, and "Load model now". Kept: **Pick chat model file** (backup if the download is rejected), **Remove chat model**, the speech recogniser choice, "Install pack" for Android's own speech pack, and "Speed report".
- Link used: `https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.litertlm` (checked against the Hugging Face page).
- The FunctionGemma link was marked unverified by earlier notes and is unchanged. If it fails, the summary shows the HTTP error.

## Find another model (Settings > Offline, under the token box)
- **Search box**: searches Hugging Face for on-device models, lists the matches (most downloaded first), and tap **Files** to see each model's `.litertlm` files with their sizes. **Download** puts the chosen file in the chat slot, replacing the current chat model (it asks first).
- **Paste a link**: any https link ending in `.litertlm` downloads the same way.
- Only LiteRT-LM files (`.litertlm`) can run in this app's engine, so `.task` and `.gguf` files are not listed. Files for one phone chip only (names like sm8750 or mt6993) are hidden.
- The Hugging Face token is attached only to huggingface.co / hf.co links, never to any other site. Model names and file names are checked before they go into a link.
- Only the **chat** slot can be swapped. The command model must know the app's command format, so it stays FunctionGemma.
- Not verified from here: the search first asks Hugging Face for the `litert-lm` library tag, and if that returns nothing it falls back to the `litert-community` author. If a search comes back empty, try a shorter word (gemma, qwen, phi). A model that downloads but won't load shows an error in the chat pill: use **Remove chat model** and pick another.

## Build notes
- New: `ModelPack.kt`, `ModelSearch.kt`. Changed: `Typer.kt`, `PragonAccessibilityService.kt` (dictate), `Prefs.kt`, `Guard.kt`, `Offline.kt` (status), `SettingsActivity.kt`, `OnDeviceLlm.kt` (warmOne), `LangDetect.kt`, `settings.html`, `app/build.gradle.kts`. Test: `tests/V14Test.kt`.
- Added dependency `play-services-base:18.5.0` (ModuleInstall, for the cut-out download). 77 / 7.7.
- **Not compiled here** (no Android SDK, no kotlinc). The punctuation rules were checked with a Python copy of the same logic. Send any `e:` lines from the GitHub build.
- Not built: Piper voice (last), SmolVLM.
