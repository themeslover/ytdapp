import os
import secrets
import shutil
import tempfile
from pathlib import Path
from typing import Annotated
from urllib.parse import quote

import jwt
from fastapi import FastAPI, File, Header, HTTPException, UploadFile
from fastapi.responses import FileResponse
from pydantic import BaseModel, HttpUrl
import yt_dlp
from yt_dlp.utils import DownloadError

app = FastAPI(title="AH Downloader API", version="0.3.0")
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
        "quiet": True,
        "no_warnings": True,
        "noplaylist": flat,
        "extract_flat": flat,
        "age_limit": 17,
        "restrictfilenames": True,
        "skip_download": True,
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


@app.get("/")
def root():
    return {"service": "ah-downloader-api", "status": "ok", "docs": "/docs", "health": "/health"}


@app.get("/health")
def health():
    deno = shutil.which("deno")
    ffmpeg = shutil.which("ffmpeg")
    return {
        "ok": True,
        "service": "ah-downloader-api",
        "youtube_js_runtime": "deno" if deno else None,
        "ffmpeg": "available" if ffmpeg else None,
        "youtube_ready": bool(deno),
    }


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
        "id": e.get("id"), "title": e.get("title") or "Untitled",
        "url": e.get("webpage_url") or e.get("url"), "duration": e.get("duration"),
        "thumbnail": e.get("thumbnail"),
    } for e in entries if e.get("webpage_url") or e.get("url")]}


@app.post("/playlist")
def playlist(req: PlaylistRequest):
    info = safe_extract(str(req.url), flat=True)
    if info.get("_type") != "playlist" and not info.get("entries"):
        raise HTTPException(400, "The URL is not a playlist")
    entries = info.get("entries") or []
    items = []
    for e in entries:
        url = e.get("webpage_url") or e.get("url")
        if not url:
            continue
        items.append({
            "id": e.get("id"), "title": e.get("title") or "Untitled", "url": url,
            "duration": e.get("duration"), "thumbnail": e.get("thumbnail"),
        })
    return {"type": "playlist", "title": info.get("title") or "YouTube Playlist", "count": len(items), "items": items}


@app.post("/resolve")
def resolve(req: ResolveRequest):
    info = safe_extract(str(req.url))
    if info.get("_type") == "playlist" or info.get("entries"):
        return {"type": "playlist", "title": info.get("title"), "count": len(info.get("entries") or []), "webpage_url": info.get("webpage_url")}
    formats = []
    for f in info.get("formats") or []:
        if not f.get("url") or f.get("protocol") in {"m3u8_native", "m3u8"}:
            continue
        formats.append({
            "format_id": f.get("format_id"), "ext": f.get("ext"), "height": f.get("height"),
            "width": f.get("width"), "fps": f.get("fps"), "abr": f.get("abr"),
            "vcodec": f.get("vcodec"), "acodec": f.get("acodec"),
            "filesize": f.get("filesize") or f.get("filesize_approx"), "url": f.get("url"),
        })
    return {"type": "media", "title": info.get("title") or "Download", "duration": info.get("duration"), "formats": formats}


@app.get("/download")
def download(url: HttpUrl, mode: str = "video", quality: str = "best"):
    """Download and, when possible, merge separate video/audio streams server-side."""
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
        # Keep the app functional without ffmpeg; choose a progressive stream.
        format_selector = f"best[ext=mp4]{height}/best{height}/best"
        postprocessors = []

    temp_dir = Path(tempfile.mkdtemp(prefix="ahdl-"))
    output_template = str(temp_dir / "%(title).180B.%(ext)s")
    opts = {
        "quiet": True,
        "no_warnings": True,
        "noplaylist": True,
        "age_limit": 17,
        "restrictfilenames": True,
        "format": format_selector,
        "outtmpl": output_template,
        "postprocessors": postprocessors,
        "merge_output_format": "mp4" if not is_audio else None,
        "overwrites": True,
    }
    opts = {k: v for k, v in opts.items() if v is not None}

    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(source, download=True)
            title = info.get("title") or "download"
            requested = info.get("requested_downloads") or []
            final_path = Path(ydl.prepare_filename(info))
            if requested and not final_path.exists():
                merged = final_path.with_suffix(".mp4")
                if merged.exists():
                    final_path = merged
            if not final_path.exists():
                candidates = [p for p in temp_dir.iterdir() if p.is_file()]
                if not candidates:
                    raise HTTPException(502, "Downloader produced no media file")
                final_path = max(candidates, key=lambda p: p.stat().st_mtime)
    except DownloadError as exc:
        shutil.rmtree(temp_dir, ignore_errors=True)
        message = str(exc).strip().splitlines()[-1] if str(exc).strip() else "Download failed"
        if "ffmpeg" in message.lower() and not ffmpeg:
            message += " Install FFmpeg on the server for high-quality video merging."
        raise HTTPException(502, message) from exc
    except HTTPException:
        shutil.rmtree(temp_dir, ignore_errors=True)
        raise
    except Exception as exc:
        shutil.rmtree(temp_dir, ignore_errors=True)
        raise HTTPException(502, f"Download failed: {exc}") from exc

    suffix = final_path.suffix.lower()
    media_type = "audio/mpeg" if suffix == ".mp3" else "audio/mp4" if suffix in {".m4a", ".mp4"} and is_audio else "video/mp4" if suffix == ".mp4" else "application/octet-stream"

    def cleanup():
        shutil.rmtree(temp_dir, ignore_errors=True)

    return FileResponse(final_path, media_type=media_type, filename=f"{title}{suffix}", background=None, headers={"X-AH-Download": "complete"})


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
