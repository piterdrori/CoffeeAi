# Install Personal Edge AI on Your Phone

Two ways to get the app on your Android device (Android 12+, physical phone recommended).

---

## Method A — USB + Android Studio (easiest first time)

Best if you have a PC and USB cable. No APK file handling.

### 1. Install Android Studio

Download from [developer.android.com/studio](https://developer.android.com/studio).

During setup, install:
- Android SDK 34
- JDK 17 (bundled with Android Studio)

### 2. Enable USB debugging on your phone

1. **Settings → About phone → Software information**
2. Tap **Build number** 7 times → Developer options unlocked
3. **Settings → Developer options → USB debugging** → ON
4. Connect phone to PC with USB cable
5. On phone: tap **Allow** when asked to trust this computer

### 3. Run the app from Android Studio

1. Open the `android/` folder in Android Studio
2. Wait for Gradle sync to finish
3. Select your phone in the device dropdown (top toolbar)
4. Click the green **Run** button (▶)

The app installs and opens on your phone automatically.

---

## Method B — WiFi download from your backend

Best after the first build — install from your phone browser without USB.

### 1. Build and publish the APK (on PC)

From the project root in PowerShell:

```powershell
.\scripts\publish-apk.ps1
```

This builds `app-debug.apk` and copies it to `backend/data/releases/`.

**Requirements:** JDK 17 and Android SDK (install via Android Studio).

### 2. Start the backend

```powershell
docker compose up -d
```

Or manually:

```powershell
cd backend
.venv\Scripts\activate
uvicorn main:app --host 0.0.0.0 --port 8080
```

### 3. Find your PC's IP address

```powershell
ipconfig
```

Look for **IPv4 Address** on your WiFi adapter (e.g. `192.168.1.42`).

### 4. Download on your phone

1. Connect phone to the **same WiFi** as your PC
2. Open browser on phone → go to `http://YOUR_PC_IP:8080`
3. Tap **Download APK**
4. When prompted, allow your browser to **install unknown apps**
5. Open the downloaded APK and install

### 5. Configure the app

1. Open **Personal Edge AI**
2. Go to **Settings** tab
3. Set **Local backend URL:** `http://YOUR_PC_IP:8080`
4. Set **API key:** same as `API_KEY` in `backend/.env` (default: `dev-api-key-change-me`)

---

## First-run checklist

| Step | Where | What to do |
|------|-------|------------|
| API key | App → Settings | Match backend `.env` |
| HF token | App → Models | Required for Gemma 3 1B (accept license on huggingface.co first) |
| Download model | App → Models | Gemma 3 1B for text (~620 MB); Gemma 4 E2B for images (~2.6 GB) |
| Personality | PC → `/admin` | Set system prompt, upload PDFs/notes |
| Sync | App → Settings | Tap "Sync memory now" |

---

## Troubleshooting

### Phone can't reach backend

- Confirm PC and phone are on the same WiFi (not guest network)
- Allow port 8080 through Windows Firewall:

```powershell
netsh advfirewall firewall add rule name="Personal Edge AI" dir=in action=allow protocol=TCP localport=8080
```

- Try opening `http://YOUR_PC_IP:8080/health` in phone browser — should show `{"status":"ok"}`

### "Download APK" button disabled on home page

Run `.\scripts\publish-apk.ps1` on your PC first, then refresh the page.

### Gradle build fails

- Open `android/` in Android Studio and let it download SDK components
- Ensure JDK 17 is selected: **File → Settings → Build → Gradle → Gradle JDK**

### Install blocked on phone

**Settings → Apps → [Your browser] → Install unknown apps** → Allow

---

## Alternative: adb install (USB, command line)

If you built the APK but prefer adb over WiFi download:

```powershell
cd android
.\gradlew.bat assembleDebug
adb install app\build\outputs\apk\debug\app-debug.apk
```

Requires [Android platform tools](https://developer.android.com/tools/releases/platform-tools) and USB debugging enabled.
