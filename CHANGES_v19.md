# Pragon Mobile v19 (versionName 7.0): Image generator (Pollinations)

**Not compiled** here (no Android SDK). If the GitHub build prints `e:` lines, send them over.

## What changed
- New `ImageGen.kt`: calls `https://image.pollinations.ai/prompt/...` (free, no key), retries once on 429/5xx, saves the result to Pictures/Pragon.
- Tools hub: new **Image generator** tile + panel (prompt, shape, engine flux/turbo/gptimage, Generate, New variation, Open, Share). Also in Sidebar > Tools (`ImageScreen`).
- Voice / chat: "generate an image of a red fox", "draw a picture of ...", "open the image generator". Opens the screen and starts straight away.
- `ToolsActivity.Bridge`: `imagine`, `shareImage`, `viewImage`. Manifest entry for `ImageScreen`. Version 70 / 7.0.
- The orb's old in-page widget is untouched.

## Notes
- The free tier is rate limited; the screen says so when it gets a 429.
- Pollinations accepts an optional token for higher limits; not wired in (say if you want a field in Settings).

## v19.1
- Generated images are cropped: the request is 9% taller and the extra strip at the bottom is cut off, so the final size is the one chosen and any service mark at the edge is gone.
