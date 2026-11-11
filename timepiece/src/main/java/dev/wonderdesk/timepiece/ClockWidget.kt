package dev.wonderdesk.timepiece

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.text.DateFormat
import java.util.Date

/** Live clock (Android ticks it, so it costs no battery), date and the next Timepiece alarm. */
class ClockWidget : AppWidgetProvider() {
    companion object {
        fun refresh(c: Context) {
            val mgr = AppWidgetManager.getInstance(c)
            mgr.getAppWidgetIds(ComponentName(c, ClockWidget::class.java)).forEach { mgr.updateAppWidget(it, build(c)) }
        }
        fun build(c: Context): RemoteViews {
            val v = RemoteViews(c.packageName, R.layout.widget_clock)
            val next = Clocks.alarms(c).filter { it.enabled }.map { it to nextTrigger(it) }.minByOrNull { it.second }
            v.setTextViewText(R.id.w_alarm, next?.let { (a, at) -> "⏰ " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at)) + " " +
                java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).toLocalDate().let { d -> when (d) { java.time.LocalDate.now() -> "today"; java.time.LocalDate.now().plusDays(1) -> "tomorrow"; else -> d.format(java.time.format.DateTimeFormatter.ofPattern("EEE")) } } +
                if (a.label.isNotBlank()) " · ${a.label}" else "" } ?: "No alarms set")
            fun open(tab: String, rc: Int) = PendingIntent.getActivity(c, rc, Intent(c, MainActivity::class.java).putExtra("tab", tab).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            v.setOnClickPendingIntent(R.id.w_root, open("clock", 31)); v.setOnClickPendingIntent(R.id.w_alarm, open("alarms", 32))
            v.setOnClickPendingIntent(R.id.w_alarms, open("alarms", 33)); v.setOnClickPendingIntent(R.id.w_timer, open("timer", 34))
            return v
        }
    }
    override fun onUpdate(c: Context, mgr: AppWidgetManager, ids: IntArray) { val v = build(c); ids.forEach { mgr.updateAppWidget(it, v) } }
}
