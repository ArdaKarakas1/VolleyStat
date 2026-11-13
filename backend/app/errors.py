"""The one error shape every route uses: {"error": {"code", "message", "details"}}."""
from __future__ import annotations

from typing import Any

from fastapi import HTTPException


def fail(code: str, message: str, status: int, **details: Any) -> None:
    raise HTTPException(status_code=status, detail={"code": code, "message": message, "details": details})
