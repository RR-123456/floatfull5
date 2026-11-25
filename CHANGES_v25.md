# v7.4 (code 74): offline screen reading and screen clicking by description

Built on v7.3 (OCR, labels, cut-out). Both need **Settings > Security > "Let Pragon read my screen"** and the accessibility service,
and use the on-phone chat model (Settings > Offline > Chat model, Gemma 3 1B IT). Nothing leaves the phone.

## Screen reading (offline question and answer)
- "what's on my screen?", "summarise this page", "answer the question on the screen", "on the screen, ..." now work with **no internet**.
  Before, they went to Gemini / Ollama and failed offline. When strict offline is on or the phone has no connection, `ScreenAI.ask` hands over to `Vision.askLocal`.
- It reads the screen's text through the accessibility service. If there is little text, it reads the pixels with OCR. If there is no text at all
  (a plain photo), it names what is in the picture with the image labeller.
- The chat model can't see images; it answers from the text. It is a 1B model: fine for summaries and look-ups ("what is the total", "who sent this"),
  weak at careful reasoning. If the chat model is not installed, Pragon reads the screen text out instead.
- Online with Gemini / Ollama, nothing changes: they still get the screenshot as well.

## Screen clicking by description
- "click X" tries, in order: (1) the accessibility tree by name (as before), (2) OCR, finds the word on the pixels and taps it (v7.3),
  (3) **new**: the chat model is shown a numbered list of the tappable items on screen and picks the one that fits ("click the search icon",
  "tap the green call button", "press the back arrow"). Item labels come from text, content descriptions and view ids; password boxes are never listed.
- Step 3 takes a second or two and runs only when 1 and 2 fail. If the model answers "none" or isn't installed, you get the usual "couldn't find" reply.
- One click per command. Multi-step tasks ("open Instagram, then message Ravi") are still beyond a 1B model.

## Files
`Vision.kt` (askLocal, pickAndTap), `PragonAccessibilityService.kt` (clickCandidates, pressNode), `OnDeviceLlm.kt` (resetChat), `ScreenAI.kt`, `CommandExecutor.kt`, `app/build.gradle.kts` (74 / 7.4).

**Not compiled here** (no Android SDK). Send any `e:` lines from the GitHub build. Not tried on a phone: the quality of the 1B model's picks is unmeasured.
