# v34: emblem size fixed when the orb is resized + emblem size controls

## What was wrong
Settings > Floating orb size (50-200%) resizes the whole orb picture, so the emblem in the orb's core grew and shrank with it.

## Fix
- The emblem now keeps one fixed size: the size it has when the orb is at 145%. At small orb sizes it never grows past the orb's core disc.
- New switch **Emblem size follows orb size** (off by default). On = the old behaviour (emblem scales with the orb).
- New **Emblem size** slider, 30-100% (100% = the 145% size / the biggest that fits the core). Default 100%.
- Both controls are in Settings > Float & phone control > Orb and UI customiser, and in the Smart Sidebar > ORB AND UI CUSTOMISER block, together with an **Orb size** slider (50-200%). Settings and sidebar share the same saved values.

## Files
Prefs.kt (emblem_follow, emblem_scale), FloatService.kt (applyEmblem / pushEmblem), SettingsActivity.kt (bridge), Sidebar.kt (sliders + switch),
assets/settings.html, assets/orb/pragon_orb.html (orb.setEmblem). The UI customiser orb is unchanged (it still follows its size).
Not compiled here: build and test on a phone.

## Neural chat in Tools
- New **Neural** tile in Tools (next to Music). Opens the Neural chat screen (assets/neural_chat.html, the page from preview/) full screen; Back closes it.
- Files: NeuralActivity.kt (new), AndroidManifest.xml, ToolsActivity.kt (openNeural), assets/tools.html, assets/neural_chat.html.

## Voice control for every option and tool (new VoiceSettings.kt)
- Say "turn on / turn off / enable / disable / toggle <option>" or "is <option> on" for: speak replies to typed messages, float wake word, realistic (Piper) voice, Pragon Live voice, interrupting Pragon, media-friendly listening, typer punctuation, typer auto-find, macros, screen reading permission, accessibility helpers permission, only-open-allowed-apps, press Send for me, show all features, emblem follows orb size, sidebar on the left.
- Sizes by voice: orb size only ("set orb size to 150", "make the orb bigger / smaller", "reset orb size"), 50-200%, live. The emblem size stays a Settings / sidebar slider (no voice command), but "emblem follows orb size on/off" works.
- Tools: "open neural", "open the video editor", "open the drafter", "open the AC remote", "open notification monitors", "open tools", plus calculator / messages / calendar / clock tool panels.
- Already had voice before (unchanged): phone switches (QuickVoice), privacy shield, sidebar on/off, Pragon contacts, always-offline lock, parallel models, firewall, headset mic, screen read/ask, click/tap/scroll.
- Hook: Router.rules (after VisionVoice), CommandExecutor actions psetting / pnumber / ptool.

## Voice: AC remote, voice and language, AI engine, play by name
- AC (IR blaster): "turn on the AC", "turn off the AC", "set the AC to 24 degrees", "AC cool / dry / heat / auto / fan mode", "AC fan low / medium / high / auto", "AC turbo / swing / light / clean / econo", "AC sleep on", "make the AC warmer / cooler (by 2)", "AC remote type B". Setting anything while the AC is off turns it on. The voice AC keeps its own remembered state (separate from the Tools > AC remote page).
- Voice and language: "set speaking speed to 1.2", "speak faster / slower", "set pitch to 0.9", "deeper / higher voice", "set speech language to Tamil", "reply in Hindi", "talk to me in Telugu", "reply in the language I speak".
- AI engine: "use the Gemini / Ollama (Ghost) / OpenAI (Custom) engine", "which engine are you using". The always-offline lock still overrides it.
- "Play <name>": plays a matching song on the phone when the music player is on and has it, otherwise the first YouTube video ("play X on YouTube" always goes to YouTube).
- Settings status badges ("Offline speech recognition", "Cut-out model") no longer stay on "?": they check themselves, show CHECKING / READY / MISSING / NOT SUPPORTED, and refresh until settled.
