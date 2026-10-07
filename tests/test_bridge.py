from __future__ import annotations

import base64
import asyncio
import hashlib
import json
import logging
import secrets
import tempfile
import time
import unittest
from pathlib import Path
from urllib.parse import parse_qs, urlparse
import httpx

from bridge.auth import PhoneAuthorization, SCOPE
from bridge.native import NativeClient, NativeError, save_key, load_key
from bridge.server import create_server
from bridge.boundary import RequestBoundary

URL = "https://pilot.trycloudflare.com"
CALLBACK = "https://chatgpt.com/connector_platform/oauth/redirect"
KEY = "A" * 43


class FakeNative:
    """Transport fixture, NOT a claim that Android has been tested."""
    def __init__(self):
        self.enabled = True
        self.session = "local-session-one"
        self.pending = ""
        self.approved = False
        self.requests = []
        self.snapshot = "S" * 32
        self.used = False
        self.count = 0
        self.text = ""
        self.row = 1

    async def transport(self, request):
        self.requests.append(request)
        assert request.url.host == "127.0.0.1"
        if request.headers.get("authorization") != "Bearer " + KEY:
            return httpx.Response(401, json={})
        body = json.loads(request.content)
        path = request.url.path
        if path == "/status":
            return httpx.Response(200, json={"enabled": self.enabled, "session": self.session})
        if not self.enabled:
            return httpx.Response(200, json={"error": "disabled"})
        if path == "/approval":
            self.pending = body["request_id"]
            return httpx.Response(200, json={"pending": True})
        if path == "/approval-status":
            yes = self.approved and body["request_id"] == self.pending
            if yes:
                self.approved = False
            return httpx.Response(200, json={"approved": yes, "session": self.session})
        if path == "/command":
            if body.get("session") != self.session:
                return httpx.Response(200, json={"error": "wrong-session"})
            command = body["command"]
            if command == "get_screen":
                self.snapshot = secrets.token_urlsafe(24)
                self.used = False
                return httpx.Response(200, json={"snapshot": self.snapshot, "nodes": [
                    {"id": "n0", "text": "Increment harmless counter", "clickable": True},
                    {"id": "n1", "text": self.text, "editable": True},
                    {"id": "n2", "text": "Harmless row " + str(self.row), "scrollable": True},
                    {"id": "n3", "text": "Tap count: " + str(self.count)},
                ]})
            if command == "launch_app":
                return httpx.Response(200, json={"completed": True, "observe_again": True})
            if self.used or body.get("snapshot") != self.snapshot:
                return httpx.Response(200, json={"error": "stale-screen; observe again"})
            self.used = True
            if command == "tap": self.count += 1
            if command == "type_text": self.text = body["text"]
            if command == "swipe": self.row += 5
            return httpx.Response(200, json={"completed": True, "observe_again": True})
        return httpx.Response(404, json={})


class BridgeTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.device = FakeNative()
        self.native = NativeClient(KEY, httpx.MockTransport(self.device.transport))
        self.server, self.auth = create_server(self.native, URL, {CALLBACK})
        self.app = self.server.streamable_http_app()
        self.bounded = RequestBoundary(self.app, URL)
        logging.getLogger().setLevel(logging.WARNING)
        self.ready = asyncio.Event()
        self.stop_lifespan = asyncio.Event()
        async def run_lifespan():
            async with self.app.router.lifespan_context(self.app):
                self.ready.set()
                await self.stop_lifespan.wait()
        self.lifespan_task = asyncio.create_task(run_lifespan())
        await self.ready.wait()
        self.web = httpx.AsyncClient(transport=httpx.ASGITransport(app=self.bounded), base_url=URL, follow_redirects=False)

    async def asyncTearDown(self):
        await self.web.aclose()
        self.stop_lifespan.set()
        await self.lifespan_task
        await self.native.close()

    async def register(self, callback=CALLBACK, method="none"):
        response = await self.web.post("/register", json={
            "redirect_uris": [callback], "token_endpoint_auth_method": method,
            "grant_types": ["authorization_code", "refresh_token"],
            "response_types": ["code"], "scope": SCOPE,
        })
        return response

    async def authorize(self):
        r = await self.register()
        self.assertEqual(r.status_code, 201, r.text)
        client = r.json()["client_id"]
        verifier = secrets.token_urlsafe(32)
        challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip("=")
        response = await self.web.get("/authorize", params={
            "client_id": client, "redirect_uri": CALLBACK, "response_type": "code",
            "scope": SCOPE, "state": "opaque-state", "code_challenge": challenge,
            "code_challenge_method": "S256", "resource": URL + "/mcp",
        })
        self.assertEqual(response.status_code, 302, response.text)
        return client, verifier, response.headers["location"]

    async def grant(self):
        client, verifier, location = await self.authorize()
        waiting = await self.web.get(location)
        self.assertEqual(waiting.status_code, 200)
        self.assertIn("physically approve", waiting.text)
        self.assertNotIn("code=", waiting.text)
        self.device.approved = True  # Represents a physical approval fixture.
        response = await self.web.get(location)
        self.assertEqual(response.status_code, 303, response.text)
        query = parse_qs(urlparse(response.headers["location"]).query)
        self.assertEqual(query["state"], ["opaque-state"])
        code = query["code"][0]
        return client, verifier, code

    async def token(self, client, verifier, code):
        return await self.web.post("/token", data={
            "grant_type": "authorization_code", "client_id": client,
            "code": code, "redirect_uri": CALLBACK, "code_verifier": verifier,
            "resource": URL + "/mcp",
        })

    async def rpc(self, token, method, params=None):
        headers = {"Accept": "application/json, text/event-stream", "MCP-Protocol-Version": "2025-06-18"}
        if token is not None:
            headers["Authorization"] = "Bearer " + token
        payload = {"jsonrpc": "2.0", "id": 1, "method": method}
        if params is not None: payload["params"] = params
        return await self.web.post("/mcp", json=payload, headers=headers)

    async def call(self, token, name, args):
        response = await self.rpc(token, "tools/call", {"name": name, "arguments": args})
        self.assertEqual(response.status_code, 200, response.text)
        return response.json()["result"]

    async def access(self):
        client, verifier, code = await self.grant()
        response = await self.token(client, verifier, code)
        self.assertEqual(response.status_code, 200, response.text)
        self.assertNotIn("refresh_token", response.json())
        return response.json()["access_token"]

    async def test_unauthenticated_and_unknown_token_rejected(self):
        for token in [None, "invalid"]:
            response = await self.rpc(token, "tools/list")
            self.assertEqual(response.status_code, 401)
            self.assertIn("resource_metadata", response.headers["www-authenticate"])

    async def test_discovery_and_exact_callback(self):
        response = await self.web.get("/.well-known/oauth-authorization-server")
        self.assertEqual(response.status_code, 200)
        self.assertIn("S256", response.json()["code_challenge_methods_supported"])
        self.assertEqual((await self.register("https://attacker.example/callback")).status_code, 400)

    async def test_approval_is_not_remote(self):
        _, _, location = await self.authorize()
        self.assertEqual((await self.web.get(location)).status_code, 200)
        self.assertEqual(len(self.auth.codes), 0)
        self.assertEqual((await self.web.post("/approve", json={})).status_code, 404)

    async def test_pkce_and_code_replay(self):
        client, verifier, code = await self.grant()
        bad = await self.token(client, "wrong" * 10, code)
        self.assertEqual(bad.status_code, 400)
        self.assertEqual(bad.json()["error"], "invalid_grant")
        good = await self.token(client, verifier, code)
        self.assertEqual(good.status_code, 200, good.text)
        replay = await self.token(client, verifier, code)
        self.assertEqual(replay.status_code, 400)

    async def test_native_kill_revokes_access(self):
        token = await self.access()
        self.device.enabled = False
        self.assertEqual((await self.rpc(token, "tools/list")).status_code, 401)

    async def test_new_session_does_not_reuse_old_grant(self):
        token = await self.access()
        self.device.session = "new-local-session"
        self.assertEqual((await self.rpc(token, "tools/list")).status_code, 401)

    async def test_grant_revoked_before_exchange(self):
        client, verifier, code = await self.grant()
        self.device.enabled = False
        self.assertEqual((await self.token(client, verifier, code)).status_code, 400)

    async def test_tool_surface_and_no_credentials_in_results(self):
        token = await self.access()
        response = await self.rpc(token, "tools/list")
        self.assertEqual(response.status_code, 200, response.text)
        tools = response.json()["result"]["tools"]
        self.assertEqual({t["name"] for t in tools}, {"get_screen", "tap", "type_text", "swipe", "launch_app"})
        self.assertNotIn(KEY, response.text)
        self.assertNotIn(token, response.text)
        for t in tools:
            self.assertFalse(t["annotations"]["openWorldHint"])

    async def test_harmless_adapter_round_trip(self):
        token = await self.access()
        await self.call(token, "launch_app", {})
        def screen(result): return json.loads(result["content"][0]["text"])
        s = screen(await self.call(token, "get_screen", {}))
        tapped = await self.call(token, "tap", {"snapshot": s["snapshot"], "element_id": "n0"})
        self.assertFalse(tapped.get("isError", False))
        s = screen(await self.call(token, "get_screen", {}))
        self.assertEqual(s["nodes"][3]["text"], "Tap count: 1")
        typed = await self.call(token, "type_text", {"snapshot": s["snapshot"], "element_id": "n1", "text": "hello phone"})
        self.assertFalse(typed.get("isError", False))
        s = screen(await self.call(token, "get_screen", {}))
        self.assertEqual(s["nodes"][1]["text"], "hello phone")
        swiped = await self.call(token, "swipe", {"snapshot": s["snapshot"], "x1": 500, "y1": 900, "x2": 500, "y2": 500})
        self.assertFalse(swiped.get("isError", False))
        s = screen(await self.call(token, "get_screen", {}))
        self.assertEqual(s["nodes"][2]["text"], "Harmless row 6")

    async def test_stale_snapshot_rejection_survives_adapter(self):
        token = await self.access()
        s = json.loads((await self.call(token, "get_screen", {}))["content"][0]["text"])
        args = {"snapshot": s["snapshot"], "element_id": "n0"}
        await self.call(token, "tap", args)
        stale = await self.call(token, "tap", args)
        self.assertTrue(stale["isError"])
        self.assertEqual(self.device.count, 1)

    async def test_schema_and_control_character_rejections(self):
        token = await self.access()
        before = sum(r.url.path == "/command" for r in self.device.requests)
        invalid = [
            ("tap", {"snapshot": "S" * 32, "x": -1, "y": 100}),
            ("tap", {"snapshot": "S" * 32, "element_id": "n0", "x": 10, "y": 10}),
            ("type_text", {"snapshot": "S" * 32, "element_id": "n1", "text": "line\nsubmit"}),
            ("launch_app", {"package": "com.android.settings"}),
            ("disable_security", {}),
        ]
        for name, args in invalid:
            with self.subTest(name=name): self.assertTrue((await self.call(token, name, args))["isError"])
        after = sum(r.url.path == "/command" for r in self.device.requests)
        self.assertEqual(before, after)

    async def test_client_secret_post_compatibility(self):
        r = await self.register(method="client_secret_post")
        self.assertEqual(r.status_code, 201, r.text)
        self.assertTrue(r.json()["client_secret"])

    async def test_expired_code(self):
        client, verifier, code = await self.grant()
        self.auth.codes[code].expires_at = time.time() - 1
        self.assertEqual((await self.token(client, verifier, code)).status_code, 400)

    async def test_html_no_referrer_and_no_inline_script(self):
        _, _, location = await self.authorize()
        r = await self.web.get(location)
        self.assertEqual(r.headers["referrer-policy"], "no-referrer")
        self.assertEqual(r.headers["cache-control"], "no-store")
        self.assertIn("frame-ancestors 'none'", r.headers["content-security-policy"])
        self.assertNotIn("<script", r.text)

    async def test_public_request_limits_and_origin(self):
        too_big = await self.web.post("/register", content=b" " * 16385)
        self.assertEqual(too_big.status_code, 413)
        wrong_origin = await self.web.get("/.well-known/oauth-authorization-server", headers={"Origin": "https://evil.example"})
        self.assertEqual(wrong_origin.status_code, 403)
        wrong_host = await self.web.get("/.well-known/oauth-authorization-server", headers={"Host": "evil.example"})
        self.assertEqual(wrong_host.status_code, 421)

    async def test_mcp_initialization(self):
        token = await self.access()
        r = await self.rpc(token, "initialize", {
            "protocolVersion": "2025-06-18", "capabilities": {},
            "clientInfo": {"name": "offline-test", "version": "1"},
        })
        self.assertEqual(r.status_code, 200, r.text)
        self.assertEqual(r.json()["result"]["serverInfo"]["name"], "PhoneBridge Pilot")


class FileAndTransportTests(unittest.IsolatedAsyncioTestCase):
    async def test_pairing_permissions_and_no_overwrite(self):
        with tempfile.TemporaryDirectory() as directory:
            p = Path(directory) / "pairing.key"
            save_key(p, KEY)
            self.assertEqual(load_key(p), KEY)
            with self.assertRaises(FileExistsError): save_key(p, KEY)
            p.chmod(0o644)
            with self.assertRaises(ValueError): load_key(p)

    async def test_no_retry_on_indeterminate_action(self):
        calls = []
        def failure(request):
            calls.append(request)
            raise httpx.ReadTimeout("private-content-must-not-escape", request=request)
        native = NativeClient(KEY, httpx.MockTransport(failure))
        with self.assertRaisesRegex(NativeError, "Indeterminate") as error:
            await native.call("/command", {"command": "tap"})
        self.assertNotIn("private-content", str(error.exception))
        self.assertEqual(len(calls), 1)
        await native.close()

    async def test_no_arbitrary_forwarding(self):
        native = NativeClient(KEY, httpx.MockTransport(lambda r: httpx.Response(200, json={})))
        with self.assertRaises(NativeError): await native.call("https://attacker.example", {})
        await native.close()

    async def test_public_origin_and_redirect_validation(self):
        native = NativeClient(KEY, httpx.MockTransport(lambda r: httpx.Response(200, json={})))
        for url in ["http://pilot.example", "https://a:b@pilot.example", "https://pilot.example/mcp", "https://pilot.example:443"]:
            with self.subTest(url=url):
                with self.assertRaises(ValueError): PhoneAuthorization(native, url, {CALLBACK})
        with self.assertRaises(ValueError): PhoneAuthorization(native, URL, {"https://evil.example/callback"})
        await native.close()


if __name__ == "__main__":
    unittest.main()
