# v8.3 (code 83): no more "I need an API key" when the phone can answer

## What happened
With the internet on and the Always-offline lock off, Pragon uses the cloud brain (Gemini). With no AI key saved it said "I need a Pragon AI key first" and never reached the on-phone models, so it looked like offline was broken.

## Fix
- Brain: if the engine is Gemini, no key is saved, and the on-phone chat model is installed, the reply now comes from the on-phone chat model (same as offline mode).
- The key message now says how to work with no key: Settings > Offline > turn on **Always-offline lock** and download the chat model.

## Still true
- Free conversation offline needs the **chat model** (Gemma 3 1B, Settings > Offline > Chat model, needs a free Hugging Face token once). Without it Pragon only runs phone commands offline and says so.
- Always-offline lock (Settings > Offline) also keeps speech and voice on the phone even when Wi-Fi is on.

Files: `Brain.kt`, `GeminiClient.kt`, `app/build.gradle.kts` (83 / 8.3). Not compiled here.
