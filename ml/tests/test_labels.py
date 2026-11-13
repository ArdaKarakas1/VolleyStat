import copy
import json

import pytest

from volleystat_ml import labels
from volleystat_ml.labels import validate_annotation, validate_annotation_file


def rally(rally_id, start, end, **extra):
    return {"rallyId": rally_id, "startMs": start, "endMs": end,
            "winningSide": "HOME", "observationStatus": "OBSERVED", **extra}


def action(action_id, **extra):
    return {"actionId": action_id, "actionType": "ATTACK", "teamSide": "HOME", "outcome": "KILL", **extra}


def document(*rallies):
    return {"annotationVersion": "VVV_ANNOTATION_V3", "videoId": "SYNTHETIC_VIDEO", "rallies": list(rallies)}


VALID = document(
    rally("R1", 32500, 39000, servingSide="HOME", reviewStatus="ACCEPTED", uncertaintyReason=None,
          actions=[action("A1", actionType="SERVE", outcome="IN_PLAY", startMs=32500, endMs=32500),
                   action("A2", playerId=None, startMs=38000, endMs=38400, endEvent=True)]),
    rally("R2", 40800, 48500, winningSide="UNKNOWN", observationStatus="UNKNOWN",
          uncertaintyReason="BALL_OCCLUDED"),
)


def test_valid_document_has_no_errors():
    assert validate_annotation(VALID) == []
    assert validate_annotation(VALID, video_duration_ms=48500) == []
    assert validate_annotation(document()) == []


def test_packaged_schema_is_the_v3_contract():
    schema = labels.load_schema()
    assert schema["title"] == "VolleyStat Annotation Contract V3"
    assert schema["properties"]["annotationVersion"] == {"const": "VVV_ANNOTATION_V3"}


# --- structure (from the schema file) --------------------------------------------------

def test_top_level_must_be_an_object_with_the_required_fields():
    assert validate_annotation([]) == ["$: expected object, found array"]
    assert validate_annotation({}) == [
        "$: missing required field 'annotationVersion'",
        "$: missing required field 'videoId'",
        "$: missing required field 'rallies'",
    ]


def test_version_video_id_and_rallies_are_checked():
    broken = document()
    broken.update(annotationVersion="VVV_ANNOTATION_V2", videoId="", rallies={})
    assert validate_annotation(broken) == [
        '$.annotationVersion: must be "VVV_ANNOTATION_V3", found "VVV_ANNOTATION_V2"',
        "$.videoId: must have at least 1 character(s)",
        "$.rallies: expected array, found object",
    ]


def test_rally_fields_are_checked_with_their_position():
    broken = document(
        rally("R1", 0, 5000),
        {"rallyId": 7, "startMs": "1000", "endMs": 6500.5, "winningSide": "LEFT"},
    )
    assert validate_annotation(broken) == [
        "$.rallies[1]: missing required field 'observationStatus'",
        "$.rallies[1].rallyId: expected string, found integer",
        "$.rallies[1].startMs: expected integer, found string",
        "$.rallies[1].endMs: expected integer, found number",
        '$.rallies[1].winningSide: must be one of HOME, AWAY, UNKNOWN, found "LEFT"',
    ]


def test_booleans_are_not_integers_and_whole_floats_are():
    assert validate_annotation(document(rally("R1", True, 5000))) == [
        "$.rallies[0].startMs: expected integer, found boolean",
    ]
    assert validate_annotation(document(rally("R1", 0.0, 5000.0))) == []


def test_minimums_of_the_schema_apply():
    errors = validate_annotation(document(rally("R1", -5, 0)))
    assert "$.rallies[0].startMs: must be at least 0, found -5" in errors
    assert "$.rallies[0].endMs: must be at least 1, found 0" in errors


def test_action_fields_are_checked():
    broken = document(rally("R1", 0, 5000, reviewStatus="DONE", uncertaintyReason=5, actions=[
        {"actionId": "A1", "actionType": "SPIKE", "teamSide": "HOME"},
        "not an action",
    ]))
    assert validate_annotation(broken) == [
        '$.rallies[0].reviewStatus: must be one of UNREVIEWED, ACCEPTED, CORRECTED, UNRESOLVABLE, DISPUTED, found "DONE"',
        "$.rallies[0].uncertaintyReason: expected string or null, found integer",
        "$.rallies[0].actions[0]: missing required field 'outcome'",
        '$.rallies[0].actions[0].actionType: must be one of SERVE, RECEPTION, SET, ATTACK, BLOCK, DIG, FAULT, '
        'CONTACT_OTHER, found "SPIKE"',
        "$.rallies[0].actions[1]: expected object, found string",
    ]


def test_schema_with_an_unknown_keyword_is_refused():
    with pytest.raises(ValueError, match="unsupported schema keyword.*maximum"):
        labels._reject_unsupported({"type": "object", "properties": {"x": {"maximum": 3}}}, "#")
    with pytest.raises(ValueError, match="unsupported type"):
        labels._reject_unsupported({"type": "date"}, "#")


# --- domain rules ----------------------------------------------------------------------

@pytest.mark.parametrize("start, end", [(5000, 5000), (6000, 5000)])
def test_start_must_be_before_end(start, end):
    assert validate_annotation(document(rally("R1", start, end))) == [
        f"$.rallies[0]: startMs ({start}) must be less than endMs ({end})",
    ]


def test_end_is_checked_against_the_video_duration_only_when_given():
    doc = document(rally("R1", 0, 5000), rally("R2", 6000, 12001))
    assert validate_annotation(doc) == []
    assert validate_annotation(doc, video_duration_ms=12001) == []
    assert validate_annotation(doc, video_duration_ms=12000) == [
        "$.rallies[1]: endMs (12001) is after the end of the video (12000 ms)",
    ]


def test_rallies_must_be_listed_in_time_order():
    doc = document(rally("R1", 10000, 15000), rally("R2", 0, 5000), rally("R3", 20000, 25000))
    assert validate_annotation(doc) == [
        "$.rallies[1]: startMs (0) is not after startMs (10000) of $.rallies[0]; rallies must be listed in time order",
    ]
    same_start = document(rally("R1", 1000, 2000), rally("R2", 1000, 3000))
    assert any("rallies must be listed in time order" in error for error in validate_annotation(same_start))


def test_rallies_must_not_overlap_but_may_touch():
    assert validate_annotation(document(rally("R1", 0, 5000), rally("R2", 5000, 9000))) == []
    assert validate_annotation(document(rally("R1", 0, 5000), rally("R2", 4999, 9000))) == [
        "$.rallies[1]: startMs (4999) is before endMs (5000) of $.rallies[0]; rallies must not overlap",
    ]


def test_overlap_is_found_even_when_the_file_is_out_of_order():
    doc = document(rally("R1", 10000, 15000), rally("R2", 0, 5000), rally("R3", 4000, 8000))
    errors = validate_annotation(doc)
    assert "$.rallies[2]: startMs (4000) is before endMs (5000) of $.rallies[1]; rallies must not overlap" in errors
    assert sum("must not overlap" in error for error in errors) == 1


def test_sequence_must_strictly_increase():
    ordered = document(rally("R1", 0, 5000, sequence=1), rally("R2", 6000, 9000, sequence=2),
                       rally("R3", 10000, 12000, sequence=5))
    assert validate_annotation(ordered) == []

    repeated = document(rally("R1", 0, 5000, sequence=1), rally("R2", 6000, 9000, sequence=2),
                        rally("R3", 10000, 12000, sequence=2))
    assert validate_annotation(repeated) == [
        "$.rallies[2].sequence: 2 is not greater than 2 of $.rallies[1]; the sequence must strictly increase",
    ]

    partial = document(rally("R1", 0, 5000, sequence=1), rally("R2", 6000, 9000),
                       rally("R3", 10000, 12000, sequence="3"))
    assert validate_annotation(partial) == [
        "$.rallies[1]: 'sequence' is missing although other rallies of this file have one",
        "$.rallies[2].sequence: expected integer, found string",
    ]


def test_sequence_restarts_with_each_set():
    doc = document(
        rally("R1", 0, 5000, setNumber=1, sequence=1), rally("R2", 6000, 9000, setNumber=1, sequence=2),
        rally("R3", 10000, 12000, setNumber=2, sequence=1), rally("R4", 13000, 15000, setNumber=2, sequence=1),
    )
    assert validate_annotation(doc) == [
        "$.rallies[3].sequence: 1 is not greater than 1 of $.rallies[2]; the sequence of set 2 must strictly increase",
    ]


@pytest.mark.parametrize("field", ["winningSide", "servingSide", "observationStatus"])
@pytest.mark.parametrize("reason", ["missing", None, "", "   "])
def test_unknown_needs_an_uncertainty_reason(field, reason):
    unknown = rally("R1", 0, 5000, **{field: "UNKNOWN"})
    if reason != "missing":
        unknown["uncertaintyReason"] = reason
    assert validate_annotation(document(unknown)) == [
        f"$.rallies[0]: 'uncertaintyReason' is required because {field} is UNKNOWN",
    ]
    unknown["uncertaintyReason"] = "CAMERA_SHAKE"
    assert validate_annotation(document(unknown)) == []


def test_known_values_need_no_uncertainty_reason():
    assert validate_annotation(document(rally("R1", 0, 5000, servingSide="AWAY", uncertaintyReason=None))) == []


def test_at_most_one_end_event_per_rally():
    one = document(rally("R1", 0, 5000, actions=[action("A1"), action("A2", endEvent=True)]),
                   rally("R2", 6000, 9000, actions=[action("A3", endEvent=True), action("A4", endEvent=False)]))
    assert validate_annotation(one) == []

    two = copy.deepcopy(one)
    two["rallies"][1]["actions"][1]["endEvent"] = True
    assert validate_annotation(two) == [
        "$.rallies[1]: 2 actions are flagged as end event (actions[0], actions[1]); at most one is allowed",
    ]

    not_boolean = document(rally("R1", 0, 5000, actions=[action("A1", endEvent="yes")]))
    assert validate_annotation(not_boolean) == ["$.rallies[0].actions[0].endEvent: expected boolean, found string"]


def test_action_times_are_checked():
    doc = document(rally("R1", 0, 5000, actions=[action("A1", startMs=3000, endMs=2000),
                                                  action("A2", startMs=4000, endMs=9000)]))
    assert validate_annotation(doc, video_duration_ms=8000) == [
        "$.rallies[0].actions[0]: startMs (3000) must not be after endMs (2000)",
        "$.rallies[0].actions[1]: endMs (9000) is after the end of the video (8000 ms)",
    ]


def test_every_problem_of_a_file_is_reported_together():
    doc = document(rally("R1", 0, 5000, winningSide="UNKNOWN"), rally("R2", 4000, 3000), rally("R3", 4500, 6000))
    assert validate_annotation(doc, video_duration_ms=5500) == [
        "$.rallies[0]: 'uncertaintyReason' is required because winningSide is UNKNOWN",
        "$.rallies[1]: startMs (4000) must be less than endMs (3000)",
        "$.rallies[2]: endMs (6000) is after the end of the video (5500 ms)",
        "$.rallies[2]: startMs (4500) is before endMs (5000) of $.rallies[0]; rallies must not overlap",
    ]


# --- files -----------------------------------------------------------------------------

def test_file_validation_reads_json_with_or_without_a_byte_order_mark(tmp_path):
    plain = tmp_path / "plain.json"
    plain.write_text(json.dumps(VALID), encoding="utf-8")
    with_mark = tmp_path / "bom.json"
    with_mark.write_text(json.dumps(VALID), encoding="utf-8-sig")
    assert validate_annotation_file(plain) == []
    assert validate_annotation_file(with_mark, video_duration_ms=40000) == [
        "$.rallies[1]: endMs (48500) is after the end of the video (40000 ms)",
    ]


def test_unreadable_files_are_reported_as_errors(tmp_path):
    broken = tmp_path / "broken.json"
    broken.write_text('{"annotationVersion": ', encoding="utf-8")
    errors = validate_annotation_file(broken)
    assert len(errors) == 1 and errors[0].startswith("$: not valid JSON (line 1, column")
    errors = validate_annotation_file(tmp_path / "missing.json")
    assert len(errors) == 1 and errors[0].startswith("$: file can not be read")
