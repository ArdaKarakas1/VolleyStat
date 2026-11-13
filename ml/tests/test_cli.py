import csv
import hashlib
import json
import subprocess
import sys

import pytest

from volleystat_ml import cli, splits


def run(capsys, *arguments):
    try:
        code = cli.main([str(argument) for argument in arguments])
    except SystemExit as stopped:
        # The argument parser reports a command line it can not use by exiting.
        code = stopped.code
    captured = capsys.readouterr()
    return code, captured.out, captured.err


def assert_unusable(result, *fragments):
    """Exit code 2, nothing on standard output and exactly one line that says why."""
    code, out, err = result
    assert (code, out) == (2, ""), err
    assert err.startswith("error: ") and err.count("\n") == 1 and "Traceback" not in err, err
    for fragment in fragments:
        assert fragment in err


def annotation(video_id, rallies):
    return {
        "annotationVersion": "VVV_ANNOTATION_V3", "videoId": video_id,
        "rallies": [
            {"rallyId": f"R{index}", "startMs": start, "endMs": end, "winningSide": "UNKNOWN",
             "observationStatus": "OBSERVED", "uncertaintyReason": "UNKNOWN_OTHER"}
            for index, (start, end) in enumerate(rallies, start=1)
        ],
    }


def write_json(path, document):
    path.write_text(json.dumps(document), encoding="utf-8")
    return path


def write_rows(path, header, rows):
    with open(path, "w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(header)
        writer.writerows(rows)
    return path


# --- segment ---------------------------------------------------------------------------

def test_module_runs_segment_and_writes_proposals_without_winner_or_confidence(clips, tmp_path):
    out = tmp_path / "pred.json"
    completed = subprocess.run(
        [sys.executable, "-m", "volleystat_ml", "segment", str(clips.moving_box), "--video-id", "SYN-1", "--out", str(out)],
        capture_output=True, text=True,
    )
    assert completed.returncode == 0, completed.stderr
    assert "frames 200/200" in completed.stderr
    assert "status OK: 3 interval(s)" in completed.stderr
    document = json.loads(out.read_text(encoding="utf-8"))
    assert document["analyzer"] == {"id": "motion-baseline", "version": "0.2", "kind": "BASELINE"}
    assert document["mock"] is False
    assert (document["videoId"], document["durationMs"], document["status"]) == ("SYN-1", 40000, "OK")
    assert document["frames"] == {"processed": 200, "expected": 200}
    assert document["params"]["sample_fps"] == 5.0
    assert len(document["rallies"]) == 3
    for rally in document["rallies"]:
        assert rally["proposedWinner"] == "UNKNOWN" and rally["confidence"] is None
        assert rally["uncertaintyReasons"] == ["NO_WINNER_MODEL"]


def test_segment_prints_json_and_reports_a_clip_without_separation(clips, capsys):
    code, out, err = run(capsys, "segment", clips.still_noisy, "--quiet")
    document = json.loads(out)
    assert code == 0 and err == ""
    assert document["videoId"] == "still_noisy"
    assert document["status"] == "NO_SEPARATION" and document["rallies"] == []


def test_segment_parameters_come_from_the_command_line(clips, capsys):
    code, out, _ = run(capsys, "segment", clips.moving_box, "--quiet", "--min-rally-ms", 7000)
    document = json.loads(out)
    assert code == 0 and document["params"]["min_rally_ms"] == 7000
    assert [(rally["startMs"], rally["endMs"]) for rally in document["rallies"]] == [(15900, 24100)]


@pytest.mark.parametrize("option, value", [
    ("--sample-fps", 0), ("--sample-fps", "nan"), ("--sample-fps", "inf"), ("--sample-fps", "fast"),
    ("--frame-width", 0), ("--frame-height", 0), ("--frame-width", 96.5),
    ("--smooth-window-ms", -1), ("--merge-gap-ms", -1), ("--min-rally-ms", 0),
    ("--noise-floor", 0), ("--min-contrast-ratio", 1.0), ("--exit-level", 0), ("--enter-level", 1.5),
])
def test_segment_option_outside_its_range_is_refused_by_the_parser(capsys, tmp_path, option, value):
    # The video is never opened: the parser stops before the command runs.
    assert_unusable(run(capsys, "segment", tmp_path / "missing.mp4", option, value), f"argument {option}: ")


def test_segment_levels_that_do_not_fit_each_other_are_refused(clips, capsys):
    assert_unusable(run(capsys, "segment", clips.moving_box, "--enter-level", 0.2, "--exit-level", 0.3),
                    "levels must satisfy")


def test_segment_fails_clearly_on_an_unreadable_file(clips, capsys):
    code, out, err = run(capsys, "segment", clips.not_a_video)
    assert code == 2 and out == "" and err.startswith("error: ")


def test_segment_refuses_a_video_it_could_not_decode_to_its_end(clips, capsys, tmp_path):
    out = tmp_path / "pred.json"
    assert_unusable(run(capsys, "segment", clips.truncated, "--quiet", "--out", out),
                    "could not decode every part of the video")
    assert not out.exists()


def test_segment_checks_the_output_path_before_it_decodes(clips, capsys, tmp_path, monkeypatch):
    def never(*arguments, **options):
        raise AssertionError("the video was decoded although the result can not be written")

    monkeypatch.setattr(cli.segmenter, "segment_video", never)
    assert_unusable(run(capsys, "segment", clips.moving_box, "--out", tmp_path), "is a directory")
    assert_unusable(run(capsys, "segment", clips.moving_box, "--out", tmp_path / "missing" / "pred.json"),
                    "the folder does not exist")


# --- evaluate --------------------------------------------------------------------------

def test_segment_output_is_evaluated_against_an_annotation_file(clips, tmp_path, capsys):
    pred = tmp_path / "pred.json"
    assert run(capsys, "segment", clips.concatenated, "--video-id", "SYN-2", "--out", pred, "--quiet")[0] == 0
    gold = write_json(tmp_path / "gold.json", annotation("SYN-2", clips.rallies_ms))

    code, out, _ = run(capsys, "evaluate", "--gold", gold, "--pred", pred)
    assert code == 0
    assert "tolerance +/-1000 ms" in out
    assert "gold rallies: 3   predicted rallies: 3" in out
    assert "TP 3   FP 0   FN 0" in out
    assert "precision: 3/3 predicted rallies match a gold rally\n" in out
    assert "recall:    3/3 gold rallies are found\n" in out
    # Three rallies: counts only, no percentage and no F1 line.
    assert "n = 3 gold rallies is below 30" in out
    assert "F1" not in out and "1.000" not in out.split("interval")[0]
    assert "interval: not informative" in out

    code, out, _ = run(capsys, "evaluate", "--gold", gold, "--pred", pred, "--json")
    document = json.loads(out)
    assert code == 0
    assert (document["tp"], document["fp"], document["fn"]) == (3, 0, 0)
    # Below 30 gold rallies the JSON carries the counts and no ratio, like the text.
    assert document["percentagesReliable"] is False
    assert document["recall"] == {"value": None, "numerator": 3, "denominator": 3}
    assert document["precision"] == {"value": None, "numerator": 3, "denominator": 3}
    assert document["f1"] is None
    assert document["videos"]["SYN-2"]["recall"] == {"value": None, "numerator": 3, "denominator": 3}
    assert document["bootstrap"]["recall"]["degenerate"] is True
    assert list(document["videos"]) == ["SYN-2"]


def test_percentages_are_printed_from_thirty_gold_rallies_on(tmp_path, capsys):
    gold_rows = [("V1", index * 20000, index * 20000 + 8000) for index in range(20)]
    gold_rows += [("V2", index * 20000, index * 20000 + 8000) for index in range(20)]
    # V1: every rally found 300 ms late. V2: four rallies missed and two invented.
    pred_rows = [("V1", start + 300, end + 300) for _, start, end in gold_rows[:20]]
    pred_rows += [("V2", start, end) for _, start, end in gold_rows[24:]]
    pred_rows += [("V2", 500000, 505000), ("V2", 600000, 605000)]
    gold = write_rows(tmp_path / "gold.csv", ["rally_id", "video_id", "start_ms", "end_ms"],
                      [(f"R{index}", *row) for index, row in enumerate(gold_rows)])
    pred = write_rows(tmp_path / "pred.csv", ["video_id", "start_ms", "end_ms"], pred_rows)

    code, out, _ = run(capsys, "evaluate", "--gold", gold, "--pred", pred, "--seed", 11)
    assert code == 0
    assert "videos evaluated: 2 (V1, V2)" in out
    assert "TP 36   FP 2   FN 4" in out
    assert "precision: 36/38 predicted rallies match a gold rally (0.947)" in out
    assert "recall:    36/40 gold rallies are found (0.900)" in out
    assert "F1:        0.923" in out
    assert "below 30" not in out
    assert "recall 95% bootstrap interval: " in out and "seed 11" in out
    # 20 of the 36 matched pairs are 300 ms late and 16 are exact. More than half of the
    # sorted errors are 300, so the two middle values (18th and 19th) are both 300.
    assert "start error over 36 matched pair(s): median 300 ms, p90 300 ms" in out
    assert "end error over 36 matched pair(s):   median 300 ms, p90 300 ms" in out
    # A late pair overlaps 7700 of 8300 ms: (20 * 7700 / 8300 + 16 * 1) / 36 = 0.9598.
    assert "mean interval IoU of matched pairs: 0.960" in out

    first = run(capsys, "evaluate", "--gold", gold, "--pred", pred, "--seed", 11, "--json")[1]
    assert first == run(capsys, "evaluate", "--gold", gold, "--pred", pred, "--seed", 11, "--json")[1]
    document = json.loads(first)
    interval = document["bootstrap"]["recall"]
    assert 0 < interval["low"] < 0.9 < interval["high"] <= 1 and interval["degenerate"] is False
    # 40 gold rallies in total carry ratios; each video has 20 and carries none.
    assert document["recall"] == {"value": 0.9, "numerator": 36, "denominator": 40}
    assert document["f1"] == pytest.approx(72 / 78)
    assert document["videos"]["V2"]["percentagesReliable"] is False
    assert document["videos"]["V2"]["recall"] == {"value": None, "numerator": 16, "denominator": 20}
    assert document["videos"]["V2"]["f1"] is None


def test_videos_present_on_one_side_only_are_listed_and_not_counted(tmp_path, capsys):
    gold = [write_json(tmp_path / "g1.json", annotation("V1", [(0, 5000)])),
            write_json(tmp_path / "g2.json", annotation("V2", [(0, 5000), (9000, 12000)]))]
    pred = write_rows(tmp_path / "pred.csv", ["video_id", "start_ms", "end_ms"],
                      [("V1", 100, 5100), ("V3", 0, 4000)])
    code, out, _ = run(capsys, "evaluate", "--gold", *gold, "--pred", pred)
    assert code == 0
    assert "videos evaluated: 1 (V1)" in out
    assert "not evaluated, no prediction file: V2" in out
    assert "not evaluated, no gold file: V3" in out
    assert "TP 1   FP 0   FN 0" in out


def test_evaluate_refuses_unusable_input(tmp_path, capsys):
    gold = write_json(tmp_path / "gold.json", annotation("V1", [(0, 5000)]))
    other = write_rows(tmp_path / "other.csv", ["video_id", "start_ms", "end_ms"], [("V9", 0, 5000)])
    code, _, err = run(capsys, "evaluate", "--gold", gold, "--pred", other)
    assert code == 2 and "no video id appears in both" in err

    broken = write_json(tmp_path / "broken.json", annotation("V1", [(5000, 1000)]))
    code, _, err = run(capsys, "evaluate", "--gold", broken, "--pred", other)
    assert code == 2 and "the gold annotation is not valid" in err
    assert "$.rallies[0]: startMs (5000) must be less than endMs (1000)" in err

    no_columns = write_rows(tmp_path / "no_columns.csv", ["video_id", "start"], [("V1", 0)])
    code, _, err = run(capsys, "evaluate", "--gold", gold, "--pred", no_columns)
    assert code == 2 and "missing column(s): start_ms, end_ms" in err

    twice = write_rows(tmp_path / "twice.csv", ["video_id", "start_ms", "end_ms"], [("V1", 0, 5000)])
    code, _, err = run(capsys, "evaluate", "--gold", gold, "--pred", twice, twice)
    assert code == 2 and "was already read from another pred file" in err


def test_gold_csv_passes_the_same_interval_rules_as_gold_json(tmp_path, capsys):
    rallies = [(4000, 10000), (4000, 10000), (16000, 24000), (17000, 23000), (30000, 36000)]
    pred = write_rows(tmp_path / "pred.csv", ["video_id", "start_ms", "end_ms"],
                      [("V1", 4000, 10000), ("V1", 16000, 24000), ("V1", 30000, 36000)])

    as_json = write_json(tmp_path / "gold.json", annotation("V1", rallies))
    code, out, err = run(capsys, "evaluate", "--gold", as_json, "--pred", pred)
    assert code == 2 and out == ""
    assert err.splitlines()[1:] == [
        "  $.rallies[1]: startMs (4000) is not after startMs (4000) of $.rallies[0]; rallies must be listed in time order",
        "  $.rallies[1]: startMs (4000) is before endMs (10000) of $.rallies[0]; rallies must not overlap",
        "  $.rallies[3]: startMs (17000) is before endMs (24000) of $.rallies[2]; rallies must not overlap",
    ]

    # The rows of another video lie in between; the rules apply to each video on its own.
    rows = [("V2", 0, 3000), ("V1", *rallies[0]), ("V1", *rallies[1]), ("V2", 5000, 9000),
            ("V1", *rallies[2]), ("V1", *rallies[3]), ("V1", *rallies[4])]
    as_csv = write_rows(tmp_path / "gold.csv", ["video_id", "start_ms", "end_ms"], rows)
    code, out, err = run(capsys, "evaluate", "--gold", as_csv, "--pred", pred)
    assert code == 2 and out == ""
    assert err.splitlines() == [
        f"error: {as_csv}: the gold intervals are not valid:",
        "  row 4: start_ms (4000) is not after start_ms (4000) of row 3; rallies must be listed in time order",
        "  row 4: start_ms (4000) is before end_ms (10000) of row 3; rallies must not overlap",
        "  row 7: start_ms (17000) is before end_ms (24000) of row 6; rallies must not overlap",
    ]

    # Predictions may overlap: a segmenter that proposes a rally twice has to be counted, not refused.
    clean = write_rows(tmp_path / "clean.csv", ["video_id", "start_ms", "end_ms"],
                       [("V1", 4000, 10000), ("V1", 16000, 24000), ("V1", 30000, 36000)])
    code, out, _ = run(capsys, "evaluate", "--gold", clean, "--pred", as_csv)
    assert code == 0 and "TP 3   FP 2   FN 0" in out


def test_evaluate_refuses_input_it_can_not_read(tmp_path, capsys):
    gold = write_json(tmp_path / "gold.json", annotation("V1", [(0, 5000)]))
    pred = write_rows(tmp_path / "pred.csv", ["video_id", "start_ms", "end_ms"], [("V1", 0, 5000)])

    assert_unusable(run(capsys, "evaluate", "--gold", tmp_path, "--pred", pred), "Is a directory")
    assert_unusable(run(capsys, "evaluate", "--gold", gold, "--pred", tmp_path / "missing.csv"), "No such file")

    wider = write_rows(tmp_path / "wider.csv", ["video_id", "start_ms", "end_ms"], [("V1", 0, 5000, 7000)])
    assert_unusable(run(capsys, "evaluate", "--gold", gold, "--pred", wider),
                    f"{wider}: row 2: 4 cells, but the header has 3 columns")

    for name in ("latin.csv", "latin.json"):
        latin = tmp_path / name
        latin.write_bytes("video_id,start_ms,end_ms\nMaç-1,0,5000\n".encode("cp1254"))
        assert_unusable(run(capsys, "evaluate", "--gold", gold, "--pred", latin), f"{latin}: the file is not UTF-8 text")


@pytest.mark.parametrize("option, value", [
    ("--resamples", 0), ("--resamples", -5), ("--resamples", "many"), ("--tolerance-ms", -1),
    ("--tolerance-ms", 0.5), ("--seed", -1),
])
def test_evaluate_option_outside_its_range_is_refused_by_the_parser(tmp_path, capsys, option, value):
    gold = write_json(tmp_path / "gold.json", annotation("V1", [(0, 5000)]))
    assert_unusable(run(capsys, "evaluate", "--gold", gold, "--pred", gold, option, value), f"argument {option}: ")


# --- validate-labels -------------------------------------------------------------------

def test_validate_labels_reports_each_problem_and_sets_the_exit_code(tmp_path, capsys):
    valid = write_json(tmp_path / "valid.json", annotation("V1", [(0, 5000), (6000, 9000)]))
    code, out, _ = run(capsys, "validate-labels", valid)
    assert code == 0 and "valid (video duration not given" in out
    code, out, _ = run(capsys, "validate-labels", valid, "--duration-ms", 9000)
    assert code == 0 and out.strip().endswith("valid")

    code, out, err = run(capsys, "validate-labels", valid, "--duration-ms", 8000)
    assert code == 1
    assert out.splitlines() == ["$.rallies[1]: endMs (9000) is after the end of the video (8000 ms)"]
    assert "1 problem(s)" in err


def test_validate_labels_tells_an_unusable_file_from_a_file_with_problems(tmp_path, capsys):
    assert_unusable(run(capsys, "validate-labels", tmp_path / "nope.json"), "No such file")
    assert_unusable(run(capsys, "validate-labels", tmp_path), "Is a directory")

    broken = tmp_path / "broken.json"
    broken.write_text('{"annotationVersion": ', encoding="utf-8")
    assert_unusable(run(capsys, "validate-labels", broken), f"{broken}: not valid JSON (line 1, column")

    latin = tmp_path / "latin.json"
    latin.write_bytes('{"videoId": "Maç"}'.encode("cp1254"))
    assert_unusable(run(capsys, "validate-labels", latin), f"{latin}: the file is not UTF-8 text")

    valid = write_json(tmp_path / "valid.json", annotation("V1", [(0, 5000)]))
    assert_unusable(run(capsys, "validate-labels", valid, "--duration-ms", 0), "argument --duration-ms: ")

    # Readable JSON that breaks the schema is a finding of the check, not unusable input.
    wrong = write_json(tmp_path / "wrong.json", {"videoId": "V1"})
    code, out, _ = run(capsys, "validate-labels", wrong)
    assert code == 1 and "$: missing required field 'rallies'" in out


def test_validate_labels_checks_action_starts_against_the_duration(tmp_path, capsys):
    document = annotation("V1", [(0, 1000)])
    document["rallies"][0]["actions"] = [
        {"actionId": "A1", "actionType": "ATTACK", "teamSide": "HOME", "outcome": "KILL", "startMs": 999999},
    ]
    late = write_json(tmp_path / "late_action.json", document)
    code, out, _ = run(capsys, "validate-labels", late, "--duration-ms", 4000)
    assert code == 1
    assert out.splitlines() == ["$.rallies[0].actions[0]: startMs (999999) is after the end of the video (4000 ms)"]


# --- make-splits and check-leakage -----------------------------------------------------

def inventory(tmp_path, rows, name="inventory.csv"):
    """rows: (video_id, match_group_id, training_allowed, split)"""
    lines = []
    for video_id, group, allowed, split in rows:
        row = dict.fromkeys(splits.INVENTORY_COLUMNS, "")
        row.update(video_id=video_id, match_group_id=group, training_allowed=allowed, split=split,
                   sha256=hashlib.sha256(video_id.encode()).hexdigest())
        lines.append([row[name] for name in splits.INVENTORY_COLUMNS])
    return write_rows(tmp_path / name, splits.INVENTORY_COLUMNS, lines)


def test_make_splits_writes_a_lock_that_check_leakage_accepts(tmp_path, capsys):
    rows = [(f"V{group}{letter}", f"M{group}", "true", "") for group in range(10) for letter in "ab"]
    source = inventory(tmp_path, rows)
    lock = tmp_path / "TEST_LOCK_V1.csv"

    code, out, _ = run(capsys, "make-splits", "--inventory", source, "--out", lock, "--locked-at", "2026-11-12T00:00:00Z")
    assert code == 0
    assert "train: 14 video(s) in 7 match group(s)" in out
    assert "validation: 2 video(s) in 1 match group(s)" in out
    assert "test: 4 video(s) in 2 match group(s)" in out
    manifest = splits.read_lock_manifest(lock)
    assert manifest.intact and f"content_sha256 {manifest.stored_sha256}" in out

    code, out, _ = run(capsys, "check-leakage", lock)
    assert code == 0 and "20 of 20 video(s) have a split; 0 problem(s)" in out

    code, _, err = run(capsys, "make-splits", "--inventory", source, "--out", lock)
    assert code == 2 and "File exists" in err

    for ratios in ("0.5,0.5", "0.5,0.3,0.3", "1.2,-0.2,0", "a,b,c", "nan,0.5,0.5"):
        assert_unusable(
            run(capsys, "make-splits", "--inventory", source, "--out", tmp_path / "other.csv", "--ratios", ratios),
            "argument --ratios: must be three numbers", "sum to 1")
    assert not (tmp_path / "other.csv").exists()


def test_check_leakage_detects_an_edited_lock(tmp_path, capsys):
    rows = [(f"V{group}{letter}", f"M{group}", "true", "") for group in range(10) for letter in "ab"]
    lock = tmp_path / "lock.csv"
    assert run(capsys, "make-splits", "--inventory", inventory(tmp_path, rows), "--out", lock)[0] == 0
    manifest = splits.read_lock_manifest(lock)
    train_video = next(video for video in manifest.videos if video.split == "train")
    text = lock.read_text(encoding="utf-8")
    edited = text.replace(f"{train_video.sha256},train,", f"{train_video.sha256},test,")
    assert edited != text
    lock.write_text(edited, encoding="utf-8")

    code, out, _ = run(capsys, "check-leakage", lock)
    assert code == 1
    assert "the manifest was changed after it was locked" in out
    assert f"match_group_id '{train_video.match_group_id}' is in 2 splits" in out


def moved_to_train(lock):
    """Move one whole test match of a lock to train and give the file a hash line that
    fits the new content. Returns the match and the new digest."""
    manifest = splits.read_lock_manifest(lock)
    group = next(video.match_group_id for video in manifest.videos if video.split == "test")
    lines = lock.read_text(encoding="utf-8").splitlines()[:-1]
    lines = [line.replace(",test,", ",train,") if f",{group}," in line else line for line in lines]
    content = "\n".join(lines) + "\n"
    digest = hashlib.sha256(content.encode()).hexdigest()
    lock.write_text(f"{content}# content_sha256={digest}\n", encoding="utf-8")
    return group, digest


def test_check_leakage_prints_the_digest_and_compares_it_with_the_expected_one(tmp_path, capsys):
    rows = [(f"V{group}{letter}", f"M{group}", "true", "") for group in range(10) for letter in "ab"]
    lock = tmp_path / "lock.csv"
    assert run(capsys, "make-splits", "--inventory", inventory(tmp_path, rows), "--out", lock)[0] == 0
    recorded = splits.read_lock_manifest(lock).stored_sha256

    code, out, _ = run(capsys, "check-leakage", lock)
    assert code == 0 and f"{lock}: content_sha256 {recorded}\n" in out
    code, out, _ = run(capsys, "check-leakage", lock, "--expect-sha256", recorded.upper())
    assert code == 0 and "0 problem(s)" in out

    # The edited file is consistent in itself. Only the digest written down at lock time,
    # outside the file, shows that it is not the lock any more.
    _, forged = moved_to_train(lock)
    code, out, _ = run(capsys, "check-leakage", lock)
    assert code == 0 and f"{lock}: content_sha256 {forged}\n" in out
    code, out, _ = run(capsys, "check-leakage", lock, "--expect-sha256", recorded)
    assert code == 1
    assert f"{lock}: content_sha256 is {forged}, but {recorded} was expected" in out
    assert "20 of 20 video(s) have a split; 1 problem(s)" in out


def test_check_leakage_refuses_an_expected_digest_it_can_not_compare(tmp_path, capsys):
    rows = [("V1", "M1", "true", "train"), ("V2", "M2", "true", "test")]
    source = inventory(tmp_path, rows)
    lock = tmp_path / "lock.csv"
    assert run(capsys, "make-splits", "--inventory", source, "--out", lock)[0] == 0
    digest = splits.read_lock_manifest(lock).stored_sha256

    assert_unusable(run(capsys, "check-leakage", lock, "--expect-sha256", "abc123"), "argument --expect-sha256: ")
    assert_unusable(run(capsys, "check-leakage", source, "--expect-sha256", digest), "is not a lock manifest")
    assert_unusable(run(capsys, "check-leakage", lock, lock, "--expect-sha256", digest), "once for each file")


def test_check_leakage_counts_a_manifest_row_with_more_cells_as_a_problem(tmp_path, capsys):
    rows = [(f"V{group}", f"M{group}", "true", "") for group in range(5)]
    lock = tmp_path / "lock.csv"
    assert run(capsys, "make-splits", "--inventory", inventory(tmp_path, rows), "--out", lock)[0] == 0
    lines = lock.read_text(encoding="utf-8").splitlines()
    lines[2] += ",appended"
    lock.write_text("\n".join(lines) + "\n", encoding="utf-8")

    code, out, _ = run(capsys, "check-leakage", lock)
    assert code == 1
    assert f"{lock}: row 3: 10 cells, but the header has 9 columns" in out
    assert "5 of 5 video(s) have a split; 1 problem(s)" in out


def test_previous_lock_keeps_a_locked_match_where_it_was(tmp_path, capsys):
    rows = [(f"V{number}", f"M0{number}", "true", "") for number in (1, 2, 3)]
    first = tmp_path / "TEST_LOCK_V1.csv"
    assert run(capsys, "make-splits", "--inventory", inventory(tmp_path, rows), "--out", first, "--seed", 0)[0] == 0
    before = {video.video_id: video.split for video in splits.read_lock_manifest(first).videos}
    assert before["V2"] == "test"
    grown = inventory(tmp_path, [*rows, ("V4", "M04", "true", "")], "grown.csv")

    # Splitting the grown inventory from nothing moves the locked test match into train.
    moved = tmp_path / "moved.csv"
    assert run(capsys, "make-splits", "--inventory", grown, "--out", moved, "--seed", 0)[0] == 0
    code, out, _ = run(capsys, "check-leakage", first, moved)
    assert code == 1
    assert f"video_id 'V2' is in different splits across the files (train: {moved}; test: {first})" in out
    assert f"match_group_id 'M02' is in different splits across the files (train: {moved}; test: {first})" in out
    assert "2 files compared; 2 problem(s)" in out

    second = tmp_path / "TEST_LOCK_V2.csv"
    code, out, _ = run(capsys, "make-splits", "--inventory", grown, "--previous", first, "--out", second, "--seed", 0)
    assert code == 0
    assert f"kept 3 video(s) as locked in {first}; assigned 1 new video(s)" in out
    after = {video.video_id: video.split for video in splits.read_lock_manifest(second).videos}
    assert {video_id: after[video_id] for video_id in before} == before and set(after) == set(before) | {"V4"}
    code, out, _ = run(capsys, "check-leakage", first, second)
    assert code == 0 and "2 files compared; 0 problem(s)" in out


def test_make_splits_names_locked_videos_that_left_the_inventory(tmp_path, capsys):
    rows = [(f"V{number}", f"M0{number}", "true", "") for number in (1, 2, 3)]
    first = tmp_path / "first.csv"
    assert run(capsys, "make-splits", "--inventory", inventory(tmp_path, rows), "--out", first)[0] == 0
    smaller = inventory(tmp_path, rows[1:], "smaller.csv")
    code, out, _ = run(capsys, "make-splits", "--inventory", smaller, "--previous", first, "--out", tmp_path / "second.csv")
    assert code == 0
    assert "kept 2 video(s) as locked" in out
    assert f"not in the inventory any more, so not in the new lock: V1" in out


def test_make_splits_refuses_a_previous_lock_it_can_not_trust(tmp_path, capsys):
    rows = [(f"V{group}", f"M{group}", "true", "") for group in range(5)]
    source = inventory(tmp_path, rows)
    first = tmp_path / "first.csv"
    assert run(capsys, "make-splits", "--inventory", source, "--out", first)[0] == 0
    text = first.read_text(encoding="utf-8")

    edited = tmp_path / "edited.csv"
    edited.write_text(text.replace(",train,", ",validation,", 1), encoding="utf-8")
    code, out, err = run(capsys, "make-splits", "--inventory", source, "--previous", edited, "--out", tmp_path / "a.csv")
    assert code == 2 and out == "" and f"{edited}: the manifest was changed after it was locked" in err

    bare = tmp_path / "bare.csv"
    bare.write_text("\n".join(text.splitlines()[:-1]) + "\n", encoding="utf-8")
    code, out, err = run(capsys, "make-splits", "--inventory", source, "--previous", bare, "--out", tmp_path / "b.csv")
    assert code == 2 and out == "" and f"{bare}: the manifest has no content_sha256 line" in err

    assert_unusable(run(capsys, "make-splits", "--inventory", source, "--previous", source, "--out", tmp_path / "c.csv"),
                    f"{source}: not a lock manifest")
    assert not any((tmp_path / name).exists() for name in ("a.csv", "b.csv", "c.csv"))


def test_inventory_in_another_encoding_is_refused_with_one_line(tmp_path, capsys):
    source = inventory(tmp_path, [("V1", "M1", "true", "train"), ("V2", "M1", "true", "test")])
    latin = tmp_path / "latin.csv"
    latin.write_bytes(source.read_text(encoding="utf-8-sig").replace(",train,", ",train,İkinci kamera ışık düşük")
                      .encode("cp1254"))
    for command in (["check-leakage", latin], ["make-splits", "--inventory", latin, "--out", tmp_path / "lock.csv"]):
        assert_unusable(run(capsys, *command), f"{latin}: the file is not UTF-8 text")
    assert not (tmp_path / "lock.csv").exists()


def test_check_leakage_reads_an_inventory_and_reports_leaks_and_training_violations(tmp_path, capsys):
    clean = inventory(tmp_path, [("V1", "M1", "true", "train"), ("V2", "M1", "true", "train"),
                                 ("V3", "M2", "false", "test"), ("V4", "M3", "true", "")], "clean.csv")
    code, out, _ = run(capsys, "check-leakage", clean)
    assert code == 0 and "3 of 4 video(s) have a split; 0 problem(s)" in out

    leaky = inventory(tmp_path, [("V1", "M1", "true", "train"), ("V2", "M1", "true", "test"),
                                 ("V3", "M2", "false", "train")], "leaky.csv")
    code, out, _ = run(capsys, "check-leakage", leaky)
    assert code == 1
    assert "video 'V3' is in the train split but its training_allowed is not true" in out
    assert "match_group_id 'M1' is in 2 splits (train: V1; test: V2)" in out
    assert "2 problem(s)" in out


def test_make_splits_refuses_an_inventory_it_can_not_group(tmp_path, capsys):
    source = inventory(tmp_path, [("V1", "", "true", ""), ("V2", "M2", "true", "")])
    code, out, err = run(capsys, "make-splits", "--inventory", source, "--out", tmp_path / "lock.csv")
    assert code == 2 and out == ""
    assert "match_group_id is empty" in err
    assert not (tmp_path / "lock.csv").exists()


# --- agreement -------------------------------------------------------------------------

LABEL_COLUMNS = ["video_id", "start_ms", "end_ms", "serving_side", "winning_side", "observation_status"]


def labelled(video_id, rallies):
    """rallies: (start_ms, end_ms, winning_side, serving_side or None)"""
    return {
        "annotationVersion": "VVV_ANNOTATION_V3", "videoId": video_id,
        "rallies": [
            {"rallyId": f"R{index}", "startMs": start, "endMs": end, "winningSide": winner,
             "observationStatus": "OBSERVED", "uncertaintyReason": "UNKNOWN_OTHER",
             **({} if serving is None else {"servingSide": serving})}
            for index, (start, end, winner, serving) in enumerate(rallies, start=1)
        ],
    }


def test_agreement_of_identical_labels_in_csv_and_json(tmp_path, capsys):
    spans = [(0, 8000, "HOME", "AWAY"), (12000, 20000, "AWAY", "HOME"), (25000, 31000, "HOME", "HOME")]
    a = write_rows(tmp_path / "a.csv", LABEL_COLUMNS,
                   [("V1", start, end, serving, winner, "OBSERVED") for start, end, winner, serving in spans])
    b = write_json(tmp_path / "b.json", labelled("V1", spans))

    code, out, err = run(capsys, "agreement", "--a", a, "--b", b)
    assert (code, err) == (0, "")
    assert "tolerance +/-1000 ms" in out
    assert "videos compared: 1 (V1)" in out
    assert "rallies: A 3   B 3   matched pairs 3   only in A 0   only in B 0" in out
    assert "start difference over 3 matched pair(s): median 0 ms, p90 0 ms" in out
    assert "mean interval IoU of matched pairs: 1.000" in out
    assert "winning side: 3/3 agree\n" in out
    assert "serving side: 3/3 agree\n" in out
    assert "n = 3 compared pairs is below 30" in out
    assert "disagreements for the adjudicator: 0 row(s)" in out
    # No adjective and no threshold: the tool measures, the owner decides.
    for word in ("good", "sufficient", "excellent", "poor", "substantial", "moderate", "pass", "fail"):
        assert word not in out.lower()


def test_agreement_below_thirty_pairs_prints_counts_and_writes_null_ratios(tmp_path, capsys):
    rows_a = [("V1", index * 20000, index * 20000 + 8000, "", "HOME" if index % 2 else "AWAY", "OBSERVED")
              for index in range(20)]
    # B disagrees on the winner of two rallies and leaves one winner empty.
    rows_b = [list(row) for row in rows_a]
    rows_b[3][4], rows_b[4][4], rows_b[5][4] = "AWAY", "HOME", ""
    a = write_rows(tmp_path / "a.csv", LABEL_COLUMNS, rows_a)
    b = write_rows(tmp_path / "b.csv", LABEL_COLUMNS, rows_b)

    code, out, _ = run(capsys, "agreement", "--a", a, "--b", b)
    assert code == 0
    assert "winning side: 17/19 agree\n" in out
    assert "  not comparable, one annotator left the field empty: 1 pair(s)" in out
    assert ("  n = 19 compared pairs is below 30: the agreement rate and Cohen's kappa are not reliable "
            "at this sample size and are not printed. Quote the counts.") in out
    assert "kappa 0" not in out and "(0." not in out
    assert "serving side: no matched pair has this field from both annotators" in out
    assert "  left empty by both annotators: 20 pair(s)" in out

    code, out, _ = run(capsys, "agreement", "--a", a, "--b", b, "--json")
    document = json.loads(out)
    assert code == 0
    winner = document["fields"]["winningSide"]
    assert winner == {
        "compared": 19, "agree": 17, "notComparable": 1, "bothEmpty": 0, "agreementRate": None, "kappa": None,
        "kappaUndefinedReason": None, "percentagesReliable": False, "minReliable": 30,
    }
    serving = document["fields"]["servingSide"]
    assert (serving["compared"], serving["bothEmpty"], serving["agreementRate"], serving["kappa"]) == (0, 20, None, None)
    assert document["rallies"] == {"a": 20, "b": 20, "matched": 20, "onlyA": 0, "onlyB": 0}
    assert document["videos"]["V1"]["fields"]["winningSide"]["agree"] == 17


def test_agreement_from_thirty_pairs_prints_ratio_and_kappa(tmp_path, capsys):
    rows_a = [(video, index * 20000, index * 20000 + 8000, "HOME", "HOME" if index % 2 else "AWAY", "OBSERVED")
              for video in ("V1", "V2") for index in range(20)]
    rows_b = [[video, start + 300, end, winner] for video, start, end, _, winner, _ in rows_a]
    # Two HOME turned AWAY and two AWAY turned HOME: B still says HOME 20 times and AWAY 20 times.
    for index in (1, 3, 22, 24):
        rows_b[index][3] = "AWAY" if rows_b[index][3] == "HOME" else "HOME"
    a = write_rows(tmp_path / "a.csv", LABEL_COLUMNS, rows_a)
    # B has no serving_side column at all.
    b = write_rows(tmp_path / "b.csv", ["video_id", "start_ms", "end_ms", "winning_side"], rows_b)

    code, out, _ = run(capsys, "agreement", "--a", a, "--b", b)
    assert code == 0
    assert "videos compared: 2 (V1, V2)" in out
    assert "rallies: A 40   B 40   matched pairs 40   only in A 0   only in B 0" in out
    assert "  V1: A 20   B 20   matched pairs 20   only in A 0   only in B 0" in out
    assert "start difference over 40 matched pair(s): median 300 ms, p90 300 ms" in out
    assert "end difference over 40 matched pair(s):   median 0 ms, p90 0 ms" in out
    # p_o = 36/40 = 0.9. Each side says HOME 20 and AWAY 20 times: p_e = 0.5 * 0.5 + 0.5 * 0.5 = 0.5.
    # kappa = (0.9 - 0.5) / (1 - 0.5) = 0.8.
    assert "winning side: 36/40 agree (0.900); Cohen's kappa 0.800" in out
    assert "below 30" not in out.split("serving side")[0]
    assert "serving side: no matched pair has this field from both annotators" in out
    assert "  not comparable, one annotator left the field empty: 40 pair(s)" in out
    assert "disagreements for the adjudicator: 4 row(s)" in out

    document = json.loads(run(capsys, "agreement", "--a", a, "--b", b, "--json")[1])
    winner = document["fields"]["winningSide"]
    assert winner["percentagesReliable"] is True and winner["minReliable"] == 30
    assert winner["agreementRate"] == pytest.approx(0.9)
    assert winner["kappa"] == pytest.approx(0.8)
    # Each video has only 20 pairs and carries no ratio.
    assert document["videos"]["V2"]["fields"]["winningSide"]["agreementRate"] is None
    assert document["boundaries"]["medianAbsStartDiffMs"] == 300


def test_agreement_says_why_kappa_is_undefined(tmp_path, capsys):
    rows = [("V1", index * 20000, index * 20000 + 8000, "", "HOME", "OBSERVED") for index in range(30)]
    a = write_rows(tmp_path / "a.csv", LABEL_COLUMNS, rows)
    b = write_rows(tmp_path / "b.csv", LABEL_COLUMNS, rows)
    code, out, _ = run(capsys, "agreement", "--a", a, "--b", b)
    assert code == 0
    line = next(line for line in out.splitlines() if line.startswith("winning side:"))
    assert line.startswith("winning side: 30/30 agree (1.000); Cohen's kappa not defined: ")
    assert "HOME" in line and "expected agreement is 1" in line
    assert "kappa 0" not in out and "kappa 1" not in out

    document = json.loads(run(capsys, "agreement", "--a", a, "--b", b, "--json")[1])
    winner = document["fields"]["winningSide"]
    assert winner["kappa"] is None and winner["agreementRate"] == 1.0
    assert "expected agreement is 1" in winner["kappaUndefinedReason"]


def test_agreement_lists_videos_only_one_annotator_labelled(tmp_path, capsys):
    a = [write_json(tmp_path / "a1.json", labelled("V1", [(0, 8000, "HOME", None)])),
         write_json(tmp_path / "a2.json", labelled("V2", [(0, 8000, "HOME", None), (9000, 15000, "AWAY", None)]))]
    b = write_rows(tmp_path / "b.csv", LABEL_COLUMNS,
                   [("V1", 200, 8000, "", "HOME", "OBSERVED"), ("V3", 0, 4000, "", "AWAY", "OBSERVED")])
    code, out, _ = run(capsys, "agreement", "--a", *a, "--b", b)
    assert code == 0
    assert "videos compared: 1 (V1)" in out
    assert "not compared, labelled by annotator A only: V2" in out
    assert "not compared, labelled by annotator B only: V3" in out
    assert "rallies: A 1   B 1   matched pairs 1   only in A 0   only in B 0" in out

    document = json.loads(run(capsys, "agreement", "--a", *a, "--b", b, "--json")[1])
    assert document["notCompared"] == {"onlyA": ["V2"], "onlyB": ["V3"]}
    assert list(document["videos"]) == ["V1"]


def test_agreement_refuses_input_without_a_common_video(tmp_path, capsys):
    a = write_json(tmp_path / "a.json", labelled("V1", [(0, 8000, "HOME", None)]))
    b = write_json(tmp_path / "b.json", labelled("V2", [(0, 8000, "HOME", None)]))
    assert_unusable(run(capsys, "agreement", "--a", a, "--b", b), "no video id appears in the files of both annotators")
    assert_unusable(run(capsys, "agreement", "--a", a, "--b", tmp_path / "missing.csv"), "No such file")
    assert_unusable(run(capsys, "agreement", "--a", a, "--b", b, "--tolerance-ms", -1), "argument --tolerance-ms: ")


@pytest.mark.parametrize("side", ["--a", "--b"])
def test_agreement_holds_both_annotators_to_the_gold_rules(tmp_path, capsys, side):
    clean = write_rows(tmp_path / "clean.csv", LABEL_COLUMNS, [("V1", 4000, 10000, "", "HOME", "OBSERVED")])
    rows = [("V1", 4000, 10000, "", "HOME", "OBSERVED"), ("V1", 4000, 10000, "", "HOME", "OBSERVED"),
            ("V1", 16000, 24000, "", "AWAY", "OBSERVED"), ("V1", 17000, 23000, "", "AWAY", "OBSERVED")]
    broken = write_rows(tmp_path / "broken.csv", LABEL_COLUMNS, rows)
    other = "--b" if side == "--a" else "--a"
    label = "A" if side == "--a" else "B"
    code, out, err = run(capsys, "agreement", side, broken, other, clean)
    assert code == 2 and out == ""
    assert err.splitlines() == [
        f"error: {broken}: the intervals of annotator {label} are not valid:",
        "  row 3: start_ms (4000) is not after start_ms (4000) of row 2; rallies must be listed in time order",
        "  row 3: start_ms (4000) is before end_ms (10000) of row 2; rallies must not overlap",
        "  row 5: start_ms (17000) is before end_ms (24000) of row 4; rallies must not overlap",
    ]

    document = labelled("V1", [(5000, 1000, "HOME", None)])
    as_json = write_json(tmp_path / "broken.json", document)
    code, out, err = run(capsys, "agreement", side, as_json, other, clean)
    assert code == 2 and out == ""
    assert f"{as_json}: the annotation of annotator {label} is not valid" in err
    assert "$.rallies[0]: startMs (5000) must be less than endMs (1000)" in err


def test_agreement_writes_the_disagreements_for_the_adjudicator(tmp_path, capsys):
    a = write_rows(tmp_path / "a.csv", LABEL_COLUMNS, [
        ("V2", 0, 8000, "HOME", "HOME", "OBSERVED"), ("V2", 20000, 26000, "", "AWAY", "OBSERVED"),
        ("V1", 5000, 9000, "", "HOME", "OBSERVED"),
    ])
    b = write_json(tmp_path / "b1.json", labelled("V1", [(1000, 3000, "HOME", None), (5000, 9000, "AWAY", None)]))
    b2 = write_json(tmp_path / "b2.json", labelled("V2", [(0, 8000, "AWAY", "AWAY"), (12000, 15000, "HOME", None)]))
    out_file = tmp_path / "disagreements.csv"

    code, out, _ = run(capsys, "agreement", "--a", a, "--b", b, b2, "--out", out_file)
    assert code == 0
    assert "disagreements for the adjudicator: 6 row(s)" in out
    assert f"wrote {out_file}" in out
    with open(out_file, encoding="utf-8", newline="") as handle:
        rows = list(csv.reader(handle))
    assert rows == [
        ["video_id", "kind", "a_start_ms", "a_end_ms", "b_start_ms", "b_end_ms", "a_value", "b_value"],
        ["V1", "ONLY_B", "", "", "1000", "3000", "", ""],
        ["V1", "WINNER", "5000", "9000", "5000", "9000", "HOME", "AWAY"],
        ["V2", "WINNER", "0", "8000", "0", "8000", "HOME", "AWAY"],
        ["V2", "SERVING", "0", "8000", "0", "8000", "HOME", "AWAY"],
        ["V2", "ONLY_B", "", "", "12000", "15000", "", ""],
        ["V2", "ONLY_A", "20000", "26000", "", "", "", ""],
    ]

    # An existing file is never overwritten, and nothing is printed for a run that wrote nothing.
    before = out_file.read_bytes()
    code, out, err = run(capsys, "agreement", "--a", a, "--b", b, b2, "--out", out_file)
    assert (code, out) == (2, "") and "File exists" in err and err.startswith("error: ")
    assert out_file.read_bytes() == before
    assert_unusable(run(capsys, "agreement", "--a", a, "--b", b, b2, "--out", tmp_path / "missing" / "d.csv"),
                    "the folder does not exist")
    assert_unusable(run(capsys, "agreement", "--a", a, "--b", b, b2, "--out", tmp_path), "is a directory")

    # With --json the file is written the same way and its path is in the document.
    second = tmp_path / "second.csv"
    document = json.loads(run(capsys, "agreement", "--a", a, "--b", b, b2, "--out", second, "--json")[1])
    assert document["disagreements"] == 6 and document["disagreementsFile"] == str(second)
    assert second.read_bytes() == before


def test_a_command_is_required():
    with pytest.raises(SystemExit) as stopped:
        cli.main([])
    assert stopped.value.code == 2


@pytest.mark.parametrize("command", ["segment", "evaluate", "agreement", "validate-labels", "make-splits", "check-leakage"])
def test_every_subcommand_is_reachable_through_the_module(command):
    completed = subprocess.run([sys.executable, "-m", "volleystat_ml", command, "--help"], capture_output=True, text=True)
    assert completed.returncode == 0, completed.stderr
    assert f"usage: python -m volleystat_ml {command}" in completed.stdout


def test_unknown_subcommand_is_refused_by_the_module():
    completed = subprocess.run([sys.executable, "-m", "volleystat_ml", "train"], capture_output=True, text=True)
    assert completed.returncode == 2 and "invalid choice: 'train'" in completed.stderr


def test_agreement_says_that_kappa_is_undefined_below_thirty_pairs_too(tmp_path, capsys):
    # Undefined is not the same as unreliable: with one single value kappa does not exist,
    # however many pairs there are, and the small-sample note must not suggest otherwise.
    rows = [("V1", index * 10000, index * 10000 + 5000, "HOME") for index in range(20)]
    header = ("video_id", "start_ms", "end_ms", "winning_side")
    first, second = write_rows(tmp_path / "a.csv", header, rows), write_rows(tmp_path / "b.csv", header, rows)

    code, out, _ = run(capsys, "agreement", "--a", first, "--b", second)
    assert code == 0
    assert "winning side: 20/20 agree\n" in out
    assert "Cohen's kappa not defined: both annotators gave the single value HOME" in out
    assert "the agreement rate is not reliable at this sample size and is not printed" in out
    assert "Cohen's kappa are not reliable" not in out


def test_agreement_refuses_a_label_value_the_schema_does_not_know(tmp_path, capsys):
    # A misspelt value would be compared as a category of its own, and a misspelt
    # NOT_ANNOTATED would turn a placeholder into a disagreement.
    header = ("video_id", "start_ms", "end_ms", "serving_side", "winning_side", "observation_status")
    good = write_rows(tmp_path / "b.csv", header, [("V1", 0, 5000, "HOME", "HOME", "OBSERVED"), ("V1", 10000, 15000, "", "AWAY", "")])
    bad = write_rows(tmp_path / "a.csv", header, [("V1", 0, 5000, "HOME", "UNKNOWN", "not_annotated"), ("V1", 10000, 15000, "Away", "home", "OBSERVED")])

    for arguments in (("--a", bad, "--b", good), ("--a", good, "--b", bad)):
        code, out, err = run(capsys, "agreement", *arguments)
        assert (code, out) == (2, "")
        assert "the labels of annotator" in err and "Traceback" not in err
        assert "row 2: observation_status must be one of OBSERVED, INFERRED, UNKNOWN, NOT_ANNOTATED, NOT_APPLICABLE or empty, found 'not_annotated'" in err
        assert "row 3: serving_side must be one of HOME, AWAY, UNKNOWN or empty, found 'Away'" in err
        assert "row 3: winning_side must be one of HOME, AWAY, UNKNOWN or empty, found 'home'" in err

    # The same values are no reason to refuse a gold file of `evaluate`, which reads the intervals only.
    code, _, _ = run(capsys, "evaluate", "--gold", bad, "--pred", good)
    assert code == 0


def test_agreement_prints_the_counts_of_each_video(tmp_path, capsys):
    header = ("video_id", "start_ms", "end_ms", "winning_side")
    first = write_rows(tmp_path / "a.csv", header, [(video, index * 10000, index * 10000 + 5000, "HOME") for video in ("V1", "V2") for index in range(3)])
    second = write_rows(tmp_path / "b.csv", header, [("V1", index * 10000, index * 10000 + 5000, "AWAY" if index == 0 else "HOME") for index in range(3)]
                        + [("V2", index * 10000 + 5000, index * 10000 + 10000, "HOME") for index in range(3)])

    code, out, _ = run(capsys, "agreement", "--a", first, "--b", second)
    assert code == 0
    assert "  V1: A 3   B 3   matched pairs 3   only in A 0   only in B 0\n    winning side 2/3 agree; serving side not compared\n" in out
    assert "  V2: A 3   B 3   matched pairs 0   only in A 3   only in B 3\n    winning side not compared; serving side not compared\n" in out
