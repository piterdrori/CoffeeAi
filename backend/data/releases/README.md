# APK releases

After building the Android app, copy the debug APK here:

```
personal-edge-ai.apk
release.json
```

Run from project root:

```powershell
.\scripts\publish-apk.ps1
```

These files are not committed to git. The backend serves them at `/download/apk`.
