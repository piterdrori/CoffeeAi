# Bundled voice models

Built into the APK for fully offline voice chat:

| Model | Path | Purpose |
|-------|------|---------|
| Whisper.cpp base.en | `stt/ggml-base.en.bin` | High-accuracy offline STT (whisper.cpp) |
| Piper Lessac medium | `tts/vits-piper-en_US-lessac-medium/` | Natural English TTS |

Fetch before building:

```powershell
.\scripts\fetch-whisper-stt.ps1
.\scripts\fetch-bundled-voice-models.ps1
```

These assets are gitignored (large). The Whisper model is copied to app storage on first launch.
