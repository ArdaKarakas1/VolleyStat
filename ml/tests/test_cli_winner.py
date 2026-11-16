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
    assert "PASS" not in out and "FAIL" not in out and "point estimate" not in out
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
    assert document["notEvaluated"] == {"goldOnly": [], "goldEmpty": [], "goldWithoutWinner": [], "predictedOnly": [], "manifestOnly": [], "automaticProposals": 0}
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


def test_the_unknown_and_not_proposed_rates_carry_an_interval_and_follow_the_thirty_rule(tmp_path, capsys):
    gold = gold_file(tmp_path, gold_rows("V1", ["HOME"] * 20))
    pred = pred_file(tmp_path, "V1", [(index, "HOME") for index in range(14)] + [(index, "UNKNOWN") for index in range(14, 18)])
    code, out, _ = evaluate(capsys, "--gold", gold, "--pred", pred)
    assert code == 0
    assert "  UNKNOWN from the component: 4/20\n    95% Wilson interval: 0.081 to 0.416\n" in out
    assert "  not proposed: 2/20\n    95% Wilson interval: 0.028 to 0.301\n" in out
    assert "    n = 20 is below 30: a percentage is not reliable at this sample size and is not printed. Quote the counts." in out
    assert "    n = 14 is below 30" in out
    assert "(0." not in out and "(1." not in out


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
    assert ("the evaluated videos carry no HOME or AWAY proposal, so nothing about a winner component was measured"
            in out)
    assert "  correct 0   wrong 0   UNKNOWN 28   not proposed 12" in out
    # Not "0/40 (0.000)": a coverage of zero would read as a measured component.
    assert "automatic coverage" not in out and "selective precision" not in out and "Wilson" not in out
    document = as_json(capsys, "--gold", gold, "--pred", pred)
    assert document["notMeasuredReason"].startswith("the evaluated videos carry no HOME or AWAY proposal")
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
            "(0 second proposals for a gold rally that another proposal pairs with, 1 overlap a gold rally whose "
            "boundary is further off, 2 lie where the gold has no rally)") in out
    # The GOLD layer names the hard cases of the double-annotated set that it leaves out.
    gold_block = out[out.index("layer GOLD"):out.index("layer SINGLE")]
    assert ("  hard cases of the double-annotated labels, outside this layer: 1 UNRESOLVABLE, 1 NEEDS_MORE_EVIDENCE; "
            "1 of them got an automatic decision, which was not scored") in gold_block
    # Only the GOLD layer says it: the SINGLE layer never held double-annotated labels.
    assert out.count("hard cases of the double-annotated labels") == 1

    result = as_json(capsys, "--gold", gold, "--pred", pred)
    assert result["excluded"] == {"NOT_ANNOTATED": 1, "NEEDS_MORE_EVIDENCE": 1, "UNRESOLVABLE": 1, "WINNER_UNKNOWN": 1}
    assert result["notScored"] == {
        "onIneligibleGold": {"UNRESOLVABLE": 1, "WINNER_UNKNOWN": 1},
        "unpaired": {"secondProposal": 0, "overlapsGold": 1, "noGoldThere": 2},
        "highConfidence": {"level": 0.9, "n": None},
    }
    layer = result["layers"]["GOLD"]
    assert (layer["eligibleN"], layer["autoN"], layer["correct"], layer["unpairedAutomatic"]) == (32, 32, 31, 3)
    assert (layer["lowerBoundPrecision"]["numerator"], layer["lowerBoundPrecision"]["denominator"]) == (31, 35)


def test_the_hard_cases_are_counted_by_their_status_even_without_a_winner(tmp_path, capsys):
    # An UNRESOLVABLE label often leaves the winner empty; the empty winner is tested first.
    rows = gold_rows("V1", ["HOME"] * 3)
    rows += [["V1", *at(3), "", "UNKNOWN", "UNRESOLVABLE"], ["V1", *at(4), "", "", "UNRESOLVABLE"],
             ["V1", *at(5), "HOME", "OBSERVED", "NEEDS_MORE_EVIDENCE"]]
    gold = gold_file(tmp_path, rows)
    pred = pred_file(tmp_path, "V1", [(index, "HOME", 0.9) for index in range(6)])
    code, out, _ = evaluate(capsys, "--gold", gold, "--pred", pred)
    assert code == 0
    assert ("  hard cases of the double-annotated labels, outside this layer: 2 UNRESOLVABLE, 1 NEEDS_MORE_EVIDENCE; "
            "3 of them got an automatic decision, which was not scored") in out
    assert as_json(capsys, "--gold", gold, "--pred", pred)["hardCases"] == {
        "statusGiven": 6, "UNRESOLVABLE": 2, "NEEDS_MORE_EVIDENCE": 1, "automaticDecisions": 3}


def test_a_test_video_whose_winners_were_never_annotated_is_not_evaluated(tmp_path, capsys):
    gold, preds, manifest, digest = gate_inputs(tmp_path)
    rows = [row for row in csv.reader(open(gold, encoding="utf-8-sig"))][1:]
    rows = [row if row[0] == "V1" else [row[0], row[1], row[2], "", "NOT_ANNOTATED", row[5]] for row in rows]
    gold_file(tmp_path, rows)
    arguments = ("--gold", gold, "--pred", *preds, "--manifest", manifest, "--expect-sha256", digest)
    code, out, _ = evaluate(capsys, *arguments)
    assert code == 0
    assert out.splitlines()[:2] == ["PILOT RUN, NOT A GATE CHECK", "  - test video(s) of the manifest were not evaluated: V2"]
    assert "not evaluated, in the test split of the manifest: V2 (the gold file annotates no winner)" in out
    # Under a manifest the video is named once, in the line of the split.
    assert "not evaluated, the gold file annotates no winner" not in out
    document = as_json(capsys, *arguments)
    assert document["notEvaluated"]["goldWithoutWinner"] == ["V2"]
    assert (document["videosEvaluated"], document["matchGroups"]) == (["V1"], 1)
    # Without its prediction file the video is named with both sides that are missing.
    code, out, _ = evaluate(capsys, "--gold", gold, "--pred", preds[0], "--manifest", manifest, "--expect-sha256", digest)
    assert "V2 (the gold file annotates no winner, no prediction file)" in out
    # A video whose winners an annotator looked at and could not tell is evaluated: a hard
    # recording stays in the test split.
    rows = [row if row[0] == "V1" else [row[0], row[1], row[2], "UNKNOWN", "UNKNOWN", row[5]] for row in rows]
    gold_file(tmp_path, rows, name="unknown.csv")
    document = as_json(capsys, "--gold", tmp_path / "unknown.csv", "--pred", *preds, "--manifest", manifest,
                       "--expect-sha256", digest)
    assert document["videosEvaluated"] == ["V1", "V2"] and document["runKind"] == "GATE_INPUT"
    # So is a video whose labels were adjudicated as unresolvable, even with the winner left empty.
    rows = [row if row[0] == "V1" else [row[0], row[1], row[2], "", "", "UNRESOLVABLE"] for row in rows]
    gold_file(tmp_path, rows, name="unresolvable.csv")
    document = as_json(capsys, "--gold", tmp_path / "unresolvable.csv", "--pred", *preds, "--manifest", manifest,
                       "--expect-sha256", digest)
    assert document["videosEvaluated"] == ["V1", "V2"] and document["notEvaluated"]["goldWithoutWinner"] == []
    # And one whose labels await more evidence.
    rows = [row if row[0] == "V1" else [row[0], row[1], row[2], "", "", "NEEDS_MORE_EVIDENCE"] for row in rows]
    gold_file(tmp_path, rows, name="evidence.csv")
    document = as_json(capsys, "--gold", tmp_path / "evidence.csv", "--pred", *preds, "--manifest", manifest,
                       "--expect-sha256", digest)
    assert document["videosEvaluated"] == ["V1", "V2"] and document["notEvaluated"]["goldWithoutWinner"] == []


def test_a_video_without_any_winner_label_is_named_without_a_manifest_and_refused_alone(tmp_path, capsys):
    rows = gold_rows("V1", ["HOME"] * 2) + [["V2", *at(index), "", "NOT_ANNOTATED", "DOUBLE_AGREED"] for index in range(2)]
    gold = gold_file(tmp_path, rows)
    preds = [pred_file(tmp_path, "V1", [(0, "HOME"), (1, "HOME")]), pred_file(tmp_path, "V2", [(0, "HOME"), (1, "AWAY")])]
    code, out, _ = evaluate(capsys, "--gold", gold, "--pred", *preds)
    assert code == 0
    assert "not evaluated, the gold file annotates no winner: V2" in out
    assert "not evaluated: 2 HOME or AWAY proposal(s) in videos that were not evaluated" in out
    assert as_json(capsys, "--gold", gold, "--pred", *preds)["notEvaluated"]["automaticProposals"] == 2
    # Alone, nothing is left to evaluate.
    only = gold_file(tmp_path, [row for row in rows if row[0] == "V2"], name="v2.csv")
    assert_unusable(evaluate(capsys, "--gold", only, "--pred", preds[1]),
                    "no gold file of a video in the predicted files annotates a winner: V2")


def test_the_gate_block_names_the_videos_and_matches_behind_the_gold_layer(tmp_path, capsys):
    gold, preds, manifest, digest = gate_inputs(tmp_path)
    rows = [row for row in csv.reader(open(gold, encoding="utf-8-sig"))][1:]
    gold_file(tmp_path, [row[:5] + ["SINGLE_HUMAN" if row[0] == "V2" else row[5]] for row in rows]
              + [["V1", *at(150), "UNKNOWN", "UNKNOWN", "DOUBLE_AGREED"]])
    # V1 also decides on a rally without a definite winner; V2 also decides where no rally is.
    for path, extra in ((preds[0], (150, "HOME")), (preds[1], (160, "HOME"))):
        document = json.loads(path.read_text(encoding="utf-8"))
        document["rallies"] += proposals(document["videoId"], [extra])["rallies"]
        write_json(path, document)
    arguments = ("--gold", gold, "--pred", *preds, "--manifest", manifest, "--expect-sha256", digest)
    code, out, _ = evaluate(capsys, *arguments)
    assert code == 0
    assert "  GOLD-layer rallies from 1 video(s) in 1 match group(s), paired at +/-1000 ms" in out
    document = as_json(capsys, *arguments)
    assert (document["g3"]["goldLayerVideos"], document["g3"]["goldLayerMatchGroups"]) == (["V1"], 1)


def test_the_bounds_of_an_interval_beside_a_target_are_not_rounded_onto_it(tmp_path, capsys):
    gold, preds, manifest, digest = gate_inputs(tmp_path, correct=108, wrong=1, unknown=11)
    code, out, _ = evaluate(capsys, "--gold", gold, "--pred", *preds, "--manifest", manifest, "--expect-sha256", digest)
    assert code == 0
    assert "  selective precision: 108/109 automatic decisions are correct (0.991)\n    95% Wilson interval: 0.9499 to 0.998" in out
    assert "interval reaches down to 0.9499" in out

    # The coverage target 0.60: a lower bound of 0.59995 is not printed 0.600.
    (tmp_path / "coverage").mkdir()
    gold, preds, manifest, digest = gate_inputs(tmp_path / "coverage", correct=52, wrong=0, unknown=21)
    code, out, _ = evaluate(capsys, "--gold", gold, "--pred", *preds, "--manifest", manifest, "--expect-sha256", digest)
    assert "  automatic coverage: 52/73 eligible rallies got an automatic decision (0.712)\n    95% Wilson interval: 0.5999 to 0.803" in out

    # Upper ends: 50/56 reaches 0.94996 and 57/112 reaches 0.5997, neither one is the target.
    for name, counts, line in (
            ("upper", (50, 6, 30), "  selective precision: 50/56 automatic decisions are correct (0.893)\n    95% Wilson interval: 0.785 to 0.94996\n"),
            ("cover", (57, 0, 55), "  automatic coverage: 57/112 eligible rallies got an automatic decision (0.509)\n    95% Wilson interval: 0.418 to 0.5997\n")):
        (tmp_path / name).mkdir()
        gold, preds, manifest, digest = gate_inputs(tmp_path / name, *counts)
        code, out, _ = evaluate(capsys, "--gold", gold, "--pred", *preds, "--manifest", manifest, "--expect-sha256", digest)
        assert line in out

    # The interval of the lower bound.
    (tmp_path / "bound").mkdir()
    gold, preds, manifest, digest = gate_inputs(tmp_path / "bound", correct=108, wrong=0, unknown=12)
    document = json.loads(preds[0].read_text(encoding="utf-8"))
    document["rallies"] += proposals("V1", [(500, "HOME")])["rallies"]
    write_json(preds[0], document)
    code, out, _ = evaluate(capsys, "--gold", gold, "--pred", *preds, "--manifest", manifest, "--expect-sha256", digest)
    assert "    if each were a wrong decision: 108/109 (0.991), 95% Wilson interval 0.9499 to 0.998\n" in out


def test_a_csv_cell_beyond_the_csv_limit_is_an_error_and_not_a_traceback(tmp_path, capsys):
    pred = pred_file(tmp_path, "V1", [(0, "HOME")])
    gold = tmp_path / "gold.csv"
    gold.write_text(",".join(GOLD_HEADER) + ",notes\nV1,0,5000,HOME,OBSERVED,DOUBLE_AGREED,\"net touch?" + "x" * 140_000
                    + "\n", encoding="utf-8")
    assert_unusable(evaluate(capsys, "--gold", gold, "--pred", pred), str(gold), "a quote may not be closed")
    # A small file is worse: the open quote swallows every later row into one cell, and the
    # gold would silently lose those rallies.
    small = tmp_path / "small.csv"
    small.write_text(",".join(GOLD_HEADER) + ",notes\nV1,0,5000,HOME,OBSERVED,DOUBLE_AGREED,\"net touch?\n"
                     "V1,10000,15000,AWAY,OBSERVED,DOUBLE_AGREED,\nV1,20000,25000,HOME,OBSERVED,DOUBLE_AGREED,\n",
                     encoding="utf-8")
    assert_unusable(evaluate(capsys, "--gold", small, "--pred", pred), str(small), "a quote may not be closed")
    stray = write_rows(tmp_path / "pred.csv", ["video_id", "start_ms", "end_ms", "proposed_winner"], [["V1", 0, 5000, "HOME"]])
    stray.write_text(stray.read_text(encoding="utf-8-sig") + 'V1,10000,15000,"AWAY\n', encoding="utf-8")
    assert_unusable(evaluate(capsys, "--gold", gold_file(tmp_path, gold_rows("V1", ["HOME"] * 2), name="g2.csv"),
                             "--pred", stray), str(stray), "a quote may not be closed")


def test_a_broken_quote_in_a_csv_header_is_an_error_with_its_line(tmp_path, capsys):
    pred = pred_file(tmp_path, "V1", [(0, "HOME")])
    for name, header in (("unclosed.csv", 'video_id,start_ms,end_ms,winning_side,observation_status,"notes'),
                         ("text.csv", 'video_id,start_ms,end_ms,"winning_side" ,observation_status')):
        gold = tmp_path / name
        gold.write_text(header + "\nV1,0,5000,HOME,OBSERVED\n", encoding="utf-8")
        assert_unusable(evaluate(capsys, "--gold", gold, "--pred", pred), f"{gold}: line 1: ")
    stray = tmp_path / "pred.csv"
    stray.write_text('video_id,start_ms,end_ms,"proposed_winner\nV1,0,5000,HOME\n', encoding="utf-8")
    assert_unusable(evaluate(capsys, "--gold", gold_file(tmp_path, gold_rows("V1", ["HOME"])), "--pred", stray),
                    f"{stray}: line 1: ")


def test_proposals_outside_the_evaluated_videos_do_not_count_as_a_measurement(tmp_path, capsys):
    gold = gold_file(tmp_path, gold_rows("V1", ["HOME"] * 4))
    preds = [pred_file(tmp_path, "V1", [(index, "UNKNOWN") for index in range(4)]), pred_file(tmp_path, "V3", [(0, "HOME", 0.9)])]
    code, out, _ = evaluate(capsys, "--gold", gold, "--pred", *preds)
    assert code == 0
    assert "the evaluated videos carry no HOME or AWAY proposal, so nothing about a winner component was measured" in out
    assert "the prediction files contain no" not in out
    # The decisions left out are named, so nobody reads the run as a component that decided nothing.
    assert "not evaluated: 1 HOME or AWAY proposal(s) in videos that were not evaluated" in out
    assert as_json(capsys, "--gold", gold, "--pred", *preds)["notEvaluated"]["automaticProposals"] == 1


def test_a_gold_layer_left_empty_by_hard_cases_still_names_them(tmp_path, capsys):
    rows = gold_rows("V1", ["HOME"] * 3, status="SINGLE_HUMAN")
    rows += [["V1", *at(3), "HOME", "OBSERVED", "UNRESOLVABLE"], ["V1", *at(4), "HOME", "OBSERVED", "UNRESOLVABLE"],
             ["V1", *at(5), "HOME", "OBSERVED", "NEEDS_MORE_EVIDENCE"]]
    gold = gold_file(tmp_path, rows)
    pred = pred_file(tmp_path, "V1", [(index, "HOME") for index in range(6)])
    code, out, _ = evaluate(capsys, "--gold", gold, "--pred", pred)
    assert code == 0
    assert ("layer GOLD (double-agreed or adjudicated labels): no eligible rally\n"
            "  hard cases of the double-annotated labels, outside this layer: 2 UNRESOLVABLE, 1 NEEDS_MORE_EVIDENCE; "
            "3 of them got an automatic decision, which was not scored\n") in out


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
    assert ("  gold winners that were inferred and not seen: 2 of 5 eligible (evidence RULE_CONSTRAINT: 1 of the 5 "
            "that state an evidence). Such a winner may come from the rule under test.") in out
    layer = as_json(capsys, "--gold", gold, "--pred", pred)["layers"]["GOLD"]
    assert layer["byObservationStatus"]["INFERRED"] == {"eligibleN": 2, "autoN": 2, "correct": 0}
    assert (layer["ruleConstraintEvidence"], layer["evidenceGiven"]) == (1, 5)

    # A winner taken from the rule is pointed out even when the annotator saw the rally.
    seen = gold_file(tmp_path, [["V1", *at(0), "HOME", "OBSERVED", "DOUBLE_AGREED", "RULE_CONSTRAINT"],
                                ["V1", *at(1), "HOME", "OBSERVED", "DOUBLE_AGREED", ""]], name="seen.csv", header=header)
    code, out, _ = evaluate(capsys, "--gold", seen, "--pred", pred)
    assert ("  gold winners that were inferred and not seen: 0 of 2 eligible (evidence RULE_CONSTRAINT: 1 of the 1 "
            "that state an evidence).") in out

    # An evidence column that nobody filled in states nothing: unknown, not zero.
    blank = gold_file(tmp_path, [[*row[:6], ""] for row in rows], name="blank.csv", header=header)
    code, out, _ = evaluate(capsys, "--gold", blank, "--pred", pred)
    assert "  gold winners that were inferred and not seen: 2 of 5 eligible. Such a winner" in out
    layer = as_json(capsys, "--gold", blank, "--pred", pred)["layers"]["GOLD"]
    assert (layer["ruleConstraintEvidence"], layer["evidenceGiven"]) == (None, 0)

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
    assert document["notEvaluated"] == {"goldOnly": ["V2"], "goldEmpty": [], "goldWithoutWinner": [], "predictedOnly": ["V3"], "manifestOnly": [], "automaticProposals": 1}

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


def test_a_layer_without_an_automatic_decision_reports_no_calibration(tmp_path, capsys):
    rows = gold_rows("V1", ["HOME"] * 3) + gold_rows("V2", ["AWAY"] * 3, status="SINGLE_HUMAN")
    gold = gold_file(tmp_path, rows)
    preds = [pred_file(tmp_path, "V1", [(index, "HOME", 0.8) for index in range(3)]),
             pred_file(tmp_path, "V2", [(index, "UNKNOWN") for index in range(3)])]
    code, out, _ = evaluate(capsys, "--gold", gold, "--pred", *preds)
    assert code == 0
    single = out[out.index("layer SINGLE"):]
    assert "  selective precision: not defined, there is no automatic decision on these rallies" in single
    # Nothing was decided there, so a calibration line would read as a component without confidences.
    assert "calibration" not in single and "wrong with a confidence" not in single
    assert "  calibration over 3 automatic decisions that carry a confidence (0 carry none):" in out[:out.index("layer SINGLE")]


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


def test_an_unscored_decision_at_the_level_itself_is_counted_as_confident(tmp_path, capsys):
    gold = gold_file(tmp_path, gold_rows("V1", ["HOME"] * 2))
    pred = pred_file(tmp_path, "V1", [(0, "HOME"), (1, "HOME"), (5, "AWAY", 0.9), (6, "AWAY", 0.89)])
    code, out, _ = evaluate(capsys, "--gold", gold, "--pred", pred)
    assert "not scored, with a confidence of 0.90 or more: 1" in out
    assert as_json(capsys, "--gold", gold, "--pred", pred)["notScored"]["highConfidence"] == {"level": 0.9, "n": 1}


def test_a_level_with_more_than_two_decimals_is_printed_as_given(tmp_path, capsys):
    gold = gold_file(tmp_path, gold_rows("V1", ["HOME"] * 2))
    pred = pred_file(tmp_path, "V1", [(0, "AWAY", 0.992), (1, "AWAY", 0.5), (5, "AWAY", 0.992)])
    code, out, _ = evaluate(capsys, "--gold", gold, "--pred", pred, "--high-confidence", 0.995)
    # Rounded to two decimals the level would read 0.99, and 0.992 is 0.99 or more.
    assert "  wrong with a confidence of 0.995 or more: 0 of 0" in out
    assert "not scored, with a confidence of 0.995 or more: 0" in out


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
    document["rallies"] += proposals("V1", [(index, "HOME") for index in range(150, 160)] + [(170, "AWAY")])["rallies"]
    write_json(preds[0], document)
    # Two hard cases of the double-annotated set; one of them got a decision.
    rows = [row for row in csv.reader(open(gold, encoding="utf-8-sig"))][1:]
    rows += [["V1", *at(170), "HOME", "OBSERVED", "UNRESOLVABLE"], ["V1", *at(171), "HOME", "OBSERVED", "NEEDS_MORE_EVIDENCE"]]
    gold_file(tmp_path, rows)
    code, out, _ = evaluate(capsys, "--gold", gold, "--pred", *preds, "--manifest", manifest, "--expect-sha256", digest)
    assert code == 0
    assert ("  counting the 10 automatic decisions that pair with no gold rally as wrong: precision 130/145 (0.897): "
            "whole interval below the target") in out
    assert "  the point estimates meet both targets" in out
    assert "  gold rallies without a definite winner: 2 (1 NEEDS_MORE_EVIDENCE, 1 UNRESOLVABLE)" in out
    assert ("  automatic decisions that were not scored: 11 (1 on gold rallies without a definite winner, "
            "10 that pair with no gold rally)") in out
    assert ("  hard cases of the double-annotated labels, outside this layer: 1 UNRESOLVABLE, 1 NEEDS_MORE_EVIDENCE; "
            "1 of them got an automatic decision, which was not scored") in out


def test_a_run_without_a_single_home_or_away_proposal_is_no_input_for_the_gate(tmp_path, capsys):
    # Today's segment output abstains everywhere. Its coverage of 0 measures no winner component.
    gold, preds, manifest, digest = gate_inputs(tmp_path, correct=0, wrong=0, unknown=200)
    arguments = ("--gold", gold, "--pred", *preds, "--manifest", manifest, "--expect-sha256", digest)
    code, out, _ = evaluate(capsys, *arguments)
    assert code == 0
    assert out.splitlines()[:2] == ["PILOT RUN, NOT A GATE CHECK",
                                    "  - the evaluated videos carry no HOME or AWAY proposal, so there is no winner "
                                    "decision to compare with the targets"]
    assert "0.000" not in out and "coverage 0/" not in out and "point estimate" not in out and "whole interval" not in out
    document = as_json(capsys, *arguments)
    assert document["runKind"] == "PILOT" and len(document["reasons"]) == 1
    assert document["g3"] == {"checked": False, "reasons": document["reasons"]}


def test_a_gold_file_without_rallies_does_not_count_as_an_evaluated_test_video(tmp_path, capsys):
    gold, preds, manifest, digest = gate_inputs(tmp_path)
    rows = [row for row in csv.reader(open(gold, encoding="utf-8-sig"))][1:]
    only_v1 = gold_file(tmp_path, [row for row in rows if row[0] == "V1"], name="v1.csv")
    empty = write_json(tmp_path / "v2.json", {"annotationVersion": "VVV_ANNOTATION_V3", "videoId": "V2", "rallies": []})
    arguments = ("--gold", only_v1, empty, "--pred", *preds, "--manifest", manifest, "--expect-sha256", digest)
    code, out, _ = evaluate(capsys, *arguments)
    assert code == 0
    assert out.splitlines()[0] == "PILOT RUN, NOT A GATE CHECK"
    assert "  - test video(s) of the manifest were not evaluated: V2" in out
    assert "videos evaluated: 1 (V1)" in out
    assert "not evaluated, in the test split of the manifest: V2 (the gold file holds no rally)" in out
    document = as_json(capsys, *arguments)
    assert document["notEvaluated"] == {"goldOnly": [], "goldEmpty": ["V2"], "goldWithoutWinner": [], "predictedOnly": [], "manifestOnly": [], "automaticProposals": 35}
    assert (document["videosEvaluated"], document["matchGroups"]) == (["V1"], 1)

    # Under a manifest the video is named once, in the line of the split.
    assert "not evaluated, the gold file holds no rally" not in out
    # Without a manifest the video is named too.
    code, out, _ = evaluate(capsys, "--gold", only_v1, empty, "--pred", *preds)
    assert "not evaluated, the gold file holds no rally: V2" in out
    # An empty gold file without a prediction file is a video without predictions, not an empty one.
    document = as_json(capsys, "--gold", only_v1, empty, "--pred", preds[0])
    assert (document["notEvaluated"]["goldOnly"], document["notEvaluated"]["goldEmpty"]) == (["V2"], [])
    # When no gold file annotates a winner nothing can be evaluated.
    assert_unusable(evaluate(capsys, "--gold", empty, "--pred", preds[1]),
                    "no gold file of a video in the predicted files annotates a winner: V2")


def test_a_share_just_below_a_target_is_not_printed_as_the_target(tmp_path, capsys):
    gold, preds, manifest, digest = gate_inputs(tmp_path, correct=189, wrong=10, unknown=101)
    code, out, _ = evaluate(capsys, "--gold", gold, "--pred", *preds, "--manifest", manifest, "--expect-sha256", digest)
    assert code == 0
    assert "  selective precision: 189/199 automatic decisions are correct (0.9497)" in out
    assert "  precision 189/199 (0.9497): point estimate below the target, interval reaches up to 0.972" in out
    assert "0.950" not in out


def test_a_gate_run_without_exclusions_says_zero_and_names_no_hard_case(tmp_path, capsys):
    gold, preds, manifest, digest = gate_inputs(tmp_path)
    code, out, _ = evaluate(capsys, "--gold", gold, "--pred", *preds, "--manifest", manifest, "--expect-sha256", digest)
    assert code == 0
    assert "  gold rallies without a definite winner: 0\n" in out
    assert "  automatic decisions that were not scored: 0\n" in out
    assert "hard cases" not in out


def test_without_a_stated_status_the_hard_cases_are_unknown_and_not_zero(tmp_path, capsys):
    gold = gold_file(tmp_path, [row[:5] for row in gold_rows("V1", ["HOME"] * 2)], header=GOLD_HEADER[:5])
    pred = pred_file(tmp_path, "V1", [(0, "HOME"), (1, "AWAY")])
    assert as_json(capsys, "--gold", gold, "--pred", pred)["hardCases"] == {
        "statusGiven": 0, "UNRESOLVABLE": None, "NEEDS_MORE_EVIDENCE": None, "automaticDecisions": None}
    # With some statuses stated the counts cover those, and the text says how many state none.
    rows = gold_rows("V1", ["HOME"] * 2) + [["V1", *at(2), "HOME", "OBSERVED", ""]]
    mixed = gold_file(tmp_path, rows, name="mixed.csv")
    pred = pred_file(tmp_path, "V1", [(0, "HOME"), (1, "AWAY"), (2, "HOME")], name="three.json")
    assert as_json(capsys, "--gold", mixed, "--pred", pred)["hardCases"] == {
        "statusGiven": 2, "UNRESOLVABLE": 0, "NEEDS_MORE_EVIDENCE": 0, "automaticDecisions": 0}
    code, out, _ = evaluate(capsys, "--gold", mixed, "--pred", pred)
    assert "  1 of 3 gold rallies state no ground_truth_status, so their hard cases are not known\n" in out


def test_a_video_without_predictions_names_what_its_gold_file_lacks_too(tmp_path, capsys):
    rows = gold_rows("V1", ["HOME"] * 2) + [["V2", *at(index), "", "NOT_ANNOTATED", "DOUBLE_AGREED"] for index in range(2)]
    gold = gold_file(tmp_path, rows)
    empty = write_json(tmp_path / "v3.json", {"annotationVersion": "VVV_ANNOTATION_V3", "videoId": "V3", "rallies": []})
    pred = pred_file(tmp_path, "V1", [(0, "HOME"), (1, "HOME")])
    code, out, _ = evaluate(capsys, "--gold", gold, empty, "--pred", pred)
    assert ("not evaluated, no prediction file: V2 (the gold file annotates no winner), "
            "V3 (the gold file holds no rally)\n") in out


def test_a_needs_more_evidence_case_alone_is_named_as_a_hard_case(tmp_path, capsys):
    gold = gold_file(tmp_path, gold_rows("V1", ["HOME"] * 2) + [["V1", *at(2), "HOME", "OBSERVED", "NEEDS_MORE_EVIDENCE"]])
    pred = pred_file(tmp_path, "V1", [(0, "HOME"), (1, "HOME")])
    code, out, _ = evaluate(capsys, "--gold", gold, "--pred", pred)
    assert "  hard cases of the double-annotated labels, outside this layer: 0 UNRESOLVABLE, 1 NEEDS_MORE_EVIDENCE\n" in out


def test_hard_cases_without_a_decision_say_nothing_about_decisions(tmp_path, capsys):
    rows = gold_rows("V1", ["HOME"] * 2) + [["V1", *at(2), "UNKNOWN", "UNKNOWN", "UNRESOLVABLE"]]
    gold = gold_file(tmp_path, rows)
    pred = pred_file(tmp_path, "V1", [(0, "HOME"), (1, "HOME")])
    code, out, _ = evaluate(capsys, "--gold", gold, "--pred", pred)
    assert "  hard cases of the double-annotated labels, outside this layer: 1 UNRESOLVABLE, 0 NEEDS_MORE_EVIDENCE\n" in out


def test_coverage_and_the_lower_bound_beside_their_targets_are_not_rounded_onto_them(tmp_path, capsys):
    # Coverage 1499/2500 = 0.5996 and, with 79 unpaired decisions, a lower bound 1499/1578 = 0.94993.
    gold, preds, manifest, digest = gate_inputs(tmp_path, correct=1499, wrong=0, unknown=1001)
    document = json.loads(preds[0].read_text(encoding="utf-8"))
    document["rallies"] += proposals("V1", [(index, "HOME") for index in range(1300, 1379)])["rallies"]
    write_json(preds[0], document)
    code, out, _ = evaluate(capsys, "--gold", gold, "--pred", *preds, "--manifest", manifest, "--expect-sha256", digest)
    assert code == 0
    assert "  automatic coverage: 1499/2500 eligible rallies got an automatic decision (0.5996)" in out
    assert "    if each were a wrong decision: 1499/1578 (0.9499)" in out


def test_targets_that_could_not_be_checked_are_not_read_as_missed(tmp_path, capsys):
    gold, preds, manifest, digest = gate_inputs(tmp_path, correct=20, wrong=0, unknown=0)
    code, out, _ = evaluate(capsys, "--gold", gold, "--pred", *preds, "--manifest", manifest, "--expect-sha256", digest)
    assert code == 0
    assert "  precision 20/20: not checked: 20 is below 30" in out
    assert "  the targets could not be checked on this sample" in out
    assert "do not meet" not in out


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
    assert document["notEvaluated"] == {"goldOnly": ["V2"], "goldEmpty": [], "goldWithoutWinner": [], "predictedOnly": [], "manifestOnly": ["V7"], "automaticProposals": 0}
    assert document["matchGroups"] == 1
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

    # A row without a split, under a digest that matches it.
    unsplit, _ = lock(tmp_path, [("V1", "M1", "test"), ("V2", "M2", "test")], name="unsplit.csv")
    content = unsplit.read_text(encoding="utf-8").split("# content_sha256=")[0]
    content = "\n".join(line.replace(",test,", ",,") if line.startswith("V2,") else line for line in content.split("\n"))
    unsplit.write_text(f"{content}# content_sha256={hashlib.sha256(content.encode()).hexdigest()}\n", encoding="utf-8")
    assert_unusable(evaluate(capsys, *base, "--manifest", unsplit), "video(s) without a split in the manifest: V2")
    # Also when that video has a gold file and no prediction file.
    assert_unusable(evaluate(capsys, "--gold", gold, "--pred", preds[0], "--manifest", unsplit),
                    "video(s) without a split in the manifest: V2")


# --- input that is refused ---------------------------------------------------------------

@pytest.mark.parametrize("change, fragment", [
    ({"mock": True}, "is the output of a mock analyzer"),
    ({"mock": "false"}, "'mock' must be true or false"),
    ({"analyzer": {"id": "demo", "version": "1", "kind": "MOCK"}}, "is the output of a mock analyzer"),
    ({"rallyListVersion": 3}, "is a reviewed rally list"),
    ({"listConfirmed": False}, "is a reviewed rally list"),
    ({"analyzer": {"kind": "BASELINE"}}, "'analyzer' must carry an id and a version"),
    ({"analyzer": {"id": 5, "version": "1"}}, "'analyzer' must carry an id and a version"),
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
    ({"startMs": 0.5, "endMs": 5000, "proposedWinner": "HOME"}, "rallies[0]: startMs and endMs must be whole milliseconds"),
    ({"startMs": 0, "endMs": 5000, "proposedWinner": "HOME", "confidence": 10 ** 400}, "confidence must be a number from 0 to 1 or null"),
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


@pytest.mark.parametrize("column", ["origin", "status", "winner", "rallyListVersion", "listConfirmed", "rally_list_version",
                                    "list_confirmed"])
def test_a_rally_list_with_user_work_is_refused_in_csv_too(tmp_path, capsys, column):
    gold = gold_file(tmp_path, gold_rows("V1", ["HOME"] * 2))
    pred = write_rows(tmp_path / "reviewed.csv", ["video_id", "start_ms", "end_ms", "proposed_winner", column],
                      [["V1", *at(0), "HOME", "x"]])
    assert_unusable(evaluate(capsys, "--gold", gold, "--pred", pred),
                    f"carries the column '{column}', so the file is a reviewed rally list", "the analyzer's own proposals are needed")


def test_a_csv_proposal_with_a_reversed_or_negative_interval_is_refused(tmp_path, capsys):
    gold = gold_file(tmp_path, gold_rows("V1", ["HOME"] * 2))
    header = ["video_id", "start_ms", "end_ms", "proposed_winner"]
    for name, row in (("reversed.csv", ["V1", 15000, 10000, "HOME"]), ("negative.csv", ["V1", -500, 5000, "HOME"])):
        pred = write_rows(tmp_path / name, header, [row])
        assert_unusable(evaluate(capsys, "--gold", gold, "--pred", pred), "does not satisfy 0 <= start < end")


def test_numbers_that_python_can_not_read_end_in_an_error_and_not_a_traceback(tmp_path, capsys):
    gold = gold_file(tmp_path, gold_rows("V1", ["HOME"] * 2))
    long_number = tmp_path / "long.json"
    long_number.write_text('{"videoId": "V1", "rallies": [{"startMs": 0, "endMs": 5000, "proposedWinner": "HOME", '
                           '"confidence": 1' + "0" * 5000 + '}]}', encoding="utf-8")
    assert_unusable(evaluate(capsys, "--gold", gold, "--pred", long_number), "not usable JSON")
    deep = tmp_path / "deep.json"
    deep.write_text("[" * 100_000 + "]" * 100_000, encoding="utf-8")
    assert_unusable(evaluate(capsys, "--gold", gold, "--pred", deep), "not usable JSON")
    assert_unusable(evaluate(capsys, "--gold", deep, "--pred", long_number), "not usable JSON")
    huge = pred_file(tmp_path, "V1", [])
    document = json.loads(huge.read_text(encoding="utf-8"))
    document["rallies"] = [{"startMs": 10 ** 400, "endMs": 10 ** 400 + 5000, "proposedWinner": "HOME"}]
    write_json(huge, document)
    # A start far beyond any video is a decision where the gold has no rally.
    code, out, err = evaluate(capsys, "--gold", gold, "--pred", huge)
    assert (code, err) == (0, "")
    assert "(0 second proposals for a gold rally that another proposal pairs with, 0 overlap a gold rally whose " \
           "boundary is further off, 1 lie where the gold has no rally)" in out


def test_proposals_that_name_different_winners_for_one_rally_are_refused(tmp_path, capsys):
    gold = gold_file(tmp_path, gold_rows("V1", ["HOME"] * 2))
    pred = pred_file(tmp_path, "V1", [(0, "HOME", 0.6), (0, "AWAY", 0.95), (1, "HOME")])
    assert_unusable(evaluate(capsys, "--gold", gold, "--pred", pred),
                    "video V1: proposals within +/-1000 ms of the gold rally 0-5000 ms name different winners (AWAY, HOME); "
                    "the component made no single decision on that rally")


def test_a_contradiction_on_a_later_rally_names_that_rally(tmp_path, capsys):
    gold = gold_file(tmp_path, gold_rows("V1", ["HOME"] * 2))
    pred = pred_file(tmp_path, "V1", [(0, "HOME"), (1, "HOME"), (1, "AWAY")])
    assert_unusable(evaluate(capsys, "--gold", gold, "--pred", pred), "gold rally 10000-15000 ms name different winners")


def test_a_second_proposal_for_a_rally_is_counted_and_named_as_such(tmp_path, capsys):
    gold = gold_file(tmp_path, gold_rows("V1", ["HOME"] * 2))
    pred = pred_file(tmp_path, "V1", [(0, "HOME", 0.6), (0, "HOME", 0.95), (1, "HOME")])
    code, out, _ = evaluate(capsys, "--gold", gold, "--pred", pred)
    assert code == 0
    assert ("not scored, automatic decisions that pair with no gold rally within +/-1000 ms: 1 (1 second proposals for a "
            "gold rally that another proposal pairs with, 0 overlap") in out
    document = as_json(capsys, "--gold", gold, "--pred", pred)
    assert document["notScored"]["unpaired"] == {"secondProposal": 1, "overlapsGold": 0, "noGoldThere": 0}
    # The same rows in the other order give the same report.
    turned = pred_file(tmp_path, "V1", [(1, "HOME"), (0, "HOME", 0.95), (0, "HOME", 0.6)], name="turned.json")
    again = as_json(capsys, "--gold", gold, "--pred", turned)
    assert again["layers"] == document["layers"] and again["notScored"] == document["notScored"]


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

    header = GOLD_HEADER + ["review_status", "winner_evidence"]
    misspelt = gold_file(tmp_path, [["V1", 0, 5000, "HOME", "OBSERVED", "", "disputed", "rule_constraint"],
                                    ["V1", 10000, 15000, "HOME", "OBSERVED", "", "UNRESOLVED", "VIDEO"]],
                         name="misspelt.csv", header=header)
    code, out, err = evaluate(capsys, "--gold", misspelt, "--pred", pred)
    assert (code, out) == (2, "")
    assert ("row 2: review_status must be one of UNREVIEWED, ACCEPTED, CORRECTED, UNRESOLVABLE, DISPUTED or empty, "
            "found 'disputed'") in err
    assert ("row 2: winner_evidence must be one of VIDEO, SCOREBOARD, AUDIO, RULE_CONSTRAINT, EXTERNAL_NOTES or empty, "
            "found 'rule_constraint'") in err
    assert "row 3: review_status must be one of" in err and "found 'UNRESOLVED'" in err

    reviewed = gold_file(tmp_path, [["V1", 0, 5000, "HOME", "OBSERVED", "", "DISPUTED", ""],
                                    ["V1", 10000, 15000, "HOME", "OBSERVED", "", "UNRESOLVABLE", ""],
                                    ["V1", 20000, 25000, "HOME", "OBSERVED", "", "ACCEPTED", ""]],
                         name="reviewed.csv", header=header)
    two = pred_file(tmp_path, "V1", [(0, "AWAY"), (1, "AWAY"), (2, "HOME")], name="two.json")
    document = as_json(capsys, "--gold", reviewed, "--pred", two)
    assert document["excluded"] == {"REVIEW_DISPUTED": 1, "REVIEW_UNRESOLVABLE": 1}
    assert document["layers"]["SINGLE"]["correct"] == 1 and document["layers"]["SINGLE"]["wrong"] == 0

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
