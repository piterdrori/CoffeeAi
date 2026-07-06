# Bundled on-device models

Place the Gemma 3 1B IT LiteRT model here before building the APK:

- `gemma3-1b-it-int4.litertlm` (~620 MB)

Fetch it once on your build machine:

```powershell
.\scripts\fetch-bundled-gemma-model.ps1
```

The `.litertlm` file is gitignored (too large for GitHub). It is packaged into the APK and extracted to app storage on first launch.
