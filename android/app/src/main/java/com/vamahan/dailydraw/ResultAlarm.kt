package com.vamahan.dailydraw

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.vamahan.dailydraw.draw.DrawProgram
import com.vamahan.dailydraw.draw.DrawRound
import com.vamahan.dailydraw.draw.DrawTicket
import com.vamahan.dailydraw.solana.SolanaRpc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * "Your result is in", with the app closed. Entering a round sets an alarm for
 * shortly after its draw; the receiver reads the round and the ticket from the
 * chain and posts what happened. The draw runs on its own schedule, so a round
 * not settled yet only moves the alarm a minute later.
 */
object ResultAlarm {
    private const val CHANNEL = "results"
    internal const val RETRY_MS = 60_000L
    internal const val MAX_TRIES = 15

    fun schedule(context: Context, round: Long, index: Int, ticket: String, atMillis: Long, attempt: Int = 0) {
        val intent = Intent(context, ResultReceiver::class.java)
            .putExtra("round", round).putExtra("index", index).putExtra("ticket", ticket).putExtra("attempt", attempt)
        val pending = PendingIntent.getBroadcast(
            context, (round * 8 + index).toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Inexact is enough: a result a minute late is fine, and exact alarms need a special permission.
        context.getSystemService(AlarmManager::class.java)
            .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
    }

    internal suspend fun check(context: Context, round: Long, index: Int, ticket: String, attempt: Int) {
        val rpc = SolanaRpc(BuildConfig.RPC_URL)
        val (roundData, ticketData) = rpc.multipleAccounts(listOf(DrawProgram.round(round).base58(), ticket))
        val r = roundData?.let(DrawRound::decode)
        val t = ticketData?.let { DrawTicket.decode(ticket, it) }
        if (r == null || t == null) return // claimed or closed already: nothing left to tell
        val result = MyResult.of(t, index, r, now = Long.MAX_VALUE, scheduledDraw = r.drawTs)
        val text = when (result.outcome) {
            Outcome.Won -> "You won ${formatSkr(result.prize)} SKR! Open the app to claim it."
            Outcome.Matched -> "You matched ${result.matches} — the winner had ${result.best}. New round open now."
            Outcome.NoMatch -> "No match this time. The next round is open — your streak is waiting."
            Outcome.Claimed -> return
            Outcome.Waiting, Outcome.Drawing -> {
                if (attempt < MAX_TRIES) schedule(context, round, index, ticket, System.currentTimeMillis() + RETRY_MS, attempt + 1)
                return
            }
        }
        notify(context, round, "Round #$round: ${r.winning.joinToString(" ")}", text)
    }

    private fun notify(context: Context, round: Long, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Draw results", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = android.app.Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_draw)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(android.app.Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(round.toInt(), notification)
    }
}

class ResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val done = goAsync()
        val round = intent.getLongExtra("round", -1)
        val index = intent.getIntExtra("index", 0)
        val ticket = intent.getStringExtra("ticket") ?: return done.finish()
        val attempt = intent.getIntExtra("attempt", 0)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ResultAlarm.check(context.applicationContext, round, index, ticket, attempt)
            } catch (e: Exception) {
                Log.w("DailyDraw", "result check failed", e)
                if (attempt < ResultAlarm.MAX_TRIES) {
                    ResultAlarm.schedule(context, round, index, ticket, System.currentTimeMillis() + ResultAlarm.RETRY_MS, attempt + 1)
                }
            } finally {
                done.finish()
            }
        }
    }
}
