# Pragon Mobile v20 (versionName 7.1): bubble, chat image gen, music, calculator, video editor

**Not compiled** here (no Android SDK). If the GitHub build prints `e:` lines, send them over.

## Bubble
- `FloatService.kt`: bubble window 150dp -> 190dp and clipped to a **circle** (oval outline, no square shadow).
- `pragon_orb.html` (bubble mode `html.tp`): soft circular fade at the edge, so the orb glow and ring ticks are never cut off square; startup emblem a little smaller so it stays inside the circle.
- Core logo: empty (transparent) border is trimmed off when the logo loads, and it is drawn as large as fits **inside** the core disc (was a small box, so wide logos like PRAGON looked tiny).

## Image generator: "Direct chat interface image gen"
- New switch in Tools > Image generator. **On**: "generate an image of ..." typed or spoken in the chat shows the picture **in the chat** (rounded, tap to open in the gallery) instead of opening the generator screen. **Off**: old behaviour.
- Uses the style engine and shape last chosen in the generator screen. The image is also saved to Pictures/Pragon.
- Only the chat screen does this (the float bubble has no place to show a picture, so it still opens the generator).
- Files: `Prefs.kt` (`imgInChat`, `imgModel`, `imgShape`), `Brain.kt` (`Reply.image/imageBytes`, `Sink.showsImages`, intercept), `StandaloneView.kt` (`addImage`), `ToolsActivity.kt` bridge (`imgChatState`, `setImgChat`, `rememberImgChoice`), `tools.html`.

## Music
- `music.html` had lost its page CSS: song rows (`item/art/meta/more`), the `opt` menu rows, bottom sheets (song menu, sort, player, settings), fixed mini player and toast. All restored, so each song is now a proper row: note icon, title + duration/artist, and a ⋮ button.

## Scientific calculator
- The key grids carried both `tile` and `tgrid`; `tile` (single column) won, giving one tall column of tiny keys. Now a real 4-column grid with styled keys, wide `=` key, styled input, result box, and the file picker hidden behind the Image button.

## Video editor
- Preview: `serve()` returned nothing when the phone did not report a file size (preview stuck at 0:00 / 0:00). It now finds the size another way, streams whole if unknown, normalises the MIME type, handles `bytes=-N`, and if the preview still cannot open the file it copies it to the app cache and retries.
- `video.html`: the **seek slider** was missing (empty placeholder), so "Split / Trim at cursor" could not work. Added, plus tap-the-video to play/pause.
- Restored the page CSS (stage, controls, timeline parts, tool grid, bottom sheets for speed / text / export).

## All pages
- `.sw.on` switch style (the pages toggle the `on` class but only `:checked` was styled) and a global `[hidden]{display:none}` so hidden panels with `display:flex` stay hidden.
