# v8.2 (code 82): see why offline voices fail, test them, and offline Tamil / Telugu / Hindi voices

## Why nothing looked like it was working
- Settings > Offline showed only MISSING / LOADING / LOADED / INSTALLED. A voice or speech model that **failed to load** (state `error: ...`) was shown as INSTALLED, and the error text was never displayed anywhere. The earlier notes said "check the pill for error:", but the pill could not show it.
- Fixed: a failed model now shows **ERROR**, and a red line under the models lists every error text (speech pack, realistic voice, each Indian voice, command and chat model, and download errors).

## Test offline voices (new button, Settings > Offline)
Runs one plain-text report and shows it in a popup:
- the sherpa-onnx native library loads or not (and which native files are in the app),
- Kokoro: files and sizes, load result with the failing line, a generated test sentence (samples, loudest level, so silence is detected), and a real playback through the app's own player,
- the same for every downloaded Tamil / Telugu / Hindi voice,
- offline listening: speech pack installed, engine setting, whether Pragon's own listener is used right now, and why not.

## Offline Tamil, Telugu, Hindi voices (new, `MmsTts.kt`)
- Meta MMS-TTS voices run by sherpa-onnx (VITS, character based: model.onnx + tokens.txt, about 110 MB each, 16 kHz, one voice). Settings > Offline has Download / Load / Unload for each language.
- A reply written in Tamil, Telugu or Hindi script is spoken by that voice when it is loaded. Otherwise: English text uses Kokoro, then the phone's own voice, as before. If an MMS sentence fails, the phone voice says it (now in the right language).
- Download sources: first `willwade/mms-tts-multilingual-models-onnx` on Hugging Face (`<iso3>/model.onnx` + `tokens.txt`), then the k2-fsa release archive `vits-mms-<iso3>.tar.bz2`. The second name was not checked from here.
- Digits are not read by these voices (they have no number words). End marks are dropped before speaking, because a trailing full stop makes sherpa-onnx add a noisy extra sentence.
- Licence of MMS voices: CC-BY-NC 4.0, non-commercial use.
- Settings > Voice > "Use the realistic voice when it is loaded" switches both Kokoro and the Indian voices.

## Listening (offline speech) notes
- The built-in speech pack is English only. With a Tamil / Telugu / Hindi listening language, Pragon's own listener is skipped and Android's recogniser listens (it needs its offline language pack), unless you paste a speech model link in "Speech model for another language".
- Engine Auto uses Pragon's own listener only when offline. Choose **Pragon** to always use it.

## Smaller changes
- Kokoro accepts `model.onnx` as well as `model.int8.onnx`.
- Files: `MmsTts.kt` (new), `VoiceDiag.kt` (new), `PiperTts.kt`, `Speaker.kt`, `Offline.kt`, `ModelPack.kt`, `SettingsActivity.kt`, `assets/settings.html`, `app/build.gradle.kts` (82 / 8.2).

Not compiled here (no Android SDK) and not run on a phone. Send the text from **Test offline voices** if anything still stays silent.
