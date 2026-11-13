"""Command line: python -m volleystat_ml <segment|evaluate|validate-labels|make-splits|check-leakage>.

Exit codes: 0 success, 1 the check found problems, 2 the input could not be used.
"""
from __future__ import annotations

import argparse
import dataclasses
import json
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Sequence

import numpy as np

from . import labels, metrics, segmenter, splits

EXIT_OK, EXIT_FOUND, EXIT_UNUSABLE = 0, 1, 2


class InputError(Exception):
    pass


def main(argv: Sequence[str] | None = None) -> int:
    parser = _parser()
    args = parser.parse_args(argv)
    try:
        return args.run(args)
    except (InputError, segmenter.SegmenterError, splits.SplitError, FileNotFoundError, FileExistsError) as error:
        print(f"error: {error}", file=sys.stderr)
        return EXIT_UNUSABLE


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="python -m volleystat_ml", description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)

    segment = commands.add_parser("segment", help="propose rally intervals for one video (motion-energy baseline)")
    segment.add_argument("video", type=Path)
    segment.add_argument("--video-id", help="identifier written to the output (default: the file name without suffix)")
    segment.add_argument("--out", type=Path, help="write the JSON result here instead of standard output")
    segment.add_argument("--quiet", action="store_true", help="do not print progress")
    for field in dataclasses.fields(segmenter.SegmenterParams):
        segment.add_argument(f"--{field.name.replace('_', '-')}", type=type(field.default), default=field.default,
                             help=f"default {field.default}")
    segment.set_defaults(run=_segment)

    evaluate = commands.add_parser("evaluate", help="compare predicted rally intervals with gold intervals")
    evaluate.add_argument("--gold", type=Path, nargs="+", required=True,
                          help="annotation JSON (V3) or CSV with video_id,start_ms,end_ms (03_RALLI_GOLD)")
    evaluate.add_argument("--pred", type=Path, nargs="+", required=True,
                          help="JSON written by 'segment', or CSV with video_id,start_ms,end_ms")
    evaluate.add_argument("--tolerance-ms", type=int, default=metrics.DEFAULT_TOLERANCE_MS)
    evaluate.add_argument("--resamples", type=int, default=2000, help="bootstrap resamples for the intervals")
    evaluate.add_argument("--seed", type=int, default=0, help="seed of the bootstrap generator")
    evaluate.add_argument("--json", action="store_true", help="print JSON instead of text")
    evaluate.set_defaults(run=_evaluate)

    validate = commands.add_parser("validate-labels", help="check an annotation JSON file against schema V3 and the domain rules")
    validate.add_argument("file", type=Path)
    validate.add_argument("--duration-ms", type=int, help="duration of the annotated video; enables the endMs check")
    validate.set_defaults(run=_validate_labels)

    make = commands.add_parser("make-splits", help="assign train/validation/test by match group and write a lock manifest")
    make.add_argument("--inventory", type=Path, required=True, help="video inventory CSV (01_VIDEO_ENVANTERI columns)")
    make.add_argument("--out", type=Path, required=True, help="lock manifest to create; an existing file is never overwritten")
    make.add_argument("--ratios", default="0.70,0.15,0.15", help="train,validation,test shares (default 0.70,0.15,0.15)")
    make.add_argument("--seed", type=int, default=0)
    make.add_argument("--locked-at", help="timestamp written to the manifest (default: now, UTC)")
    make.set_defaults(run=_make_splits)

    check = commands.add_parser("check-leakage", help="look for a match group or a file hash in two splits")
    check.add_argument("file", type=Path, help="lock manifest or video inventory CSV")
    check.set_defaults(run=_check_leakage)
    return parser


def _segment(args: argparse.Namespace) -> int:
    try:
        params = segmenter.SegmenterParams(**{
            field.name: getattr(args, field.name) for field in dataclasses.fields(segmenter.SegmenterParams)
        })
    except ValueError as error:
        raise InputError(str(error)) from error
    result = segmenter.segment_video(args.video, params, progress=None if args.quiet else _progress_printer())
    document = {
        "analyzer": {"id": segmenter.ALGORITHM_ID, "version": segmenter.ALGORITHM_VERSION, "kind": "BASELINE"},
        "mock": False,
        "videoId": args.video_id or args.video.stem,
        "durationMs": result.duration_ms,
        "status": result.status,
        "frames": {"processed": result.frames_processed, "expected": result.frames_expected},
        # Raw scores for tuning the parameters. Not a confidence.
        "energy": {"idle": result.idle_energy, "active": result.active_energy, "contrastRatio": result.contrast_ratio},
        "params": dataclasses.asdict(params),
        "rallies": [
            {"startMs": interval.start_ms, "endMs": interval.end_ms, "proposedWinner": "UNKNOWN",
             "confidence": None, "uncertaintyReasons": ["NO_WINNER_MODEL"]}
            for interval in result.intervals
        ],
    }
    text = json.dumps(document, indent=2) + "\n"
    if args.out:
        args.out.write_text(text, encoding="utf-8")
    else:
        sys.stdout.write(text)
    if not args.quiet:
        print(f"status {result.status}: {len(result.intervals)} interval(s)", file=sys.stderr)
    return EXIT_OK


def _progress_printer():
    last_tenth = -1

    def report(done: int, total: int) -> None:
        nonlocal last_tenth
        tenth = done * 10 // total
        if tenth != last_tenth:
            last_tenth = tenth
            print(f"frames {done}/{total}", file=sys.stderr)

    return report


def _evaluate(args: argparse.Namespace) -> int:
    gold = _load_intervals(args.gold, "gold")
    predicted = _load_intervals(args.pred, "pred")
    shared = sorted(set(gold) & set(predicted))
    if not shared:
        raise InputError("no video id appears in both the gold and the predicted files")
    per_video = {
        video_id: metrics.evaluate_boundaries(gold[video_id], predicted[video_id], args.tolerance_ms)
        for video_id in shared
    }
    total = metrics.pool(per_video.values())
    # A video without gold is not annotated, which is different from "has no rallies",
    # and a video without a prediction file was not analysed. Neither is counted.
    gold_only = sorted(set(gold) - set(predicted))
    predicted_only = sorted(set(predicted) - set(gold))

    intervals = {}
    for name, hits, count in (("precision", total.tp, total.predicted_count), ("recall", total.tp, total.gold_count)):
        if count:
            outcomes = np.array([1.0] * hits + [0.0] * (count - hits))
            intervals[name] = metrics.bootstrap_ci(outcomes, resamples=args.resamples, seed=args.seed)

    if args.json:
        document = total.to_dict()
        document["videos"] = {video_id: evaluation.to_dict() for video_id, evaluation in per_video.items()}
        document["notEvaluated"] = {"goldOnly": gold_only, "predictedOnly": predicted_only}
        document["bootstrap"] = {
            name: {"low": ci.low, "high": ci.high, "confidence": ci.confidence, "resamples": ci.resamples,
                   "degenerate": ci.degenerate, "seed": args.seed, "unit": "rally"}
            for name, ci in intervals.items()
        }
        print(json.dumps(document, indent=2))
        return EXIT_OK

    print(f"Rally boundary evaluation, tolerance +/-{total.tolerance_ms} ms on start and on end")
    print(f"videos evaluated: {len(shared)} ({', '.join(shared)})")
    if gold_only:
        print(f"not evaluated, no prediction file: {', '.join(gold_only)}")
    if predicted_only:
        print(f"not evaluated, no gold file: {', '.join(predicted_only)}")
    print(f"gold rallies: {total.gold_count}   predicted rallies: {total.predicted_count}")
    print(f"TP {total.tp}   FP {total.fp}   FN {total.fn}")
    print(f"precision: {total.tp}/{total.predicted_count} predicted rallies match a gold rally"
          + _ratio(total.precision, total.percentages_reliable))
    print(f"recall:    {total.tp}/{total.gold_count} gold rallies are found"
          + _ratio(total.recall, total.percentages_reliable))
    if total.percentages_reliable:
        print(f"F1:        {_number(total.f1, 3)}")
    else:
        print(f"n = {total.gold_count} gold rallies is below {metrics.MIN_RELIABLE_GOLD}: percentages are not "
              "reliable at this sample size and are not printed. Quote the counts above.")
    for name, ci in intervals.items():
        if ci.degenerate:
            print(f"{name} interval: not informative, every resample gave {ci.low:.3f}")
        else:
            print(f"{name} {ci.confidence:.0%} bootstrap interval: {ci.low:.3f} to {ci.high:.3f}")
    if intervals:
        print(f"  ({args.resamples} resamples of single rallies, seed {args.seed}; rallies of one match are "
              "correlated, so an interval for new matches is wider)")
    print(f"start error over {total.tp} matched pair(s): median {_number(total.median_abs_start_error_ms, 0)} ms, "
          f"p90 {_number(total.p90_abs_start_error_ms, 0)} ms")
    print(f"end error over {total.tp} matched pair(s):   median {_number(total.median_abs_end_error_ms, 0)} ms, "
          f"p90 {_number(total.p90_abs_end_error_ms, 0)} ms")
    print(f"mean interval IoU of matched pairs: {_number(total.mean_iou, 3)}")
    return EXIT_OK


def _number(value: float | None, digits: int) -> str:
    return "not available" if value is None else f"{value:.{digits}f}"


def _ratio(value: float | None, reliable: bool) -> str:
    return f" ({value:.3f})" if reliable and value is not None else ""


def _load_intervals(paths: Sequence[Path], role: str) -> dict[str, list[tuple[int, int]]]:
    loaded: dict[str, list[tuple[int, int]]] = {}
    for path in paths:
        if path.suffix.lower() == ".json":
            video_id, intervals = _intervals_from_json(path, role)
            groups = {video_id: intervals}
        else:
            groups = _intervals_from_csv(path)
        for video_id, intervals in groups.items():
            if video_id in loaded:
                raise InputError(f"{path}: video id '{video_id}' was already read from another {role} file")
            loaded[video_id] = intervals
    return loaded


def _intervals_from_json(path: Path, role: str) -> tuple[str, list[tuple[int, int]]]:
    try:
        document: Any = json.loads(path.read_text(encoding="utf-8-sig"))
    except json.JSONDecodeError as error:
        raise InputError(f"{path}: not valid JSON: {error}") from error
    if role == "gold":
        # Metrics computed against broken gold labels are worthless, so gold must validate first.
        problems = labels.validate_annotation(document)
        if problems:
            raise InputError(f"{path}: the gold annotation is not valid:\n  " + "\n  ".join(problems))
    try:
        intervals = [(int(rally["startMs"]), int(rally["endMs"])) for rally in document["rallies"]]
        video_id = str(document["videoId"])
    except (KeyError, TypeError, ValueError) as error:
        raise InputError(f"{path}: expected an object with videoId and rallies[].startMs/endMs") from error
    return video_id, _checked(intervals, str(path))


def _intervals_from_csv(path: Path) -> dict[str, list[tuple[int, int]]]:
    columns, rows = splits.read_csv(path.read_text(encoding="utf-8-sig"))
    missing = [name for name in ("video_id", "start_ms", "end_ms") if name not in columns]
    if missing:
        raise InputError(f"{path}: missing column(s): {', '.join(missing)}")
    groups: dict[str, list[tuple[int, int]]] = {}
    for number, row in enumerate(rows, start=2):
        try:
            interval = (int(row["start_ms"]), int(row["end_ms"]))
        except ValueError as error:
            raise InputError(f"{path} row {number}: start_ms and end_ms must be whole milliseconds") from error
        if not row["video_id"]:
            raise InputError(f"{path} row {number}: video_id is empty")
        groups.setdefault(row["video_id"], []).append(interval)
    return {video_id: _checked(intervals, f"{path} (video {video_id})") for video_id, intervals in groups.items()}


def _checked(intervals: list[tuple[int, int]], source: str) -> list[tuple[int, int]]:
    for start, end in intervals:
        if not 0 <= start < end:
            raise InputError(f"{source}: interval {start}..{end} does not satisfy 0 <= start < end")
    return intervals


def _validate_labels(args: argparse.Namespace) -> int:
    problems = labels.validate_annotation_file(args.file, args.duration_ms)
    for problem in problems:
        print(problem)
    if problems:
        print(f"{args.file}: {len(problems)} problem(s)", file=sys.stderr)
        return EXIT_FOUND
    note = "" if args.duration_ms is not None else " (video duration not given, endMs not checked against it)"
    print(f"{args.file}: valid{note}")
    return EXIT_OK


def _make_splits(args: argparse.Namespace) -> int:
    try:
        shares = [float(part) for part in args.ratios.split(",")]
        ratios = dict(zip(splits.SPLITS, shares, strict=True))
    except ValueError as error:
        raise InputError("--ratios needs three numbers: train,validation,test") from error
    videos = splits.read_inventory(args.inventory)
    try:
        assignment = splits.assign_splits(videos, ratios=ratios, seed=args.seed)
    except ValueError as error:
        raise InputError(str(error)) from error
    locked_at = args.locked_at or datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    digest = splits.write_lock_manifest(args.out, videos, assignment, locked_at=locked_at)
    for split in splits.SPLITS:
        members = [video for video in videos if assignment[video.video_id] == split]
        groups = {video.match_group_id for video in members}
        print(f"{split}: {len(members)} video(s) in {len(groups)} match group(s)")
    print(f"wrote {args.out}")
    print(f"content_sha256 {digest}")
    return EXIT_OK


def _check_leakage(args: argparse.Namespace) -> int:
    problems = []
    if splits.is_lock_manifest(args.file):
        manifest = splits.read_lock_manifest(args.file)
        videos = list(manifest.videos)
        if manifest.stored_sha256 is None:
            problems.append("the manifest has no content_sha256 line, so it can not be told whether it was edited")
        elif not manifest.intact:
            problems.append(
                f"the manifest was changed after it was locked: recorded content_sha256 {manifest.stored_sha256}, "
                f"current content gives {manifest.computed_sha256}"
            )
    else:
        videos = splits.read_inventory(args.file)
        problems += splits.training_violations(videos)
    problems += splits.find_leakage(videos)
    assigned = sum(1 for video in videos if video.split)
    for problem in problems:
        print(problem)
    print(f"{args.file}: {assigned} of {len(videos)} video(s) have a split; {len(problems)} problem(s)")
    return EXIT_FOUND if problems else EXIT_OK
