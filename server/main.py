import os
import secrets
import shutil
import tempfile
from pathlib import Path
from typing import Annotated

import jwt
from fastapi import FastAPI, File, Header, HTTPException, UploadFile
from fastapi.responses import FileResponse
from pydantic import BaseModel, HttpUrl
import yt_dlp
from yt_dlp.utils import DownloadError

app = FastAPI(title="AH Downloader API", version="0.4.0")
JWT_SECRET = os.environ.get("JWT_SECRET", secrets.token_urlsafe(32))


class LoginRequest(BaseModel):
    username: str
    password: str


class ResolveRequest(BaseModel):
    url: HttpUrl
    mode: str = "video"
    quality: str = "best"


class PlaylistRequest(BaseModel):
    url: HttpUrl


class BatchRequest(BaseModel):
    urls: list[HttpUrl]
    max_items: int = 500


def require_user(authorization: str | None) -> str:
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(401, "Authentication required")
    try:
        payload = jwt.decode(authorization[7:], JWT_SECRET, algorithms=["HS256"])
        return str(payload["sub"])
    except jwt.PyJWTError as exc:
        raise HTTPException(401, "Invalid token") from exc


def safe_extract(url: str, *, flat: bool = False):
    # No browser cookies, credential injection, DRM handling, or age-gate bypass.
    opts = {
        "quiet": True, "no_warnings": True, "noplaylist": flat, "extract_flat": flat,
        "age_limit": 17, "restrictfilenames": True, "skip_download": True,
    }
    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=False)
    except DownloadError as exc:
        message = str(exc).strip().splitlines()[-1] if str(exc).strip() else "YouTube extraction failed"
        if "JavaScript runtime" in message or "js runtime" in message.lower():
            message += " Install Deno 2.3+ and make sure deno.exe is on PATH."
        raise HTTPException(502, message) from exc
    except Exception as exc:
        raise HTTPException(502, f"Media extraction failed: {exc}") from exc
    age_limit = info.get("age_limit")
    if age_limit and age_limit >= 18:
        raise HTTPException(403, "Age-restricted media is not supported")
    return info


def height_selector(quality: str) -> str:
    requested = "".join(ch for ch in quality if ch.isdigit())
    return f"[height<={requested}]" if requested else ""


def cleanup_dir(path: str):
    shutil.rmtree(path, ignore_errors=True)


def playlist_items(info: dict) -> list[dict]:
    entries = info.get("entries") or []
    items = []
    for e in entries:
        url = e.get("webpage_url") or e.get("url")
        if not url:
            continue
        items.append({
            "id": e.get("id"),
            "title": e.get("title") or "Untitled",
            "url": url,
            "duration": e.get("duration"),
            "thumbnail": e.get("thumbnail"),
            "channel": e.get("channel") or e.get("uploader"),
            "index": e.get("playlist_index"),
        })
    return items


@app.get("/")
def root():
    return {"service": "ah-downloader-api", "status": "ok", "version": "0.4.0", "docs": "/docs", "health": "/health"}


@app.get("/health")
def health():
    deno = shutil.which("deno")
    ffmpeg = shutil.which("ffmpeg")
    return {"ok": True, "service": "ah-downloader-api", "youtube_js_runtime": "deno" if deno else None,
            "ffmpeg": "available" if ffmpeg else None, "youtube_ready": bool(deno)}


@app.post("/auth/login")
def login(req: LoginRequest):
    user = os.environ.get("YTD_DEMO_USER", "demo")
    password = os.environ.get("YTD_DEMO_PASSWORD", "change-me")
    if not secrets.compare_digest(req.username, user) or not secrets.compare_digest(req.password, password):
        raise HTTPException(401, "Invalid credentials")
    token = jwt.encode({"sub": user}, JWT_SECRET, algorithm="HS256")
    return {"access_token": token, "token_type": "bearer"}


@app.get("/search")
def search(q: str, limit: int = 20):
    q = q.strip()
    if not q:
        raise HTTPException(400, "Search query is required")
    limit = max(1, min(limit, 50))
    info = safe_extract(f"ytsearch{limit}:{q}", flat=True)
    entries = info.get("entries") or []
    return {"items": [{
        "id": e.get("id"),
        "title": e.get("title") or "Untitled",
        "url": e.get("webpage_url") or e.get("url"),
        "duration": e.get("duration"),
        "thumbnail": e.get("thumbnail"),
        "channel": e.get("channel") or e.get("uploader"),
        "view_count": e.get("view_count"),
        "upload_date": e.get("upload_date"),
        "live": bool(e.get("is_live")),
    } for e in entries if e.get("webpage_url") or e.get("url")]}


@app.post("/playlist")
def playlist(req: PlaylistRequest):
    info = safe_extract(str(req.url), flat=True)
    if info.get("_type") != "playlist" and not info.get("entries"):
        raise HTTPException(400, "The URL is not a playlist")
    items = playlist_items(info)
    return {"type": "playlist", "title": info.get("title") or "YouTube Playlist", "count": len(items), "items": items}


@app.post("/batch")
def batch(req: BatchRequest):
    max_items = max(1, min(req.max_items, 1000))
    output = []
    seen = set()
    errors = []
    for raw in req.urls:
        if len(output) >= max_items:
            break
        url = str(raw)
        try:
            info = safe_extract(url, flat=True)
            if info.get("entries") or info.get("_type") == "playlist":
                candidates = playlist_items(info)
            else:
                candidates = [{"id": info.get("id"), "title": info.get("title") or "Untitled", "url": info.get("webpage_url") or url,
                               "duration": info.get("duration"), "thumbnail": info.get("thumbnail"),
                               "channel": info.get("channel") or info.get("uploader"), "index": None}]
            for item in candidates:
                item_url = item.get("url")
                if not item_url or item_url in seen or len(output) >= max_items:
                    continue
                seen.add(item_url)
                output.append(item)
        except HTTPException as exc:
            errors.append({"url": url, "error": str(exc.detail)})
    return {"items": output, "count": len(output), "errors": errors}


@app.post("/resolve")
def resolve(req: ResolveRequest):
    info = safe_extract(str(req.url))
    if info.get("_type") == "playlist" or info.get("entries"):
        return {"type": "playlist", "title": info.get("title"), "count": len(info.get("entries") or []),
                "webpage_url": info.get("webpage_url")}
    formats = []
    for f in info.get("formats") or []:
        if not f.get("url") or f.get("protocol") in {"m3u8_native", "m3u8"}:
            continue
        formats.append({"format_id": f.get("format_id"), "ext": f.get("ext"), "height": f.get("height"),
                       "width": f.get("width"), "fps": f.get("fps"), "abr": f.get("abr"),
                       "vcodec": f.get("vcodec"), "acodec": f.get("acodec"),
                       "filesize": f.get("filesize") or f.get("filesize_approx"), "url": f.get("url")})
    return {"type": "media", "title": info.get("title") or "Download", "duration": info.get("duration"), "formats": formats}


@app.get("/download")
def download(url: HttpUrl, mode: str = "video", quality: str = "best"):
    source = str(url)
    is_audio = mode.lower() == "audio"
    height = height_selector(quality)
    ffmpeg = shutil.which("ffmpeg")
    if is_audio:
        format_selector = "bestaudio/best"
        postprocessors = []
    elif ffmpeg:
        format_selector = f"bestvideo{height}+bestaudio/best{height}/best"
        postprocessors = [{"key": "FFmpegMerger"}]
    else:
        format_selector = f"best[ext=mp4]{height}/best{height}/best"
        postprocessors = []

    temp_dir = Path(tempfile.mkdtemp(prefix="ahdl-"))
    output_template = str(temp_dir / "%(title).180B.%(ext)s")
    opts = {"quiet": True, "no_warnings": True, "noplaylist": True, "age_limit": 17,
            "restrictfilenames": True, "format": format_selector, "outtmpl": output_template,
            "postprocessors": postprocessors, "overwrites": True}
    if not is_audio and ffmpeg:
        opts["merge_output_format"] = "mp4"
    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(source, download=True)
            title = info.get("title") or "download"
            final_path = Path(ydl.prepare_filename(info))
            if not final_path.exists() and not is_audio:
                merged = final_path.with_suffix(".mp4")
                if merged.exists():
                    final_path = merged
            if not final_path.exists():
                candidates = [p for p in temp_dir.iterdir() if p.is_file()]
                if not candidates:
                    raise HTTPException(502, "Downloader produced no media file")
                final_path = max(candidates, key=lambda p: p.stat().st_mtime)
    except DownloadError as exc:
        cleanup_dir(str(temp_dir))
        message = str(exc).strip().splitlines()[-1] if str(exc).strip() else "Download failed"
        if "ffmpeg" in message.lower() and not ffmpeg:
            message += " Install FFmpeg on the server for high-quality video merging."
        raise HTTPException(502, message) from exc
    except HTTPException:
        cleanup_dir(str(temp_dir))
        raise
    except Exception as exc:
        cleanup_dir(str(temp_dir))
        raise HTTPException(502, f"Download failed: {exc}") from exc

    suffix = final_path.suffix.lower()
    media_type = "audio/mp4" if suffix == ".m4a" else "audio/webm" if suffix == ".webm" and is_audio else "video/mp4" if suffix == ".mp4" else "application/octet-stream"
    from starlette.background import BackgroundTask
    return FileResponse(final_path, media_type=media_type, filename=f"{title}{suffix}",
                        background=BackgroundTask(cleanup_dir, str(temp_dir)),
                        headers={"X-AH-Download": "complete"})


@app.post("/upload")
async def upload(file: Annotated[UploadFile, File()], authorization: str | None = Header(default=None)):
    user = require_user(authorization)
    upload_root = Path(os.environ.get("UPLOAD_DIR", "./uploads")) / user
    upload_root.mkdir(parents=True, exist_ok=True)
    safe_name = Path(file.filename or "upload.bin").name
    target = upload_root / safe_name
    with target.open("wb") as out:
        while chunk := await file.read(1024 * 1024):
            out.write(chunk)
    return {"ok": True, "filename": safe_name, "size": target.stat().st_size}
