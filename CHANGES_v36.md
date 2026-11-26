# v36: voice tuner rebuilt (Charon is moved onto the Pragon reference voice for real)

## Why v35 did not sound like the reference
v35 only did two things: an 11-band equaliser, and a request to the phone's AudioTrack for a pitch change. Many phones ignore that request on a
call-type stream, so the pitch never moved, and an 11-band equaliser cannot change a voice's size or pitch. The reference reading was also
slightly off (it was measured with wide, ragged filters).

## What v36 does (new `VoiceMorph.java`, plain Java, no Android needed)
1. **Pitch, inside the audio.** A delay-line pitch shifter that repeats or drops whole voice periods and crossfades (no dependence on the phone).
   It listens to Charon's pitch (running median) and moves it to the reference pitch (137.3 Hz, same pitch method). Moves at most 2 semitones
   per second, limited to +/-6 semitones.
2. **Voice size (formants).** Shifting pitch drags the resonances along; a cepstral-envelope warp moves them back 65% of the way, so the voice
   does not turn into a chipmunk or a giant. New slider **Voice size** (+/-6 st) in Settings > Voice tuner.
3. **Tone.** 21 smooth bands (70 Hz to 12 kHz) instead of 11; closed loop (it measures its own output), 4 s fade-in, slow slew, saved between chats.
4. Warmth, presence, metallic, space, loudness and the soft limiter work as before. Reset the tuner puts everything back.

The reference was re-measured with this engine's own analysis from both "assistant voice" clips (smoothed 0.4 octave so it does not copy
the words of the clip): median pitch 137.3 Hz and 21 band levels (`REF_BAND_DB`).

## Fixes that came with it
- The last words of a sentence are no longer held back: after a short pause the player pushes the tuner's delay out (`Chain.flush`).
- If you interrupt Pragon, whatever is still inside the tuner is thrown away (`Chain.interrupt`), so no stale half-sentence is played later.
- Old v35 saved tuner state is ignored automatically (new format), so the first chat after the update re-learns Charon in a few seconds.
- Added latency: about 85 ms (shifter delay + analysis window). Fine for spoken replies.

## Files
New: `VoiceMorph.java`, `tests/VoiceMorphTest.java`, `tests/MorphRobustTest.java`, `tests/RefTool.java`, `tests/WavIO.java`.
Changed: `VoiceTuner.kt` (wrapper), `LiveSession.kt` (Player: flush on pause, interrupt, no AudioTrack pitch by default), `assets/settings.html` (Voice size slider).
Removed: `VoiceTunerCore.kt`.
`pitchMode` (0 = in the audio, default; 1 = ask the phone's player, the v35 way) exists in the saved settings JSON for diagnosis; there is no screen for it.

## Tested (laptop, Java 21)
Proxy "other voices" were made from the reference clip itself (pitch moved 1.3x up, 0.78x down, 1.15x up with a tone tilt, and a formant-preserving
+1.22 shift), streamed in 100 ms chunks, and scored against the original reference with a time-aligned spectral distance:

| proxy voice | pitch error before -> after | tone error (dB) before -> after | envelope distance (dB) before -> after |
|---|---|---|---|
| up 1.3x | +2.9 st -> -0.1 st | 3.1 -> 1.7 | 6.1 -> 4.3 |
| down 0.78x | -2.8 st -> -1.3 st | 6.0 -> 1.3 | 8.4 -> 3.5 |
| up 1.15x + tilt | +1.4 st -> -0.2 st | 3.5 -> 1.4 | 5.2 -> 3.3 |
| formant-preserved +1.22 | +2.4 st -> -0.9 st | 13.8 -> 1.6 | 11.9 -> 3.5 |

Also: length preserved for random chunk sizes, no clipping, silence in gives silence out, the last words are released by the flush, interrupt clears the
delay, state save/restore, about 10x real time on a laptop CPU.

## Not tested / limits (be honest)
- **Not compiled or run on a phone, and not tried on real Charon audio.** Kotlin parts (`VoiceTuner.kt`, `LiveSession.kt`) were checked by reading only.
- It matches the reference's pitch and average tone and voice size. It does not change *how* the voice speaks (accent, rhythm, breathiness, word
  stress). A different voice with a different speaking style will still be a near cousin, not an identical copy. A real copy needs a voice-conversion model.
- If a result sounds slightly "phasey" or warbly on big pitch moves, lower **Tone match** or set **Pitch fine-tune** towards 0; the shift is automatic up to 6 st.
