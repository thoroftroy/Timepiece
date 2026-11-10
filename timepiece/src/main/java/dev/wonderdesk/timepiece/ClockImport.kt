package dev.wonderdesk.timepiece

import android.accessibilityservice.AccessibilityService
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** An alarm read from another clock app. [days] uses Timepiece's bits: Monday = bit 0 … Sunday = bit 6. */
data class FoundAlarm(val hour: Int, val minute: Int, val days: Int, val label: String, val enabled: Boolean) {
    val key get() = "$hour:$minute:$days:$label"
}

/**
 * Bringing alarms over from the phone's own clock app.
 *
 * Android has no way for one app to read another app's alarms, so there are two routes:
 *  - the next alarm, which Android shares with every app ([nextSystemAlarm]);
 *  - everything, read from the Clock app's alarm list by [ClockReader] while the user scrolls it.
 *    The reader only looks at clock apps, only while an import is running, and keeps nothing else.
 */
object ClockImport {
    val CLOCK_PACKAGES = setOf("com.google.android.deskclock", "com.android.deskclock", "com.sec.android.app.clockpackage", "com.oneplus.deskclock",
        "com.coloros.alarmclock", "com.android.alarmclock", "com.huawei.deskclock", "com.miui.clock", "com.motorola.timeweatherwidget", "com.lge.clock")

    val listening = mutableStateOf(false)
    val found = mutableStateMapOf<String, FoundAlarm>()

    fun start() { found.clear(); listening.value = true }
    fun stop() { listening.value = false }

    /** The clock app installed on this phone, if any. */
    fun clockApp(c: Context): String? = CLOCK_PACKAGES.firstOrNull { runCatching { c.packageManager.getPackageInfo(it, 0) }.isSuccess }

    fun openClockAlarms(c: Context, pkg: String?) {
        val i = Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (pkg != null) i.setPackage(pkg)
        runCatching { c.startActivity(i) }.onFailure { pkg?.let { p -> c.packageManager.getLaunchIntentForPackage(p)?.let { c.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } } }
    }

    /** The next alarm any app has set, if it isn't one of Timepiece's own. */
    fun nextSystemAlarm(c: Context): Pair<FoundAlarm, String>? {
        val info = c.getSystemService(AlarmManager::class.java).nextAlarmClock ?: return null
        val from = info.showIntent?.creatorPackage ?: ""
        if (from == c.packageName) return null
        val t = Instant.ofEpochMilli(info.triggerTime).atZone(ZoneId.systemDefault())
        val name = runCatching { c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(from, 0)).toString() }.getOrDefault("your clock app")
        return FoundAlarm(t.hour, t.minute, 0, "", true) to name
    }

    /* ------------------------------------- reading the alarm list ------------------------------------- */

    private val TIME = Regex("^(\\d{1,2})[:.](\\d{2})\\s*([AaPp]\\.?\\s?[Mm]\\.?)?$")
    private val AMPM = Regex("^([AaPp])\\.?\\s?[Mm]\\.?$")
    private val NOISE = setOf("alarm", "alarms", "label", "add label", "delete", "ringtone", "vibrate", "snooze", "schedule", "clock", "timer", "stopwatch", "bedtime",
        "google assistant routine", "default", "not scheduled", "today", "tomorrow", "add alarm", "more options", "expand alarm", "collapse alarm", "dismiss", "settings",
        "send feedback", "help", "world clock", "edit", "cancel", "save", "done", "ring once", "repeat")

    private fun dayNames(style: TextStyle): Map<String, Int> = DayOfWeek.entries.associate { it.getDisplayName(style, Locale.getDefault()).lowercase().trimEnd('.') to (it.value - 1) } +
        DayOfWeek.entries.associate { it.getDisplayName(style, Locale.ENGLISH).lowercase().trimEnd('.') to (it.value - 1) }

    private val SHORT by lazy { dayNames(TextStyle.SHORT) }
    private val FULL by lazy { dayNames(TextStyle.FULL) }

    /** Days from texts like "Mon, Wed, Fri", "Every day", "Weekdays", "Sat–Sun". 0 when it rings once. */
    fun parseDays(texts: List<String>): Int {
        var mask = 0
        for (raw in texts) {
            val t = raw.lowercase().trim()
            when {
                t == "every day" || t == "daily" || t == "everyday" -> return 0x7F
                t == "weekdays" || t == "mon–fri" || t == "mon-fri" -> mask = mask or 0x1F
                t == "weekends" || t == "sat–sun" || t == "sat-sun" -> mask = mask or 0x60
                else -> {
                    val parts = t.split(',', ' ', '·', '/').map { it.trim().trimEnd('.') }.filter { it.isNotEmpty() }
                    val idx = parts.map { SHORT[it] ?: FULL[it] }
                    if (parts.isNotEmpty() && idx.all { it != null }) idx.forEach { mask = mask or (1 shl it!!) }
                }
            }
        }
        return mask
    }

    private fun texts(n: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>, depth: Int = 0) {
        if (n == null || depth > 40) return
        out += n
        for (i in 0 until n.childCount) texts(n.getChild(i), out, depth + 1)
    }

    private fun label(n: AccessibilityNodeInfo) = (n.text ?: n.contentDescription)?.toString()?.trim().orEmpty()

    /** Reads every alarm row visible in [root]. */
    fun read(root: AccessibilityNodeInfo) {
        val all = mutableListOf<AccessibilityNodeInfo>(); texts(root, all)
        val times = all.filter { TIME.matches(label(it)) }
        for (tn in times) {
            // Climb to the smallest container holding this time and an on/off switch, but never one
            // that holds a second alarm's time (that would be the whole list).
            fun isSwitch(n: AccessibilityNodeInfo) = n.isCheckable && (n.className?.contains("Switch") == true || n.className?.contains("CompoundButton") == true || n.className?.contains("ToggleButton") == true)
            var row: AccessibilityNodeInfo = tn
            var best: AccessibilityNodeInfo? = null
            for (hop in 0 until 8) {
                val up = row.parent ?: break
                val inner = mutableListOf<AccessibilityNodeInfo>(); texts(up, inner)
                if (inner.count { TIME.matches(label(it)) } > 1) break
                row = up
                if (inner.any { isSwitch(it) }) { best = up; break }
            }
            val container = best ?: row
            var inner = mutableListOf<AccessibilityNodeInfo>(); texts(container, inner)
            if (best == null) {
                // Flattened lists (Google Clock exposes every alarm as siblings): one alarm is everything
                // after the previous on/off switch up to and including its own switch.
                val parent = row.parent ?: tn.parent
                if (parent != null) {
                    val flat = mutableListOf<AccessibilityNodeInfo>(); texts(parent, flat)
                    val at = flat.indexOfFirst { it == tn }
                    if (at >= 0) {
                        val start = (flat.subList(0, at).indexOfLast { isSwitch(it) } + 1)
                        val endRel = flat.subList(at, flat.size).indexOfFirst { isSwitch(it) }
                        val end = if (endRel >= 0) at + endRel + 1 else flat.size
                        inner = flat.subList(start, end).filter { it != parent }.toMutableList()
                    }
                }
            }
            val m = TIME.find(label(tn)) ?: continue
            var h = m.groupValues[1].toInt(); val min = m.groupValues[2].toInt()
            val ampm = m.groupValues[3].ifEmpty { inner.map { label(it) }.firstOrNull { AMPM.matches(it) } ?: "" }
            if (ampm.isNotEmpty()) { val pm = ampm.first().lowercaseChar() == 'p'; if (pm && h < 12) h += 12; if (!pm && h == 12) h = 0 }
            if (h !in 0..23 || min !in 0..59) continue
            val sw = inner.firstOrNull { isSwitch(it) }
            val words = inner.map { label(it) }.filter { it.isNotEmpty() && !TIME.matches(it) && !AMPM.matches(it) }
            // Labels are plain text, never buttons ("Expand 8:30 AM alarm") or switches.
            val labelWords = inner.filter { it.className?.contains("Text") == true && !it.isCheckable && !it.isClickable || it.className?.endsWith("TextView") == true }
                .mapNotNull { it.text?.toString()?.trim() }.filter { it.isNotEmpty() && !TIME.matches(it) && !AMPM.matches(it) }
            // day toggles (expanded rows): checkable nodes named after days
            var days = 0
            inner.filter { it.isCheckable && it != sw }.forEach { d -> val name = label(d).lowercase().trimEnd('.'); (FULL[name] ?: SHORT[name])?.let { i -> if (d.isChecked) days = days or (1 shl i) } }
            if (days == 0) days = parseDays(words)
            val text = labelWords.firstOrNull { w -> w.length in 1..40 && w.lowercase() !in NOISE && parseDays(listOf(w)) == 0 && !w.lowercase().startsWith("default") &&
                !w.contains("alarm in", true) && !w.contains("ringtone", true) && !w.startsWith("Expand", true) && !w.startsWith("Collapse", true) && !w.endsWith(" min") && w.lowercase() !in SHORT.keys }
            val a = FoundAlarm(h, min, days, text.orEmpty(), sw?.isChecked ?: true)
            found[a.key] = a
        }
    }
}

/** Reads alarms from the Clock app, only while an import is running. */
class ClockReader : AccessibilityService() {
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (!ClockImport.listening.value) return
        val pkg = e?.packageName?.toString() ?: return
        if (pkg !in ClockImport.CLOCK_PACKAGES) return
        runCatching { rootInActiveWindow?.let { ClockImport.read(it) } }
    }
    override fun onInterrupt() {}
}

/** Alarms and timers requested by Google Assistant, other apps or "Set alarm" shortcuts. */
object AlarmIntents {
    /** Returns a message to show, or null if the intent wasn't for us. */
    fun handle(c: Context, i: Intent?): String? {
        when (i?.action) {
            AlarmClock.ACTION_SET_ALARM -> {
                if (!i.hasExtra(AlarmClock.EXTRA_HOUR)) return null
                val h = i.getIntExtra(AlarmClock.EXTRA_HOUR, 7).coerceIn(0, 23); val m = i.getIntExtra(AlarmClock.EXTRA_MINUTES, 0).coerceIn(0, 59)
                // Calendar.SUNDAY = 1 … SATURDAY = 7 → Monday bit 0 … Sunday bit 6
                val days = i.getIntegerArrayListExtra(AlarmClock.EXTRA_DAYS)?.fold(0) { acc, d -> acc or (1 shl ((d + 5) % 7)) } ?: 0
                val list = Clocks.alarms(c).toMutableList()
                val a = Alarm(id = (list.maxOfOrNull { it.id } ?: 0) + 1, hour = h, minute = m, label = i.getStringExtra(AlarmClock.EXTRA_MESSAGE).orEmpty(), days = days,
                    vibrate = i.getBooleanExtra(AlarmClock.EXTRA_VIBRATE, true))
                list += a; Clocks.saveAlarms(c, list.sortedBy { it.hour * 60 + it.minute }); Clocks.schedule(c, a)
                return "Alarm set for %02d:%02d".format(h, m)
            }
            AlarmClock.ACTION_SET_TIMER -> {
                val secs = i.getIntExtra(AlarmClock.EXTRA_LENGTH, 0).takeIf { it > 0 } ?: return null
                val list = Clocks.timers(c).toMutableList()
                val t = CountdownTimer((list.maxOfOrNull { it.id } ?: 0) + 1, i.getStringExtra(AlarmClock.EXTRA_MESSAGE).orEmpty(), secs * 1000L).let { it.copy(endAt = System.currentTimeMillis() + it.durationMs) }
                list += t; Clocks.saveTimers(c, list); Clocks.scheduleTimer(c, t)
                return "Timer started for ${secs / 60} min ${secs % 60} s"
            }
        }
        return null
    }
}
