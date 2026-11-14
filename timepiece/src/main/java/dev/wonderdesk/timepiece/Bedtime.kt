package dev.wonderdesk.timepiece

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Bedtime reminder: a gentle notice at the time you want to start winding down, telling you when your next alarm
 * rings and how much sleep that leaves. One inexact alarm a day, re-armed after restarts.
 */
object Bedtime {
    private const val CHANNEL = "timepiece-bedtime"
    private fun prefs(c: Context) = c.getSharedPreferences("timepiece", Context.MODE_PRIVATE)
    fun enabled(c: Context) = prefs(c).getBoolean("bedtime", false)
    fun minutes(c: Context) = prefs(c).getInt("bedtimeMin", 22 * 60 + 30)

    fun set(c: Context, on: Boolean, minutes: Int = minutes(c)) {
        prefs(c).edit().putBoolean("bedtime", on).putInt("bedtimeMin", minutes).apply()
        schedule(c)
    }

    private fun pending(c: Context) = PendingIntent.getBroadcast(c, 0xBED, Intent(c, BedtimeReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun schedule(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        am.cancel(pending(c))
        if (!enabled(c)) return
        val m = minutes(c); val now = LocalDateTime.now()
        var at = now.toLocalDate().atTime(m / 60, m % 60)
        if (!at.isAfter(now)) at = at.plusDays(1)
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), pending(c))
    }

    fun notify(c: Context) {
        val next = Clocks.alarms(c).filter { it.enabled }.map { nextTrigger(it) }.minOrNull()
        val text = if (next == null) "No alarm set for the morning" else {
            val h = (next - System.currentTimeMillis()) / 3_600_000.0
            "Alarm at " + java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(next)) + " · " +
                (if (h < 24) "%.1f hours of sleep if you go now".format(h) else "not in the next day")
        }
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Bedtime reminder", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(c, 0xBED, Intent(c, MainActivity::class.java).putExtra("tab", "alarms"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        runCatching { nm.notify(0xBED, Notification.Builder(c, CHANNEL).setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Time to wind down").setContentText(text).setContentIntent(open).setAutoCancel(true).build()) }
    }
}

class BedtimeReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { if (Bedtime.enabled(c)) Bedtime.notify(c); Bedtime.schedule(c) }
}
