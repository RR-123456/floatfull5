# v7.5 (code 75): camera input, object detection, scene description

Built on v7.4. Everything runs on the phone. English text only.

## New
- **Camera input** for OCR, labels and cut-out: add "with the camera" ("scan the text with the camera", "label this with the camera", "cut out the subject with the camera").
  `VisionCaptureActivity` opens the phone's camera app, takes one photo (asks for the camera permission the first time), reads it upright, deletes the working
  copy from the gallery and hands it back. Without "with the camera" the tools still use a screenshot, as in v7.3.
- **Object detection** (ML Kit Object Detection, model bundled): "what objects do you see", "detect objects", "what objects are on the screen".
  Up to 5 objects, each said with where it is ("a cup on the left, a laptop in the middle"). ML Kit names boxes only coarsely, so each box is cropped and run
  through the image labeller for a finer name. A copy with the boxes drawn is saved to Pictures/Pragon (so this needs Master or Agent mode, not Assistant).
  Default source = camera; say "on the screen" for a screenshot.
- **Scene description**: "what do you see", "describe the scene", "describe what you see". There is no vision model: labels + objects + any text found are
  given to the on-phone chat model (Gemma 3 1B), which writes one or two sentences from only those facts. Without the chat model the facts are read out.
  Default source = camera; "describe the scene on the screen" uses a screenshot.

## Already there (nothing new needed)
- Hands-free voice conversation: float mode (say "Pragon, I'm home"): keeps listening, answers by voice, offline chat model for free conversation.
- Chat model download button: Settings > Offline > Chat model (link + Hugging Face token, or Pick file).
- Screen reading / clicking: v7.3 and v7.4.

## Not in this build
- **Piper voice** (kept for last, as asked). The offline voice is still Android's built-in text to speech.
- **SmolVLM-256M**: not added. It is not a .litertlm file, so it may not run in the LiteRT-LM engine; scene description uses labels + objects + text + Gemma instead.
- **Typer punctuation model**: later.
- Object detection is photo-based, not a live camera preview.

## Files
New: `VisionCaptureActivity.kt`. Changed: `Vision.kt`, `VisionVoice.kt`, `CommandExecutor.kt`, `Brain.kt`, `Macros.kt`, `Tools.kt`, `AndroidManifest.xml`, `app/build.gradle.kts` (object-detection 17.0.2; 75 / 7.5).

**Not compiled here** (no Android SDK). Phrase patterns were checked with a Python port (all pass; no hits on "read the screen", "what is this picture", "what am i looking at", "open camera").
Send any `e:` lines from the GitHub build. Not tried on a phone: the camera hand-off from float mode, and how good the 1B scene sentences are.
