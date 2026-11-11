package dev.wonderdesk.timepiece

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** days: bit 0 = Monday … bit 6 = Sunday. 0 means "once". */
/**
 * days: bit 0 = Monday … bit 6 = Sunday. everyDays > 1 repeats every N days counting from [anchorDay]
 * (epoch day). [date] (epoch day) makes a one-time alarm on that date. [skipUntil] skips any
 * ring before that moment ("Skip next" / "Pause for today"). Empty [sound] means the default alarm.
 */
data class Alarm(
    val id: Int, val hour: Int, val minute: Int, val label: String = "", val enabled: Boolean = true,
    val days: Int = 0, val vibrate: Boolean = true, val snoozeMinutes: Int = 10,
    val everyDays: Int = 0, val anchorDay: Long = java.time.LocalDate.now().toEpochDay(), val date: Long = -1,
    val skipUntil: Long = 0, val sound: String = "", val soundName: String = "", val gradual: Boolean = false,
) {
    val repeating get() = days != 0 || everyDays > 1
    fun toJson() = JSONObject().put("id", id).put("hour", hour).put("minute", minute).put("label", label).put("enabled", enabled)
        .put("days", days).put("vibrate", vibrate).put("snooze", snoozeMinutes).put("every", everyDays).put("anchor", anchorDay)
        .put("date", date).put("skip", skipUntil).put("sound", sound).put("soundName", soundName).put("gradual", gradual)
    companion object {
        fun from(o: JSONObject) = Alarm(o.getInt("id"), o.getInt("hour"), o.getInt("minute"), o.optString("label"), o.optBoolean("enabled", true),
            o.optInt("days"), o.optBoolean("vibrate", true), o.optInt("snooze", 10), o.optInt("every"),
            o.optLong("anchor", java.time.LocalDate.now().toEpochDay()), o.optLong("date", -1), o.optLong("skip"),
            o.optString("sound"), o.optString("soundName"), o.optBoolean("gradual"))
    }
}

data class CountdownTimer(val id: Int, val label: String, val durationMs: Long, val endAt: Long = 0L, val remainingMs: Long = durationMs) {
    val running get() = endAt > 0
    fun remaining(now: Long = System.currentTimeMillis()) = if (running) (endAt - now).coerceAtLeast(0) else remainingMs
    fun toJson() = JSONObject().put("id", id).put("label", label).put("duration", durationMs).put("endAt", endAt).put("remaining", remainingMs)
    companion object {
        fun from(o: JSONObject) = CountdownTimer(o.getInt("id"), o.optString("label"), o.getLong("duration"), o.optLong("endAt"), o.optLong("remaining", o.getLong("duration")))
    }
}

val DAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

fun repeatLabel(a: Alarm): String = when {
    a.everyDays > 1 -> "Every ${a.everyDays} days"
    a.date >= 0 && a.days == 0 -> java.time.LocalDate.ofEpochDay(a.date).format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM"))
    else -> daysLabel(a.days)
}

fun daysLabel(days: Int): String = when (days) {
    0 -> "Once"
    0b1111111 -> "Every day"
    0b0011111 -> "Weekdays"
    0b1100000 -> "Weekends"
    else -> DAY_NAMES.filterIndexed { i, _ -> days and (1 shl i) != 0 }.joinToString(" ")
}

/** Next time [a] should ring after [now], honouring skips, patterns and dates. Pure for testing. */
fun nextTrigger(a: Alarm, now: Long = System.currentTimeMillis()): Long {
    val after = maxOf(now, a.skipUntil)
    val zone = java.time.ZoneId.systemDefault()
    fun at(day: java.time.LocalDate) = day.atTime(a.hour, a.minute).atZone(zone).toInstant().toEpochMilli()
    var day = java.time.Instant.ofEpochMilli(after).atZone(zone).toLocalDate()
    if (a.date >= 0 && a.days == 0 && a.everyDays <= 1) {
        val d = java.time.LocalDate.ofEpochDay(a.date)
        return if (at(d) > after) at(d) else at(day).let { if (it > after) it else at(day.plusDays(1)) }
    }
    repeat(400) {
        val ok = when {
            a.everyDays > 1 -> Math.floorMod(day.toEpochDay() - a.anchorDay, a.everyDays.toLong()) == 0L
            a.days != 0 -> a.days and (1 shl (day.dayOfWeek.value - 1)) != 0
            else -> true
        }
        if (ok && at(day) > after) return at(day)
        day = day.plusDays(1)
    }
    return at(day)
}

object Clocks {
    private const val PREFS = "timepiece-alarms"
    const val RING_CHANNEL = "timepiece-ring-v2"
    const val UPCOMING_CHANNEL = "timepiece-upcoming"
    const val UPCOMING_BASE = 70_000
    const val EXTRA_KIND = "kind"
    const val EXTRA_ID = "id"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun alarms(c: Context): List<Alarm> {
        val p = prefs(c)
        p.getString("alarms2", null)?.let { raw -> return runCatching { JSONArray(raw).let { a -> (0 until a.length()).map { Alarm.from(a.getJSONObject(it)) } } }.getOrDefault(emptyList()) }
        // Migrate alarms saved by older versions.
        val old = p.getString("alarms", null) ?: return emptyList()
        val migrated = runCatching {
            JSONArray(old).let { a -> (0 until a.length()).map { i -> val o = a.getJSONObject(i); Alarm(o.getString("id").hashCode() and 0xFFFFF, o.getInt("hour"), o.getInt("minute"), o.optString("label").takeIf { it != "Alarm" } ?: "", o.optBoolean("enabled", true)) } }
        }.getOrDefault(emptyList())
        saveAlarms(c, migrated)
        return migrated
    }

    fun saveAlarms(c: Context, list: List<Alarm>) { prefs(c).edit().putString("alarms2", JSONArray(list.map { it.toJson() }).toString()).apply(); runCatching { ClockWidget.refresh(c) } }

    fun timers(c: Context): List<CountdownTimer> = runCatching {
        JSONArray(prefs(c).getString("timers", "[]")).let { a -> (0 until a.length()).map { CountdownTimer.from(a.getJSONObject(it)) } }
    }.getOrDefault(emptyList())

    fun saveTimers(c: Context, list: List<CountdownTimer>) { prefs(c).edit().putString("timers", JSONArray(list.map { it.toJson() }).toString()).apply() }

    fun canExact(c: Context): Boolean = Build.VERSION.SDK_INT < 31 || c.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun firePending(c: Context, kind: String, id: Int, label: String): PendingIntent =
        PendingIntent.getBroadcast(c, (if (kind == "timer") 500_000 else if (kind == "snooze") 1_000_000 else 0) + id,
            Intent(c, RingReceiver::class.java).putExtra(EXTRA_KIND, kind).putExtra(EXTRA_ID, id).putExtra("label", label),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun showPending(c: Context) = PendingIntent.getActivity(c, 7, Intent(c, MainActivity::class.java).putExtra("tab", "alarms"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun setAt(c: Context, at: Long, pi: PendingIntent) {
        val am = c.getSystemService(AlarmManager::class.java)
        if (canExact(c)) am.setAlarmClock(AlarmManager.AlarmClockInfo(at, showPending(c)), pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    fun schedule(c: Context, a: Alarm) {
        val pi = firePending(c, "alarm", a.id, a.label)
        val am = c.getSystemService(AlarmManager::class.java)
        am.cancel(pi)
        am.cancel(firePending(c, "snooze", a.id, a.label))
        am.cancel(upcomingPending(c, a.id))
        c.getSystemService(NotificationManager::class.java).cancel(UPCOMING_BASE + a.id)
        if (!a.enabled) return
        val at = nextTrigger(a)
        setAt(c, at, pi)
        val warn = at - 3_600_000L
        if (warn > System.currentTimeMillis()) am.setAndAllowWhileIdle(AlarmManager.RTC, warn, upcomingPending(c, a.id))
        else showUpcoming(c, a)
    }

    private fun upcomingPending(c: Context, id: Int) = PendingIntent.getBroadcast(c, 2_000_000 + id,
        Intent(c, RingReceiver::class.java).setAction(RingReceiver.ACTION_UPCOMING).putExtra(EXTRA_ID, id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** Tray notice for the next hour's alarm, with Skip. */
    fun showUpcoming(c: Context, a: Alarm) {
        if (!c.uiPrefs().getBoolean("upcoming", true)) return
        ensureChannel(c)
        val at = nextTrigger(a)
        if (at - System.currentTimeMillis() > 3_700_000L) return
        val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(at))
        val skip = PendingIntent.getBroadcast(c, 3_000_000 + a.id, Intent(c, RingReceiver::class.java).setAction(RingReceiver.ACTION_SKIP).putExtra(EXTRA_ID, a.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(c, UPCOMING_CHANNEL).setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Upcoming alarm $time").setContentText(a.label.ifBlank { repeatLabel(a) })
            .setContentIntent(showPending(c)).setAutoCancel(true).setWhen(at).setShowWhen(true)
            .addAction(Notification.Action.Builder(null, "Skip this one", skip).build()).build()
        c.getSystemService(NotificationManager::class.java).notify(UPCOMING_BASE + a.id, n)
    }

    private fun Context.uiPrefs() = getSharedPreferences("timepiece", Context.MODE_PRIVATE)

    fun cancel(c: Context, a: Alarm) {
        val am = c.getSystemService(AlarmManager::class.java)
        am.cancel(firePending(c, "alarm", a.id, a.label)); am.cancel(firePending(c, "snooze", a.id, a.label))
    }

    fun snooze(c: Context, id: Int, label: String, minutes: Int) {
        setAt(c, System.currentTimeMillis() + minutes * 60_000L, firePending(c, "snooze", id, label))
    }

    fun scheduleTimer(c: Context, t: CountdownTimer) {
        val pi = firePending(c, "timer", t.id, t.label)
        c.getSystemService(AlarmManager::class.java).cancel(pi)
        if (t.running) setAt(c, t.endAt, pi)
    }

    fun rescheduleAll(c: Context) {
        alarms(c).forEach { schedule(c, it) }
        timers(c).filter { it.running }.forEach { scheduleTimer(c, it) }
    }

    fun ensureChannel(c: Context) {
        val mgr = c.getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(RING_CHANNEL) != null) return
        mgr.deleteNotificationChannel("timepiece-ring")
        mgr.createNotificationChannel(NotificationChannel(RING_CHANNEL, "Ringing alarms and timers", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Shown while an alarm or timer rings. The sound is chosen per alarm in Timepiece."
            setSound(null, null)
            enableVibration(false)
            setBypassDnd(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        mgr.createNotificationChannel(NotificationChannel(UPCOMING_CHANNEL, "Upcoming alarms", NotificationManager.IMPORTANCE_LOW).apply {
            description = "A heads-up an hour before an alarm, with a Skip button"
        })
    }

    fun ringNotificationId(kind: String, id: Int) = if (kind == "timer") 50_000 + id else 10_000 + id

    fun stopRinging(c: Context, kind: String, id: Int) {
        c.getSystemService(NotificationManager::class.java).cancel(ringNotificationId(kind, id))
    }
}

/** Fires for alarms, snoozes and timers: posts an insistent full-screen notification. */
class RingReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val kind = intent.getStringExtra(Clocks.EXTRA_KIND) ?: "alarm"
        val id = intent.getIntExtra(Clocks.EXTRA_ID, 0)
        when (intent.action) {
            ACTION_DISMISS -> { Clocks.stopRinging(c, kind, id); RingService.stop(c); return }
            ACTION_SKIP -> {
                val list = Clocks.alarms(c)
                list.find { it.id == id }?.let { a -> val n = a.copy(skipUntil = nextTrigger(a) + 1000); Clocks.saveAlarms(c, list.map { if (it.id == id) n else it }); Clocks.schedule(c, n) }
                c.getSystemService(NotificationManager::class.java).cancel(Clocks.UPCOMING_BASE + id)
                return
            }
            ACTION_UPCOMING -> { Clocks.alarms(c).find { it.id == id && it.enabled }?.let { Clocks.showUpcoming(c, it) }; return }
            ACTION_SNOOZE -> {
                Clocks.stopRinging(c, kind, id); RingService.stop(c)
                val a = Clocks.alarms(c).find { it.id == id }
                Clocks.snooze(c, id, a?.label ?: intent.getStringExtra("label").orEmpty(), a?.snoozeMinutes ?: 10)
                return
            }
        }
        val baseKind = if (kind == "snooze") "alarm" else kind
        var label = intent.getStringExtra("label").orEmpty()
        if (kind == "alarm") {
            val list = Clocks.alarms(c)
            val a = list.find { it.id == id } ?: return
            label = a.label
            // Repeating alarms reschedule themselves; one-off alarms switch off.
            if (!a.repeating) Clocks.saveAlarms(c, list.map { if (it.id == id) it.copy(enabled = false, date = -1) else it }) else Clocks.schedule(c, a)
        }
        if (kind == "timer") {
            val list = Clocks.timers(c)
            Clocks.saveTimers(c, list.map { if (it.id == id) it.copy(endAt = 0L, remainingMs = it.durationMs) else it })
        }
        val alarm = Clocks.alarms(c).find { it.id == id }
        RingService.start(c, baseKind, id, label, if (baseKind == "alarm") alarm else null)
    }

    companion object {
        const val ACTION_DISMISS = "dev.wonderdesk.timepiece.DISMISS"
        const val ACTION_SNOOZE = "dev.wonderdesk.timepiece.SNOOZE"
        const val ACTION_SKIP = "dev.wonderdesk.timepiece.SKIP"
        const val ACTION_UPCOMING = "dev.wonderdesk.timepiece.UPCOMING"

        fun buildRing(c: Context, kind: String, id: Int, label: String): Notification {
            Clocks.ensureChannel(c)
            val title = if (kind == "timer") (label.ifBlank { "Timer" } + " finished") else label.ifBlank { "Alarm" }
            val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date())
            val full = PendingIntent.getActivity(c, Clocks.ringNotificationId(kind, id),
                Intent(c, RingActivity::class.java).putExtra(Clocks.EXTRA_KIND, kind).putExtra(Clocks.EXTRA_ID, id).putExtra("label", title)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            fun action(act: String) = PendingIntent.getBroadcast(c, Clocks.ringNotificationId(kind, id) + act.length,
                Intent(c, RingReceiver::class.java).setAction(act).putExtra(Clocks.EXTRA_KIND, kind).putExtra(Clocks.EXTRA_ID, id).putExtra("label", label),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val b = Notification.Builder(c, Clocks.RING_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(title)
                .setContentText(if (kind == "timer") "Time's up" else time)
                .setCategory(Notification.CATEGORY_ALARM)
                .setFullScreenIntent(full, true)
                .setContentIntent(full)
                .setOngoing(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(Notification.Action.Builder(null, if (kind == "timer") "Stop" else "Dismiss", action(ACTION_DISMISS)).build())
            if (kind == "alarm") b.addAction(Notification.Action.Builder(null, "Snooze", action(ACTION_SNOOZE)).build())
            return b.build()
        }
    }
}

/** Alarms are forgotten by Android on reboot and time changes, so set them again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) = Clocks.rescheduleAll(c)
}
