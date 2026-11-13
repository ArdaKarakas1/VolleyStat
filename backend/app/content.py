"""Serve stored video bytes with HTTP Range support (docs/API_V1.md, section 6).

Written here instead of using starlette's FileResponse so that a bad range answers with the
error envelope of this API, like every other error.
"""
from __future__ import annotations

import re
from typing import BinaryIO, Iterator

from fastapi import HTTPException
from fastapi.responses import StreamingResponse

BLOCK_SIZE = 256 * 1024
# At most 19 digits: enough for any file size, and int() never sees an absurdly long number.
_RANGE = re.compile(r"\s*(\d{0,19})\s*-\s*(\d{0,19})\s*", re.ASCII)


class RangeNotSatisfiable(Exception):
    pass


def parse_range(header: str | None, size: int) -> tuple[int, int] | None:
    """Return the inclusive (first, last) byte to send, or None when the whole file is the answer."""
    if not header:
        return None
    unit, _, spec = header.partition("=")
    # RFC 9110 lets a server ignore a unit it does not know. Several ranges would need a
    # multipart body, which no player of this API asks for; the whole file is a valid answer.
    if unit.strip().lower() != "bytes" or "," in spec:
        return None
    match = _RANGE.fullmatch(spec)
    if not match or not (match[1] or match[2]):
        raise RangeNotSatisfiable
    if match[1]:
        first = int(match[1])
        last = min(int(match[2]), size - 1) if match[2] else size - 1
    else:
        # "-N" asks for the last N bytes.
        if int(match[2]) == 0:
            raise RangeNotSatisfiable
        first, last = max(0, size - int(match[2])), size - 1
    if first >= size or first > last:
        raise RangeNotSatisfiable
    return first, last


def _blocks(handle: BinaryIO, first: int, length: int) -> Iterator[bytes]:
    with handle:
        handle.seek(first)
        while length > 0:
            block = handle.read(min(BLOCK_SIZE, length))
            if not block:
                return
            length -= len(block)
            yield block


def stream_file(handle: BinaryIO, size: int, media_type: str, range_header: str | None, if_range: str | None) -> StreamingResponse:
    """Build the response for an open file. The caller opens it, so a missing file fails before any header is sent."""
    try:
        # No validator (ETag, Last-Modified) is sent, so an If-Range condition can never hold
        # and the request falls back to the whole file.
        span = None if if_range else parse_range(range_header, size)
    except RangeNotSatisfiable:
        handle.close()
        raise HTTPException(
            status_code=416,
            detail={"code": "RANGE_NOT_SATISFIABLE", "message": "Requested byte range lies outside the video", "details": {"sizeBytes": size}},
            headers={"Content-Range": f"bytes */{size}", "Accept-Ranges": "bytes"},
        )
    first, last = span or (0, size - 1)
    headers = {"Accept-Ranges": "bytes", "Content-Length": str(last - first + 1)}
    if span:
        headers["Content-Range"] = f"bytes {first}-{last}/{size}"
    return StreamingResponse(_blocks(handle, first, last - first + 1), status_code=206 if span else 200,
                             media_type=media_type, headers=headers)
