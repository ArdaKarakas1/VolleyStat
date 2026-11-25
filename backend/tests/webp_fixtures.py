"""Synthetic WebP files for the logo tests (design v5.1 section 4.2; plan P5.3).

The three files below are test patterns: one solid colour each, encoded once with libwebp 1.6.0 through Pillow
(`Image.new("RGB", (side, side), (91, 46, 160)).save(..., "WEBP", quality=80, method=6)`) and pasted as hex. They
show no real image. The builders wrap a VP8 payload in RIFF, change the frame header, append chunks and bytes, and
build the WebP forms the server refuses (VP8L, VP8X with ALPH, EXIF, XMP, ICC or animation). The server never
decodes pixels, so a builder may change a header or pad a payload without making a picture that a decoder shows.
"""
import base64
import struct

SOLID_16 = bytes.fromhex(
    "52494646340000005745425056503820280000009001009d012a1000100001402625a00274ba00039800fef48887fe7a"
    "67f5b7f1ff999dd718ecc000")
SOLID_128 = bytes.fromhex(
    "52494646620000005745425056503820560000007006009d012a800080003e6d369949a42322a1208800800d89696ee1"
    "7490004f6d88bc4151cf6c45e20a8e7b622f105473db117882a39ed88bc4136000fefe969fffffcb3fff65bfecb78cff"
    "f3b56c7d760000000000")
SOLID_256 = bytes.fromhex(
    "52494646bc0000005745425056503820b0000000f010009d012a000100013e6d369949a42322a1202800800d89696ee1"
    "76b11b4009ec03df6c9c87bed9390f7db2721efb64e43df6c9c87bed9390f7db2721efb64e43df6c9c87bed9390f7db2"
    "721efb64e43df6c9c87bed9390f7db2721efb64e43df6c9c87bed9390f7db2721efb64e43df6c9c87bed9390f7db2721"
    "efb64e43df6c9c87bed9390f7db2721efb64e14000feff73a7fffff967ffecb7fd96f19fff3b56c7d760000000000000"
    "00000000")

# The logo the app sends: 256 px for members and 128 px for link views (design 4.2).
LARGE, SMALL = SOLID_256, SOLID_128
KIB = 1024


def b64(data: bytes) -> str:
    return base64.b64encode(data).decode("ascii")


def logo_body(large: bytes = LARGE, small: bytes = SMALL) -> dict[str, str]:
    """The body of PUT /v1/teams/{id}/logo."""
    return {"large": b64(large), "small": b64(small)}


# --- RIFF ----------------------------------------------------------------------------------------------------

def chunk(fourcc: bytes, payload: bytes, pad: bytes = b"\x00") -> bytes:
    """One RIFF chunk: its four-character code, its size and its payload, with the pad byte after an odd payload."""
    assert len(fourcc) == 4
    return fourcc + struct.pack("<I", len(payload)) + payload + (pad if len(payload) % 2 else b"")


def riff(*chunks: bytes, size_change: int = 0) -> bytes:
    """A RIFF WEBP file of the chunks. `size_change` moves the RIFF size field away from the true size."""
    body = b"WEBP" + b"".join(chunks)
    return b"RIFF" + struct.pack("<I", len(body) + size_change) + body


def vp8_payload(data: bytes) -> bytes:
    """The payload of the VP8 chunk of a simple lossy file (one VP8 chunk right after the RIFF header)."""
    assert data[12:16] == b"VP8 "
    size = struct.unpack_from("<I", data, 16)[0]
    return data[20:20 + size]


def simple(payload: bytes) -> bytes:
    """A simple lossy file of this VP8 payload, with every size field right."""
    return riff(chunk(b"VP8 ", payload))


def padded(data: bytes, extra: bytes) -> bytes:
    """The file with `extra` appended to its VP8 payload, so that the sizes stay right and the frame header stays."""
    return simple(vp8_payload(data) + extra)


def of_length(data: bytes, total: int) -> bytes:
    """The file padded inside its VP8 payload to exactly `total` bytes. A RIFF file has an even length."""
    assert total % 2 == 0 and total >= len(data)
    built = padded(data, b"\x00" * (total - len(data)))
    assert len(built) == total
    return built


def with_frame(data: bytes, *, tag: int | None = None, start: bytes | None = None, width: int | None = None,
               height: int | None = None) -> bytes:
    """The file with fields of its VP8 frame header replaced: the 3-byte frame tag, the start code, and the 16-bit
    width and height fields (14 bits of size and 2 bits of scale)."""
    payload = bytearray(vp8_payload(data))
    if tag is not None:
        payload[0:3] = tag.to_bytes(3, "little")
    if start is not None:
        payload[3:6] = start
    if width is not None:
        payload[6:8] = struct.pack("<H", width)
    if height is not None:
        payload[8:10] = struct.pack("<H", height)
    return simple(bytes(payload))


def frame_tag(data: bytes) -> int:
    return int.from_bytes(vp8_payload(data)[0:3], "little")


def header_of_size(width: int, height: int) -> bytes:
    """A simple lossy file whose frame header names this size (the payload stays the 16 x 16 pattern's)."""
    return with_frame(SOLID_16, width=width, height=height)


# --- the forms the server refuses ----------------------------------------------------------------------------

def vp8l(width: int = 16, height: int = 16) -> bytes:
    """A lossless (VP8L) file: the signature byte, the 14-bit sizes less one, no alpha, version 0, then data."""
    bits = (width - 1) | ((height - 1) << 14)
    return riff(chunk(b"VP8L", b"\x2f" + struct.pack("<I", bits) + b"\x00" * 11))


def vp8x_header(flags: int, width: int = 16, height: int = 16) -> bytes:
    """The VP8X chunk of an extended file: the feature flags (ICC 0x20, alpha 0x10, EXIF 0x08, XMP 0x04, animation
    0x02) and the 24-bit canvas sizes less one."""
    return chunk(b"VP8X", bytes([flags, 0, 0, 0]) + (width - 1).to_bytes(3, "little")
                 + (height - 1).to_bytes(3, "little"))


def vp8x(data: bytes = SOLID_16) -> bytes:
    """An extended file with nothing but the VP8 frame."""
    return riff(vp8x_header(0), chunk(b"VP8 ", vp8_payload(data)))


def vp8x_alph(data: bytes = SOLID_16) -> bytes:
    """An extended file with an alpha plane (ALPH) before the frame, as an encoder writes a picture with alpha."""
    return riff(vp8x_header(0x10), chunk(b"ALPH", b"\x00" + b"\xff" * 16), chunk(b"VP8 ", vp8_payload(data)))


def vp8x_exif(data: bytes = SOLID_16) -> bytes:
    """An extended file with EXIF data after the frame, where a camera writes its make and the place."""
    return riff(vp8x_header(0x08), chunk(b"VP8 ", vp8_payload(data)), chunk(b"EXIF", b"MM\x00\x2a" + b"\x00" * 8))


def vp8x_xmp(data: bytes = SOLID_16) -> bytes:
    return riff(vp8x_header(0x04), chunk(b"VP8 ", vp8_payload(data)), chunk(b"XMP ", b"<x:xmpmeta/>"))


def vp8x_icc(data: bytes = SOLID_16) -> bytes:
    return riff(vp8x_header(0x20), chunk(b"ICCP", b"\x00" * 12), chunk(b"VP8 ", vp8_payload(data)))


def animated(data: bytes = SOLID_16) -> bytes:
    """An animated file: ANIM and one ANMF frame that holds the VP8 chunk."""
    frame = b"\x00" * 6 + (15).to_bytes(3, "little") + (15).to_bytes(3, "little") + b"\x64\x00\x00\x00"
    return riff(vp8x_header(0x02), chunk(b"ANIM", b"\x00" * 6),
                chunk(b"ANMF", frame + chunk(b"VP8 ", vp8_payload(data))))


def first_chunk(fourcc: bytes, data: bytes = SOLID_16) -> bytes:
    """A file whose first chunk is `fourcc`, with the VP8 frame after it."""
    return riff(chunk(fourcc, b"\x00" * 8), chunk(b"VP8 ", vp8_payload(data)))


def then_chunk(fourcc: bytes, data: bytes = SOLID_16) -> bytes:
    """The simple file with a second chunk after its VP8 chunk, inside the RIFF size."""
    return riff(chunk(b"VP8 ", vp8_payload(data)), chunk(fourcc, b"\x00" * 8))


def trailing(data: bytes, extra: bytes = b"\x00\x00") -> bytes:
    """The file with bytes after its VP8 chunk, inside a RIFF size that counts them."""
    return riff(chunk(b"VP8 ", vp8_payload(data)) + extra)


def odd_payload(pad: bytes = b"\x00") -> bytes:
    """A file whose VP8 payload has an odd length, so that a pad byte follows it."""
    payload = vp8_payload(SOLID_16) + b"\x00"
    assert len(payload) % 2 == 1
    return riff(chunk(b"VP8 ", payload, pad=pad))
