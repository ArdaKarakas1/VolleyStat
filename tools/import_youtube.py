#!/usr/bin/env python3
"""Download one authorized YouTube clip into the ignored data directory."""

import argparse
import hashlib
import json
import shutil
import subprocess
from pathlib import Path
from urllib.parse import urlsplit


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("url")
    parser.add_argument("--rights", required=True, help="License or written permission reference")
    parser.add_argument("--output", type=Path, default=Path("data/imports"))
    args = parser.parse_args()
    url = urlsplit(args.url)
    if url.scheme != "https" or url.netloc.lower() not in ("www.youtube.com", "youtube.com", "youtu.be", "m.youtube.com"):
        parser.error("Use an HTTPS YouTube video URL.")
    if not args.rights.strip():
        parser.error("Provide a license or permission reference.")
    for executable in ("yt-dlp", "ffmpeg", "ffprobe", "deno"):
        if not shutil.which(executable):
            parser.error(f"{executable} is required.")
    folder = args.output.expanduser().resolve()
    folder.mkdir(parents=True, exist_ok=True, mode=0o700)
    result = subprocess.run(
        [
            "yt-dlp", "--ignore-config", "--no-playlist", "--no-overwrites", "--no-progress",
            "--js-runtimes", "deno",
            "-f", "bv[height<=1080][ext=mp4][vcodec^=avc1]+ba[ext=m4a]/b[height<=1080][ext=mp4][vcodec^=avc1]",
            "--merge-output-format", "mp4",
            "--print", "after_move:filepath", "-o", str(folder / "%(id)s.%(ext)s"), "--", args.url,
        ], check=True, stdout=subprocess.PIPE, text=True,
    )
    paths = result.stdout.splitlines()
    if len(paths) != 1:
        parser.error("Expected one downloaded video path.")
    clip = Path(paths[0]).resolve(strict=True)
    if clip.parent != folder or clip.suffix != ".mp4":
        parser.error("Unexpected output path or file type.")
    if not 0 < clip.stat().st_size <= 4 * 1024 ** 3:
        parser.error("Downloaded file is outside the VolleyStat upload limit (4 GiB).")
    digest = hashlib.sha256()
    with clip.open("rb") as file:
        for chunk in iter(lambda: file.read(1024 * 1024), b""):
            digest.update(chunk)
    probe = subprocess.run(
        ["ffprobe", "-v", "error", "-show_format", "-show_streams", "-of", "json", str(clip)],
        check=True, capture_output=True, text=True,
    )
    metadata = {
        "source_url": args.url, "rights": args.rights, "sha256": digest.hexdigest(),
        "bytes": clip.stat().st_size, "probe": json.loads(probe.stdout),
    }
    manifest = clip.with_suffix(".provenance.json")
    if not manifest.exists():
        manifest.write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(clip)
    print(manifest)


if __name__ == "__main__":
    main()
