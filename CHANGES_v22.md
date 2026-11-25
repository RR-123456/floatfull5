# v22: second offline model (chat) setup

Adds a "Chat model" block in Settings > Offline (under the command model):
- Pick file: choose a .litertlm file from phone storage; it is copied to models/chat.litertlm (works for gated models downloaded in a browser).
- Download chat model: paste an https link; optional Hugging Face token (used for that download only, not saved).
- Load chat now / Remove, plus an INSTALLED/MISSING pill and size.
- Offline.download() takes an optional token; importChat/removeChat added; status JSON has chatMb.
- The chat model reloads automatically after install. It runs beside FunctionGemma (existing parallel-load logic, RAM-checked).

Recommended file: litert-community/Gemma3-1B-IT, 4-bit .litertlm (ekv1280), NOT the sm8xxx Qualcomm files (Nord CE5 is MediaTek).
Not tested on a device. The model file itself is not bundled (gated licence, ~0.6 GB).
