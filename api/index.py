"""Vercel serverless entrypoint for the FastAPI backend."""
from __future__ import annotations

import os
import sys

# Writable storage on Vercel serverless (/tmp only).
os.environ.setdefault("DATA_DIR", "/tmp/edge-ai-data")
os.environ.setdefault("VERCEL", "1")

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BACKEND = os.path.join(ROOT, "backend")
if BACKEND not in sys.path:
    sys.path.insert(0, BACKEND)

from mangum import Mangum  # noqa: E402
from main import app  # noqa: E402

handler = Mangum(app, lifespan="off")
