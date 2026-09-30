package com.vamahan.dailydraw

import android.content.Context
import com.vamahan.dailydraw.draw.DrawRound
import com.vamahan.dailydraw.draw.DrawTicket
import com.vamahan.dailydraw.draw.RoundStatus
import org.json.JSONArray
import org.json.JSONObject

/**
 * A round as people say it: the UTC date it starts on and its number that day,
 * "2026-09-30 · #50". The on-chain id keeps counting from genesis and reaches
 * the thousands within days of five-minute rounds; nobody should read that.
 */
fun roundLabel(startTs: Long, roundSecs: Long): String {
    val day = Math.floorDiv(startTs, 86_400L)
    val index = (startTs - day * 86_400L) / roundSecs + 1
    return "${java.time.LocalDate.ofEpochDay(day)} · #$index"
}

fun com.vamahan.dailydraw.draw.DrawConfig.labelOf(round: Long) = roundLabel(genesisTs + round * roundSecs, roundSecs)

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
    /** When this round is drawn, from the round itself: a schedule change moves
     * future rounds only, so the config cannot say this for past ones. */
    val drawTs: Long = 0,
    /** The live ticket account, or null once it has been closed. */
    val ticket: String? = null,
) {
    val key get() = "$round:$index"
    val hits get() = picks.filter { it in winning }.toSet()
    val canClaim get() = outcome == Outcome.Won && ticket != null
    val canReturnDeposit get() = (outcome == Outcome.NoMatch || outcome == Outcome.Matched) && ticket != null

    companion object {
        /**
         * The result of a ticket whose account is gone: the crank pays winners and
         * returns losers' deposits right after the draw, so the ticket is often
         * closed before the app or the notification looks. The round (kept for an
         * hour) and the picks the player made are enough to say what happened.
         */
        fun closed(round: Long, index: Int, picks: List<Int>, r: DrawRound): MyResult {
            val matches = picks.count { it in r.winning }
            val won = r.best > 0 && matches == r.best
            return MyResult(
                round, index, picks,
                outcome = when {
                    won -> Outcome.Claimed
                    matches > 0 -> Outcome.Matched
                    else -> Outcome.NoMatch
                },
                winning = r.winning, matches = matches, best = r.best,
                prize = if (won) r.share else 0, drawTs = r.drawTs, ticket = null,
            )
        }

        fun of(ticket: DrawTicket, index: Int, round: DrawRound?, now: Long, scheduledDraw: Long): MyResult {
            val drawTs = round?.drawTs?.takeIf { it > 0 } ?: scheduledDraw
            val base = MyResult(ticket.round, index, ticket.picks, Outcome.Waiting, drawTs = drawTs, ticket = ticket.address)
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
                drawTs = o.optLong("drawTs"),
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
                put("matches", r.matches); put("best", r.best); put("prize", r.prize); put("drawTs", r.drawTs)
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
