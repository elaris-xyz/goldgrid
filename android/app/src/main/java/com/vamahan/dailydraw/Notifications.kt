package com.vamahan.dailydraw

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import com.vamahan.dailydraw.draw.DrawConfig

/** What the player can be told about, each on its own Android channel. */
enum class Alert(val channel: String, val label: String, val importance: Int) {
    Win("wins", "Wins", NotificationManager.IMPORTANCE_HIGH),
    Result("results", "Results", NotificationManager.IMPORTANCE_DEFAULT),
    Round("rounds", "New rounds", NotificationManager.IMPORTANCE_DEFAULT),
}

/** How often a new round is announced. Five-minute rounds make "every round" a lot. */
enum class RoundReminders { Off, Hourly, EveryRound }

/**
 * Every notification goes through here: the player's switches in Settings decide
 * whether it is posted at all, and the sound switch picks between a channel with
 * the Goldgrid chime and a quiet twin (a channel's sound is fixed at creation).
 */
object Notifier {
    private val legacy = listOf("results", "results_chime")

    fun canPost(context: Context): Boolean {
        val granted = Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return granted && context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
    }

    fun enabled(session: Session, alert: Alert) = when (alert) {
        Alert.Win -> session.notifyWins
        Alert.Result -> session.notifyResults
        Alert.Round -> session.roundReminders != RoundReminders.Off
    }

    fun post(context: Context, alert: Alert, id: Int, build: Notification.Builder.() -> Unit) {
        val session = Session(context)
        if (!canPost(context) || !enabled(session, alert)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = ensureChannel(context, manager, alert, loud = session.notifySound)
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_draw)
            .setColor(0xFFFFD803.toInt())
            .setContentIntent(open)
            .setAutoCancel(true)
            .apply(build)
            .build()
        manager.notify(id, notification)
    }

    private fun ensureChannel(context: Context, manager: NotificationManager, alert: Alert, loud: Boolean): String {
        legacy.forEach(manager::deleteNotificationChannel)
        val id = if (loud) alert.channel else "${alert.channel}_quiet"
        if (manager.getNotificationChannel(id) == null) {
            manager.createNotificationChannel(
                NotificationChannel(id, if (loud) alert.label else "${alert.label} (silent)", if (loud) alert.importance else NotificationManager.IMPORTANCE_LOW).apply {
                    if (loud) {
                        setSound(
                            Uri.parse("android.resource://${context.packageName}/${R.raw.goldgrid_chime}"),
                            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                        )
                        enableVibration(true)
                        vibrationPattern = longArrayOf(0, 120, 80, 120, 80, 260)
                    } else {
                        setSound(null, null)
                        enableVibration(false)
                    }
                },
            )
        }
        return id
    }
}

/**
 * "A new round is open", on the player's schedule. The next time comes from the
 * draw's config (kept by the app) and the chain-clock offset; each reminder sets
 * the next one, and the app sets it again whenever it opens.
 */
object RoundReminder {
    private const val REQUEST = 7_001

    fun reschedule(context: Context) {
        val session = Session(context)
        val alarms = context.getSystemService(AlarmManager::class.java)
        val pending = PendingIntent.getBroadcast(
            context, REQUEST, Intent(context, RoundReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val config = session.configData?.let { runCatching { DrawConfig.decode(it) }.getOrNull() }
        if (session.roundReminders == RoundReminders.Off || config == null) {
            alarms.cancel(pending)
            return
        }
        val chainNow = System.currentTimeMillis() / 1000 + session.clockOffset
        val earliest = if (session.roundReminders == RoundReminders.Hourly) maxOf(chainNow, session.lastRoundReminder + 3_600) else chainNow
        val round = config.roundAt(earliest) + 1
        val startsAt = config.genesisTs + round * config.roundSecs
        // A few seconds in, so the round is really open when the player taps.
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, (startsAt - session.clockOffset + 5) * 1000, pending)
    }

    internal fun fire(context: Context) {
        val session = Session(context)
        val config = session.configData?.let { runCatching { DrawConfig.decode(it) }.getOrNull() } ?: return
        val chainNow = System.currentTimeMillis() / 1000 + session.clockOffset
        val round = config.roundAt(chainNow)
        val closesIn = ((config.closeTs(round) - chainNow) / 60).coerceAtLeast(1)
        session.lastRoundReminder = chainNow
        Notifier.post(context, Alert.Round, REQUEST) {
            setContentTitle("Round ${config.labelOf(round)} is open")
            setContentText("Pick your five — entries close in $closesIn min. Free to play.")
        }
        reschedule(context)
    }
}

class RoundReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = RoundReminder.fire(context.applicationContext)
}
