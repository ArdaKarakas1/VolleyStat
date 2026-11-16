"""Tests of `python -m volleystat_ml evaluate-winner`. Every label and every proposal
here is made up: no winner component exists, so these tests prove the plumbing only."""
import csv
import hashlib
import json

import pytest

from test_cli import assert_unusable, run, write_json, write_rows
from volleystat_ml import splits

GOLD_HEADER = ["video_id", "start_ms", "end_ms", "winning_side", "observation_status", "ground_truth_status"]
ANALYZER = {"id": "next-serve-rule", "version": "0.1", "kind": "BASELINE"}


def at(index):
    return index * 10_000, index * 10_000 + 5_000


def gold_rows(video_id, sides, status="DOUBLE_AGREED", observation="OBSERVED"):
    return [[video_id, *at(index), side, observation if side != "UNKNOWN" else "UNKNOWN", status]
            for index, side in enumerate(sides)]


def gold_file(tmp_path, rows, name="gold.csv", header=GOLD_HEADER):
    return write_rows(tmp_path / name, header, rows)


def proposals(video_id, items, analyzer=ANALYZER, **extra):
    """`items`: (rally number, proposed winner) or (rally number, proposed winner, confidence)."""
    document = {"videoId": video_id, "mock": False, "rallies": [
        {"startMs": at(item[0])[0], "endMs": at(item[0])[1], "proposedWinner": item[1],
         "confidence": item[2] if len(item) > 2 else None}
        for item in items
    ], **extra}
    if analyzer is not None:
        document["analyzer"] = analyzer
    return document


def pred_file(tmp_path, video_id, items, name=None, **options):
    return write_json(tmp_path / (name or f"pred_{video_id}.json"), proposals(video_id, items, **options))


def forty(tmp_path, status="DOUBLE_AGREED"):
    """40 eligible rallies; 27 correct, 3 wrong, 6 abstentions, 4 not proposed."""
    sides = ["HOME" if index % 2 else "AWAY" for index in range(40)]
    gold = gold_file(tmp_path, gold_rows("V1", sides, status))
    other = {"HOME": "AWAY", "AWAY": "HOME"}
    items = ([(index, sides[index]) for index in range(27)] + [(index, other[sides[index]]) for index in range(27, 30)]
             + [(index, "UNKNOWN") for index in range(30, 36)])
    return gold, pred_file(tmp_path, "V1", items)


def evaluate(capsys, *arguments):
    return run(capsys, "evaluate-winner", *arguments)


def as_json(capsys, *arguments):
    code, out, err = evaluate(capsys, *arguments, "--json")
    assert code == 0, err
    return json.loads(out)


# --- a pilot run -------------------------------------------------------------------------

def test_a_run_without_a_manifest_is_a_pilot_run_and_says_so_first(tmp_path, capsys):
    gold, pred = forty(tmp_path)
    code, out, _ = evaluate(capsys, "--gold", gold, "--pred", pred)
    assert code == 0
    lines = out.splitlines()
    assert lines[0] == "PILOT RUN, NOT A GATE CHECK"
    assert lines[1] == "  - no lock manifest was given, so the tool can not tell that these are the locked test videos"
    assert "Rally winner evaluation, rallies paired at +/-1000 ms on start and on end" in out
    assert "analyzer: next-serve-rule 0.1" in out
    assert "videos evaluated: 1 (V1)" in out
    assert "gold rallies: 40   predicted rallies: 36   automatic proposals: 30" in out
    assert "layer GOLD (double-agreed or adjudicated labels): 40 eligible rallies in 1 video(s)" in out
    assert "  correct 27   wrong 3   UNKNOWN 6   not proposed 4" in out
    assert "  selective precision: 27/30 automatic decisions are correct (0.900)" in out
    assert "  automatic coverage: 30/40 eligible rallies got an automatic decision (0.750)" in out
    assert "  UNKNOWN from the component: 6/40 (0.150)" in out
    assert "  not proposed: 4/40 (0.100)" in out
    assert "layer SINGLE (one annotator, or no status given): no eligible rally" in out
    # A pilot run is compared with no target, and nothing reads as a verdict.
    assert "G3 targets: not checked, this is a pilot run" in out
    assert "PASS" not in out and "FAIL" not in out and "met by" not in out
    assert out.rstrip().endswith("The gate decision is made and signed by a person.")


def test_the_json_of_a_pilot_run_carries_the_same_numbers_and_no_comparison(tmp_path, capsys):
    gold, pred = forty(tmp_path)
    document = as_json(capsys, "--gold", gold, "--pred", pred)
    assert document["runKind"] == "PILOT"
    assert document["reasons"] == ["no lock manifest was given, so the tool can not tell that these are the locked test videos"]
    assert document["g3"] == {"checked": False, "reasons": document["reasons"]}
    assert (document["toleranceMs"], document["highConfidenceLevel"], document["minReliable"]) == (1000, 0.9, 30)
    assert document["analyzer"] == {"id": "next-serve-rule", "version": "0.1"}
    assert (document["split"], document["manifestSha256"], document["manifestDigestCompared"], document["matchGroups"]) == (None, None, False, None)
    assert document["videosEvaluated"] == ["V1"]
    assert (document["goldRallies"], document["predictedRallies"], document["automaticProposals"]) == (40, 36, 30)
    layer = document["layers"]["GOLD"]
    assert (layer["eligibleN"], layer["autoN"], layer["correct"], layer["wrong"], layer["unknown"], layer["notProposed"]) == (40, 30, 27, 3, 6, 4)
    assert layer["selectivePrecision"]["value"] == 0.9
    assert layer["selectivePrecision"]["interval"]["method"] == "wilson"
    assert layer["autoCoverage"] == {
        "value": 0.75, "numerator": 30, "denominator": 40, "reliable": True,
        "interval": {"low": pytest.approx(0.5981, abs=1e-4), "high": pytest.approx(0.8581, abs=1e-4),
                     "confidence": 0.95, "method": "wilson", "unit": "rally"}}
    assert document["layers"]["SINGLE"]["eligibleN"] == 0
    assert document["notEvaluated"] == {"goldOnly": [], "predictedOnly": [], "manifestOnly": []}
    assert document["failuresFile"] is None
    assert document["inputs"] == {
        "gold": [{"file": str(gold), "sha256": hashlib.sha256(gold.read_bytes()).hexdigest()}],
        "pred": [{"file": str(pred), "sha256": hashlib.sha256(pred.read_bytes()).hexdigest()}],
    }


def test_below_thirty_only_the_counts_and_the_interval_are_printed(tmp_path, capsys):
    sides = ["HOME"] * 20
    gold = gold_file(tmp_path, gold_rows("V1", sides))
    pred = pred_file(tmp_path, "V1", [(index, "HOME" if index < 18 else "AWAY") for index in range(20)])
    code, out, _ = evaluate(capsys, "--gold", gold, "--pred", pred)
    assert code == 0
    assert "  selective precision: 18/20 automatic decisions are correct\n" in out
    assert "    95% Wilson interval: 0.699 to 0.972" in out
    assert "  automatic coverage: 20/20 eligible rallies got an automatic decision\n" in out
    assert "(0.900)" not in out and "(1.000)" not in out
    assert "n = 20 is below 30: a percentage is not reliable at this sample size and is not printed. Quote the counts." in out
    layer = as_json(capsys, "--gold", gold, "--pred", pred)["layers"]["GOLD"]
    assert layer["selectivePrecision"]["value"] is None
    assert layer["selectivePrecision"]["interval"]["low"] == pytest.approx(0.699, abs=1e-3)


def test_labels_of_one_annotator_are_reported_in_their_own_layer(tmp_path, capsys):
    gold, pred = forty(tmp_path, status="SINGLE_HUMAN")
    code, out, _ = evaluate(capsys, "--gold", gold, "--pred", pred)
    assert code == 0
    assert "  - no eligible rally carries a double-agreed or adjudicated label" in out
    assert "layer GOLD (double-agreed or adjudicated labels): no eligible rally" in out
    assert "layer SINGLE (one annotator, or no status given): 40 eligible rallies in 1 video(s)" in out
    document = as_json(capsys, "--gold", gold, "--pred", pred)
    assert document["layers"]["GOLD"]["eligibleN"] == 0
    assert document["layers"]["SINGLE"]["selectivePrecision"]["value"] == 0.9


def test_annotation_json_without_a_status_counts_as_one_annotator(tmp_path, capsys):
    annotation = {"annotationVersion": "VVV_ANNOTATION_V3", "videoId": "V1", "rallies": [
        {"rallyId": f"R{index}", "startMs": at(index)[0], "endMs": at(index)[1], "winningSide": "HOME",
         "observationStatus": "OBSERVED", "reviewStatus": "ACCEPTED"} for index in range(3)]}
    annotation["rallies"][2]["reviewStatus"] = "DISPUTED"
    gold = write_json(tmp_path / "gold.json", annotation)
    pred = pred_file(tmp_path, "V1", [(0, "HOME"), (1, "AWAY"), (2, "HOME")])
    document = as_json(capsys, "--gold", gold, "--pred", pred)
    assert document["layers"]["SINGLE"]["eligibleN"] == 2
    assert document["excluded"] == {"REVIEW_DISPUTED": 1}
    assert document["notScored"]["onIneligibleGold"] == {"REVIEW_DISPUTED": 1}

    for rally, status in zip(annotation["rallies"], ("DOUBLE_AGREED", "ADJUDICATED", "UNRESOLVABLE")):
        rally["groundTruthStatus"] = status
    document = as_json(capsys, "--gold", write_json(tmp_path / "gold.json", annotation), "--pred", pred)
    assert document["layers"]["GOLD"]["eligibleN"] == 2
    assert document["excluded"] == {"UNRESOLVABLE": 1}


def test_segment_like_output_measures_nothing_about_a_winner(tmp_path, capsys):
    gold = gold_file(tmp_path, gold_rows("V1", ["HOME"] * 40))
    pred = pred_file(tmp_path, "V1", [(index, "UNKNOWN") for index in range(28)],
                     analyzer={"id": "motion-energy", "version": "0.1", "kind": "BASELINE"})
    code, out, _ = evaluate(capsys, "--gold", gold, "--pred", pred)
    assert code == 0
    assert ("the prediction files contain no HOME or AWAY proposal, so nothing about a winner component was measured"
            in out)
    assert "  correct 0   wrong 0   UNKNOWN 28   not proposed 12" in out
    # Not "0/40 (0.000)": a coverage of zero would read as a measured component.
    assert "automatic coverage" not in out and "selective precision" not in out and "Wilson" not in out
    document = as_json(capsys, "--gold", gold, "--pred", pred)
    assert document["notMeasuredReason"].startswith("the prediction files contain no HOME or AWAY proposal")
    assert document["layers"]["GOLD"]["autoCoverage"] == {
        "value": None, "numerator": 0, "denominator": 40, "reliable": False, "interval": None}


def test_the_output_of_segment_is_read_without_edits(clips, tmp_path, capsys):
    pred = tmp_path / "pred.json"
    assert run(capsys, "segment", clips.concatenated, "--video-id", "SYN-2", "--out", pred, "--quiet")[0] == 0
    gold = gold_file(tmp_path, [["SYN-2", start, end, "HOME", "OBSERVED", "SINGLE_HUMAN"] for start, end in clips.rallies_ms])
    code, out, err = evaluate(capsys, "--gold", gold, "--pred", pred)
    assert code == 0, err
    assert "analyzer: motion-baseline 0.2" in out
    assert "nothing about a winner component was measured" in out
    assert "layer SINGLE (one annotator, or no status given): 3 eligible rallies in 1 video(s)" in out


# --- what is excluded and what is not scored ---------------------------------------------

def test_excluded_gold_and_unscored_decisions_are_counted_and_stay_out_of_the_ratios(tmp_path, capsys):
    rows = gold_rows("V1", ["HOME"] * 32)
    rows += [["V1", *at(32), "UNKNOWN", "UNKNOWN", "UNRESOLVABLE"], ["V1", *at(33), "UNKNOWN", "UNKNOWN", "DOUBLE_AGREED"],
             ["V1", *at(34), "HOME", "OBSERVED", "NEEDS_MORE_EVIDENCE"], ["V1", *at(35), "", "NOT_ANNOTATED", ""]]
    gold = gold_file(tmp_path, rows)
    items = [(index, "HOME") for index in range(31)] + [(31, "AWAY"), (32, "HOME"), (33, "AWAY"), (34, "UNKNOWN")]
    document = proposals("V1", items)
    # One decision 3 s late on rally 35 (overlaps, outside the tolerance), two where the gold has no rally.
    start, end = at(35)
    document["rallies"].append({"startMs": start + 3000, "endMs": end + 3000, "proposedWinner": "HOME", "confidence": None})
    document["rallies"] += proposals("V1", [(40, "HOME"), (41, "AWAY")])["rallies"]
    pred = write_json(tmp_path / "pred.json", document)

    code, out, _ = evaluate(capsys, "--gold", gold, "--pred", pred)
    assert code == 0
    assert ("gold rallies without a definite winner: 4 (1 NOT_ANNOTATED, 1 NEEDS_MORE_EVIDENCE, 1 UNRESOLVABLE, "
            "1 WINNER_UNKNOWN)") in out
    assert "  selective precision: 31/32 automatic decisions are correct (0.969)" in out
    assert "  automatic decisions that pair with no gold rally in these videos: 3 (not scored)" in out
    assert "    if each were a wrong decision: 31/35 (0.886), 95% Wilson interval 0.740 to 0.955" in out
    assert "not scored, automatic decisions on gold rallies without a definite winner: 2 (1 UNRESOLVABLE, 1 WINNER_UNKNOWN)" in out
    assert ("not scored, automatic decisions that pair with no gold rally within +/-1000 ms: 3 "
            "(1 overlap a gold rally whose boundary is further off, 2 lie where the gold has no rally)") in out

    result = as_json(capsys, "--gold", gold, "--pred", pred)
    assert result["excluded"] == {"NOT_ANNOTATED": 1, "NEEDS_MORE_EVIDENCE": 1, "UNRESOLVABLE": 1, "WINNER_UNKNOWN": 1}
    assert result["notScored"] == {
        "onIneligibleGold": {"UNRESOLVABLE": 1, "WINNER_UNKNOWN": 1},
        "unpaired": {"overlapsGold": 1, "noGoldThere": 2},
        "highConfidence": {"level": 0.9, "n": None},
    }
    layer = result["layers"]["GOLD"]
    assert (layer["eligibleN"], layer["autoN"], layer["correct"], layer["unpairedAutomatic"]) == (32, 32, 31, 3)
    assert (layer["lowerBoundPrecision"]["numerator"], layer["lowerBoundPrecision"]["denominator"]) == (31, 35)


def test_the_text_shows_what_was_proposed_for_each_gold_side(tmp_path, capsys):
    sides = ["HOME"] * 34 + ["AWAY"] * 6
    gold = gold_file(tmp_path, gold_rows("V1", sides))
    pred = pred_file(tmp_path, "V1", [(index, "HOME") for index in range(38)])
    code, out, _ = evaluate(capsys, "--gold", gold, "--pred", pred)
    assert code == 0
    # A component that always says HOME: the precision is the share of HOME in the gold.
    assert "  selective precision: 34/38 automatic decisions are correct (0.895)" in out
    assert "  gold HOME 34: proposed HOME 34, AWAY 0, UNKNOWN 0, not proposed 0" in out
    assert "  gold AWAY 6: proposed HOME 4, AWAY 0, UNKNOWN 0, not proposed 2" in out


def test_inferred_gold_winners_are_pointed_out(tmp_path, capsys):
    header = GOLD_HEADER + ["winner_evidence"]
    rows = [[*row, "VIDEO"] for row in gold_rows("V1", ["HOME"] * 3)]
    rows += [["V1", *at(3), "AWAY", "INFERRED", "DOUBLE_AGREED", "RULE_CONSTRAINT"],
             ["V1", *at(4), "AWAY", "INFERRED", "DOUBLE_AGREED", "SCOREBOARD"]]
    gold = gold_file(tmp_path, rows, header=header)
    pred = pred_file(tmp_path, "V1", [(index, "HOME") for index in range(5)])
    code, out, _ = evaluate(capsys, "--gold", gold, "--pred", pred)
    assert code == 0
    assert ("  gold winners that were inferred and not seen: 2 of 5 eligible (1 with the evidence RULE_CONSTRAINT). "
            "Such a winner may come from the rule under test.") in out
    layer = as_json(capsys, "--gold", gold, "--pred", pred)["layers"]["GOLD"]
    assert layer["byObservationStatus"]["INFERRED"] == {"eligibleN": 2, "autoN": 2, "correct": 0}
    assert layer["ruleConstraintEvidence"] == 1

    plain = gold_file(tmp_path, gold_rows("V1", ["HOME"] * 5), name="plain.csv")
    code, out, _ = evaluate(capsys, "--gold", plain, "--pred", pred)
    assert "inferred" not in out
    assert as_json(capsys, "--gold", plain, "--pred", pred)["layers"]["GOLD"]["ruleConstraintEvidence"] is None


def test_videos_present_on_one_side_only_are_listed_and_not_counted(tmp_path, capsys):
    gold = gold_file(tmp_path, gold_rows("V1", ["HOME"] * 2) + gold_rows("V2", ["HOME"] * 5))
    preds = [pred_file(tmp_path, "V1", [(0, "HOME"), (1, "HOME")]), pred_file(tmp_path, "V3", [(0, "HOME")])]
    code, out, _ = evaluate(capsys, "--gold", gold, "--pred", *preds)
    assert code == 0
    assert "videos evaluated: 1 (V1)" in out
    assert "not evaluated, no prediction file: V2" in out
    assert "not evaluated, no gold file: V3" in out
    assert "gold rallies: 2   predicted rallies: 2   automatic proposals: 2" in out
    document = as_json(capsys, "--gold", gold, "--pred", *preds)
    assert document["notEvaluated"] == {"goldOnly": ["V2"], "predictedOnly": ["V3"], "manifestOnly": []}

    assert_unusable(evaluate(capsys, "--gold", gold, "--pred", preds[1]),
                    "no video id appears in both the gold and the predicted files")


# --- calibration -------------------------------------------------------------------------

def test_a_component_without_confidences_has_no_calibration_and_no_confident_wrong_count(tmp_path, capsys):
    gold, pred = forty(tmp_path)
    code, out, _ = evaluate(capsys, "--gold", gold, "--pred", pred)
    assert code == 0
    assert "  calibration: not measured, no automatic decision carries a confidence" in out
    assert "  wrong with a confidence of 0.90 or more: not measured, no automatic decision carries a confidence" in out
    assert "  wrong automatic decisions without a confidence: 3 of 30" in out
    calibration = as_json(capsys, "--gold", gold, "--pred", pred)["layers"]["GOLD"]["calibration"]
    assert calibration["n"] == 0 and calibration["brier"] is None and calibration["ece"] is None
    assert calibration["highConfidence"]["wrong"] is None


def confident(tmp_path, count=40):
    """`count` decisions at 0.9 of which every tenth is wrong, and two at 0.55 (one wrong)."""
    gold = gold_file(tmp_path, gold_rows("V1", ["HOME"] * (count + 3)))
    items = [(index, "AWAY" if index % 10 == 9 else "HOME", 0.9) for index in range(count)]
    items += [(count, "HOME", 0.55), (count + 1, "AWAY", 0.55), (count + 2, "AWAY")]
    return gold, pred_file(tmp_path, "V1", items)


def test_calibration_is_reported_with_its_table_and_a_reference(tmp_path, capsys):
    gold, pred = confident(tmp_path)
    code, out, _ = evaluate(capsys, "--gold", gold, "--pred", pred, "--resamples", 300, "--seed", 5)
    assert code == 0
    assert "  calibration over 42 automatic decisions that carry a confidence (1 carry none):" in out
    assert "    Brier " in out and "95% bootstrap interval" in out
    assert "    ECE over 10 bins " in out
    assert "a perfectly calibrated component with these confidences shows" in out
    assert "    confidence 0.5 to 0.6: 2 decisions, mean confidence 0.550, 1 correct" in out
    assert "    confidence 0.9 to 1.0: 40 decisions, mean confidence 0.900, 36 correct" in out
    assert "  wrong with a confidence of 0.90 or more: 4 of 40" in out
    assert "  wrong automatic decisions without a confidence: 1 of 1" in out

    calibration = as_json(capsys, "--gold", gold, "--pred", pred, "--resamples", 300, "--seed", 5)["layers"]["GOLD"]["calibration"]
    assert calibration["n"] == 42 and calibration["reliable"] is True
    assert calibration["brier"]["value"] == pytest.approx((36 * 0.01 + 4 * 0.81 + 0.45 ** 2 + 0.55 ** 2) / 42)
    assert (calibration["brier"]["interval"]["resamples"], calibration["brier"]["interval"]["seed"]) == (300, 5)
    assert calibration["ece"] == pytest.approx(2 / 42 * 0.05)
    assert calibration["eceIfCalibrated"]["resamples"] == 300
    assert calibration["highConfidence"] == {"level": 0.9, "n": 40, "wrong": 4, "notMeasuredReason": None}
    assert (calibration["withoutConfidence"], calibration["wrongWithoutConfidence"]) == (1, 1)


def test_calibration_below_thirty_gives_the_table_and_no_brier_or_ece(tmp_path, capsys):
    gold, pred = confident(tmp_path, count=20)
    code, out, _ = evaluate(capsys, "--gold", gold, "--pred", pred)
    assert code == 0
    assert "    n = 22 is below 30: Brier and ECE are not reliable at this sample size and are not printed." in out
    assert "Brier 0." not in out and "ECE over" not in out
    assert "    confidence 0.9 to 1.0: 20 decisions, mean confidence 0.900, 18 correct" in out
    assert "  wrong with a confidence of 0.90 or more: 2 of 20" in out


def test_the_level_of_a_confident_decision_is_an_option_and_the_output_is_reproducible(tmp_path, capsys):
    gold, pred = confident(tmp_path)
    code, out, _ = evaluate(capsys, "--gold", gold, "--pred", pred, "--high-confidence", 0.5)
    assert "  wrong with a confidence of 0.50 or more: 5 of 42" in out
    first = evaluate(capsys, "--gold", gold, "--pred", pred, "--json", "--seed", 9)[1]
    assert first == evaluate(capsys, "--gold", gold, "--pred", pred, "--json", "--seed", 9)[1]
    assert first != evaluate(capsys, "--gold", gold, "--pred", pred, "--json", "--seed", 10)[1]


# --- the lock manifest and the G3 targets ------------------------------------------------

def lock(tmp_path, videos, name="lock.csv"):
    """`videos`: (video id, match group, split). Returns (path, content digest)."""
    rows = [splits.Video(video_id, group, hashlib.sha256(video_id.encode()).hexdigest(), split=split)
            for video_id, group, split in videos]
    path = tmp_path / name
    digest = splits.write_lock_manifest(path, rows, {video.video_id: video.split for video in rows},
                                        locked_at="2026-11-16T00:00:00Z")
    return path, digest


def gate_inputs(tmp_path, correct=130, wrong=5, unknown=65):
    """Two test videos of two matches; the given outcomes are split over them."""
    total = correct + wrong + unknown
    half = total // 2
    outcomes = ["C"] * correct + ["W"] * wrong + ["U"] * unknown
    rows, preds = [], []
    for video_id, part in (("V1", outcomes[:half]), ("V2", outcomes[half:])):
        rows += gold_rows(video_id, ["HOME"] * len(part))
        preds.append(pred_file(tmp_path, video_id, [
            (index, {"C": "HOME", "W": "AWAY", "U": "UNKNOWN"}[outcome]) for index, outcome in enumerate(part)]))
    manifest, digest = lock(tmp_path, [("V1", "M1", "test"), ("V2", "M2", "test"), ("V3", "M3", "validation")])
    return gold_file(tmp_path, rows), preds, manifest, digest


def test_a_run_on_the_whole_locked_test_split_is_input_for_the_gate_decision(tmp_path, capsys):
    gold, preds, manifest, digest = gate_inputs(tmp_path)
    arguments = ("--gold", gold, "--pred", *preds, "--manifest", manifest, "--expect-sha256", digest)
    code, out, _ = evaluate(capsys, *arguments)
    assert code == 0
    assert out.splitlines()[0] == (
        f"INPUT FOR THE G3 DECISION: locked test split {digest}, 2 video(s) in 2 match group(s), all evaluated")
    assert "PILOT" not in out
    assert f"manifest: split test, content_sha256 {digest} (compared with --expect-sha256), 2 of 2 videos of the split evaluated" in out
    assert "G3 targets on the GOLD layer: precision at least 0.95, coverage at least 0.60" in out
    assert "  precision 130/135 (0.963): point estimate at or above the target, interval reaches down to 0.916" in out
    assert "  coverage 135/200 (0.675): point estimate and whole interval at or above the target" in out
    assert "  the point estimates meet both targets" in out
    assert "  video ids were compared by name; file hashes were not verified" in out
    assert "PASS" not in out and "FAIL" not in out
    assert out.rstrip().endswith("The gate decision is made and signed by a person.")

    document = as_json(capsys, *arguments)
    assert (document["runKind"], document["reasons"]) == ("GATE_INPUT", [])
    assert (document["split"], document["manifestSha256"], document["manifestDigestCompared"], document["matchGroups"]) == ("test", digest, True, 2)
    g3 = document["g3"]
    assert (g3["checked"], g3["reasons"], g3["pointEstimatesMeetTargets"]) == (True, [], True)
    assert g3["targets"] == {"precision": 0.95, "coverage": 0.6}
    assert (g3["precision"]["pointEstimateMeetsTarget"], g3["precision"]["intervalLowMeetsTarget"]) == (True, False)
    assert (g3["coverage"]["pointEstimateMeetsTarget"], g3["coverage"]["intervalLowMeetsTarget"]) == (True, True)
    assert "targetsMet" not in g3 and "decision" not in g3


def test_a_missed_coverage_is_named_even_when_too_few_decisions_are_left_for_the_precision(tmp_path, capsys):
    gold, preds, manifest, digest = gate_inputs(tmp_path, correct=25, wrong=0, unknown=175)
    code, out, _ = evaluate(capsys, "--gold", gold, "--pred", *preds, "--manifest", manifest, "--expect-sha256", digest)
    assert code == 0
    assert "  precision 25/25: not checked: 25 is below 30" in out
    assert "  coverage 25/200 (0.125): whole interval below the target" in out
    assert "  the point estimates do not meet the targets" in out


def test_the_gate_block_names_unscored_decisions_and_the_lower_bound(tmp_path, capsys):
    gold, preds, manifest, digest = gate_inputs(tmp_path)
    document = json.loads(preds[0].read_text(encoding="utf-8"))
    document["rallies"] += proposals("V1", [(index, "HOME") for index in range(150, 160)])["rallies"]
    write_json(preds[0], document)
    code, out, _ = evaluate(capsys, "--gold", gold, "--pred", *preds, "--manifest", manifest, "--expect-sha256", digest)
    assert code == 0
    assert ("  counting the 10 automatic decisions that pair with no gold rally as wrong: precision 130/145 (0.897): "
            "whole interval below the target") in out
    assert "  the point estimates meet both targets" in out


@pytest.mark.parametrize("case, fragment", [
    ("no digest", "the digest of the manifest was not compared with the one noted at lock time (--expect-sha256)"),
    ("validation", "the videos are in the validation split, and the gate is checked on the test split only"),
    ("missing video", "test video(s) of the manifest were not evaluated: V2"),
    ("tolerance", "rallies were paired at +/-1500 ms and not at the fixed 1000 ms"),
    ("no analyzer", "the prediction files do not name the analyzer and its version"),
    ("single", "no eligible rally carries a double-agreed or adjudicated label"),
])
def test_a_run_that_misses_one_condition_is_a_pilot_run_and_names_it(tmp_path, capsys, case, fragment):
    gold, preds, manifest, digest = gate_inputs(tmp_path)
    arguments = ["--gold", gold, "--pred", *preds, "--manifest", manifest, "--expect-sha256", digest]
    if case == "no digest":
        arguments = arguments[:-2]
    elif case == "validation":
        manifest, digest = lock(tmp_path, [("V1", "M1", "validation"), ("V2", "M2", "validation"), ("V9", "M9", "test")],
                                name="validation.csv")
        arguments[-3:] = [manifest, "--expect-sha256", digest]
    elif case == "missing video":
        arguments = ["--gold", gold, "--pred", preds[0], "--manifest", manifest, "--expect-sha256", digest]
    elif case == "tolerance":
        arguments += ["--tolerance-ms", 1500]
    elif case == "no analyzer":
        for path in preds:
            document = json.loads(path.read_text(encoding="utf-8"))
            del document["analyzer"]
            write_json(path, document)
    elif case == "single":
        rows = [row[:5] + ["SINGLE_HUMAN"] for row in csv.reader(open(gold, encoding="utf-8-sig"))][1:]
        gold_file(tmp_path, rows)
    code, out, err = evaluate(capsys, *arguments)
    assert code == 0, err
    assert out.splitlines()[0] == "PILOT RUN, NOT A GATE CHECK"
    assert f"  - {fragment}" in out
    assert "G3 targets: not checked, this is a pilot run" in out and "point estimate" not in out
    document = as_json(capsys, *arguments)
    assert document["runKind"] == "PILOT" and fragment in document["reasons"]
    assert document["g3"] == {"checked": False, "reasons": document["reasons"]}


def test_a_missing_test_video_is_listed_with_the_side_that_is_missing(tmp_path, capsys):
    gold, preds, manifest, digest = gate_inputs(tmp_path)
    manifest, digest = lock(tmp_path, [("V1", "M1", "test"), ("V2", "M2", "test"), ("V7", "M7", "test")], name="three.csv")
    code, out, _ = evaluate(capsys, "--gold", gold, "--pred", preds[0], "--manifest", manifest, "--expect-sha256", digest)
    assert code == 0
    assert "1 of 3 videos of the split evaluated" in out
    assert "not evaluated, in the test split of the manifest: V2 (no prediction file), V7 (no gold file, no prediction file)" in out
    document = as_json(capsys, "--gold", gold, "--pred", preds[0], "--manifest", manifest, "--expect-sha256", digest)
    assert document["notEvaluated"] == {"goldOnly": ["V2"], "predictedOnly": [], "manifestOnly": ["V7"]}
    assert "test video(s) of the manifest were not evaluated: V2, V7" in document["reasons"]


def test_a_manifest_that_can_not_be_trusted_or_does_not_fit_the_videos_is_refused(tmp_path, capsys):
    gold, preds, manifest, digest = gate_inputs(tmp_path)
    base = ("--gold", gold, "--pred", *preds)

    assert_unusable(evaluate(capsys, *base, "--manifest", manifest, "--expect-sha256", "0" * 64),
                    f"content_sha256 is {digest}, but {'0' * 64} was expected")
    assert_unusable(evaluate(capsys, *base, "--expect-sha256", digest), "--expect-sha256 needs --manifest")

    edited = tmp_path / "edited.csv"
    edited.write_text(manifest.read_text(encoding="utf-8").replace("V3,M3", "V3,M1"), encoding="utf-8")
    assert_unusable(evaluate(capsys, *base, "--manifest", edited), "the manifest was changed after it was locked")

    other, _ = lock(tmp_path, [("V1", "M1", "test")], name="other.csv")
    assert_unusable(evaluate(capsys, *base, "--manifest", other), "not in the manifest: V2")

    mixed, _ = lock(tmp_path, [("V1", "M1", "test"), ("V2", "M2", "validation")], name="mixed.csv")
    assert_unusable(evaluate(capsys, *base, "--manifest", mixed),
                    "the videos are in more than one split (V1: test, V2: validation)")


# --- input that is refused ---------------------------------------------------------------

@pytest.mark.parametrize("change, fragment", [
    ({"mock": True}, "is the output of a mock analyzer"),
    ({"mock": "false"}, "'mock' must be true or false"),
    ({"analyzer": {"id": "demo", "version": "1", "kind": "MOCK"}}, "is the output of a mock analyzer"),
    ({"rallyListVersion": 3}, "is a reviewed rally list"),
    ({"listConfirmed": False}, "is a reviewed rally list"),
])
def test_a_prediction_document_that_is_no_analyzer_output_is_refused(tmp_path, capsys, change, fragment):
    gold = gold_file(tmp_path, gold_rows("V1", ["HOME"] * 2))
    pred = write_json(tmp_path / "pred.json", {**proposals("V1", [(0, "HOME")]), **change})
    assert_unusable(evaluate(capsys, "--gold", gold, "--pred", pred), str(pred), fragment)


@pytest.mark.parametrize("key, value", [("origin", "USER"), ("status", "VERIFIED"), ("winner", "HOME")])
def test_a_rally_list_that_carries_user_work_is_refused(tmp_path, capsys, key, value):
    gold = gold_file(tmp_path, gold_rows("V1", ["HOME"] * 2))
    document = proposals("V1", [(0, "HOME"), (1, "HOME")])
    document["rallies"][1][key] = value
    pred = write_json(tmp_path / "pred.json", document)
    assert_unusable(evaluate(capsys, "--gold", gold, "--pred", pred),
                    f"rallies[1] carries '{key}'", "is a reviewed rally list", "the analyzer's own proposals are needed")


@pytest.mark.parametrize("rally, fragment", [
    ({"startMs": 0, "endMs": 5000}, "rallies[0]: 'proposedWinner' is missing; write UNKNOWN for an abstention"),
    ({"startMs": 0, "endMs": 5000, "proposedWinner": "home"}, "rallies[0]: proposedWinner must be HOME, AWAY or UNKNOWN, found 'home'"),
    ({"startMs": 0, "endMs": 5000, "proposedWinner": None}, "rallies[0]: proposedWinner must be HOME, AWAY or UNKNOWN, found None"),
    ({"startMs": 0, "endMs": 5000, "proposedWinner": "HOME", "confidence": 1.2}, "rallies[0]: confidence must be a number from 0 to 1 or null, found 1.2"),
    ({"startMs": 0, "endMs": 5000, "proposedWinner": "HOME", "confidence": True}, "rallies[0]: confidence must be a number from 0 to 1 or null, found True"),
    ({"startMs": 0, "endMs": 5000, "proposedWinner": "HOME", "confidence": "0.9"}, "rallies[0]: confidence must be a number from 0 to 1 or null, found '0.9'"),
    ({"startMs": 5000, "endMs": 5000, "proposedWinner": "HOME"}, "interval 5000..5000 does not satisfy 0 <= start < end"),
    ({"startMs": "0", "endMs": 5000, "proposedWinner": "HOME"}, "rallies[0]: startMs and endMs must be whole milliseconds"),
])
def test_a_proposal_that_breaks_a_rule_is_refused_with_its_position(tmp_path, capsys, rally, fragment):
    gold = gold_file(tmp_path, gold_rows("V1", ["HOME"] * 2))
    pred = write_json(tmp_path / "pred.json", {"videoId": "V1", "rallies": [rally]})
    assert_unusable(evaluate(capsys, "--gold", gold, "--pred", pred), str(pred), fragment)


def test_not_a_number_is_no_confidence(tmp_path, capsys):
    gold = gold_file(tmp_path, gold_rows("V1", ["HOME"] * 2))
    pred = tmp_path / "pred.json"
    pred.write_text('{"videoId": "V1", "rallies": [{"startMs": 0, "endMs": 5000, "proposedWinner": "HOME", "confidence": NaN}]}',
                    encoding="utf-8")
    assert_unusable(evaluate(capsys, "--gold", gold, "--pred", pred), "confidence must be a number from 0 to 1 or null")


def test_prediction_csv_is_read_with_its_own_rules(tmp_path, capsys):
    gold = gold_file(tmp_path, gold_rows("V1", ["HOME", "AWAY", "HOME"]))
    header = ["video_id", "start_ms", "end_ms", "proposed_winner", "confidence"]
    pred = write_rows(tmp_path / "pred.csv", header, [["V1", *at(0), "HOME", "0.8"], ["V1", *at(1), "HOME", ""],
                                                      ["V1", *at(2), "UNKNOWN", ""]])
    document = as_json(capsys, "--gold", gold, "--pred", pred)
    layer = document["layers"]["GOLD"]
    assert (layer["correct"], layer["wrong"], layer["unknown"]) == (1, 1, 1)
    assert (layer["calibration"]["n"], layer["calibration"]["withoutConfidence"]) == (1, 1)
    assert document["analyzer"] is None

    # The confidence column may be left out; the proposed winner may not.
    no_confidence = write_rows(tmp_path / "plain.csv", header[:4], [["V1", *at(0), "HOME"]])
    assert as_json(capsys, "--gold", gold, "--pred", no_confidence)["layers"]["GOLD"]["correct"] == 1
    no_winner = write_rows(tmp_path / "intervals.csv", ["video_id", "start_ms", "end_ms", "winner"], [["V1", *at(0), "HOME"]])
    assert_unusable(evaluate(capsys, "--gold", gold, "--pred", no_winner), "missing column(s): proposed_winner")


def test_every_bad_cell_of_a_prediction_csv_is_reported_with_its_row(tmp_path, capsys):
    gold = gold_file(tmp_path, gold_rows("V1", ["HOME"] * 4))
    pred = tmp_path / "pred.csv"
    # A spreadsheet with a Turkish locale writes semicolons and a decimal comma.
    pred.write_text("video_id;start_ms;end_ms;proposed_winner;confidence\n"
                    "V1;0;5000;HOME;0,85\nV1;10000;15000;;\nV1;20000;25000;home;0.5\nV1;30000;35000;AWAY;1.5\n",
                    encoding="utf-8")
    code, out, err = evaluate(capsys, "--gold", gold, "--pred", pred)
    assert (code, out) == (2, "")
    assert err.splitlines() == [
        f"error: {pred}: the proposals are not valid:",
        "  row 2: confidence must be a number from 0 to 1 written with a decimal point, or empty, found '0,85'",
        "  row 3: proposed_winner must be HOME, AWAY or UNKNOWN, found ''; write UNKNOWN for an abstention",
        "  row 4: proposed_winner must be HOME, AWAY or UNKNOWN, found 'home'",
        "  row 5: confidence must be a number from 0 to 1 written with a decimal point, or empty, found '1.5'",
    ]


def test_one_evaluation_reads_the_proposals_of_one_analyzer(tmp_path, capsys):
    gold = gold_file(tmp_path, gold_rows("V1", ["HOME"]) + gold_rows("V2", ["HOME"]))
    first = pred_file(tmp_path, "V1", [(0, "HOME")])
    newer = pred_file(tmp_path, "V2", [(0, "HOME")], analyzer={**ANALYZER, "version": "0.2"})
    assert_unusable(evaluate(capsys, "--gold", gold, "--pred", first, newer),
                    "the prediction files name different analyzers: next-serve-rule 0.1, next-serve-rule 0.2")
    unnamed = pred_file(tmp_path, "V2", [(0, "HOME")], analyzer=None)
    assert_unusable(evaluate(capsys, "--gold", gold, "--pred", first, unnamed),
                    "some prediction files name an analyzer and others do not")
    twice = pred_file(tmp_path, "V1", [(0, "HOME")], name="again.json")
    assert_unusable(evaluate(capsys, "--gold", gold, "--pred", first, twice),
                    "video id 'V1' was already read from another prediction file")


def test_gold_labels_must_be_schema_values_for_this_command(tmp_path, capsys):
    pred = pred_file(tmp_path, "V1", [(0, "HOME")])
    lower = gold_file(tmp_path, [["V1", 0, 5000, "home", "OBSERVED", "double_agreed"]])
    code, out, err = evaluate(capsys, "--gold", lower, "--pred", pred)
    assert (code, out) == (2, "")
    assert "row 2: winning_side must be one of HOME, AWAY, UNKNOWN or empty, found 'home'" in err
    assert ("row 2: ground_truth_status must be one of SINGLE_HUMAN, DOUBLE_AGREED, ADJUDICATED, UNRESOLVABLE, "
            "NEEDS_MORE_EVIDENCE or empty, found 'double_agreed'") in err

    overlapping = gold_file(tmp_path, [["V1", 0, 5000, "HOME", "OBSERVED", ""], ["V1", 4000, 9000, "HOME", "OBSERVED", ""]])
    code, out, err = evaluate(capsys, "--gold", overlapping, "--pred", pred)
    assert (code, out) == (2, "") and "the gold intervals are not valid" in err

    snake = write_json(tmp_path / "gold.json", {"annotationVersion": "VVV_ANNOTATION_V3", "videoId": "V1", "rallies": [
        {"rallyId": "R1", "startMs": 0, "endMs": 5000, "winningSide": "HOME", "observationStatus": "OBSERVED",
         "ground_truth_status": "UNRESOLVABLE"}]})
    code, out, err = evaluate(capsys, "--gold", snake, "--pred", pred)
    assert (code, out) == (2, "") and "'ground_truth_status' is the CSV spelling" in err


def test_the_gold_role_of_evaluate_is_not_changed_by_the_new_command(tmp_path, capsys):
    lower = gold_file(tmp_path, [["V1", 0, 5000, "home", "OBSERVED", "double_agreed"]])
    pred = write_rows(tmp_path / "pred.csv", ["video_id", "start_ms", "end_ms"], [["V1", 0, 5000]])
    assert run(capsys, "evaluate", "--gold", lower, "--pred", pred)[0] == 0


@pytest.mark.parametrize("option, value", [
    ("--tolerance-ms", -1), ("--high-confidence", 1.5), ("--high-confidence", -0.1), ("--resamples", 0), ("--seed", -1),
    ("--expect-sha256", "abc"),
])
def test_an_option_outside_its_range_is_refused_by_the_parser(tmp_path, capsys, option, value):
    gold = gold_file(tmp_path, gold_rows("V1", ["HOME"]))
    assert_unusable(evaluate(capsys, "--gold", gold, "--pred", gold, option, value), f"argument {option}: ")


# --- the failure list --------------------------------------------------------------------

def test_the_failure_list_is_written_for_error_analysis_and_never_overwritten(tmp_path, capsys):
    gold, pred = forty(tmp_path)
    out_file = tmp_path / "failures.csv"
    code, out, _ = evaluate(capsys, "--gold", gold, "--pred", pred, "--out", out_file)
    assert code == 0
    assert "failures for error analysis: 13 row(s)" in out and f"wrote {out_file}" in out
    with open(out_file, encoding="utf-8", newline="") as handle:
        rows = list(csv.DictReader(handle))
    assert list(rows[0]) == ["video_id", "kind", "layer", "gold_start_ms", "gold_end_ms", "pred_start_ms", "pred_end_ms",
                             "gold_winner", "proposed_winner", "confidence", "reason"]
    assert [row["kind"] for row in rows] == ["WRONG"] * 3 + ["UNKNOWN"] * 6 + ["NOT_PROPOSED"] * 4
    assert rows[0] == {"video_id": "V1", "kind": "WRONG", "layer": "GOLD", "gold_start_ms": "270000", "gold_end_ms": "275000",
                       "pred_start_ms": "270000", "pred_end_ms": "275000", "gold_winner": "HOME", "proposed_winner": "AWAY",
                       "confidence": "", "reason": ""}
    assert rows[-1]["pred_start_ms"] == "" and rows[-1]["proposed_winner"] == ""

    assert_unusable(evaluate(capsys, "--gold", gold, "--pred", pred, "--out", out_file), "File exists")
    assert_unusable(evaluate(capsys, "--gold", gold, "--pred", pred, "--out", tmp_path), "is a directory")
    assert_unusable(evaluate(capsys, "--gold", gold, "--pred", pred, "--out", tmp_path / "missing" / "f.csv"),
                    "the folder does not exist")
    document = as_json(capsys, "--gold", gold, "--pred", pred, "--out", tmp_path / "second.csv")
    assert document["failuresFile"] == str(tmp_path / "second.csv")
