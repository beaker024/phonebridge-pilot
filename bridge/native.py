from __future__ import annotations

import asyncio
import json
import os
from pathlib import Path
from typing import Any
import httpx


class NativeError(RuntimeError):
    pass


class NativeClient:
    """Only this fixed loopback address is allowed. Never forward bearer tokens."""

    def __init__(self, key: str, transport: httpx.AsyncBaseTransport | None = None):
        if len(key) != 43 or not all(c.isalnum() or c in "-_" for c in key):
            raise ValueError("Invalid local pairing key")
        self.client = httpx.AsyncClient(
            base_url="http://127.0.0.1:8766",
            headers={"Authorization": "Bearer " + key, "Content-Type": "application/json"},
            timeout=6,
            follow_redirects=False,
            trust_env=False,  # Local IPC; must never traverse the public tunnel/proxy.
            transport=transport,
        )
        self.lock = asyncio.Lock()

    async def call(self, path: str, payload: dict[str, Any]) -> dict[str, Any]:
        if path not in {"/status", "/command", "/approval", "/approval-status"}:
            raise NativeError("Unknown local route")
        # No retries: a timeout can follow a successful action.
        try:
            async with self.lock:
                response = await self.client.post(path, json=payload)
            if response.status_code != 200:
                raise NativeError("Native controller unavailable or authentication rejected")
            if len(response.content) > 3_000_000:
                raise NativeError("Native response exceeds limit")
            value = response.json()
            if not isinstance(value, dict):
                raise NativeError("Invalid native response")
            if "error" in value:
                # Native messages are fixed policy reasons, not text/arguments.
                raise NativeError(str(value["error"]))
            return value
        except (httpx.HTTPError, json.JSONDecodeError) as error:
            raise NativeError("Indeterminate connection failure; obtain a fresh observation") from error

    async def close(self) -> None:
        await self.client.aclose()


def load_key(path: Path) -> str:
    if path.is_symlink():
        raise ValueError("Pairing file must not be a symbolic link")
    if path.stat().st_mode & 0o077:
        raise ValueError("Pairing file must have mode 0600")
    return path.read_text().strip()


def save_key(path: Path, key: str) -> None:
    if len(key) != 43 or not all(c.isalnum() or c in "-_" for c in key):
        raise ValueError("Invalid local pairing key")
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    fd = os.open(path, flags, 0o600)
    with os.fdopen(fd, "w") as file:
        file.write(key + "\n")
