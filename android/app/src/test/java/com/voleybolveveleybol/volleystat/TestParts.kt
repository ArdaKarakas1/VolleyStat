package com.voleybolveveleybol.volleystat

import com.voleybolveveleybol.volleystat.model.MatchPart
import com.voleybolveveleybol.volleystat.model.SetScore
import org.json.JSONArray
import org.json.JSONObject

/** One part of a video holding [sets], the way the server would count it: a side's sets won are the finished sets it took. */
fun part(vararg sets: SetScore, number: Int = 1, kind: String = MatchPart.UNFINISHED, winner: String? = null, firstRally: Int = 1, lastRally: Int = 1) =
    MatchPart(
        number, kind, winner, sets.count { it.complete && it.home > it.away }, sets.count { it.complete && it.away > it.home },
        sets.toList(), firstRally, lastRally,
    )

/** The JSON of one part of a video. The rally list sends it without statistics; [teams] and the rest add them for a report. */
fun partJson(
    sets: List<SetScore>, number: Int = 1, kind: String = MatchPart.UNFINISHED, winner: String? = null,
    teams: JSONObject? = null, players: JSONArray? = null, unattributed: JSONObject? = null,
): JSONObject = JSONObject().put("number", number).put("kind", kind).put("winner", winner ?: JSONObject.NULL)
    .put("setsWon", JSONObject().put("home", sets.count { it.complete && it.home > it.away }).put("away", sets.count { it.complete && it.away > it.home }))
    .put("sets", JSONArray(sets.map { JSONObject().put("number", it.number).put("home", it.home).put("away", it.away).put("complete", it.complete) }))
    .put("firstRally", 1).put("lastRally", 1)
    .putOpt("teams", teams).putOpt("players", players).putOpt("unattributed", unattributed)
