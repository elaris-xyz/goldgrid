package com.vamahan.dailydraw

import android.content.Context
import com.vamahan.dailydraw.draw.DrawRound
import com.vamahan.dailydraw.draw.DrawTicket
import com.vamahan.dailydraw.draw.RoundStatus
import org.json.JSONArray
import org.json.JSONObject

/** Where one of the player's tickets stands, in the words the screen uses. */
enum class Outcome { Waiting, Drawing, NoMatch, Matched, Won, Claimed }

/**
 * One ticket and its result. Kept on the device as well as read from the chain:
 * once a prize is claimed or a deposit returned, the ticket account is closed,
 * and when a round's last ticket goes the round account goes too, taking the
 * winning numbers with it. The player's history must outlive both.
 */
data class MyResult(
    val round: Long,
    val index: Int,
    val picks: List<Int>,
    val outcome: Outcome,
    val winning: List<Int> = emptyList(),
    val matches: Int = 0,
    val best: Int = 0,
    val prize: Long = 0,
    /** The live ticket account, or null once it has been closed. */
    val ticket: String? = null,
) {
    val key get() = "$round:$index"
    val hits get() = picks.filter { it in winning }.toSet()
    val canClaim get() = outcome == Outcome.Won && ticket != null
    val canReturnDeposit get() = (outcome == Outcome.NoMatch || outcome == Outcome.Matched) && ticket != null

    companion object {
        fun of(ticket: DrawTicket, index: Int, round: DrawRound?, now: Long, drawTs: Long): MyResult {
            val base = MyResult(ticket.round, index, ticket.picks, Outcome.Waiting, ticket = ticket.address)
            if (round == null || round.status != RoundStatus.Settled || !ticket.scored) {
                return base.copy(outcome = if (now < drawTs) Outcome.Waiting else Outcome.Drawing)
            }
            val won = round.best > 0 && ticket.matches == round.best
            return base.copy(
                outcome = when {
                    won && ticket.claimed -> Outcome.Claimed
                    won -> Outcome.Won
                    ticket.matches > 0 -> Outcome.Matched
                    else -> Outcome.NoMatch
                },
                winning = round.winning, matches = ticket.matches, best = round.best,
                prize = if (won) round.share else 0,
            )
        }
    }
}

/** The player's results, per wallet, in the app's private preferences. */
class ResultStore(context: Context) {
    private val prefs = context.getSharedPreferences("results", Context.MODE_PRIVATE)

    fun load(wallet: String): List<MyResult> = runCatching {
        val array = JSONArray(prefs.getString(wallet, "[]"))
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            MyResult(
                round = o.getLong("round"), index = o.getInt("index"), picks = o.ints("picks"),
                outcome = Outcome.valueOf(o.getString("outcome")), winning = o.ints("winning"),
                matches = o.getInt("matches"), best = o.getInt("best"), prize = o.getLong("prize"),
                ticket = o.optString("ticket").ifEmpty { null },
            )
        }
    }.getOrDefault(emptyList())

    fun save(wallet: String, results: List<MyResult>) {
        val array = JSONArray()
        results.take(KEEP).forEach { r ->
            array.put(JSONObject().apply {
                put("round", r.round); put("index", r.index); put("picks", JSONArray(r.picks))
                put("outcome", r.outcome.name); put("winning", JSONArray(r.winning))
                put("matches", r.matches); put("best", r.best); put("prize", r.prize)
                put("ticket", r.ticket ?: "")
            })
        }
        prefs.edit().putString(wallet, array.toString()).apply()
    }

    private fun JSONObject.ints(name: String): List<Int> {
        val a = getJSONArray(name)
        return (0 until a.length()).map { a.getInt(it) }
    }

    private companion object {
        const val KEEP = 40
    }
}
