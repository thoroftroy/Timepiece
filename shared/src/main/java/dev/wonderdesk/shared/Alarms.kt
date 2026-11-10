package dev.wonderdesk.shared

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import java.util.Calendar

data class AlarmEntry(val id: String, val hour: Int, val minute: Int, val label: String, val enabled: Boolean)

private const val ALARM_PREFS = "timepiece-alarms"
private const val ALARM_CHANNEL = "timepiece-alarms"

fun loadAlarms(context: Context): List<AlarmEntry> {
    val prefs = context.getSharedPreferences(ALARM_PREFS, Context.MODE_PRIVATE)
    val raw = prefs.getString("alarms", null) ?: return emptyList()
    return runCatching {
        val array = org.json.JSONArray(raw)
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            AlarmEntry(o.getString("id"), o.getInt("hour"), o.getInt("minute"), o.optString("label", "Alarm"), o.optBoolean("enabled", true))
        }
    }.getOrDefault(emptyList())
}

fun saveAlarms(context: Context, alarms: List<AlarmEntry>) {
    val array = org.json.JSONArray()
    alarms.forEach { a ->
        array.put(org.json.JSONObject().put("id", a.id).put("hour", a.hour).put("minute", a.minute).put("label", a.label).put("enabled", a.enabled))
    }
    context.getSharedPreferences(ALARM_PREFS, Context.MODE_PRIVATE).edit().putString("alarms", array.toString()).apply()
}

fun scheduleAlarm(context: Context, alarm: AlarmEntry) {
    val am = context.getSystemService(AlarmManager::class.java)
    val intent = Intent(context, AlarmReceiver::class.java).putExtra("label", alarm.label)
    val pi = PendingIntent.getBroadcast(context, alarm.id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    if (!alarm.enabled) { am.cancel(pi); return }
    val next = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, alarm.hour)
        set(Calendar.MINUTE, alarm.minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
    }
    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.timeInMillis, pi)
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val label = intent.getStringExtra("label") ?: "Alarm"
        val mgr = context.getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(NotificationChannel(ALARM_CHANNEL, "Alarms", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM).build())
            enableVibration(true)
        })
        val open = PendingIntent.getActivity(context, 0, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClassName("dev.wonderdesk.timepiece", "dev.wonderdesk.timepiece.MainActivity"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val note = android.app.Notification.Builder(context, ALARM_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(label)
            .setContentText("Wonderdesk alarm")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        mgr.notify(alarmId(intent), note)
    }
    private fun alarmId(intent: Intent) = intent.getStringExtra("label")?.hashCode() ?: 1
}
