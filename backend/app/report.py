"""Match report computed from reviewed rallies (docs/API_V1.md, section 7).

Pure: no database, no clock. Only user decisions count; a model proposal never becomes a point.
A number is reported only when it was counted. What was not measured is null with status UNKNOWN,
and a count that can still grow is PARTIAL.

The team points and the set score answer different questions. The points count every verified
rally of the video. The set score needs the rallies in order, so it ends at the first rally
without a winner: whoever won that rally decides where every later point belongs.

One video may hold several matches (ADR-0118). A match ends when a team wins the sets the rule
asks for (FIVB 6.3.1), and the next rally opens a new match with the same rule. The count splits
the video into parts: every finished match, then the sets after the last finished match, which
finish no match. Each part carries its own statistics; the video keeps the total.

An analyzer misses rallies, and nothing here can know how many. The user can add, split, merge
and move rallies, and can then confirm that the list holds every rally of the video. Until that
confirmation every count is a lower bound and a set boundary may sit in the wrong place,
however many cards the user answered.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Mapping, Sequence

HOME = "HOME"
AWAY = "AWAY"
SIDES = (HOME, AWAY)
STATUSES = ("PROPOSED", "VERIFIED", "UNRESOLVED", "REJECTED")

WIN_BY = 2
# What the app offers and the server accepts: a match of best of three or of five, and three set lengths.
SETS_TO_WIN = (2, 3)
TARGETS = (25, 21, 15)


@dataclass(frozen=True)
class MatchRules:
    """The rule the user chose for a match. The server counts the sets of its rallies with it.

    The deciding set is set number 2 * sets_to_win - 1 and ends at `deciding_set_target`; every
    other set ends at `set_target`, and every set needs a lead of WIN_BY points. A match ends when
    a team has won `sets_to_win` sets; the next rally opens a new match of the same rule.
    """
    sets_to_win: int = 3
    set_target: int = 25
    deciding_set_target: int = 15

    @property
    def deciding_set_number(self) -> int:
        return 2 * self.sets_to_win - 1

    @classmethod
    def of(cls, match) -> "MatchRules":
        """The rule stored on a row of the matches table."""
        return cls(match["sets_to_win"], match["set_target"], match["deciding_set_target"])

    def target(self, set_number: int) -> int:
        return self.deciding_set_target if set_number == self.deciding_set_number else self.set_target

    def payload(self) -> dict[str, int]:
        """A new dict on every call, so a caller that edits its copy changes no other answer."""
        return {"setsToWin": self.sets_to_win, "setTarget": self.set_target,
                "decidingSetTarget": self.deciding_set_target, "winBy": WIN_BY,
                "decidingSetNumber": self.deciding_set_number}


# The rule of every match that was stored before the user could choose one.
FIVB = MatchRules()

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


# The kinds of the parts of a video. MATCH: a team won the sets the rule asks for. AFTER_MATCH:
# sets after the last finished match that finish no match. UNFINISHED: the same, in a video in
# which no match finished. STOPPED: the part the count was in when it met a rally without a
# winner; it may still become a match, and the count can not tell.
MATCH, AFTER_MATCH, UNFINISHED, STOPPED = "MATCH", "AFTER_MATCH", "UNFINISHED", "STOPPED"
PART_KINDS = (MATCH, AFTER_MATCH, UNFINISHED, STOPPED)


def _walk(numbered: Sequence[tuple[int, Mapping[str, Any]]], rules: MatchRules,
          after: dict[int, dict[str, int]] | None = None) -> tuple[list[dict[str, Any]], int | None]:
    """Split (sequence number, rally) pairs in match order into parts, up to the first rally without a winner.

    Returns the parts, each with its rallies under "_rallies", and the sequence number of that
    first rally (None when the count reached the end). `after`, when given, receives the score of
    the running set after each counted rally.
    """
    parts: list[dict[str, Any]] = []
    part: dict[str, Any] | None = None
    current: dict[str, Any] | None = None
    stopped_at = None
    for sequence, rally in numbered:
        if rally["status"] != "VERIFIED":
            stopped_at = sequence
            break
        if part is None:
            part = {"number": len(parts) + 1, "kind": None, "winner": None, "setsWon": {"home": 0, "away": 0},
                    "sets": [], "firstRally": sequence, "lastRally": sequence, "_rallies": []}
            parts.append(part)
        if current is None:
            current = {"number": len(part["sets"]) + 1, "home": 0, "away": 0, "complete": False}
            part["sets"].append(current)
        winner = rally["winner"]
        current[winner.lower()] += 1
        part["lastRally"] = sequence
        part["_rallies"].append(rally)
        if after is not None:
            after[sequence] = {"match": part["number"], "set": current["number"], "home": current["home"], "away": current["away"]}
        target = rules.target(current["number"])
        if max(current["home"], current["away"]) >= target and abs(current["home"] - current["away"]) >= WIN_BY:
            current["complete"] = True
            current = None
            part["setsWon"][winner.lower()] += 1
            if part["setsWon"][winner.lower()] == rules.sets_to_win:
                part["kind"], part["winner"] = MATCH, winner
                part = None
    if part is not None:
        finished = any(entry["kind"] == MATCH for entry in parts)
        part["kind"] = STOPPED if stopped_at is not None else AFTER_MATCH if finished else UNFINISHED
    return parts, stopped_at


def _public(part: Mapping[str, Any]) -> dict[str, Any]:
    return {key: value for key, value in part.items() if not key.startswith("_")}


def running_score(rallies: Sequence[Mapping[str, Any]], *, rules: MatchRules
                  ) -> tuple[list[dict[str, Any]], dict[int, dict[str, int]]]:
    """The parts of a rally list counted with `rules`, and the score of the running set after each counted rally by sequence number.

    The same walk as the score of the report, without the statistics of each part: a rejected
    rally has no entry, and neither has the first rally without a winner nor any rally after it.
    """
    after: dict[int, dict[str, int]] = {}
    parts, _ = _walk(_numbered(rallies), rules, after)
    return [_public(part) for part in parts], after


def _numbered(rallies: Sequence[Mapping[str, Any]]) -> list[tuple[int, Mapping[str, Any]]]:
    # A rejected rally is not a rally: it takes part in no score, no statistic and no denominator.
    # It keeps its sequence number, which is what the client knows the rally by.
    return [(rally.get("sequence", position), rally) for position, rally in enumerate(rallies, start=1)
            if rally["status"] != "REJECTED"]


def _statistics(verified: Sequence[Mapping[str, Any]], complete: bool
                ) -> tuple[dict[str, Any], list[dict[str, Any]], dict[str, int | None]]:
    """Team values, player rows and unattributed points of verified rallies.

    `complete` says that these rallies are every rally of what they describe; only then is a
    count MEASURED. A player row needs a scoring action and a jersey the user chose.
    """
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
        actions_complete = complete and all(rally["terminal_action"] in ACTION_FIELDS for rally in points)
        if verified:
            # One verified rally is enough for both sides: a side that won none of them has counted 0 points.
            team = {"points": {"value": len(points), "status": "MEASURED" if complete else "PARTIAL"}}
            unattributed[side.lower()] = len(points) - sum(entry["points"] for entry in players.values() if entry["side"] == side)
        else:
            team = {"points": dict(UNKNOWN_VALUE)}
            unattributed[side.lower()] = None
        for action, field in ACTION_FIELDS.items():
            team[field] = _value(sum(rally["terminal_action"] == action for rally in points), actions_complete)
        teams[side.lower()] = team
    return teams, [players[key] for key in sorted(players)], unattributed


def build_report(rallies: Sequence[Mapping[str, Any]], *, rules: MatchRules, rallies_may_be_missing: bool = False,
                 proposed: int | None = None) -> dict[str, Any]:
    """Build the computed part of the report from the rallies of one job, given in match order, with the rule of its match.

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

    teams, players, unattributed = _statistics(verified, winners_complete)
    parts, stopped_at = _walk(numbered, rules)
    for part in parts:
        # A part is complete when the list is and the count did not stop inside it.
        part_teams, part_players, part_unattributed = _statistics(
            part.pop("_rallies"), not rallies_may_be_missing and part["kind"] != STOPPED)
        part.update(teams=part_teams, players=part_players, unattributed=part_unattributed)

    with_action = [rally for rally in verified if rally["terminal_action"] in ACTION_FIELDS]
    with_scorer = sum(entry["points"] for entry in players)
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
        "rules": rules.payload(),
        "score": {
            "status": "UNKNOWN" if not verified else "MEASURED" if stopped_at is None and not rallies_may_be_missing else "PARTIAL",
            "matches": parts,
            "stoppedAtRally": stopped_at,
            # The rallies from the first one without a winner to the end: no part holds them,
            # while the video totals still count the verified ones among them.
            "unplacedRallies": 0 if stopped_at is None else sum(sequence >= stopped_at for sequence, _ in numbered),
        },
        "teams": teams,
        "players": players,
        "unattributed": unattributed,
        "coverage": [
            _coverage("RALLY_WINNER", len(eligible), len(verified) + proposed_only, len(verified)),
            _coverage("TERMINAL_ACTION", len(eligible), len(with_action), len(with_action)),
            _coverage("SCORER", scorer_eligible, with_scorer, with_scorer),
        ],
    }
