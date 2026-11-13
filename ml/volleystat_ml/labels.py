"""Validator for annotation files that follow ANNOTATION_SCHEMA_V3.json.

Two layers:

1. Structure, read from the schema file itself (schemas/ANNOTATION_SCHEMA_V3.json)
   by a small interpreter for the keywords that schema uses.
2. Domain rules the schema can not express:
   - 0 <= startMs < endMs, and endMs <= video duration when the duration is given
   - rallies are listed in strictly increasing order: by startMs, and by `sequence`
     where the file carries one (within a set when `setNumber` is present)
   - rallies do not overlap
   - a rally whose winningSide, servingSide or observationStatus is UNKNOWN
     states an uncertaintyReason
   - at most one action per rally is flagged as the end event (`endEvent: true`)

`sequence`, `setNumber` and `endEvent` are not part of the JSON schema, which allows
additional properties. They are the JSON spelling of CSV columns of the handover
templates: `sequence` and `set_number` of 03_RALLI_GOLD.csv, and `end_event` of
04_AKSIYON_GOLD.csv (that template is not copied into schemas/).

Every error is one string that starts with the location of the problem.
"""
from __future__ import annotations

import json
from functools import lru_cache
from importlib import resources
from pathlib import Path
from typing import Any

SCHEMA_FILE = "ANNOTATION_SCHEMA_V3.json"
UNKNOWN = "UNKNOWN"

# Keywords the interpreter enforces, and the ones that carry no constraint.
_ENFORCED = {"type", "required", "properties", "items", "enum", "const", "minimum", "minLength"}
_ANNOTATIONS = {"$schema", "title", "description"}
_TYPE_NAMES = {"object", "array", "string", "integer", "number", "boolean", "null"}


@lru_cache(maxsize=1)
def load_schema() -> dict[str, Any]:
    text = resources.files("volleystat_ml.schemas").joinpath(SCHEMA_FILE).read_text(encoding="utf-8")
    schema = json.loads(text)
    _reject_unsupported(schema, "#")
    return schema


def validate_annotation_file(path: Path, video_duration_ms: int | None = None) -> list[str]:
    try:
        document = json.loads(Path(path).read_text(encoding="utf-8-sig"))
    except (OSError, UnicodeDecodeError) as error:
        return [f"$: file can not be read: {error}"]
    except json.JSONDecodeError as error:
        return [f"$: not valid JSON (line {error.lineno}, column {error.colno}): {error.msg}"]
    return validate_annotation(document, video_duration_ms)


def validate_annotation(document: Any, video_duration_ms: int | None = None) -> list[str]:
    """Return every problem found, in document order. An empty list means the file is valid."""
    errors: list[str] = []
    _check(document, load_schema(), "$", errors)
    if isinstance(document, dict) and isinstance(document.get("rallies"), list):
        _check_rallies(document["rallies"], video_duration_ms, errors)
    return errors


def _reject_unsupported(schema: Any, where: str) -> None:
    """A schema keyword this interpreter does not know would be skipped silently, and the
    validator would then accept files the schema forbids. Fail when the schema is loaded."""
    if not isinstance(schema, dict):
        raise ValueError(f"{where}: a schema must be an object")
    unknown = set(schema) - _ENFORCED - _ANNOTATIONS
    if unknown:
        raise ValueError(f"{where}: unsupported schema keyword(s): {', '.join(sorted(unknown))}")
    declared = schema.get("type", [])
    for name in [declared] if isinstance(declared, str) else declared:
        if name not in _TYPE_NAMES:
            raise ValueError(f"{where}: unsupported type '{name}'")
    for key, child in schema.get("properties", {}).items():
        _reject_unsupported(child, f"{where}/properties/{key}")
    if "items" in schema:
        _reject_unsupported(schema["items"], f"{where}/items")


def _is_integer(value: Any) -> bool:
    # bool is a subclass of int in Python but is not a JSON number. JSON Schema counts
    # a number without a fractional part (1000.0) as an integer.
    if isinstance(value, bool):
        return False
    return isinstance(value, int) or (isinstance(value, float) and value.is_integer())


def _type_name(value: Any) -> str:
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "boolean"
    if _is_integer(value):
        return "integer"
    if isinstance(value, float):
        return "number"
    if isinstance(value, str):
        return "string"
    if isinstance(value, list):
        return "array"
    if isinstance(value, dict):
        return "object"
    return type(value).__name__


def _has_type(value: Any, name: str) -> bool:
    actual = _type_name(value)
    return actual == name or (name == "number" and actual == "integer")


def _check(value: Any, schema: dict[str, Any], path: str, errors: list[str]) -> None:
    if "type" in schema:
        allowed = [schema["type"]] if isinstance(schema["type"], str) else schema["type"]
        if not any(_has_type(value, name) for name in allowed):
            errors.append(f"{path}: expected {' or '.join(allowed)}, found {_type_name(value)}")
            return
    if "const" in schema and value != schema["const"]:
        errors.append(f"{path}: must be {json.dumps(schema['const'])}, found {json.dumps(value)}")
    if "enum" in schema and value not in schema["enum"]:
        errors.append(f"{path}: must be one of {', '.join(map(str, schema['enum']))}, found {json.dumps(value)}")
    if "minimum" in schema and _has_type(value, "number") and value < schema["minimum"]:
        errors.append(f"{path}: must be at least {schema['minimum']}, found {value}")
    if "minLength" in schema and isinstance(value, str) and len(value) < schema["minLength"]:
        errors.append(f"{path}: must have at least {schema['minLength']} character(s)")
    if isinstance(value, dict):
        for key in schema.get("required", []):
            if key not in value:
                errors.append(f"{path}: missing required field '{key}'")
        for key, child in schema.get("properties", {}).items():
            if key in value:
                _check(value[key], child, f"{path}.{key}", errors)
    if isinstance(value, list) and "items" in schema:
        for index, item in enumerate(value):
            _check(item, schema["items"], f"{path}[{index}]", errors)


def _check_rallies(rallies: list[Any], video_duration_ms: int | None, errors: list[str]) -> None:
    timed: list[tuple[int, int, int]] = []
    previous_start: tuple[int, int] | None = None
    previous_sequence: dict[Any, tuple[int, int]] = {}
    with_sequence = [index for index, rally in enumerate(rallies) if isinstance(rally, dict) and "sequence" in rally]

    for index, rally in enumerate(rallies):
        if not isinstance(rally, dict):
            continue
        path = f"$.rallies[{index}]"
        start, end = rally.get("startMs"), rally.get("endMs")
        if _is_integer(start) and _is_integer(end):
            if not start < end:
                errors.append(f"{path}: startMs ({start}) must be less than endMs ({end})")
            elif start >= 0:
                timed.append((int(start), int(end), index))
            if video_duration_ms is not None and end > video_duration_ms:
                errors.append(f"{path}: endMs ({end}) is after the end of the video ({video_duration_ms} ms)")
        if _is_integer(start):
            if previous_start is not None and start <= previous_start[0]:
                errors.append(
                    f"{path}: startMs ({start}) is not after startMs ({previous_start[0]}) of "
                    f"$.rallies[{previous_start[1]}]; rallies must be listed in time order"
                )
            previous_start = (start, index)

        if with_sequence:
            sequence = rally.get("sequence")
            if "sequence" not in rally:
                errors.append(f"{path}: 'sequence' is missing although other rallies of this file have one")
            elif not _is_integer(sequence):
                errors.append(f"{path}.sequence: expected integer, found {_type_name(sequence)}")
            elif "setNumber" in rally and not _is_integer(rally["setNumber"]):
                errors.append(f"{path}.setNumber: expected integer, found {_type_name(rally['setNumber'])}")
            else:
                set_number = rally.get("setNumber")
                scope = f" of set {set_number}" if "setNumber" in rally else ""
                key = json.dumps(set_number, sort_keys=True)
                if key in previous_sequence and sequence <= previous_sequence[key][0]:
                    errors.append(
                        f"{path}.sequence: {sequence} is not greater than {previous_sequence[key][0]} "
                        f"of $.rallies[{previous_sequence[key][1]}]; the sequence{scope} must strictly increase"
                    )
                previous_sequence[key] = (sequence, index)

        unknown_fields = [name for name in ("servingSide", "winningSide", "observationStatus")
                          if rally.get(name) == UNKNOWN]
        reason = rally.get("uncertaintyReason")
        if unknown_fields and not (isinstance(reason, str) and reason.strip()):
            errors.append(
                f"{path}: 'uncertaintyReason' is required because "
                f"{' and '.join(unknown_fields)} {'is' if len(unknown_fields) == 1 else 'are'} UNKNOWN"
            )

        actions = rally.get("actions")
        if isinstance(actions, list):
            _check_actions(actions, path, video_duration_ms, errors)

    # Overlap is checked in time order so that a file with rallies out of order is not
    # reported a second time as overlapping, and a real overlap is not missed.
    timed.sort()
    for (_, earlier_end, earlier_index), (later_start, _, later_index) in zip(timed, timed[1:]):
        if later_start < earlier_end:
            errors.append(
                f"$.rallies[{later_index}]: startMs ({later_start}) is before endMs ({earlier_end}) of "
                f"$.rallies[{earlier_index}]; rallies must not overlap"
            )


def _check_actions(actions: list[Any], rally_path: str, video_duration_ms: int | None, errors: list[str]) -> None:
    end_events = []
    for index, action in enumerate(actions):
        if not isinstance(action, dict):
            continue
        path = f"{rally_path}.actions[{index}]"
        start, end = action.get("startMs"), action.get("endMs")
        # A single contact may be annotated as one instant, so start == end is allowed here.
        if _is_integer(start) and _is_integer(end) and start > end:
            errors.append(f"{path}: startMs ({start}) must not be after endMs ({end})")
        if video_duration_ms is not None and _is_integer(end) and end > video_duration_ms:
            errors.append(f"{path}: endMs ({end}) is after the end of the video ({video_duration_ms} ms)")
        if "endEvent" in action:
            if not isinstance(action["endEvent"], bool):
                errors.append(f"{path}.endEvent: expected boolean, found {_type_name(action['endEvent'])}")
            elif action["endEvent"]:
                end_events.append(index)
    if len(end_events) > 1:
        listed = ", ".join(f"actions[{index}]" for index in end_events)
        errors.append(f"{rally_path}: {len(end_events)} actions are flagged as end event ({listed}); at most one is allowed")
