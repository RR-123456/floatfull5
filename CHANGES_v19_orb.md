# v19.2: orb page updated

- `assets/orb/pragon_orb.html` replaced with `pragon_orb_expand_then_draw_beams_freq (36)`.
  New in the page: startup animation (dot, emblem draws out, beams, expands to the orb), light beams on the emblem,
  Frequency click pack + Hz setting, repeat-gap setting for looping sounds, core logo replaces the label text
  (with a label on/off switch), emblem sound-effect slots, smoother cluster-combo spin, new default SFX.
- `OrbWeb.kt`: URL is now `...pragon_orb.html#noinit`. The new page hides itself until its startup animation finishes
  unless the URL has `#noinit`; without this the floating bubble / radial menu / customiser would stay blank for up to 9 s.
- Checked: every page API the Kotlin side uses (`orb.setTransparent/setPart/move/openMenu/menuOpen/onselect`, the
  `pragonCmd` customiser message, ids `sframe emblem ecb ecp stage`) still exists. Not compiled or run on a device.
- Page grew 2.7 MB to 3.1 MB (embedded sounds).

## v19.3: bubble not cropped + startup animation in the bubble
- `FloatService.kt`: bubble window 108dp -> 150dp, so the orb, its ring ticks and the startup emblem fit (they were cut at the edges). Orb scale in the bubble 1.25 -> 1.0.
- `pragon_orb.html`: new `#tpinit` mode (class `tp`): transparent page, HUD hidden, emblem sized to the window (84vmin), startup animation plays; class `done` removes the emblem layers afterwards.
- `OrbWeb.kt`: bubble loads `...#tpinit` and plays dot > emblem draws > logo > blinks > expands into the orb (about 7 s). Radial menu and customiser still load `#noinit` (no animation, so they are never blank).
- `BUBBLE_INIT` waits for the page to open, then hides the emblem layers; fallbacks if the animation does not run (3 s) or hangs (20 s).
- Not compiled or run on a device. If the bubble stays blank or flashes black, or the 150dp window blocks too much of the screen, tell me and I'll tune the size.

## v19.4: Pollinations image generation fixed + API key panel
- Why it failed: the old address `image.pollinations.ai/prompt/...` is gone. Pollinations now serves everything from `https://gen.pollinations.ai/image/<prompt>` and expects an API key (`Authorization: Bearer <key>`, made at enter.pollinations.ai).
- `ImageGen.kt`: new endpoint, key sent as a Bearer header, styles flux / turbo / gptimage / seedream / nanobanana. Clear messages for 401 (no or bad key), 402 (no Pollen balance), 403 (model not allowed / prompt blocked), 429 (busy), 404, plus the service's own reason text. With a key the image is requested at the exact size (the bottom-strip crop is only used without a key). New `testKey()` checks the key through `/account/balance`.
- `Prefs.kt`: `pollKey` / `savePollKey`.
- `ToolsActivity.kt` bridge: `pollKeyState`, `setPollKey`, `testPollKey`, `clipboardText`, `openPollSite`.
- `tools.html` Image generator: new **🔑 Pollinations API key** panel (opens by itself when no key is saved, or when a request fails for key reasons): Paste, Save, Test key, Get a key, Remove key, Show / hide. The key is stored in the app's private preferences and sent only to gen.pollinations.ai. Style list gained Seedream and Nano Banana.
- Voice "generate an image of ..." goes through the same screen and uses the saved key.
- Not compiled or run on a device. Some styles (gptimage, seedream, nanobanana) may need Pollen balance or a higher Pollinations tier; Flux is the safest to test first.

## v19.5: Pollinations key moved to Settings
- `settings.html`: new **Image generation** card under SETUP (after "Pragon online"): status dot, key box, Paste, Show / hide, Save and test, Test key, Get a key, Remove key. Browser-preview mock added.
- `SettingsActivity.kt` bridge: `getPollKey`, `savePollKey`, `clearPollKey`, `testPollKey` (answers through `pmPollTest`), `clipboardText`, `openPollSite`.
- `tools.html` Image generator: the key panel is removed. When a request fails for key or balance reasons, an **Open Settings to add the key** button appears. `ToolsActivity.kt`: key methods removed, `openSettings()` added.
- `ImageGen.kt`: messages now point to Settings > SETUP > Image generation. Key storage (`Prefs.pollKey`) unchanged, so a key saved in v19.4 still works.
- Not compiled or run on a device.
- v19.5.1: Settings > Image generation buttons laid out in two clean rows (Paste | Show/hide, Test key | Get a key) with Save and test and Remove key full width; fixes the staggered buttons.
