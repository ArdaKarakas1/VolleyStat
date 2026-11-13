"""A bound on the request bodies the server holds in memory (docs/API_V1.md, section 8).

FastAPI reads a JSON body completely before it validates it, so without a bound one request can
take as much memory as its sender likes. This middleware reads the body first and gives up at the
limit, which covers every route, also one that is added later.
"""
from __future__ import annotations

from typing import Any, Awaitable, Callable, MutableMapping

from fastapi.responses import JSONResponse

MAX_JSON_BODY_BYTES = 64 * 1024

Scope = MutableMapping[str, Any]
Message = MutableMapping[str, Any]
Receive = Callable[[], Awaitable[Message]]
Send = Callable[[Message], Awaitable[None]]
App = Callable[[Scope, Receive, Send], Awaitable[None]]


def _declared_length(scope: Scope) -> int | None:
    """The Content-Length of the request when it is a plain number. Only a shortcut for the
    refusal: the count while reading is what enforces the limit."""
    for name, value in scope["headers"]:
        if name == b"content-length" and value.isdigit() and len(value) <= 18:
            return int(value)
    return None


class BodyLimit:
    """ASGI middleware that answers 413 PAYLOAD_TOO_LARGE for a body above `max_bytes`.

    `exempt(scope)` names the requests that stream their body and bound it themselves.
    """

    def __init__(self, app: App, max_bytes: int = MAX_JSON_BODY_BYTES,
                 exempt: Callable[[Scope], bool] = lambda scope: False):
        self.app = app
        self.max_bytes = max_bytes
        self.exempt = exempt

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http" or self.exempt(scope):
            await self.app(scope, receive, send)
            return
        declared = _declared_length(scope)
        if declared is not None and declared > self.max_bytes:
            await self._refuse(scope, receive, send)
            return
        body = bytearray()
        more = True
        while more:
            message = await receive()
            if message["type"] != "http.request":
                # The client went away in the middle of its body. Nobody is left to answer.
                return
            body += message.get("body", b"")
            if len(body) > self.max_bytes:
                await self._refuse(scope, receive, send)
                return
            more = message.get("more_body", False)

        delivered = False

        async def replay() -> Message:
            nonlocal delivered
            if delivered:
                # Past the body the route waits for the disconnect of the client, as without this middleware.
                return await receive()
            delivered = True
            return {"type": "http.request", "body": bytes(body), "more_body": False}

        await self.app(scope, replay, send)

    async def _refuse(self, scope: Scope, receive: Receive, send: Send) -> None:
        response = JSONResponse(status_code=413, content={"error": {
            "code": "PAYLOAD_TOO_LARGE",
            "message": f"The request body is larger than {self.max_bytes} bytes",
            "details": {"maxBytes": self.max_bytes},
        }})
        await response(scope, receive, send)
