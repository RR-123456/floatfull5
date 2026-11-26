# v35: voice tuner (Charon sounds like the Pragon reference voice)

**What it does.** Pragon Live speaks in Gemini's "Charon" voice. The tuner sits between Gemini and the speaker and pulls Charon toward the
reference voice (the "assistant voice" clip from the orb):

- **Tone match**: it listens to Charon, keeps a running average of 11 frequency bands, and an 11-band equaliser moves that average
  toward the reference voice's average. It starts after about 2 s of speech and remembers what it learned for the next chat.
- **Pitch match**: it keeps a running median of Charon's pitch and asks the player (AudioTrack playback pitch, speed unchanged)
  to move it to the reference pitch (about 133 Hz). Limited to +/-5 semitones.
- **Knobs**: tone match strength, pitch fine-tune, warmth, presence, metallic, space (small room), loudness. A soft limiter stops clipping.

**Where.** Settings > Pragon Live voice > Voice tuner (all controls), and a **Charon voice** tab in the UI customiser (one slider: Charon to reference voice, plus on/off and match pitch). Changes apply to the next chunk of speech. "Reset the tuner" puts everything back.

**Files.** New: `VoiceTunerCore.kt` (the maths, plain Kotlin), `VoiceTuner.kt` (settings + per-session chain).
Changed: `OrbWeb.kt` + `CustomiserActivity.kt` (give the customiser page `window.PragonTuner`), `pragon_orb.html` (the Charon voice tab; also the new click sound "Assistant voice 2 (trimmed)"), `LiveSession.kt` (Player runs the tuner and sets the pitch), `Prefs.kt` (2 keys pairs), `SettingsActivity.kt` (3 bridge calls), `settings.html` (the screen).

**Measured from the reference clip** (with the same filter bank and pitch method the app uses): median pitch 133.1 Hz,
band shares in `REF_BAND_DB`. No echo or reverb was found in the reference, so metallic/space start at 0.

**Tested** (on a laptop, `VoiceTunerCore` only): on a deliberately different voice, the average tone error against the reference fell
from 4.2 dB to 1.2 dB and the pitch moved from about 174 Hz to about 125 Hz (limit reached). Not tested on a phone or on real Charon audio.

**Known limits.** Some phones ignore a pitch change on a call-type audio stream; then only the tone match applies (the pitch slider will do nothing).
Pitch is moved by changing playback pitch, which also moves the voice's formants a little (a "bigger/smaller" voice), so large moves sound less natural.
