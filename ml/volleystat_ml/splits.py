"""Train / validation / test splits that never divide a match.

Rule from the product specification: the split is made at match level, so frames
(or re-encoded copies, or cut pieces) of one match never end up in two splits.
The unit is `match_group_id` from the video inventory (01_VIDEO_ENVANTERI.csv).

- A split that the inventory already records is kept, and so is every assignment of
  a previous lock manifest that is handed in. A frozen test set must not move because
  new videos were added: shares worked out again from nothing can send a locked test
  match to train.
- A video whose `training_allowed` is not exactly "true" never enters the train
  split. This module does not judge `right_state` or any other rights column.
- The lock manifest (11_TEST_MANIFEST.csv columns) ends with the SHA-256 of its
  own header and rows, so a later edit is detected. An edit that also replaces that
  line is only found by comparing with the digest noted outside the file at lock time.
"""
from __future__ import annotations

import csv
import hashlib
import io
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Mapping, Sequence

TRAIN, VALIDATION, TEST = "train", "validation", "test"
SPLITS = (TRAIN, VALIDATION, TEST)
DEFAULT_RATIOS = {TRAIN: 0.70, VALIDATION: 0.15, TEST: 0.15}
ANNOTATION_VERSION = "VVV_ANNOTATION_V3"

# Headers of schemas/01_VIDEO_ENVANTERI.csv and schemas/11_TEST_MANIFEST.csv.
INVENTORY_COLUMNS = (
    "video_id", "match_group_id", "source_type", "source_url_or_path", "right_state",
    "license_evidence_url", "training_allowed", "data_subject_permission_state", "recording_date",
    "arena_id", "camera_setup_id", "orientation", "width", "height", "fps_nominal", "duration_ms",
    "sha256", "quality_band", "split", "notes",
)
MANIFEST_COLUMNS = (
    "video_id", "match_group_id", "arena_id", "camera_setup_id", "sha256", "split",
    "annotation_version", "locked_at", "notes",
)
_REQUIRED_INVENTORY_COLUMNS = ("video_id", "match_group_id", "training_allowed", "sha256")

_SPLIT_ALIASES = {"train": TRAIN, "validation": VALIDATION, "val": VALIDATION, "test": TEST}
_SHA256 = re.compile(r"[0-9a-f]{64}")
_TRAILER = re.compile(r"# content_sha256=([0-9a-f]{64})")


class SplitError(Exception):
    """The input can not be split, or a split breaks a rule. `problems` lists every finding."""

    def __init__(self, problems: Sequence[str]):
        super().__init__("\n".join(problems))
        self.problems = list(problems)


@dataclass(frozen=True)
class Video:
    video_id: str
    match_group_id: str
    sha256: str
    # None means the source does not say (a lock manifest has no such column).
    # Only True opens the train split.
    training_allowed: bool | None = None
    split: str | None = None
    arena_id: str = ""
    camera_setup_id: str = ""


@dataclass(frozen=True)
class LockManifest:
    videos: tuple[Video, ...]
    stored_sha256: str | None
    computed_sha256: str
    # Rows with more cells than the header. The hash covers the manifest columns only,
    # so it does not see a cell that was appended to a row.
    problems: tuple[str, ...] = ()

    @property
    def intact(self) -> bool:
        return self.stored_sha256 == self.computed_sha256


def read_inventory(path: Path) -> list[Video]:
    """Read a video inventory CSV. Raises SplitError listing every unusable row."""
    columns, rows, problems = read_csv(read_text(path))
    missing = [name for name in _REQUIRED_INVENTORY_COLUMNS if name not in columns]
    if missing:
        raise SplitError([f"inventory is missing column(s): {', '.join(missing)}"])
    videos, row_problems = _videos_from_rows(rows, has_training_column=True)
    if problems or row_problems:
        raise SplitError(problems + row_problems)
    return videos


def assign_splits(
    videos: Sequence[Video],
    *,
    ratios: Mapping[str, float] = DEFAULT_RATIOS,
    seed: int = 0,
    previous: Iterable[Video] = (),
) -> dict[str, str]:
    """Return {video_id: split} for every video, whole match groups at a time.

    Groups are visited in an order derived from `seed` and each goes to the split that
    is furthest below its share of the videos. Ties go to test, then validation: with
    very few matches a held-out test match is worth more than a larger train split.

    `previous` holds the videos of an earlier lock manifest. Every video and every match
    group named there keeps its split, and only the rest is assigned. A lock that can not
    be kept (it contradicts itself, the inventory or a training permission) is refused.
    """
    if set(ratios) != set(SPLITS) or min(ratios.values()) < 0 or abs(sum(ratios.values()) - 1) > 1e-6:
        raise ValueError("ratios must give train, validation and test shares that are >= 0 and sum to 1")
    if not videos:
        raise SplitError(["the inventory has no videos"])

    problems = _identity_problems(videos)
    groups: dict[str, list[Video]] = {}
    for video in videos:
        groups.setdefault(video.match_group_id, []).append(video)
    locked_video: dict[str, str] = {}
    locked_group: dict[str, set[str]] = {}
    for video in previous:
        if video.split:
            locked_video[video.video_id] = video.split
            locked_group.setdefault(video.match_group_id, set()).add(video.split)
    fixed: dict[str, str] = {}
    for group, members in groups.items():
        recorded = sorted({video.split for video in members if video.split})
        # A video keeps its locked split even if the inventory now lists it under another
        # match, and a new video of a locked match joins that match.
        locked = locked_group.get(group, set()) | {
            locked_video[video.video_id] for video in members if video.video_id in locked_video
        }
        if len(locked) > 1:
            problems.append(
                f"match_group_id '{group}' can not keep the previous lock, which has its videos or its match in "
                f"more than one split: {', '.join(split for split in SPLITS if split in locked)}"
            )
        elif locked and set(recorded) - locked:
            problems.append(
                f"match_group_id '{group}' is {next(iter(locked))} in the previous lock, but the inventory records "
                f"{', '.join(split for split in recorded if split not in locked)}"
            )
        elif len(recorded) > 1:
            problems.append(f"match_group_id '{group}' is already recorded in more than one split: {', '.join(recorded)}")
        elif locked or recorded:
            fixed[group] = next(iter(locked)) if locked else recorded[0]
            if fixed[group] == TRAIN:
                problems += [_training_problem(video) for video in members if video.training_allowed is not True]
    if problems:
        raise SplitError(problems)

    counts = dict.fromkeys(SPLITS, 0)
    for group, split in fixed.items():
        counts[split] += len(groups[group])

    def restricted(group: str) -> bool:
        return any(video.training_allowed is not True for video in groups[group])

    # A hash of the seed and the group id orders the groups, so the order of one group
    # does not depend on which other groups exist or on the Python version.
    free = sorted(
        (group for group in groups if group not in fixed),
        key=lambda group: hashlib.sha256(f"{seed}\x1f{group}".encode()).hexdigest(),
    )
    # Groups that may not be trained on choose first, while validation and test are still empty.
    for group in [g for g in free if restricted(g)] + [g for g in free if not restricted(g)]:
        candidates = (TEST, VALIDATION) if restricted(group) else (TEST, VALIDATION, TRAIN)
        fixed[group] = max(candidates, key=lambda split: ratios[split] * len(videos) - counts[split])
        counts[fixed[group]] += len(groups[group])
    return {video.video_id: fixed[video.match_group_id] for video in videos}


def find_leakage(videos: Iterable[Video], assignment: Mapping[str, str] | None = None) -> list[str]:
    """Report every match_group_id and every sha256 that appears in two splits.

    The split of a video comes from `assignment` when given, else from the video itself.
    Videos without a split are ignored.
    """
    by_group: dict[str, dict[str, list[str]]] = {}
    by_hash: dict[str, dict[str, list[str]]] = {}
    for video in videos:
        split = assignment.get(video.video_id) if assignment is not None else video.split
        if not split:
            continue
        by_group.setdefault(video.match_group_id, {}).setdefault(split, []).append(video.video_id)
        by_hash.setdefault(video.sha256, {}).setdefault(split, []).append(video.video_id)

    def describe(found: dict[str, list[str]]) -> str:
        return "; ".join(f"{split}: {', '.join(sorted(found[split]))}" for split in SPLITS if split in found)

    problems = [
        f"match_group_id '{group}' is in {len(found)} splits ({describe(found)})"
        for group, found in sorted(by_group.items()) if len(found) > 1
    ]
    problems += [
        f"sha256 {digest} is in {len(found)} splits ({describe(found)})"
        for digest, found in sorted(by_hash.items()) if len(found) > 1
    ]
    return problems


def find_changed_splits(files: Mapping[str, Iterable[Video]]) -> list[str]:
    """Report every video_id and every match_group_id that sits in different splits in
    different files (lock manifests or inventories, keyed by the name to print).

    A match that is test in one lock and train in a later one has leaked into training,
    although each file on its own is free of leakage.
    """
    by_video: dict[str, dict[str, list[str]]] = {}
    by_group: dict[str, dict[str, list[str]]] = {}
    for name, videos in files.items():
        for video in videos:
            if not video.split:
                continue
            for found, key in ((by_video, video.video_id), (by_group, video.match_group_id)):
                names = found.setdefault(key, {}).setdefault(video.split, [])
                if name not in names:
                    names.append(name)

    def describe(found: dict[str, list[str]]) -> str:
        return "; ".join(f"{split}: {', '.join(found[split])}" for split in SPLITS if split in found)

    return [
        f"{label} '{key}' is in different splits across the files ({describe(found)})"
        for label, table in (("video_id", by_video), ("match_group_id", by_group))
        for key, found in sorted(table.items()) if len(found) > 1
    ]


def training_violations(videos: Iterable[Video], assignment: Mapping[str, str] | None = None) -> list[str]:
    """Videos in the train split whose training_allowed is not true."""
    problems = []
    for video in videos:
        split = assignment.get(video.video_id) if assignment is not None else video.split
        if split == TRAIN and video.training_allowed is not True:
            problems.append(_training_problem(video))
    return problems


def write_lock_manifest(
    path: Path,
    videos: Sequence[Video],
    assignment: Mapping[str, str],
    *,
    locked_at: str,
    annotation_version: str = ANNOTATION_VERSION,
) -> str:
    """Write the lock manifest and return its content SHA-256.

    Refuses a split with leakage, a train video without training permission or a video
    without a split. Never overwrites: a changed test set is a new, renamed version.
    """
    problems = [f"video '{video.video_id}' has no split" for video in videos if video.video_id not in assignment]
    problems += find_leakage(videos, assignment) + training_violations(videos, assignment)
    if problems:
        raise SplitError(problems)
    rows = [
        {
            "video_id": video.video_id,
            "match_group_id": video.match_group_id,
            "arena_id": video.arena_id,
            "camera_setup_id": video.camera_setup_id,
            "sha256": video.sha256,
            "split": assignment[video.video_id],
            "annotation_version": annotation_version,
            "locked_at": locked_at,
            "notes": "",
        }
        for video in sorted(videos, key=lambda video: video.video_id)
    ]
    content = _canonical(rows)
    digest = hashlib.sha256(content.encode("utf-8")).hexdigest()
    with open(path, "x", encoding="utf-8", newline="") as handle:
        handle.write(f"{content}# content_sha256={digest}\n")
    return digest


def read_lock_manifest(path: Path) -> LockManifest:
    """Read a lock manifest. Check `.intact` and `.problems` before trusting it.

    The hash is taken over the parsed cells of the manifest columns, so a byte-order mark
    or changed line endings do not count as an edit; any changed or removed value and any
    added or removed row does. A cell appended after the last column lies outside the
    hash and is listed in `.problems` instead.
    """
    lines = read_text(path).splitlines()
    while lines and not lines[-1].strip():
        lines.pop()
    stored = None
    trailer = _TRAILER.fullmatch(lines[-1].strip()) if lines else None
    if trailer:
        stored = trailer.group(1)
        lines.pop()
    columns, rows, shape_problems = read_csv("\n".join(lines))
    if tuple(columns) != MANIFEST_COLUMNS:
        raise SplitError([f"not a lock manifest: the header must be {','.join(MANIFEST_COLUMNS)}"])
    videos, problems = _videos_from_rows(rows, has_training_column=False)
    if problems:
        raise SplitError(problems)
    digest = hashlib.sha256(_canonical(rows).encode("utf-8")).hexdigest()
    return LockManifest(tuple(videos), stored, digest, tuple(shape_problems))


def is_lock_manifest(path: Path) -> bool:
    first_line = read_text(path).partition("\n")[0]
    return tuple(read_csv(first_line)[0]) == MANIFEST_COLUMNS


def read_text(path: Path) -> str:
    """Read a text file as UTF-8, with or without a byte-order mark."""
    try:
        return Path(path).read_text(encoding="utf-8-sig")
    except UnicodeDecodeError as error:
        # Decoded with a guessed encoding, an id or a split name could change silently.
        raise SplitError([
            f"the file is not UTF-8 text (byte {error.start}: {error.reason}); save it with UTF-8 encoding"
        ]) from error


def read_csv(text: str) -> tuple[list[str], list[dict[str, str]], list[str]]:
    """Parse CSV text into (columns, rows, problems). Cells are stripped and empty rows
    are dropped.

    `problems` names every row that has more cells than the header. Such a row was
    shifted by an unquoted delimiter or had a value added, so its cells can not be
    trusted to sit under the right column. Rows are numbered like the lines of the file
    (the header is row 1), not counting empty rows.
    """
    # A spreadsheet with a Turkish locale saves CSV with semicolons.
    header = text.partition("\n")[0]
    delimiter = ";" if header.count(";") > header.count(",") else ","
    reader = csv.DictReader(io.StringIO(text, newline=""), delimiter=delimiter, restval="")
    columns = [name.strip() for name in (reader.fieldnames or [])]
    reader.fieldnames = columns
    rows, problems = [], []
    for row in reader:
        cells = {name: (row.get(name) or "").strip() for name in columns}
        # The reader collects the cells beyond the last column under the key None.
        surplus = row.get(None) or []
        if any(cells.values()) or any(cell.strip() for cell in surplus):
            rows.append(cells)
            if surplus:
                problems.append(
                    f"row {len(rows) + 1}: {len(columns) + len(surplus)} cells, but the header has {len(columns)} columns"
                )
    return columns, rows, problems


def _canonical(rows: Sequence[Mapping[str, str]]) -> str:
    buffer = io.StringIO()
    writer = csv.writer(buffer, lineterminator="\n")
    writer.writerow(MANIFEST_COLUMNS)
    for row in rows:
        writer.writerow([row[name] for name in MANIFEST_COLUMNS])
    return buffer.getvalue()


def _videos_from_rows(rows: Sequence[Mapping[str, str]], *, has_training_column: bool) -> tuple[list[Video], list[str]]:
    videos, problems = [], []
    for number, row in enumerate(rows, start=2):  # row 1 is the header
        label = f"row {number} (video_id '{row['video_id']}')"
        if not row["video_id"]:
            problems.append(f"row {number}: video_id is empty")
        if not row["match_group_id"]:
            problems.append(f"{label}: match_group_id is empty; the video can not be kept with its match")
        digest = row["sha256"].lower()
        if not _SHA256.fullmatch(digest):
            problems.append(f"{label}: sha256 must be 64 hexadecimal characters")
        split = row.get("split", "")
        if split and split.lower() not in _SPLIT_ALIASES:
            problems.append(f"{label}: unknown split '{split}'; use train, validation or test")
        videos.append(Video(
            video_id=row["video_id"],
            match_group_id=row["match_group_id"],
            sha256=digest,
            training_allowed=(row["training_allowed"].lower() == "true") if has_training_column else None,
            split=_SPLIT_ALIASES.get(split.lower()),
            arena_id=row.get("arena_id", ""),
            camera_setup_id=row.get("camera_setup_id", ""),
        ))
    problems += _identity_problems(videos)
    return videos, problems


def _identity_problems(videos: Sequence[Video]) -> list[str]:
    problems = []
    seen: set[str] = set()
    for video in videos:
        if video.video_id and video.video_id in seen:
            problems.append(f"video_id '{video.video_id}' appears more than once")
        seen.add(video.video_id)
    # The same file under two matches means one of the rows is wrong. Splitting anyway
    # could put identical frames into train and test.
    groups_of_hash: dict[str, set[str]] = {}
    for video in videos:
        if _SHA256.fullmatch(video.sha256):
            groups_of_hash.setdefault(video.sha256, set()).add(video.match_group_id)
    for digest, groups in sorted(groups_of_hash.items()):
        if len(groups) > 1:
            problems.append(f"sha256 {digest} is listed under several match groups: {', '.join(sorted(groups))}")
    return problems


def _training_problem(video: Video) -> str:
    return f"video '{video.video_id}' is in the train split but its training_allowed is not true"
