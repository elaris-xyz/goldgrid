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
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
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
    private const val GOLD = 0xFFF5C451.toInt()
    private const val INK = 0xFF0E0F13.toInt()
    private const val CARD = 0xFF2A2D38.toInt()
    private const val MUTED = 0xFF8A8F9C.toInt()
    private const val WIN = 0xFF4ADE80.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
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
        val (title, text) = when (result.outcome) {
            Outcome.Won -> "🎉 You won ${formatSkr(result.prize)} SKR!" to
                "Round #$round: ${result.matches} of your numbers came up and nobody did better. Tap to claim."
            Outcome.Matched -> "So close — ${result.matches} matched" to
                "Round #$round went to a ticket with ${result.best}. A new round is open now."
            Outcome.NoMatch -> "Round #$round is drawn" to
                "No match this time. The next round is open — keep your streak going."
            Outcome.Claimed -> return
            Outcome.Waiting, Outcome.Drawing -> {
                if (attempt < MAX_TRIES) schedule(context, round, index, ticket, System.currentTimeMillis() + RETRY_MS, attempt + 1)
                return
            }
        }
        notify(context, round, title, text, ballsPicture(r.winning, t.picks))
    }

    /**
     * The result as the app shows it: the winning balls in gold, the player's
     * below them with every hit in green. Words alone read like a bank alert.
     */
    private fun ballsPicture(winning: List<Int>, picks: List<Int>): Bitmap {
        val width = 1000
        val height = 440
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(INK)
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MUTED; textSize = 34f }
        val ball = Paint(Paint.ANTI_ALIAS_FLAG)
        val digits = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 54f; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
        }
        val radius = 62f
        val gap = 36f
        val startX = (width - (5 * 2 * radius + 4 * gap)) / 2 + radius
        fun row(numbers: List<Int>, y: Float, fill: (Int) -> Int, ink: (Int) -> Int) {
            numbers.forEachIndexed { i, n ->
                val x = startX + i * (2 * radius + gap)
                ball.color = fill(n)
                canvas.drawCircle(x, y, radius, ball)
                digits.color = ink(n)
                canvas.drawText("$n", x, y + 19f, digits)
            }
        }
        val hits = picks.filter { it in winning }.toSet()
        canvas.drawText("Winning numbers", startX - radius, 52f, label)
        row(winning, 135f, { GOLD }, { INK })
        canvas.drawText("Your numbers", startX - radius, 262f, label)
        row(picks, 345f, { if (it in hits) WIN else CARD }, { if (it in hits) INK else WHITE })
        return bitmap
    }

    private fun notify(context: Context, round: Long, title: String, text: String, picture: Bitmap) {
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
            .setColor(GOLD)
            .setContentTitle(title)
            .setContentText(text)
            .setLargeIcon(picture)
            .setStyle(android.app.Notification.BigPictureStyle().bigPicture(picture).setSummaryText(text).bigLargeIcon(null as Bitmap?))
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
