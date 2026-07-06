# Personal Edge AI



Offline Android assistant with on-device Gemma inference (LiteRT-LM), Vosk STT, Android TTS, and a Hermes-inspired memory backend.



## Project layout



- `android/` — Kotlin Compose app (min SDK 31)

- `backend/` — FastAPI memory server + web UI

- `docker-compose.yml` — one-command backend startup

- `docs/INSTALL-APK.md` — step-by-step phone install guide



## Backend (home PC / NAS)



```bash

cd backend

python -m venv .venv

.venv\Scripts\activate   # Windows

pip install -r requirements.txt

uvicorn main:app --host 0.0.0.0 --port 8080

```



**Website:** http://localhost:8080 (home, setup guide, APK download)  

**Admin:** http://localhost:8080/admin



Or with Docker:



```bash

docker compose up -d

```



Set `API_KEY` in `.env` or environment. Optional cloud mirror:



```env

CLOUD_MIRROR_URL=https://your-vps.example.com

CLOUD_MIRROR_API_KEY=your-key

```



## Install on your phone



See **[docs/INSTALL-APK.md](docs/INSTALL-APK.md)** for full instructions.



**Quick path (WiFi):**



1. `.\scripts\publish-apk.ps1` — build & publish APK

2. `docker compose up -d` — start backend

3. On phone (same WiFi): open `http://YOUR_PC_IP:8080` → Download APK

4. App Settings → set backend URL + API key



**Quick path (USB):** Open `android/` in Android Studio → connect phone → Run.



## Android app (after install)



1. Set backend URL in **Settings** (default `http://192.168.1.100:8080`)

2. Add Hugging Face token for Gemma 3 1B (gated model)

3. Download models from **Models** tab

4. Chat offline; memory syncs when backend is reachable



## Models



| Model | Size | Capabilities |

|-------|------|--------------|

| Gemma 3 1B IT | ~620 MB | Text chat |

| Gemma 4 E2B IT | ~2.6 GB | Text + image + audio |



## API



All endpoints require `X-API-Key` header (except `/`, `/health`, `/setup`, `/admin`, `/download/*`).



- `GET /v1/config`

- `POST /v1/memory/prefetch`

- `POST /v1/memory/sync`

- `POST /v1/files/upload`

- `GET /v1/files`

- `POST /v1/sync/pull`

- `POST /v1/sync/push`



## Voice (STT + TTS)

Offline speech uses **Vosk** (STT) and the device **Text-to-Speech** engine (replies). For emulator testing:

```powershell
.\scripts\setup-vosk-emulator.ps1   # one-time: ~40 MB Vosk model
```

In the emulator: **Extended controls (⋯) → Microphone** — enable the virtual mic (uses your PC mic).

In the app: **Chat → mic icon** → tap the orb to talk. Pause or tap again to send; replies are read aloud. Turn **Auto-read replies (TTS)** on/off in Settings.

For more natural TTS on a physical device, install **Google Speech Services** and download the **en-us** high-quality voice in system TTS settings.

Place an English Vosk model in `filesDir/vosk-model` on device, or bundle `model-en-us` in assets for offline speech.

