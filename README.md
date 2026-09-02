# YTD App

Modern Android client + API for fast batch media retrieval from public or user-authorized sources.

## Current baseline

- Kotlin + Jetpack Compose UI
- Android Gradle Plugin 9.x / Java 17
- Compose BOM 2026.08.00
- Search UI and URL intake
- Quality presets: best, 1080p, 720p, 480p, 360p, audio
- Queue-first architecture for batch jobs
- FastAPI resolver/search/upload service
- JWT-protected uploads
- GitHub Actions debug APK artifact build

## Architecture

`app/` is the Android client. `server/` is an optional self-hosted resolver/upload service. The client is deliberately separated from extraction so providers can be replaced without rewriting the UI.

## Run the API

```bash
cd server
python -m venv .venv
# activate the venv, then:
pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 8080
```

Set a production `JWT_SECRET`, `YTD_DEMO_USER`, and `YTD_DEMO_PASSWORD`. Replace the demo login with a real identity provider before public deployment.

## Download policy

The resolver is intended for media the user is legally entitled to retrieve. It does **not** implement browser-cookie injection, DRM circumvention, age-gate bypass, private-content access, or other access-control evasion. When a source refuses access, the job should be marked retryable or failed rather than bypassing the control.

YouTube's Terms apply to use of YouTube and its content, so deployments should be limited to permitted use cases and content for which the user has the necessary rights/authorization. See the official terms before production deployment.

## Planned production work

1. Wire the Android client to `/search`, `/resolve`, and a streaming download endpoint.
2. Add Room-backed persistent queue + WorkManager foreground downloads.
3. Add segmented/concurrent downloading with bounded connections, resume, checksum validation, exponential backoff, and per-item error isolation.
4. Add playlist expansion and bulk selection.
5. Add authenticated upload UI and resumable multipart uploads.
6. Add real account management (OIDC/OAuth), encrypted token storage, quotas and rate limits.
7. Add Media3 playback, file browser, share sheet, duplicate detection and storage-aware scheduling.
8. Add signed release builds and Play-compatible policy/configuration review.
