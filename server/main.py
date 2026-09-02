import os
import secrets
import subprocess
import tempfile
from pathlib import Path
from typing import Annotated

import jwt
from fastapi import FastAPI, File, Header, HTTPException, UploadFile
from pydantic import BaseModel, HttpUrl
import yt_dlp

app = FastAPI(title="YTD App API", version="0.1.0")
JWT_SECRET = os.environ.get("JWT_SECRET", secrets.token_urlsafe(32))


class LoginRequest(BaseModel):
    username: str
    password: str


class ResolveRequest(BaseModel):
    url: HttpUrl
    mode: str = "best"
    quality: str = "best"


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
    with yt_dlp.YoutubeDL(opts) as ydl:
        info = ydl.extract_info(url, download=False)
        if info.get("age_limit", 0) and info["age_limit"] >= 18:
            raise HTTPException(403, "Age-restricted media is not supported")
        return info


@app.get("/health")
def health():
    return {"ok": True, "service": "ytdapp-api"}


@app.post("/auth/login")
def login(req: LoginRequest):
    # Replace this demo credential store with PostgreSQL/Argon2 in production.
    user = os.environ.get("YTD_DEMO_USER", "demo")
    password = os.environ.get("YTD_DEMO_PASSWORD", "change-me")
    if not secrets.compare_digest(req.username, user) or not secrets.compare_digest(req.password, password):
        raise HTTPException(401, "Invalid credentials")
    token = jwt.encode({"sub": user}, JWT_SECRET, algorithm="HS256")
    return {"access_token": token, "token_type": "bearer"}


@app.get("/search")
def search(q: str, limit: int = 20):
    limit = max(1, min(limit, 50))
    info = safe_extract(f"ytsearch{limit}:{q}", flat=True)
    entries = info.get("entries") or []
    return {
        "items": [
            {"id": e.get("id"), "title": e.get("title"), "url": e.get("webpage_url") or e.get("url"), "duration": e.get("duration"), "thumbnail": e.get("thumbnail")}
            for e in entries
        ]
    }


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
            "format_id": f.get("format_id"),
            "ext": f.get("ext"),
            "height": f.get("height"),
            "width": f.get("width"),
            "fps": f.get("fps"),
            "abr": f.get("abr"),
            "vcodec": f.get("vcodec"),
            "acodec": f.get("acodec"),
            "filesize": f.get("filesize") or f.get("filesize_approx"),
            "url": f.get("url"),
        })
    return {"type": "media", "title": info.get("title"), "duration": info.get("duration"), "formats": formats}


@app.post("/upload")
async def upload(
    file: Annotated[UploadFile, File()],
    authorization: str | None = Header(default=None),
):
    user = require_user(authorization)
    upload_root = Path(os.environ.get("UPLOAD_DIR", "./uploads")) / user
    upload_root.mkdir(parents=True, exist_ok=True)
    safe_name = Path(file.filename or "upload.bin").name
    target = upload_root / safe_name
    with target.open("wb") as out:
        while chunk := await file.read(1024 * 1024):
            out.write(chunk)
    return {"ok": True, "filename": safe_name, "size": target.stat().st_size}
