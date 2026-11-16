"""Match report computed from reviewed rallies (docs/API_V1.md, section 7).

Pure: no database, no clock. Only user decisions count; a model proposal never becomes a point.
A number is reported only when it was counted. What was not measured is null with status UNKNOWN,
and a count that can still grow is PARTIAL.

The team points and the set score answer different questions. The points count every verified
rally of the match. The set score needs the rallies in order, so it ends at the first rally
without a winner: whoever won that rally decides where every later point belongs.

An analyzer misses rallies, and nothing here can know how many. The user can add, split, merge
and move rallies, and can then confirm that the list holds every rally of the video. Until that
confirmation every count is a lower bound and a set boundary may sit in the wrong place,
however many cards the user answered.
"""
from __future__ import annotations

from typing import Any, Mapping, Sequence

HOME = "HOME"
AWAY = "AWAY"
SIDES = (HOME, AWAY)
STATUSES = ("PROPOSED", "VERIFIED", "UNRESOLVED", "REJECTED")

# No rule ends the match: amateur and training matches often play a fixed number of sets, so
# rallies keep opening sets. Only the set with the deciding number is played to the short target.
RULES = {"setTarget": 25, "decidingSetTarget": 15, "winBy": 2, "decidingSetNumber": 5}

SCORING_FIELDS = {"ATTACK": "attackPoints", "BLOCK": "blockPoints", "ACE": "aces"}
ACTION_FIELDS = {**SCORING_FIELDS, "OPPONENT_ERROR": "opponentErrors"}

UNKNOWN_VALUE = {"value": None, "status": "UNKNOWN"}


def _value(count: int, complete: bool) -> dict[str, Any]:
    if complete:
        return {"value": count, "status": "MEASURED"}
    if count > 0:
        return {"value": count, "status": "PARTIAL"}
    # A lower bound of zero says nothing, so it is not printed as a number.
    return dict(UNKNOWN_VALUE)


def _coverage(metric: str, eligible: int, observed: int, verified: int) -> dict[str, Any]:
    status = "UNKNOWN" if verified == 0 else "MEASURED" if verified == eligible else "PARTIAL"
    return {"metricId": metric, "eligibleN": eligible, "observedN": observed, "verifiedN": verified, "status": status}


def _score(numbered: Sequence[tuple[int, Mapping[str, Any]]], may_be_missing: bool,
           after: dict[int, dict[str, int]] | None = None) -> dict[str, Any]:
    """Count sets over (sequence number, rally) pairs in match order, up to the first rally without a winner.

    `after`, when given, receives the score of the running set after each counted rally.
    """
    sets: list[dict[str, Any]] = []
    won = {HOME: 0, AWAY: 0}
    current: dict[str, Any] | None = None
    stopped_at = None
    for sequence, rally in numbered:
        if rally["status"] != "VERIFIED":
            stopped_at = sequence
            break
        if current is None:
            current = {"number": len(sets) + 1, "home": 0, "away": 0, "complete": False}
        current[rally["winner"].lower()] += 1
        if after is not None:
            after[sequence] = {"set": current["number"], "home": current["home"], "away": current["away"]}
        target = RULES["decidingSetTarget"] if current["number"] == RULES["decidingSetNumber"] else RULES["setTarget"]
        lead = abs(current["home"] - current["away"])
        if max(current["home"], current["away"]) >= target and lead >= RULES["winBy"]:
            current["complete"] = True
            won[rally["winner"]] += 1
            sets.append(current)
            current = None
    if current is not None:
        sets.append(current)
    if not any(rally["status"] == "VERIFIED" for _, rally in numbered):
        return {"status": "UNKNOWN", "setsWon": {"home": None, "away": None}, "sets": [], "stoppedAtRally": stopped_at}
    return {
        "status": "MEASURED" if stopped_at is None and not may_be_missing else "PARTIAL",
        # Only finished sets count, so under PARTIAL these are lower bounds.
        "setsWon": {"home": won[HOME], "away": won[AWAY]},
        "sets": sets,
        "stoppedAtRally": stopped_at,
    }


def running_score(rallies: Sequence[Mapping[str, Any]]) -> tuple[list[dict[str, Any]], dict[int, dict[str, int]]]:
    """The sets of a rally list, and the score of the running set after each counted rally by sequence number.

    The same walk as the score of the report: a rejected rally has no entry, and neither has the
    first rally without a winner nor any rally after it.
    """
    after: dict[int, dict[str, int]] = {}
    sets = _score(_numbered(rallies), True, after)["sets"]
    return sets, after


def _numbered(rallies: Sequence[Mapping[str, Any]]) -> list[tuple[int, Mapping[str, Any]]]:
    # A rejected rally is not a rally: it takes part in no score, no statistic and no denominator.
    # It keeps its sequence number, which is what the client knows the rally by.
    return [(rally.get("sequence", position), rally) for position, rally in enumerate(rallies, start=1)
            if rally["status"] != "REJECTED"]


def build_report(rallies: Sequence[Mapping[str, Any]], *, rallies_may_be_missing: bool = False,
                 proposed: int | None = None) -> dict[str, Any]:
    """Build the computed part of the report from the rallies of one job, given in match order.

    `rallies_may_be_missing` is true until the user confirmed that the list holds every rally of
    the video. Then no count is reported as MEASURED. `proposed` is the number of rallies the
    analyzer proposed; when absent every rally of the list counts as a proposal.

    Each record has `status` (PROPOSED, VERIFIED, UNRESOLVED or REJECTED), `winner` (HOME or AWAY
    exactly when the status is VERIFIED, otherwise None), `terminal_action` (ATTACK, BLOCK, ACE,
    OPPONENT_ERROR or None), `scorer_jersey` (int or None) and optionally `proposed_winner`,
    `user_created` (true for a rally the user made) and `sequence` (the number of the rally in
    its job; the position in the list when absent).
    The caller adds the identity fields (matchId, jobId, revision, analyzer, mock, rallyList).
    """
    for rally in rallies:
        if rally["status"] not in STATUSES or (rally["status"] == "VERIFIED") != (rally["winner"] in SIDES):
            raise ValueError(f"inconsistent rally record: {dict(rally)!r}")

    count = {status: sum(rally["status"] == status for rally in rallies) for status in STATUSES}
    numbered = _numbered(rallies)
    eligible = [rally for _, rally in numbered]
    verified = [rally for rally in eligible if rally["status"] == "VERIFIED"]
    winners_complete = bool(verified) and len(verified) == len(eligible) and not rallies_may_be_missing

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
    unattributed: dict[str, int | None] = {}
    for side in SIDES:
        points = [rally for rally in verified if rally["winner"] == side]
        actions_complete = winners_complete and all(rally["terminal_action"] in ACTION_FIELDS for rally in points)
        if verified:
            # One verified rally is enough for both sides: a side that won none of them has counted 0 points.
            team = {"points": {"value": len(points), "status": "MEASURED" if winners_complete else "PARTIAL"}}
            unattributed[side.lower()] = len(points) - sum(entry["points"] for entry in players.values() if entry["side"] == side)
        else:
            team = {"points": dict(UNKNOWN_VALUE)}
            unattributed[side.lower()] = None
        for action, field in ACTION_FIELDS.items():
            team[field] = _value(sum(rally["terminal_action"] == action for rally in points), actions_complete)
        teams[side.lower()] = team

    with_action = [rally for rally in verified if rally["terminal_action"] in ACTION_FIELDS]
    with_scorer = sum(entry["points"] for entry in players.values())
    # A rally that is known to have ended on an opponent error has no scorer to find.
    scorer_eligible = len(eligible) - sum(rally["terminal_action"] == "OPPONENT_ERROR" for rally in verified)
    # A model proposal that nobody reviewed yet is observed but not verified.
    proposed_only = sum(rally["status"] == "PROPOSED" and rally.get("proposed_winner") in SIDES for rally in eligible)

    return {
        "verificationStatus": verification,
        "rallies": {
            # What the analyzer proposed, what the list holds now, and how much of it the user made.
            "proposed": len(rallies) if proposed is None else proposed,
            "total": len(rallies), "userCreated": sum(bool(rally.get("user_created")) for rally in rallies),
            "verified": count["VERIFIED"], "rejected": count["REJECTED"],
            "unresolved": count["UNRESOLVED"], "open": count["PROPOSED"],
        },
        "ralliesMayBeMissing": rallies_may_be_missing,
        "rules": dict(RULES),
        "score": _score(numbered, rallies_may_be_missing),
        "teams": teams,
        "players": [players[key] for key in sorted(players)],
        "unattributed": unattributed,
        "coverage": [
            _coverage("RALLY_WINNER", len(eligible), len(verified) + proposed_only, len(verified)),
            _coverage("TERMINAL_ACTION", len(eligible), len(with_action), len(with_action)),
            _coverage("SCORER", scorer_eligible, with_scorer, with_scorer),
        ],
    }
