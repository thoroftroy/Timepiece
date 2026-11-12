package dev.wonderdesk.timepiece

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.wonderdesk.shared.*
import java.text.DateFormat
import java.util.Date

/** Live clock (Android ticks it, so it costs no battery), date and the next Timepiece alarm. Each one can show its own city. */
class ClockWidget : AppWidgetProvider() {
    companion object {
        fun refresh(c: Context) {
            val mgr = AppWidgetManager.getInstance(c)
            mgr.getAppWidgetIds(ComponentName(c, ClockWidget::class.java)).forEach { runCatching { mgr.updateAppWidget(it, build(c, it)) } }
        }
        fun build(c: Context, id: Int): RemoteViews = dev.wonderdesk.shared.responsiveWidget(R.layout.widget_clock_small, R.layout.widget_clock_wide, R.layout.widget_clock) { fill(c, id, it) }
        private fun fill(c: Context, id: Int, layout: Int): RemoteViews {
            val s = WidgetPrefs(c, id)
            val v = RemoteViews(c.packageName, layout)
            val look = runCatching { WidgetLook.valueOf(s.str("look", "GLASS")) }.getOrDefault(WidgetLook.GLASS)
            v.applyLook(look, R.id.w_root, Triple(R.drawable.wd_widget_bg, R.drawable.wd_widget_bg_dark, R.drawable.wd_widget_bg_clear), listOf(R.id.w_time), listOf(R.id.w_date))
            val seconds = s.bool("seconds", false)
            v.setCharSequence(R.id.w_time, "setFormat12Hour", if (seconds) "h:mm:ss" else "h:mm")
            v.setCharSequence(R.id.w_time, "setFormat24Hour", if (seconds) "HH:mm:ss" else "HH:mm")
            val zone = s.str("zone", "")
            v.setString(R.id.w_time, "setTimeZone", zone.ifBlank { null }); v.setString(R.id.w_date, "setTimeZone", zone.ifBlank { null })
            val city = zone.substringAfterLast('/').replace('_', ' ')
            v.setCharSequence(R.id.w_date, "setFormat12Hour", if (zone.isBlank()) "EEEE, d MMMM" else "'$city' · EEE d MMM")
            v.setCharSequence(R.id.w_date, "setFormat24Hour", if (zone.isBlank()) "EEEE, d MMMM" else "'$city' · EEE d MMM")
            v.setViewVisibility(R.id.w_date, if (s.bool("date", true)) View.VISIBLE else View.GONE)
            val showAlarm = s.bool("alarm", true) && zone.isBlank()
            val next = if (showAlarm) Clocks.alarms(c).filter { it.enabled }.map { it to nextTrigger(it) }.minByOrNull { it.second } else null
            v.setTextViewText(R.id.w_alarm, next?.let { (a, at) -> "⏰ " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at)) + " " +
                java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).toLocalDate().let { d -> when (d) { java.time.LocalDate.now() -> "today"; java.time.LocalDate.now().plusDays(1) -> "tomorrow"; else -> d.format(java.time.format.DateTimeFormatter.ofPattern("EEE")) } } +
                if (a.label.isNotBlank()) " · ${a.label}" else "" } ?: if (showAlarm) "No alarms set" else "")
            val buttons = s.bool("buttons", true)
            v.setViewVisibility(R.id.w_timer, if (buttons) View.VISIBLE else View.GONE); v.setViewVisibility(R.id.w_alarms, if (buttons) View.VISIBLE else View.GONE)
            v.setViewVisibility(R.id.w_alarm, if (showAlarm) View.VISIBLE else View.INVISIBLE)
            v.setViewVisibility(R.id.w_row, if (showAlarm || buttons) View.VISIBLE else View.GONE)
            fun open(tab: String, rc: Int) = PendingIntent.getActivity(c, rc * 1000 + id % 1000, Intent(c, MainActivity::class.java).putExtra("tab", tab).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            v.setOnClickPendingIntent(R.id.w_root, open("clock", 31)); v.setOnClickPendingIntent(R.id.w_alarm, open("alarms", 32))
            v.setOnClickPendingIntent(R.id.w_alarms, open("alarms", 33)); v.setOnClickPendingIntent(R.id.w_timer, open("timer", 34))
            if (layout != R.layout.widget_clock) v.setViewVisibility(R.id.w_row, View.GONE)
            return v
        }
    }
    override fun onUpdate(c: Context, mgr: AppWidgetManager, ids: IntArray) { ids.forEach { runCatching { mgr.updateAppWidget(it, build(c, it)) } } }
    override fun onDeleted(c: Context, ids: IntArray) { ids.forEach { WidgetPrefs(c, it).clear() } }
}

class ClockWidgetSetup : WidgetSetupActivity() {
    override fun redraw() { AppWidgetManager.getInstance(this).updateAppWidget(widgetId, ClockWidget.build(this, widgetId)) }
    @Composable override fun Content() {
        val c = LocalContext.current
        val s = remember { WidgetPrefs(c, widgetId) }
        var look by remember { mutableStateOf(runCatching { WidgetLook.valueOf(s.str("look", "GLASS")) }.getOrDefault(WidgetLook.GLASS)) }
        var seconds by remember { mutableStateOf(s.bool("seconds", false)) }
        var date by remember { mutableStateOf(s.bool("date", true)) }
        var alarm by remember { mutableStateOf(s.bool("alarm", true)) }
        var buttons by remember { mutableStateOf(s.bool("buttons", true)) }
        var zone by remember { mutableStateOf(s.str("zone", "")) }
        var picking by remember { mutableStateOf(false) }
        WidgetSetup("Timepiece", "Clock widget", onCancel = { finish() }, onSave = { s.put("look" to look.name, "seconds" to seconds, "date" to date, "alarm" to alarm, "buttons" to buttons, "zone" to zone); save() }) {
            Panel(title = "Shows") {
                ListRow("Time zone", subtitle = if (zone.isBlank()) "This phone's time" else zone.replace('_', ' '), icon = WonderIcons.Public, tint = androidx.compose.ui.graphics.Color(0xFF1E7FE8), chevron = true, onClick = { picking = true })
                PanelDivider(); SwitchRow("Seconds", seconds, { seconds = it })
                PanelDivider(); SwitchRow("Date", date, { date = it })
                PanelDivider(); SwitchRow("Next alarm", alarm && zone.isBlank(), { alarm = it }, subtitle = if (zone.isNotBlank()) "Only on a clock showing this phone's time" else null)
                PanelDivider(); SwitchRow("Timer and Alarms buttons", buttons, { buttons = it })
            }
            WidgetLookPicker(look) { look = it }
        }
        if (picking) ZonePicker(onDismiss = { picking = false }) { zone = it; picking = false }
    }
}

@Composable
private fun ZonePicker(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    val all = remember { java.time.ZoneId.getAvailableZoneIds().filter { '/' in it && !it.startsWith("Etc") && !it.startsWith("SystemV") }.sorted() }
    val shown = remember(q) { all.filter { q.isBlank() || it.replace('_', ' ').contains(q, true) }.take(200) }
    WonderDialog(onDismissRequest = onDismiss, title = { Text("Choose a city") }, text = {
        Column {
            SearchField(q, { q = it }, "Search cities")
            Spacer(Modifier.height(8.dp))
            androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 420.dp)) {
                item { ListRow("This phone's time", onClick = { onPick("") }) }
                items(shown.size) { i -> val z = shown[i]; ListRow(z.substringAfterLast('/').replace('_', ' '), subtitle = z.substringBefore('/'), onClick = { onPick(z) }) }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
