"""Bound all public requests, including OAuth discovery/registration endpoints."""
from urllib.parse import urlparse
from starlette.responses import PlainTextResponse


class RequestBoundary:
    def __init__(self, app, origin: str):
        self.app = app
        self.host = urlparse(origin).hostname
        self.origin = origin.rstrip("/")

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        headers = {}
        for key, value in scope["headers"]:
            key = key.lower()
            if key in headers and key in {b"host", b"content-length", b"authorization", b"origin"}:
                return await PlainTextResponse("Duplicate header", status_code=400)(scope, receive, send)
            headers[key] = value
        if headers.get(b"host", b"").decode("latin-1") != self.host:
            return await PlainTextResponse("Invalid host", status_code=421)(scope, receive, send)
        origin = headers.get(b"origin")
        if origin is not None and origin.decode("latin-1") != self.origin:
            return await PlainTextResponse("Invalid origin", status_code=403)(scope, receive, send)
        chunks = []
        total = 0
        while True:
            message = await receive()
            if message["type"] == "http.disconnect":
                return
            total += len(message.get("body", b""))
            if total > 16384:
                return await PlainTextResponse("Request too large", status_code=413)(scope, receive, send)
            chunks.append(message)
            if not message.get("more_body", False):
                break
        async def bounded_receive():
            if chunks:
                return chunks.pop(0)
            return await receive()
        await self.app(scope, bounded_receive, send)
