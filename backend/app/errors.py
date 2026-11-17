"""The one error shape every route uses: {"error": {"code", "message", "details"}}."""
from __future__ import annotations

from typing import Any

from fastapi import HTTPException


def fail(code: str, message: str, status: int, **details: Any) -> None:
    raise HTTPException(status_code=status, detail={"code": code, "message": message, "details": details})


def fail_with_headers(code: str, message: str, status: int, headers: dict[str, str], **details: Any) -> None:
    """fail() with response headers, such as Retry-After or WWW-Authenticate."""
    raise HTTPException(status_code=status, detail={"code": code, "message": message, "details": details}, headers=headers)
