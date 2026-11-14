package dev.wonderdesk.timepiece

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.wonderdesk.shared.*

/** One-tap timers: four preset buttons start a timer without opening the app; the next one to finish counts down live. */
class TimerWidget : AppWidgetProvider() {
    companion object {
        const val START = "dev.wonderdesk.timepiece.WIDGET_TIMER"
        val DEFAULT = listOf(1, 5, 10, 25)
        private val BUTTONS = listOf(R.id.w_b0, R.id.w_b1, R.id.w_b2, R.id.w_b3)
        fun presets(c: Context, id: Int) = WidgetPrefs(c, id).str("presets", DEFAULT.joinToString(",")).split(',').mapNotNull { it.trim().toIntOrNull() }.take(4).ifEmpty { DEFAULT }
        fun label(m: Int) = if (m >= 60 && m % 60 == 0) "${m / 60} h" else if (m >= 60) "${m / 60}h${m % 60}" else "$m min"

        fun build(c: Context, id: Int): RemoteViews {
            val s = WidgetPrefs(c, id)
            val look = runCatching { WidgetLook.valueOf(s.str("look", "GLASS")) }.getOrDefault(WidgetLook.GLASS)
            val v = RemoteViews(c.packageName, R.layout.widget_timers)
            v.applyLook(look, R.id.w_root, Triple(R.drawable.wd_widget_bg, R.drawable.wd_widget_bg_dark, R.drawable.wd_widget_bg_clear), listOf(R.id.w_chrono), listOf(R.id.w_label))
            val p = presets(c, id)
            BUTTONS.forEachIndexed { i, b ->
                val m = p.getOrNull(i)
                if (m == null) v.setViewVisibility(b, View.GONE) else {
                    v.setViewVisibility(b, View.VISIBLE); v.setTextViewText(b, label(m))
                    v.setOnClickPendingIntent(b, widgetBroadcast(c, TimerWidget::class.java, 7700 + (id % 100) * 10 + i, START) { it.putExtra("minutes", m) })
                }
            }
            val next = Clocks.timers(c).filter { it.running && it.endAt > System.currentTimeMillis() }.minByOrNull { it.endAt }
            if (next != null) {
                v.setViewVisibility(R.id.w_chrono, View.VISIBLE)
                v.setChronometer(R.id.w_chrono, SystemClock.elapsedRealtime() + (next.endAt - System.currentTimeMillis()), null, true)
                v.setChronometerCountDown(R.id.w_chrono, true)
                v.setTextViewText(R.id.w_label, next.label.ifBlank { "Timer" })
            } else { v.setViewVisibility(R.id.w_chrono, View.GONE); v.setTextViewText(R.id.w_label, "Start a timer") }
            v.setOnClickPendingIntent(R.id.w_root, widgetOpen(c, MainActivity::class.java, 7690 + id % 100) { it.putExtra("tab", "timer") })
            return v
        }
    }
    override fun onUpdate(c: Context, mgr: AppWidgetManager, ids: IntArray) { ids.forEach { runCatching { mgr.updateAppWidget(it, build(c, it)) } } }
    override fun onReceive(c: Context, i: Intent) {
        super.onReceive(c, i)
        if (i.action != START) return
        val m = i.getIntExtra("minutes", 0).takeIf { it > 0 } ?: return
        val list = Clocks.timers(c)
        val ms = m * 60_000L
        val t = CountdownTimer((list.maxOfOrNull { it.id } ?: 0) + 1, label(m) + " timer", ms, endAt = System.currentTimeMillis() + ms, remainingMs = ms)
        Clocks.saveTimers(c, list + t); Clocks.scheduleTimer(c, t)
        android.widget.Toast.makeText(c, "${label(m)} timer started", android.widget.Toast.LENGTH_SHORT).show()
        refreshWidgets(c, TimerWidget::class.java)
    }
    override fun onDeleted(c: Context, ids: IntArray) { ids.forEach { WidgetPrefs(c, it).clear() } }
}

class TimerWidgetSetup : WidgetSetupActivity() {
    override fun redraw() { AppWidgetManager.getInstance(this).updateAppWidget(widgetId, TimerWidget.build(this, widgetId)) }
    @Composable override fun Content() {
        val c = LocalContext.current
        val s = remember { WidgetPrefs(c, widgetId) }
        var look by remember { mutableStateOf(runCatching { WidgetLook.valueOf(s.str("look", "GLASS")) }.getOrDefault(WidgetLook.GLASS)) }
        val presets = remember { mutableStateListOf<Int>().apply { addAll(TimerWidget.presets(c, widgetId)) } }
        WidgetSetup("Timepiece", "Timer buttons", onCancel = { finish() }, onSave = { s.put("look" to look.name, "presets" to presets.joinToString(",")); save() }) {
            Panel(title = "Buttons", footer = "Each button starts a timer straight from the home screen.") {
                presets.indices.forEach { i ->
                    if (i > 0) PanelDivider()
                    Column(Modifier.padding(12.dp)) {
                        Text("Button ${i + 1}: ${TimerWidget.label(presets[i])}", style = MaterialTheme.typography.titleSmall)
                        Slider(presets[i].toFloat(), { presets[i] = it.toInt().coerceAtLeast(1) }, valueRange = 1f..120f)
                    }
                }
            }
            WidgetLookPicker(look) { look = it }
        }
    }
}
