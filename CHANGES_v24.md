# v7.3 (code 73): on-phone vision (English only)

Three tools, all run on the phone, built on one screenshot of the screen. Each needs **Settings > Security > "Let Pragon read my screen"**
and the accessibility service, and refuses while a password box is visible. They are user-only (the AI model and the PC link can't run them).

| Tool | Say | How |
|---|---|---|
| Text reading (OCR) | "OCR", "scan the text", "extract the text on the screen", "read the text in this picture" | ML Kit Text Recognition (Latin), model bundled in the APK; reads the text aloud and shows it in chat |
| Image labels | "label this picture", "what objects are on the screen", "list the objects" | ML Kit Image Labeling (about 400 everyday labels, 60%+ confidence), model bundled |
| Subject cut-out | "cut out the subject", "remove the background", "make a sticker" | ML Kit Subject Segmentation (beta). Saves a transparent PNG to Pictures/Pragon |

**Click by OCR:** "click <word>" first looks in the accessibility tree as before. If nothing has that text (text drawn inside a picture, games,
some web views) it now reads the screen with OCR and taps the matching word.

## Notes
- Cut-out model comes through Google Play services: the manifest asks for it at install time; otherwise it downloads on first use (needs internet once).
  The OCR and label models are inside the APK, so the APK grows by a few MB.
- Offline Assistant mode may run OCR and labels (they only answer); cut-out saves a file, so it needs Master or Agent mode.
- Files: Vision.kt, VisionVoice.kt (new); CommandExecutor.kt, Router.kt, Brain.kt, Macros.kt, Tools.kt, AndroidManifest.xml, app/build.gradle.kts.
- Phrase patterns were checked with a Python port of the regexes (all pass, no false hits on "read the screen", "what is this picture", "click send").
- **Not compiled here** (no Android SDK / kotlinc). The ML Kit calls (Tasks.await, SubjectSegmenterOptions, ImageLabelerOptions) follow the documented API, but send any `e:` lines from the GitHub build. Not tried on a phone.
