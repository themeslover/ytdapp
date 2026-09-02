# AH Downloader

A Kotlin + Jetpack Compose Android downloader with a self-hosted FastAPI resolver. The app provides built-in YouTube search, in-app playback through the YouTube embedded player, single-video downloads, playlist expansion, and a WorkManager download queue.

## Features

- AH Downloader branding and launcher icon
- Built-in YouTube search
- Play search results inside the app with the YouTube embedded player
- Download individual YouTube videos or direct media URLs
- Video/audio mode with best, 1080, 720, 480 and 360 quality choices
- Public playlist expansion and bulk queueing (up to 200 items per request)
- WorkManager foreground downloads with retry/resume support
- Download history and queue status
- Configurable API server address for emulator or physical phone
- JWT-protected upload endpoint for authenticated server-side uploads

## Run the API on Windows

```bat
cd /d D:\ytdapp\server
.venv\Scripts\activate
python -m pip install -r requirements.txt
python -m uvicorn main:app --reload --host 0.0.0.0 --port 8000
```

Check `http://localhost:8000/health`. The response reports whether a supported YouTube JavaScript runtime is available.

### YouTube extraction runtime

Current yt-dlp YouTube extraction requires a supported external JavaScript runtime. The recommended runtime is Deno 2.3+ and `yt-dlp[default]` is already used by this project so the EJS package is installed with the Python environment.

After installing Deno, restart the API server and confirm `/health` reports `youtube_ready: true`.

## Android API address

- Android Emulator: `http://10.0.2.2:8000`
- Physical phone on the same LAN: use the PC's LAN IPv4, for example `http://192.168.1.10:8000`

Windows Firewall must allow inbound TCP port 8000 for a physical phone to reach the server.

## Build the Android app

From the repository root:

```bat
gradlew.bat --no-daemon :app:assembleDebug
```

APK output:

```text
app\build\outputs\apk\debug\app-debug.apk
```

## Download policy

The resolver is intended for media the user is legally entitled to retrieve. It does **not** implement browser-cookie injection, DRM circumvention, age-gate bypass, private-content access, or other access-control evasion. When a source refuses access, the app reports the failure instead of bypassing the control.

YouTube's terms and platform policies apply to use of YouTube and its content. Deployments should be limited to permitted use cases and content for which the user has the necessary rights or authorization.
