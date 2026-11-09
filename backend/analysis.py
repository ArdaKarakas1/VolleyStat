"""Inspect real video metadata. An analyzer may return timestamped review proposals."""

import json
import math
import subprocess
from dataclasses import dataclass, field


@dataclass
class AnalysisResult:
    mode: str
    proposals: list = field(default_factory=list)


def manual_review(_path, _video):
    return AnalysisResult("manual_only")


def inspect_video(path):
    with path.open("rb") as file:
        signature = file.read(8)
    if signature[4:8] in (b"ftyp", b"moov", b"mdat", b"wide", b"free"):
        demuxer = "mov"
    elif signature.startswith(b"\x1a\x45\xdf\xa3"):
        demuxer = "matroska"
    else:
        raise ValueError("Geçerli bir MP4/MOV veya WebM video bulunamadı.")
    try:
        result = subprocess.run(
            [
                "ffprobe", "-v", "error", "-protocol_whitelist", "file", "-f", demuxer,
                "-show_entries", "format=format_name,duration:stream=codec_type,codec_name,width,height:stream_disposition=attached_pic",
                "-of", "json", str(path),
            ],
            capture_output=True, text=True, timeout=90, check=True,
        )
    except FileNotFoundError as error:
        raise ValueError("FFprobe kurulu değil. Kurduktan sonra analizi yeniden başlatın.") from error
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
        raise ValueError("Video okunamadı. Dosyayı ve biçimini kontrol edin.") from error
    try:
        data = json.loads(result.stdout)
        container = data["format"]
        duration = float(container["duration"])
        stream = next(
            entry for entry in data["streams"]
            if entry["codec_type"] == "video"
            and not entry.get("disposition", {}).get("attached_pic")
            and entry.get("width", 0) > 0 and entry.get("height", 0) > 0
        )
        if not math.isfinite(duration) or not 0.001 <= duration <= 12 * 3600:
            raise ValueError()
        formats = container["format_name"].split(",")
        if "mov" in formats:
            mime = "video/mp4"
        elif "matroska" in formats and stream["codec_name"] in ("vp8", "vp9", "av1"):
            mime = "video/webm"
        else:
            raise ValueError()
        return {
            "duration_ms": round(duration * 1000),
            "width": stream["width"],
            "height": stream["height"],
            "codec": stream["codec_name"],
            "mime": mime,
        }
    except (KeyError, ValueError, TypeError, StopIteration) as error:
        raise ValueError("Geçerli bir MP4/MOV veya WebM video bulunamadı.") from error
