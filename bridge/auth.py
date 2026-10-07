from __future__ import annotations

import html
import secrets
import time
from urllib.parse import urlencode, urlparse
from typing import Any
from mcp.server.auth.provider import (
    AccessToken, AuthorizationCode, AuthorizationParams,
    RefreshToken, RegistrationError, TokenError, AuthorizeError,
)
from mcp.shared.auth import OAuthClientInformationFull, OAuthToken
from .native import NativeClient, NativeError

SCOPE = "phone.pilot"


class PhoneAuthorization:
    """Short-lived, memory-only OAuth grants tied to an enabled native session.

    The installed MCP SDK implements OAuth endpoints, client authentication and
    S256 PKCE checking. Physical approval on the phone is the only grant path.
    There is no refresh token and no remote policy/activation tool.
    """

    def __init__(self, native: NativeClient, public_url: str, redirect_uris: set[str]):
        u = urlparse(public_url)
        if u.scheme != "https" or not u.hostname or u.path not in {"", "/"} or u.query or u.fragment or u.username or u.password or u.port:
            raise ValueError("Public URL must be an HTTPS origin without credentials/path/port")
        if not redirect_uris:
            raise ValueError("Configure exact ChatGPT OAuth redirect URIs")
        for uri in redirect_uris:
            p = urlparse(uri)
            if p.scheme != "https" or p.hostname != "chatgpt.com" or p.username or p.password or p.port or p.fragment:
                raise ValueError("Redirect must be an exact HTTPS chatgpt.com URI")
        self.native = native
        self.url = public_url.rstrip("/")
        self.resource = self.url + "/mcp"
        self.redirect_uris = redirect_uris
        self.clients: dict[str, OAuthClientInformationFull] = {}
        self.pending: dict[str, dict[str, Any]] = {}
        self.codes: dict[str, AuthorizationCode] = {}
        self.sessions: dict[str, str] = {}
        self.tokens: dict[str, AccessToken] = {}

    def prune(self) -> None:
        now = time.time()
        self.pending = {k: v for k, v in self.pending.items() if v["expires"] > now}
        self.codes = {k: v for k, v in self.codes.items() if v.expires_at > now}
        self.tokens = {k: v for k, v in self.tokens.items() if (v.expires_at or 0) > now}
        self.sessions = {k: v for k, v in self.sessions.items() if k in self.codes}

    async def get_client(self, client_id: str) -> OAuthClientInformationFull | None:
        return self.clients.get(client_id)

    async def register_client(self, client_info: OAuthClientInformationFull) -> None:
        if not client_info.redirect_uris or any(str(u) not in self.redirect_uris for u in client_info.redirect_uris):
            raise RegistrationError("invalid_redirect_uri", "Only configured ChatGPT callbacks are permitted")
        if client_info.token_endpoint_auth_method not in {"none", "client_secret_post", "client_secret_basic"}:
            raise RegistrationError("invalid_client_metadata", "Unsupported client authentication method")
        # SDK 1.29 requires these two advertised grant types during DCR. Refresh
        # tokens are never issued by this pilot, so re-approval remains mandatory.
        if set(client_info.grant_types) != {"authorization_code", "refresh_token"} or client_info.response_types != ["code"]:
            raise RegistrationError("invalid_client_metadata", "Unexpected grant types or response types")
        if set((client_info.scope or SCOPE).split()) != {SCOPE}:
            raise RegistrationError("invalid_client_metadata", "Unknown scope")
        if len(self.clients) >= 32:
            raise RegistrationError("invalid_client_metadata", "Restart bridge to clear registration capacity")
        if not client_info.client_id:
            raise RegistrationError("invalid_client_metadata", "Missing client ID")
        self.clients[client_info.client_id] = client_info

    async def authorize(self, client: OAuthClientInformationFull, params: AuthorizationParams) -> str:
        self.prune()
        if str(params.redirect_uri) not in self.redirect_uris or params.resource != self.resource:
            raise AuthorizeError("invalid_request", "Unexpected callback or resource")
        if set(params.scopes or []) != {SCOPE} or len(params.code_challenge) != 43:
            raise AuthorizeError("invalid_request", "Scope and S256 PKCE are required")
        if self.pending:
            raise AuthorizeError("temporarily_unavailable", "A connection is already awaiting local approval")
        status = await self.native.call("/status", {})
        if not status.get("enabled") or not status.get("session"):
            raise AuthorizeError("access_denied", "Enable the pilot locally first")
        request_id = secrets.token_hex(24)
        await self.native.call("/approval", {"request_id": request_id})
        self.pending[request_id] = {
            "params": params, "client_id": client.client_id,
            "session": status["session"], "expires": time.time() + 120,
        }
        return self.url + "/complete?request_id=" + request_id

    async def complete(self, request_id: str) -> tuple[str | None, str]:
        self.prune()
        p = self.pending.get(request_id)
        if p is None:
            return None, "This request expired or was already used. Reconnect from ChatGPT."
        response = await self.native.call("/approval-status", {"request_id": request_id})
        if not response.get("approved"):
            code = html.escape(request_id[:6])
            return None, (
                "<p>Compare connection code <strong>" + code + "</strong> with PhoneBridge Pilot. "
                "Switch to that app, read the code, and physically approve ONLY if you initiated this connection. "
                "Then return here and tap Check approval. Do not enter passwords or pairing keys here.</p>"
                '<form method="get"><input type="hidden" name="request_id" value="'
                + html.escape(request_id, quote=True) + '"><button>Check approval</button></form>'
            )
        del self.pending[request_id]
        if response.get("session") != p["session"]:
            raise NativeError("Local session changed; reconnect")
        params: AuthorizationParams = p["params"]
        code = secrets.token_urlsafe(32)
        self.codes[code] = AuthorizationCode(
            code=code, client_id=p["client_id"], scopes=[SCOPE],
            expires_at=time.time() + 60, code_challenge=params.code_challenge,
            redirect_uri=params.redirect_uri,
            redirect_uri_provided_explicitly=params.redirect_uri_provided_explicitly,
            resource=self.resource,
        )
        self.sessions[code] = p["session"]
        query = {"code": code}
        if params.state is not None:
            query["state"] = params.state
        uri = str(params.redirect_uri)
        return uri + ("&" if "?" in uri else "?") + urlencode(query), ""

    async def load_authorization_code(self, client: OAuthClientInformationFull, authorization_code: str) -> AuthorizationCode | None:
        self.prune()
        code = self.codes.get(authorization_code)
        return code if code and code.client_id == client.client_id else None

    async def exchange_authorization_code(self, client: OAuthClientInformationFull, authorization_code: AuthorizationCode) -> OAuthToken:
        code = self.codes.pop(authorization_code.code, None)
        session = self.sessions.pop(authorization_code.code, None)
        if not code or code.client_id != client.client_id or code.expires_at <= time.time() or not session:
            raise TokenError("invalid_grant", "Expired or replayed authorization code")
        status = await self.native.call("/status", {})
        if not status.get("enabled") or status.get("session") != session:
            raise TokenError("invalid_grant", "Local session revoked")
        if len(self.tokens) >= 8:
            raise TokenError("invalid_grant", "Grant capacity reached")
        token = secrets.token_urlsafe(32)
        self.tokens[token] = AccessToken(
            token=token, client_id=client.client_id or "", scopes=[SCOPE],
            expires_at=int(time.time()) + 900, resource=self.resource,
            claims={"native_session": session},
        )
        return OAuthToken(access_token=token, expires_in=900, scope=SCOPE)

    async def load_access_token(self, token: str) -> AccessToken | None:
        self.prune()
        access = self.tokens.get(token)
        if access is None or access.resource != self.resource:
            return None
        try:
            status = await self.native.call("/status", {})
        except NativeError:
            return None
        if not status.get("enabled") or status.get("session") != (access.claims or {}).get("native_session"):
            self.tokens.pop(token, None)
            return None
        return access

    async def load_refresh_token(self, client: OAuthClientInformationFull, refresh_token: str) -> None:
        return None

    async def exchange_refresh_token(self, client: OAuthClientInformationFull, refresh_token: RefreshToken, scopes: list[str]) -> OAuthToken:
        raise TokenError("unsupported_grant_type", "Enable and approve a new session on the phone")

    async def revoke_token(self, token: AccessToken | RefreshToken) -> None:
        self.tokens.pop(token.token, None)
