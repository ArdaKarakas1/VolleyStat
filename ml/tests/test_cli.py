import csv
import hashlib
import json
import subprocess
import sys

import pytest

from volleystat_ml import cli, splits


def run(capsys, *arguments):
    code = cli.main([str(argument) for argument in arguments])
    captured = capsys.readouterr()
    return code, captured.out, captured.err


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
    assert document["analyzer"] == {"id": "motion-baseline", "version": "0.1", "kind": "BASELINE"}
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

    code, out, err = run(capsys, "segment", clips.moving_box, "--sample-fps", 0)
    assert code == 2 and out == "" and "sample_fps must be a positive number" in err


def test_segment_fails_clearly_on_an_unreadable_file(clips, capsys):
    code, out, err = run(capsys, "segment", clips.not_a_video)
    assert code == 2 and out == "" and err.startswith("error: ")


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
    assert document["percentagesReliable"] is False
    assert document["recall"] == {"value": 1.0, "numerator": 3, "denominator": 3}
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
    assert "start error over 36 matched pair(s): median 150 ms, p90 300 ms" in out

    first = run(capsys, "evaluate", "--gold", gold, "--pred", pred, "--seed", 11, "--json")[1]
    assert first == run(capsys, "evaluate", "--gold", gold, "--pred", pred, "--seed", 11, "--json")[1]
    interval = json.loads(first)["bootstrap"]["recall"]
    assert 0 < interval["low"] < 0.9 < interval["high"] <= 1 and interval["degenerate"] is False


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

    code, _, err = run(capsys, "make-splits", "--inventory", source, "--out", tmp_path / "other.csv", "--ratios", "0.5,0.5")
    assert code == 2 and "--ratios needs three numbers" in err
    code, _, err = run(capsys, "make-splits", "--inventory", source, "--out", tmp_path / "other.csv", "--ratios", "0.5,0.3,0.3")
    assert code == 2 and "sum to 1" in err
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


def test_a_command_is_required():
    with pytest.raises(SystemExit) as stopped:
        cli.main([])
    assert stopped.value.code == 2
