# Bundled voice models (Sherpa-ONNX)

Built into the APK for fully offline voice chat:

| Model | Path | Purpose |
|-------|------|---------|
| Zipformer EN 2023-06-26 | `stt/sherpa-onnx-streaming-zipformer-en-2023-06-26/` | High-quality streaming STT (full-precision ONNX) |
| Piper Lessac medium | `tts/vits-piper-en_US-lessac-medium/` | Natural English TTS |

Fetch before building:

```powershell
.\scripts\fetch-bundled-voice-models.ps1
```

These folders are gitignored (large). They are extracted to app storage on first launch.
