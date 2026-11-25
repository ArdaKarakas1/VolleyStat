"""The team logo check: a simple lossy WebP and nothing else (docs/API_V1.md, section 10, Takımlar; design v5.1
section 4.2; ADR-0120).

The app draws the logo on an opaque background and encodes two lossy WebP files: 256 px for members and 128 px for
link views. The server keeps a file only when it is exactly this:

    RIFF <size = length - 8> WEBP
    "VP8 " <chunk size> <payload> [one zero pad byte when the payload has an odd length]   -- and the file ends here

where the payload starts with a VP8 frame header (RFC 6386, section 9.1): a 3-byte frame tag of a shown key frame
of version 0 to 3 whose first partition fits in the chunk, the start code 9d 01 2a, and a 16-bit width and height
with no scale bits. Every other form is refused: lossless VP8L, the extended VP8X with alpha (ALPH), EXIF, XMP, ICC
or animation, any second chunk, any byte after the chunk. The lossless decoder path, where the libwebp bugs of 2023
lived, therefore never reaches a phone through this server, and no camera or location data is stored.

The check reads headers only and never decodes pixels: it reads fixed offsets of the bytes it is given and keeps
nothing else. A refusal raises ValueError with the rule the file broke.
"""
from __future__ import annotations

import struct

KIB = 1024
# (largest side in pixels, largest file in bytes) of each image of a logo (design 4.2; plan U13).
SIZES: dict[str, tuple[int, int]] = {"large": (256, 36 * KIB), "small": (128, 8 * KIB)}

_RIFF_HEADER = 12  # "RIFF", the RIFF size, "WEBP"
_CHUNK_HEADER = 8  # the four-character code and the chunk size
_FRAME_HEADER = 10  # the frame tag (3), the start code (3), the width (2) and the height (2) of a key frame
_START_CODE = b"\x9d\x01\x2a"
_HIGHEST_VERSION = 3
_SIZE_BITS = 0x3FFF  # the low 14 bits of a width or height field; the top 2 bits are its scale


def check_lossy_webp(data: bytes, max_side: int, max_bytes: int) -> tuple[int, int]:
    """The width and height of `data` when it is a simple lossy WebP within the limits; ValueError otherwise."""
    if len(data) > max_bytes:
        raise ValueError(f"The file is larger than {max_bytes} bytes")
    if len(data) < _RIFF_HEADER + _CHUNK_HEADER + _FRAME_HEADER:
        raise ValueError("The file is too short for a RIFF header, a VP8 chunk and a frame header")
    if data[0:4] != b"RIFF":
        raise ValueError("The file is not a RIFF file")
    riff_size = struct.unpack_from("<I", data, 4)[0]
    if riff_size != len(data) - 8:
        raise ValueError("The RIFF size is not the length of the file less 8")
    if data[8:12] != b"WEBP":
        raise ValueError("The RIFF file is not a WebP file")
    fourcc = data[12:16]
    if fourcc != b"VP8 ":
        raise ValueError(f"The first chunk is {fourcc!r}, not the 'VP8 ' chunk of a simple lossy file")
    chunk_size = struct.unpack_from("<I", data, 16)[0]
    padding = chunk_size % 2
    if _RIFF_HEADER + _CHUNK_HEADER + chunk_size + padding != len(data):
        raise ValueError("The VP8 chunk does not end the file")
    if padding and data[-1] != 0:
        raise ValueError("The pad byte after the VP8 chunk is not zero")
    if chunk_size < _FRAME_HEADER:
        raise ValueError("The VP8 chunk is shorter than a frame header")

    frame = data[_RIFF_HEADER + _CHUNK_HEADER:]
    tag = frame[0] | (frame[1] << 8) | (frame[2] << 16)
    if tag & 1:
        raise ValueError("The frame is not a key frame")
    if (tag >> 1) & 7 > _HIGHEST_VERSION:
        raise ValueError(f"The frame version is above {_HIGHEST_VERSION}")
    if not (tag >> 4) & 1:
        raise ValueError("The frame is not shown")
    partition = tag >> 5
    if not 1 <= partition <= chunk_size - _FRAME_HEADER:
        raise ValueError("The first partition is empty or does not fit in the VP8 chunk")
    if frame[3:6] != _START_CODE:
        raise ValueError("The key frame has no start code")
    width_field, height_field = struct.unpack_from("<HH", frame, 6)
    if width_field & ~_SIZE_BITS or height_field & ~_SIZE_BITS:
        raise ValueError("The frame asks for a scale")
    width, height = width_field & _SIZE_BITS, height_field & _SIZE_BITS
    if not 1 <= width <= max_side:
        raise ValueError(f"The width is not 1 to {max_side} pixels")
    if not 1 <= height <= max_side:
        raise ValueError(f"The height is not 1 to {max_side} pixels")
    return width, height
