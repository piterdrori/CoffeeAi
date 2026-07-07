#!/usr/bin/env python3
"""Download ggml-tiny.en.bin for bundled Whisper STT (fast on-device)."""
from __future__ import annotations

import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / "backend" / "data" / "cache" / "ggml-tiny.en.bin"
DEST = ROOT / "android" / "app" / "src" / "main" / "assets" / "voice" / "stt" / "ggml-tiny.en.bin"
URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en.bin"
MIN_BYTES = 70 * 1024 * 1024


def download(url: str, target: Path) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    print(f"Downloading {url}")
    print(f"  -> {target}")
    with urllib.request.urlopen(url) as response, target.open("wb") as out:
        total = int(response.headers.get("Content-Length", 0))
        done = 0
        while True:
            chunk = response.read(1024 * 1024)
            if not chunk:
                break
            out.write(chunk)
            done += len(chunk)
            if total:
                pct = done * 100 // total
                print(f"\r  {done // (1024 * 1024)} MB / {total // (1024 * 1024)} MB ({pct}%)", end="", flush=True)
    print()


def main() -> int:
    if DEST.exists() and DEST.stat().st_size > MIN_BYTES:
        print(f"Model already present: {DEST} ({DEST.stat().st_size} bytes)")
        return 0

    if not CACHE.exists() or CACHE.stat().st_size <= MIN_BYTES:
        download(URL, CACHE)

    size = CACHE.stat().st_size
    if size <= MIN_BYTES:
        print(f"Download failed or incomplete: {size} bytes", file=sys.stderr)
        return 1

    DEST.parent.mkdir(parents=True, exist_ok=True)
    DEST.write_bytes(CACHE.read_bytes())
    print(f"Installed model: {DEST} ({DEST.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
