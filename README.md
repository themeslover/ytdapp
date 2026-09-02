# AH Downloader

AH Downloader is a Kotlin + Jetpack Compose Android media downloader designed around a **zero-setup mobile experience**. The app now bundles an on-device yt-dlp/Python/QuickJS/FFmpeg runtime, so normal downloads no longer depend on a FastAPI server running on a PC.

## Current architecture

- **Primary:** on-device yt-dlp engine inside the APK.
- **Fallback:** optional self-hosted FastAPI resolver for sources which need a server-side path.
- **Download queue:** WorkManager foreground jobs with retry/resume behavior.
- **Storage:** Android Downloads / `Download/AH Downloader` on modern Android.

The app therefore works without manually entering a PC IP address for its normal download path.

## Current features

- AH Downloader branding and Material 3 UI
- Built-in YouTube search through the bundled yt-dlp engine
- Direct URL downloads without a PC server
- Video and audio modes
- Best, 1080p, 720p, 480p and 360p video selection
- Audio extraction with FFmpeg
- Public playlist expansion and bulk queueing
- Background WorkManager downloads
- Progress notifications
- Retry/resume support
- Download history and queue state
- Optional server fallback and LAN auto-discovery
- Built-in YouTube embedded playback

## Build the Android app

From the repository root:

```bat
gradlew.bat --no-daemon :app:assembleDebug
```

APK output:

```text
app\build\outputs\apk\debug\app-debug.apk
```

The APK includes native components from `youtubedl-android` and FFmpeg, so it is expected to be larger than the earlier prototype.

## Optional FastAPI server

The server remains available for compatibility and can still be used as a fallback:

```bat
cd /d D:\ytdapp\server
.venv\Scripts\activate
python -m pip install -r requirements.txt
python -m uvicorn main:app --reload --host 0.0.0.0 --port 8000
```

Android Emulator: `http://10.0.2.2:8000`

Physical phone: the app can discover a compatible server on the local network when the default emulator address is used.

## Roadmap toward the premium experience

Next implementation stages are focused on the parts that make AH Downloader feel like a polished all-in-one downloader rather than a basic URL tool:

1. Native offline media library and Media3 player
2. Clipboard and Android Share-sheet URL detection
3. Automatic download format/quality dialog with file-size estimates
4. Multi-download concurrency controls and smart queue scheduling
5. Duplicate detection and download history database
6. Download folders, rename/share/open/delete actions
7. Playlist-aware library organization and metadata
8. Browser-style discovery screen and supported-site handling
9. Theme, Wi-Fi-only, storage and notification preferences
10. Automated diagnostics and update checks for the bundled downloader runtime

## Download policy

The resolver is intended for media the user is legally entitled to retrieve. It does **not** implement DRM circumvention, age-gate bypass, private-content access, cookie theft, or other access-control evasion. When a source refuses access, AH Downloader reports the failure instead of bypassing the control.

## Third-party runtime

The Android build uses `youtubedl-android` 0.18.1, which bundles yt-dlp/Python and provides the Android wrapper used by the local engine. That project is GPL-3.0 licensed. FFmpeg is provided through its corresponding module. Review and preserve the applicable third-party license and source-code obligations when distributing AH Downloader.
