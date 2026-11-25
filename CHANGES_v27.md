# v7.6 (code 76): BlindCam, the talking camera

New tile in the Tools hub: **BlindCam** (Sidebar > Tools, or Tools screen). Everything runs on the phone. **Nothing is saved**: frames stay in memory and are dropped after analysis.

## How it works
- **Tap to capture**: big button, tap anywhere on the picture, or press **Volume Up**. It looks at the next frame and speaks what it found.
- **Live**: button, or **Volume Down**. Looks about every 2.5 seconds and speaks **only when what it sees has changed** (it won't repeat itself, and won't talk over itself).
- **Modes** (switch any time, announced aloud; the buttons are large and read out by TalkBack):
  - **Auto**: a scene sentence ("I see a cup on the left, a laptop in the middle") plus any text found.
  - **Read text**: the English text in view.
  - **Objects**: each object and where it is (left / middle / right).
  - **Describe**: the scene sentence only.
- A tap-capture in Auto / Describe uses the on-phone chat model (Gemma 3 1B) to write a nicer sentence (a few seconds). **Live always uses the quick version** built straight from the detected objects, so it keeps up. If the chat model is not installed, the quick version is used everywhere.
- The status line at the top shows the same text and is a TalkBack live region.

## Build notes
- New: `BlindCamActivity.kt` (CameraX preview + frame analysis, buttons built in code). `Vision.analyzeFrame` + `Vision.CamMode` do the analysis (ML Kit object detection, labels, OCR; Gemma for the interactive sentence).
- CameraX 1.3.4 added (camera-core, camera2, lifecycle, view). `tools.html` tile + `ToolsActivity.Bridge.blindcam()`. Manifest activity. 76 / 7.6.
- Controls are buttons, tap and volume keys. **No voice commands inside BlindCam yet** (the microphone is not used there).
- **Not compiled here** (no Android SDK). Send any `e:` lines from the GitHub build. Not tried on a phone: the live loop speed on your phone, and how good the 1B sentences are.
- Still not built: Piper voice (last), SmolVLM, Typer punctuation model.
