"""Volleyball event contract and verified-only statistics for both teams."""

from collections import Counter, defaultdict

OUTCOMES = {
    "rally": {"won"},
    "attack": {"kill", "error", "in_play"},
    "serve": {"ace", "error", "in_play"},
    "reception": {"in_play", "error"},
    "block": {"solo", "assist", "error"},
    "assist": {"credited"},
    "dig": {"credited"},
    "handling": {"error"},
}
COUNTS = {
    "attack": {"kill": "kills", "error": "attack_errors"},
    "serve": {"ace": "aces", "error": "serve_errors"},
    "reception": {"error": "reception_errors"},
    "block": {"solo": "solo_blocks", "assist": "block_assists", "error": "block_errors"},
    "assist": {"credited": "assists"},
    "dig": {"credited": "digs"},
    "handling": {"error": "handling_errors"},
}
METRICS = (
    "attack_attempts", "kills", "attack_errors", "serve_attempts", "aces", "serve_errors",
    "reception_attempts", "reception_errors", "solo_blocks", "block_assists",
    "block_errors", "assists", "digs", "handling_errors",
)


def validate_event(data, duration_ms):
    if not isinstance(data, dict):
        raise ValueError("Event must be an object.")
    keys = ("team", "set", "rally", "at_ms", "kind", "outcome")
    event = {key: data.get(key) for key in keys}
    event["player"] = data.get("player")
    if event["team"] not in ("home", "away"):
        raise ValueError("Choose a team.")
    for key, lower, upper in (
        ("set", 1, 5), ("rally", 1, 999), ("at_ms", 0, duration_ms - 1)
    ):
        if type(event[key]) is not int or not lower <= event[key] <= upper:
            raise ValueError(f"{key} must be an integer between {lower} and {upper}.")
    kind, outcome, player = event["kind"], event["outcome"], event["player"]
    if not isinstance(kind, str) or not isinstance(outcome, str) or outcome not in OUTCOMES.get(kind, ()):
        raise ValueError("Invalid event type or outcome.")
    if player is not None and (type(player) is not int or not 1 <= player <= 99 or kind == "rally"):
        raise ValueError("A jersey number must be 1–99. Rally points belong to a team.")
    return event


def tally(events):
    counts = {team: Counter() for team in ("home", "away")}
    players = {team: defaultdict(Counter) for team in counts}
    sets = defaultdict(lambda: {"home": 0, "away": 0})
    verified = 0
    pending = 0
    for event in events:
        if event["status"] != "verified":
            pending += event["status"] == "pending"
            continue
        verified += 1
        team, kind, outcome = event["team"], event["kind"], event["outcome"]
        if kind == "rally":
            sets[event["set"]][team] += 1
            continue
        targets = [counts[team]]
        if event["player"] is not None:
            targets.append(players[team][event["player"]])
        for counter in targets:
            if kind in ("attack", "serve", "reception"):
                counter[f"{kind}_attempts"] += 1
            metric = COUNTS[kind].get(outcome)
            if metric:
                counter[metric] += 1

    def summarize(counter):
        result = {metric: counter[metric] for metric in METRICS}
        attempts = counter["attack_attempts"]
        result["hitting_percentage"] = (
            (counter["kills"] - counter["attack_errors"]) / attempts if attempts else None
        )
        result["team_blocks"] = counter["solo_blocks"] + counter["block_assists"] / 2
        return result

    return {
        "verified_events": verified,
        "pending_events": pending,
        "sets": [{"set": number, **score} for number, score in sorted(sets.items())],
        "teams": {
            team: {
                "totals": summarize(counts[team]),
                "players": [
                    {"player": jersey, **summarize(counter)}
                    for jersey, counter in sorted(players[team].items())
                ],
            }
            for team in counts
        },
    }
