"""Match report computed from reviewed rallies (docs/API_V1.md, section 7).

Pure: no database, no clock. Only user decisions count; a model proposal never becomes a point.
A number is reported only when it was counted. What was not measured is null with status UNKNOWN,
and a count that can still grow is PARTIAL.
"""
from __future__ import annotations

from typing import Any, Mapping, Sequence

HOME = "HOME"
AWAY = "AWAY"
SIDES = (HOME, AWAY)
STATUSES = ("PROPOSED", "VERIFIED", "UNRESOLVED", "REJECTED")

RULES = {"setTarget": 25, "decidingSetTarget": 15, "winBy": 2, "bestOf": 5}

SCORING_FIELDS = {"ATTACK": "attackPoints", "BLOCK": "blockPoints", "ACE": "aces"}
ACTION_FIELDS = {**SCORING_FIELDS, "OPPONENT_ERROR": "opponentErrors"}


def _value(count: int, complete: bool) -> dict[str, Any]:
    if complete:
        return {"value": count, "status": "MEASURED"}
    if count > 0:
        return {"value": count, "status": "PARTIAL"}
    # A lower bound of zero says nothing, so it is not printed as a number.
    return {"value": None, "status": "UNKNOWN"}


def _coverage(metric: str, eligible: int, observed: int, verified: int) -> dict[str, Any]:
    status = "UNKNOWN" if verified == 0 else "MEASURED" if verified == eligible else "PARTIAL"
    return {"metricId": metric, "eligibleN": eligible, "observedN": observed, "verifiedN": verified, "status": status}


def _score(eligible: Sequence[Mapping[str, Any]]) -> dict[str, Any]:
    if not any(rally["status"] == "VERIFIED" for rally in eligible):
        return {"status": "UNKNOWN", "setsWon": {"home": None, "away": None}, "sets": []}
    sets: list[dict[str, Any]] = []
    won = {HOME: 0, AWAY: 0}
    sets_to_win = RULES["bestOf"] // 2 + 1
    current: dict[str, Any] | None = None
    # Once a rally without a winner lies inside a set, the true score of that set is unknown:
    # the set can not be closed, and no later set boundary can be placed either.
    gap = False
    for rally in eligible:
        if max(won.values()) == sets_to_win:
            # The match is decided. Rallies after it do not open another set.
            break
        if current is None:
            current = {"number": len(sets) + 1, "home": 0, "away": 0, "complete": False}
        if rally["status"] != "VERIFIED":
            gap = True
            continue
        current[rally["winner"].lower()] += 1
        target = RULES["decidingSetTarget"] if current["number"] == RULES["bestOf"] else RULES["setTarget"]
        lead = abs(current["home"] - current["away"])
        if not gap and max(current["home"], current["away"]) >= target and lead >= RULES["winBy"]:
            current["complete"] = True
            won[rally["winner"]] += 1
            sets.append(current)
            current = None
    if current is not None and current["home"] + current["away"] > 0:
        sets.append(current)
    return {
        "status": "PARTIAL" if gap else "MEASURED",
        "setsWon": {"home": won[HOME], "away": won[AWAY]},
        "sets": sets,
    }


def build_report(rallies: Sequence[Mapping[str, Any]]) -> dict[str, Any]:
    """Build the computed part of the report from the rallies of one job, given in match order.

    Each record has `status` (PROPOSED, VERIFIED, UNRESOLVED or REJECTED), `winner` (HOME or AWAY
    exactly when the status is VERIFIED, otherwise None), `terminal_action` (ATTACK, BLOCK, ACE,
    OPPONENT_ERROR or None), `scorer_jersey` (int or None) and optionally `proposed_winner`.
    The caller adds the identity fields (matchId, jobId, revision, analyzer, mock).
    """
    for rally in rallies:
        if rally["status"] not in STATUSES or (rally["status"] == "VERIFIED") != (rally["winner"] in SIDES):
            raise ValueError(f"inconsistent rally record: {dict(rally)!r}")

    count = {status: sum(rally["status"] == status for rally in rallies) for status in STATUSES}
    # A rejected rally is not a rally: it takes part in no score, no statistic and no denominator.
    eligible = [rally for rally in rallies if rally["status"] != "REJECTED"]
    verified = [rally for rally in eligible if rally["status"] == "VERIFIED"]
    winners_complete = bool(verified) and len(verified) == len(eligible)

    if count["PROPOSED"] == len(rallies):
        verification = "DRAFT"
    elif count["PROPOSED"] == 0 and count["UNRESOLVED"] == 0:
        verification = "VERIFIED"
    else:
        verification = "PARTIALLY_VERIFIED"

    players: dict[tuple[int, int], dict[str, Any]] = {}
    for rally in verified:
        field = SCORING_FIELDS.get(rally["terminal_action"])
        if field is None or rally["scorer_jersey"] is None:
            continue
        key = (SIDES.index(rally["winner"]), rally["scorer_jersey"])
        entry = players.setdefault(key, {
            "side": rally["winner"], "jersey": rally["scorer_jersey"],
            "points": 0, "attackPoints": 0, "blockPoints": 0, "aces": 0,
        })
        entry["points"] += 1
        entry[field] += 1

    teams: dict[str, Any] = {}
    unattributed: dict[str, int] = {}
    for side in SIDES:
        points = [rally for rally in verified if rally["winner"] == side]
        actions_complete = winners_complete and all(rally["terminal_action"] in ACTION_FIELDS for rally in points)
        team = {"points": _value(len(points), winners_complete)}
        for action, field in ACTION_FIELDS.items():
            team[field] = _value(sum(rally["terminal_action"] == action for rally in points), actions_complete)
        teams[side.lower()] = team
        unattributed[side.lower()] = len(points) - sum(entry["points"] for entry in players.values() if entry["side"] == side)

    with_action = [rally for rally in verified if rally["terminal_action"] in ACTION_FIELDS]
    with_scorer = sum(entry["points"] for entry in players.values())
    # A rally that is known to have ended on an opponent error has no scorer to find.
    scorer_eligible = len(eligible) - sum(rally["terminal_action"] == "OPPONENT_ERROR" for rally in verified)
    # A model proposal that nobody reviewed yet is observed but not verified.
    proposed_only = sum(rally["status"] == "PROPOSED" and rally.get("proposed_winner") in SIDES for rally in eligible)

    return {
        "verificationStatus": verification,
        "rallies": {
            "proposed": len(rallies), "verified": count["VERIFIED"], "rejected": count["REJECTED"],
            "unresolved": count["UNRESOLVED"], "open": count["PROPOSED"],
        },
        "rules": dict(RULES),
        "score": _score(eligible),
        "teams": teams,
        "players": [players[key] for key in sorted(players)],
        "unattributed": unattributed,
        "coverage": [
            _coverage("RALLY_WINNER", len(eligible), len(verified) + proposed_only, len(verified)),
            _coverage("TERMINAL_ACTION", len(eligible), len(with_action), len(with_action)),
            _coverage("SCORER", scorer_eligible, with_scorer, with_scorer),
        ],
    }
