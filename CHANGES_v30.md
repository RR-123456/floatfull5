# v8.0 (code 80): realistic offline voice (Kokoro) replaces Piper

## Why Piper did not speak
The Piper "amy-low" voice was loaded with a separate word list (lexicon-en-us.txt). That list writes some sounds as two characters (for example the long "a" sound), but the voice's token list only has one-character entries, so the voice could not be built from it. The note in v7.8 that sherpa-onnx 2.x dropped espeak-ng was also wrong: the current sherpa-onnx documentation still passes the espeak-ng-data folder to its voices.

## What replaces it
- **Kokoro 82M, int8, English** (`kokoro-int8-en-v0_19`), run by the sherpa-onnx library already in the app. It is the most natural small offline voice sherpa-onnx supports, 24 kHz, 11 voices (this build speaks with voice 1, "af_bella", American female).
- It uses sherpa-onnx's own documented Kokoro setup: model + voices.bin + tokens.txt + espeak-ng-data. **No word list**, and numbers and names are handled by espeak-ng.
- **Download:** about 103 MB, about 160 MB on the phone (Settings > Offline > **Realistic voice**: Download, Load, Unload). It is also part of "Download and load all models". Saved in its own folder; the old Piper files (about 73 MB) are deleted when the new voice finishes downloading.
- Same safety net: if the voice is not downloaded, not loaded, switched off, or fails on a sentence, the phone's own voice speaks that sentence.
- Speed follows the Speed slider. Switch: Settings > Voice > "Use the realistic voice when it is loaded".
- Files: `PiperTts.kt` (same object name, Kokoro inside), `ModelPack.kt`, `Offline.kt`, `SettingsActivity.kt`, `settings.html`, `tools/fetch_sherpa.sh` (the build now stops with a clear message if the sherpa-onnx release has no Kokoro config).

## Notes
- **After installing, tap Download on "Realistic voice" once** (the old Piper download does not count), then Load.
- Kokoro is heavier than Piper: the first sentence after loading takes a moment, and a long reply is spoken sentence by sentence as it is made. If it ever lags, the phone's own voice still works as the fallback.
- Not compiled or run on a phone here. If the pill says `error: ...`, send me that text.
- Other voices: change `SPEAKER` in `PiperTts.kt` (0 af, 1 af_bella, 2 af_nicole, 3 af_sarah, 4 af_sky, 5 am_adam, 6 am_michael, 7 bf_emma, 8 bf_isabella, 9 bm_george, 10 bm_lewis).
