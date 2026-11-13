"""Command line: python -m volleystat_ml <segment|evaluate|agreement|validate-labels|make-splits|check-leakage>.

Exit codes: 0 success, 1 the check found problems, 2 the input could not be used.
Input that can not be used is reported on standard error as "error: ..." without a
traceback: one line for a file, a folder or an option value, and one line per row when
the rows of a file break a rule.
"""
from __future__ import annotations

import argparse
import csv
import dataclasses
import json
import math
import re
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, NoReturn, Sequence

import numpy as np

from . import agreement, labels, metrics, segmenter, splits

EXIT_OK, EXIT_FOUND, EXIT_UNUSABLE = 0, 1, 2


class InputError(Exception):
    pass


class _Parser(argparse.ArgumentParser):
    def error(self, message: str) -> NoReturn:
        # argparse would print the usage text in front. A bad command line is reported like
        # every other unusable input, in one line; --help still prints the usage.
        self.exit(EXIT_UNUSABLE, f"error: {message}\n")


def main(argv: Sequence[str] | None = None) -> int:
    parser = _parser()
    args = parser.parse_args(argv)
    try:
        return args.run(args)
    # OSError covers a missing file, a directory given as a file and a file that exists
    # where a new one was to be written.
    except (InputError, segmenter.SegmenterError, splits.SplitError, OSError) as error:
        print(f"error: {error}", file=sys.stderr)
        return EXIT_UNUSABLE


def _in_range(kind: type, *, minimum: float | None = None, above: float | None = None,
              maximum: float | None = None) -> Callable[[str], Any]:
    """An argparse type: a finite number of `kind` inside the given range."""
    noun = "a whole number" if kind is int else "a number"
    bounds = " and ".join(text for text, bound in (
        (f"at least {minimum}", minimum), (f"greater than {above}", above), (f"at most {maximum}", maximum),
    ) if bound is not None)
    rule = f"{noun}, {bounds}" if bounds else noun

    def parse(text: str) -> Any:
        try:
            value = kind(text)
        except ValueError:
            value = math.nan
        if (not math.isfinite(value) or (minimum is not None and value < minimum)
                or (above is not None and value <= above) or (maximum is not None and value > maximum)):
            raise argparse.ArgumentTypeError(f"must be {rule}, got {text!r}")
        return value

    return parse


def _ratios(text: str) -> dict[str, float]:
    try:
        shares = [float(part) for part in text.split(",")]
    except ValueError:
        shares = []
    if (len(shares) != len(splits.SPLITS) or not all(math.isfinite(share) and share >= 0 for share in shares)
            or abs(sum(shares) - 1) > 1e-6):
        raise argparse.ArgumentTypeError(
            f"must be three numbers for train,validation,test that are not negative and sum to 1, got {text!r}")
    return dict(zip(splits.SPLITS, shares))


def _sha256(text: str) -> str:
    digest = text.strip().lower()
    if not re.fullmatch(r"[0-9a-f]{64}", digest):
        raise argparse.ArgumentTypeError("must be 64 hexadecimal characters, as make-splits prints them")
    return digest


# The range of each segmenter option taken by itself, so that the parser refuses a value
# before any video is opened. SegmenterParams checks the same ranges and also the one rule
# that ties two options together (exit_level <= enter_level). A parameter without an
# entry here stops the parser from being built.
_SEGMENT_RANGES: dict[str, dict[str, float]] = {
    "sample_fps": {"above": 0},
    "frame_width": {"minimum": 1},
    "frame_height": {"minimum": 1},
    "smooth_window_ms": {"minimum": 0},
    "noise_floor": {"above": 0},
    "min_contrast_ratio": {"above": 1},
    "enter_level": {"above": 0, "maximum": 1},
    "exit_level": {"above": 0, "maximum": 1},
    "merge_gap_ms": {"minimum": 0},
    "min_rally_ms": {"minimum": 1},
}


def _parser() -> argparse.ArgumentParser:
    parser = _Parser(prog="python -m volleystat_ml", description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)

    segment = commands.add_parser("segment", help="propose rally intervals for one video (motion-energy baseline)")
    segment.add_argument("video", type=Path)
    segment.add_argument("--video-id", help="identifier written to the output (default: the file name without suffix)")
    segment.add_argument("--out", type=Path, help="write the JSON result here instead of standard output")
    segment.add_argument("--quiet", action="store_true", help="do not print progress")
    for field in dataclasses.fields(segmenter.SegmenterParams):
        segment.add_argument(f"--{field.name.replace('_', '-')}", default=field.default,
                             type=_in_range(type(field.default), **_SEGMENT_RANGES[field.name]),
                             help=f"default {field.default}")
    segment.set_defaults(run=_segment)

    evaluate = commands.add_parser("evaluate", help="compare predicted rally intervals with gold intervals")
    evaluate.add_argument("--gold", type=Path, nargs="+", required=True,
                          help="annotation JSON (V3) or CSV with video_id,start_ms,end_ms (03_RALLI_GOLD)")
    evaluate.add_argument("--pred", type=Path, nargs="+", required=True,
                          help="JSON written by 'segment', or CSV with video_id,start_ms,end_ms")
    evaluate.add_argument("--tolerance-ms", type=_in_range(int, minimum=0), default=metrics.DEFAULT_TOLERANCE_MS)
    evaluate.add_argument("--resamples", type=_in_range(int, minimum=1), default=2000,
                          help="bootstrap resamples for the intervals")
    evaluate.add_argument("--seed", type=_in_range(int, minimum=0), default=0, help="seed of the bootstrap generator")
    evaluate.add_argument("--json", action="store_true",
                          help="print JSON instead of text; below 30 gold rallies its ratios are null")
    evaluate.set_defaults(run=_evaluate)

    agree = commands.add_parser(
        "agreement", help="measure how well two annotators agree on the same videos (experiment B0)")
    agree.add_argument("--a", type=Path, nargs="+", required=True,
                       help="label files of annotator A: annotation JSON (V3) or CSV in the 03_RALLI_GOLD layout")
    agree.add_argument("--b", type=Path, nargs="+", required=True, help="label files of annotator B, same formats")
    agree.add_argument("--tolerance-ms", type=_in_range(int, minimum=0), default=agreement.DEFAULT_TOLERANCE_MS,
                       help="two rallies pair when start and end are each within this many ms")
    agree.add_argument("--out", type=Path,
                       help="CSV to create with one row per disagreement; an existing file is never overwritten")
    agree.add_argument("--json", action="store_true",
                       help="print JSON instead of text; below 30 compared pairs its agreement rate and kappa are null")
    agree.set_defaults(run=_agreement)

    validate = commands.add_parser("validate-labels", help="check an annotation JSON file against schema V3 and the domain rules")
    validate.add_argument("file", type=Path)
    validate.add_argument("--duration-ms", type=_in_range(int, minimum=1),
                          help="duration of the annotated video; enables the checks against the end of the video")
    validate.set_defaults(run=_validate_labels)

    make = commands.add_parser("make-splits", help="assign train/validation/test by match group and write a lock manifest")
    make.add_argument("--inventory", type=Path, required=True, help="video inventory CSV (01_VIDEO_ENVANTERI columns)")
    make.add_argument("--previous", type=Path,
                      help="lock manifest of the previous version; every assignment in it is kept and only "
                           "videos and match groups it does not name are assigned")
    make.add_argument("--out", type=Path, required=True, help="lock manifest to create; an existing file is never overwritten")
    make.add_argument("--ratios", type=_ratios, default=dict(splits.DEFAULT_RATIOS),
                      help="train,validation,test shares (default 0.70,0.15,0.15)")
    make.add_argument("--seed", type=_in_range(int), default=0)
    make.add_argument("--locked-at", help="timestamp written to the manifest (default: now, UTC)")
    make.set_defaults(run=_make_splits)

    check = commands.add_parser(
        "check-leakage",
        help="look for a match group or a file hash in two splits, and for a video or a match group whose "
             "split differs between the given files")
    check.add_argument("files", type=Path, nargs="+", metavar="file", help="lock manifest or video inventory CSV")
    check.add_argument("--expect-sha256", type=_sha256, action="append", metavar="DIGEST",
                       help="content_sha256 that make-splits printed when the manifest was locked; exit code 1 "
                            "when the file gives another one. With several files: once for each, in the same order")
    check.set_defaults(run=_check_leakage)
    return parser


def _segment(args: argparse.Namespace) -> int:
    try:
        params = segmenter.SegmenterParams(**{
            field.name: getattr(args, field.name) for field in dataclasses.fields(segmenter.SegmenterParams)
        })
    except ValueError as error:
        raise InputError(str(error)) from error
    if args.out is not None:
        # Found here and not after the whole video was decoded.
        if args.out.is_dir():
            raise InputError(f"--out {args.out}: is a directory, a file name is needed")
        if not args.out.parent.is_dir():
            raise InputError(f"--out {args.out}: the folder does not exist")
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


# The roles whose files must pass the label rules (order, overlap, duplicates), and how a
# refusal names the file. Predictions are not held to them: a segmenter that proposes a
# rally twice has to be counted, not refused.
# The roles whose categorical labels are compared, so their CSV values must be schema values.
# A gold file of `evaluate` is read for its intervals only.
_COMPARED_ROLES = ("annotator A", "annotator B")


def _label_values() -> dict[str, list[str]]:
    """The values each categorical CSV column may carry: those of the annotation schema."""
    rally = labels.load_schema()["properties"]["rallies"]["items"]["properties"]
    return {"serving_side": rally["servingSide"]["enum"], "winning_side": rally["winningSide"]["enum"],
            "observation_status": rally["observationStatus"]["enum"]}


_LABEL_RULES = {
    "gold": ("the gold annotation is", "the gold intervals are"),
    "annotator A": ("the annotation of annotator A is", "the intervals of annotator A are"),
    "annotator B": ("the annotation of annotator B is", "the intervals of annotator B are"),
}


def _load_intervals(paths: Sequence[Path], role: str) -> dict[str, list[tuple[int, int]]]:
    return {
        video_id: [(rally.start_ms, rally.end_ms) for rally in rallies]
        for video_id, rallies in _load_rallies(paths, role).items()
    }


def _load_rallies(paths: Sequence[Path], role: str) -> dict[str, list[agreement.Rally]]:
    loaded: dict[str, list[agreement.Rally]] = {}
    for path in paths:
        if path.suffix.lower() == ".json":
            video_id, rallies = _rallies_from_json(path, role)
            groups = {video_id: rallies}
        else:
            groups = _rallies_from_csv(path, role)
        for video_id, rallies in groups.items():
            if video_id in loaded:
                raise InputError(f"{path}: video id '{video_id}' was already read from another {role} file")
            loaded[video_id] = rallies
    return loaded


def _named(path: Path, read: Callable[[Path], Any]) -> Any:
    """Call a reader of the splits module and put the file name in front of each problem
    it reports. Several commands read more than one file."""
    try:
        return read(path)
    except splits.SplitError as error:
        raise splits.SplitError([f"{path}: {problem}" for problem in error.problems]) from error


def _read_json(path: Path) -> Any:
    try:
        return json.loads(_named(path, splits.read_text))
    except json.JSONDecodeError as error:
        raise InputError(f"{path}: not valid JSON (line {error.lineno}, column {error.colno}): {error.msg}") from error


def _rallies_from_json(path: Path, role: str) -> tuple[str, list[agreement.Rally]]:
    document = _read_json(path)
    if role in _LABEL_RULES:
        # Metrics computed against broken labels are worthless, so labels must validate first.
        problems = labels.validate_annotation(document)
        if problems:
            raise InputError(f"{path}: {_LABEL_RULES[role][0]} not valid:\n  " + "\n  ".join(problems))
    try:
        rallies = [
            agreement.Rally(int(rally["startMs"]), int(rally["endMs"]), rally.get("winningSide"),
                            rally.get("servingSide"), rally.get("observationStatus"))
            for rally in document["rallies"]
        ]
        video_id = str(document["videoId"])
    except (KeyError, TypeError, ValueError, AttributeError) as error:
        raise InputError(f"{path}: expected an object with videoId and rallies[].startMs/endMs") from error
    _checked([(rally.start_ms, rally.end_ms) for rally in rallies], str(path))
    return video_id, rallies


def _rallies_from_csv(path: Path, role: str) -> dict[str, list[agreement.Rally]]:
    columns, rows, shape_problems = splits.read_csv(_named(path, splits.read_text))
    missing = [name for name in ("video_id", "start_ms", "end_ms") if name not in columns]
    if missing:
        raise InputError(f"{path}: missing column(s): {', '.join(missing)}")
    if shape_problems:
        raise InputError(f"{path}: {'; '.join(shape_problems)}")
    groups: dict[str, list[agreement.Rally]] = {}
    row_numbers: dict[str, list[int]] = {}
    value_problems: list[str] = []
    for number, row in enumerate(rows, start=2):
        try:
            start, end = int(row["start_ms"]), int(row["end_ms"])
        except ValueError as error:
            raise InputError(f"{path} row {number}: start_ms and end_ms must be whole milliseconds") from error
        if not row["video_id"]:
            raise InputError(f"{path} row {number}: video_id is empty")
        if role in _COMPARED_ROLES:
            # A misspelt value would be compared as a category of its own, and a misspelt
            # NOT_ANNOTATED would turn a placeholder winner into a disagreement.
            for column, allowed in _label_values().items():
                value = (row.get(column) or "").strip()
                if value and value not in allowed:
                    value_problems.append(f"row {number}: {column} must be one of {', '.join(allowed)} or empty, found {value!r}")
        # A column the file does not have and an empty cell both mean "no value given".
        groups.setdefault(row["video_id"], []).append(agreement.Rally(
            start, end, row.get("winning_side"), row.get("serving_side"), row.get("observation_status")))
        row_numbers.setdefault(row["video_id"], []).append(number)
    if value_problems:
        raise InputError(f"{path}: the labels of {role} are not valid:\n  " + "\n  ".join(value_problems))
    if role in _LABEL_RULES:
        # The same rules as for a JSON label file: a rally listed twice or two rallies that
        # overlap would each be counted, and the recall would be taken over too many.
        problems = []
        for video_id, rallies in groups.items():
            per_row, overlaps = labels.interval_problems(
                [(rally.start_ms, rally.end_ms) for rally in rallies],
                lambda index: f"row {row_numbers[video_id][index]}", names=("start_ms", "end_ms"))
            problems += [problem for found in per_row for problem in found] + overlaps
        if problems:
            raise InputError(f"{path}: {_LABEL_RULES[role][1]} not valid:\n  " + "\n  ".join(problems))
    for video_id, rallies in groups.items():
        _checked([(rally.start_ms, rally.end_ms) for rally in rallies], f"{path} (video {video_id})")
    return groups


def _checked(intervals: list[tuple[int, int]], source: str) -> list[tuple[int, int]]:
    for start, end in intervals:
        if not 0 <= start < end:
            raise InputError(f"{source}: interval {start}..{end} does not satisfy 0 <= start < end")
    return intervals


def _agreement(args: argparse.Namespace) -> int:
    first = _load_rallies(args.a, "annotator A")
    second = _load_rallies(args.b, "annotator B")
    shared = sorted(set(first) & set(second))
    if not shared:
        raise InputError("no video id appears in the files of both annotators")
    if args.out is not None:
        if args.out.is_dir():
            raise InputError(f"--out {args.out}: is a directory, a file name is needed")
        if not args.out.parent.is_dir():
            raise InputError(f"--out {args.out}: the folder does not exist")
    per_video = {video_id: agreement.compare(first[video_id], second[video_id], args.tolerance_ms)
                 for video_id in shared}
    total = agreement.pool(per_video.values())
    # A video one annotator did not label is not a video with no rallies, so it is listed
    # and takes part in no number.
    only_a = sorted(set(first) - set(second))
    only_b = sorted(set(second) - set(first))
    rows = agreement.disagreement_rows(per_video)
    if args.out is not None:
        # Written before anything is printed: a refused file leaves no report that looks complete.
        with open(args.out, "x", encoding="utf-8", newline="") as handle:
            writer = csv.writer(handle)
            writer.writerow(agreement.DISAGREEMENT_COLUMNS)
            writer.writerows([["" if row[name] is None else row[name] for name in agreement.DISAGREEMENT_COLUMNS]
                              for row in rows])

    if args.json:
        document = total.to_dict()
        document["videos"] = {video_id: result.to_dict() for video_id, result in per_video.items()}
        document["notCompared"] = {"onlyA": only_a, "onlyB": only_b}
        document["disagreementsFile"] = None if args.out is None else str(args.out)
        print(json.dumps(document, indent=2))
        return EXIT_OK

    print(f"Annotator agreement, tolerance +/-{args.tolerance_ms} ms on start and on end")
    print(f"videos compared: {len(shared)} ({', '.join(shared)})")
    if only_a:
        print(f"not compared, labelled by annotator A only: {', '.join(only_a)}")
    if only_b:
        print(f"not compared, labelled by annotator B only: {', '.join(only_b)}")
    print("rallies: " + _rally_counts(total))
    if len(per_video) > 1:
        for video_id, result in per_video.items():
            print(f"  {video_id}: " + _rally_counts(result))
            # Counts only. The differences and ratios of one video are in the JSON output.
            print("    " + "; ".join(_field_counts(title, result.fields[name]) for name, title in _FIELD_TITLES))
    boundaries = total.boundaries
    print(f"start difference over {total.matched} matched pair(s): "
          f"median {_number(boundaries.median_abs_start_error_ms, 0)} ms, "
          f"p90 {_number(boundaries.p90_abs_start_error_ms, 0)} ms")
    print(f"end difference over {total.matched} matched pair(s):   "
          f"median {_number(boundaries.median_abs_end_error_ms, 0)} ms, "
          f"p90 {_number(boundaries.p90_abs_end_error_ms, 0)} ms")
    print(f"mean interval IoU of matched pairs: {_number(boundaries.mean_iou, 3)}")
    for name, title in _FIELD_TITLES:
        _print_field(title, total.fields[name])
    print(f"disagreements for the adjudicator: {len(rows)} row(s)")
    if args.out is not None:
        print(f"wrote {args.out}")
    return EXIT_OK


def _rally_counts(result: agreement.Agreement) -> str:
    return (f"A {result.a_count}   B {result.b_count}   matched pairs {result.matched}   "
            f"only in A {result.only_a}   only in B {result.only_b}")


_FIELD_TITLES = (("winning_side", "winning side"), ("serving_side", "serving side"))


def _field_counts(title: str, field: agreement.FieldAgreement) -> str:
    return f"{title} {field.agree}/{field.compared} agree" if field.compared else f"{title} not compared"


def _print_field(title: str, field: agreement.FieldAgreement) -> None:
    undefined = field.kappa_undefined_reason if field.compared else None
    if not field.compared:
        print(f"{title}: no matched pair has this field from both annotators")
    elif field.percentages_reliable:
        kappa = f"Cohen's kappa {field.kappa:.3f}" if field.kappa is not None else f"Cohen's kappa not defined: {undefined}"
        print(f"{title}: {field.agree}/{field.compared} agree ({field.observed:.3f}); {kappa}")
    else:
        print(f"{title}: {field.agree}/{field.compared} agree")
        # Undefined is not the same as unreliable: a kappa that does not exist is said to
        # not exist at every sample size.
        if undefined is not None:
            print(f"  Cohen's kappa not defined: {undefined}")
    if field.not_comparable:
        print(f"  not comparable, one annotator left the field empty: {field.not_comparable} pair(s)")
    if field.both_empty:
        print(f"  left empty by both annotators: {field.both_empty} pair(s)")
    if field.compared and not field.percentages_reliable:
        what = "the agreement rate is" if undefined is not None else "the agreement rate and Cohen's kappa are"
        verb = "is" if undefined is not None else "are"
        print(f"  n = {field.compared} compared pairs is below {agreement.MIN_RELIABLE}: {what} not reliable "
              f"at this sample size and {verb} not printed. Quote the counts.")


def _validate_labels(args: argparse.Namespace) -> int:
    # A file that can not be read or parsed was not checked at all, which is exit code 2
    # and not a finding of the check.
    problems = labels.validate_annotation(_read_json(args.file), args.duration_ms)
    for problem in problems:
        print(problem)
    if problems:
        print(f"{args.file}: {len(problems)} problem(s)", file=sys.stderr)
        return EXIT_FOUND
    note = "" if args.duration_ms is not None else " (video duration not given, no time checked against the end of the video)"
    print(f"{args.file}: valid{note}")
    return EXIT_OK


def _manifest_problems(manifest: splits.LockManifest) -> list[str]:
    problems = list(manifest.problems)
    if manifest.stored_sha256 is None:
        problems.append("the manifest has no content_sha256 line, so it can not be told whether it was edited")
    elif not manifest.intact:
        problems.append(
            f"the manifest was changed after it was locked: recorded content_sha256 {manifest.stored_sha256}, "
            f"current content gives {manifest.computed_sha256}"
        )
    return problems


def _make_splits(args: argparse.Namespace) -> int:
    videos = _named(args.inventory, splits.read_inventory)
    previous: tuple[splits.Video, ...] = ()
    if args.previous is not None:
        manifest = _named(args.previous, splits.read_lock_manifest)
        problems = _manifest_problems(manifest)
        if problems:
            # Assignments taken from an edited lock would be locked in again as if they were the old ones.
            raise splits.SplitError([f"{args.previous}: {problem}" for problem in problems])
        previous = manifest.videos
    assignment = splits.assign_splits(videos, ratios=args.ratios, seed=args.seed, previous=previous)
    locked_at = args.locked_at or datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    digest = splits.write_lock_manifest(args.out, videos, assignment, locked_at=locked_at)
    for split in splits.SPLITS:
        members = [video for video in videos if assignment[video.video_id] == split]
        groups = {video.match_group_id for video in members}
        print(f"{split}: {len(members)} video(s) in {len(groups)} match group(s)")
    if args.previous is not None:
        locked = {video.video_id for video in previous}
        kept = sum(video.video_id in locked for video in videos)
        print(f"kept {kept} video(s) as locked in {args.previous}; assigned {len(videos) - kept} new video(s)")
        gone = sorted(locked - {video.video_id for video in videos})
        if gone:
            print(f"not in the inventory any more, so not in the new lock: {', '.join(gone)}")
    print(f"wrote {args.out}")
    print(f"content_sha256 {digest}")
    return EXIT_OK


def _check_leakage(args: argparse.Namespace) -> int:
    expected = args.expect_sha256 or []
    if expected and len(expected) != len(args.files):
        raise InputError(f"--expect-sha256 was given {len(expected)} time(s) for {len(args.files)} file(s); "
                         "give it once for each file, in the same order")
    # Every file is read before anything is printed, so unusable input leaves no half report.
    reports = []
    for index, path in enumerate(args.files):
        digest = None
        if _named(path, splits.is_lock_manifest):
            manifest = _named(path, splits.read_lock_manifest)
            videos = list(manifest.videos)
            digest = manifest.computed_sha256
            problems = _manifest_problems(manifest)
            if expected and digest != expected[index]:
                problems.append(f"content_sha256 is {digest}, but {expected[index]} was expected")
        elif expected:
            raise InputError(f"{path}: is not a lock manifest, so there is no content_sha256 to compare")
        else:
            videos = _named(path, splits.read_inventory)
            problems = splits.training_violations(videos)
        reports.append((path, videos, digest, problems + splits.find_leakage(videos)))

    found = 0
    for path, videos, digest, problems in reports:
        for problem in problems:
            print(f"{path}: {problem}")
        if digest is not None:
            # Printed on every run so that it can be compared with the digest noted at lock
            # time: a file edited together with its hash line is consistent in itself.
            print(f"{path}: content_sha256 {digest}")
        assigned = sum(1 for video in videos if video.split)
        print(f"{path}: {assigned} of {len(videos)} video(s) have a split; {len(problems)} problem(s)")
        found += len(problems)
    if len(reports) > 1:
        changed = splits.find_changed_splits({str(path): videos for path, videos, _, _ in reports})
        for problem in changed:
            print(problem)
        print(f"{len(reports)} files compared; {len(changed)} problem(s)")
        found += len(changed)
    return EXIT_FOUND if found else EXIT_OK
