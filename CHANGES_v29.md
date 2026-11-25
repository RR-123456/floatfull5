# v7.9 (code 79): Unload button for each offline model

- Settings > Offline: every model row that has a **Load** button (Speech pack, Piper voice, Command model, Chat model) now has an **Unload** button to its right.
- **Unload** frees the model from memory only. The file stays on the phone, so Load brings it back. (Remove chat model still deletes the file.)
- Piper unloaded: the phone's own voice speaks until Piper is loaded again. Speech pack unloaded: it reloads by itself the next time you use offline speech.
- Rows now put the buttons on a line under the description so three buttons fit on a narrow phone.
- Files: `settings.html`, `SettingsActivity.kt` (unloadOne), `OnDeviceLlm.kt` (unloadOne).
- Not compiled on Android here; layout checked in a browser at phone width.
