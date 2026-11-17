package dev.wonderdesk.timepiece

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.wonderdesk.shared.*
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/** A different wake-up time for each day: one repeating alarm per day, made together. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeeklyScheduleDialog(existing: List<Alarm>, nextId: Int, onDismiss: () -> Unit, onCreate: (List<Alarm>) -> Unit) {
    val context = LocalContext.current
    val p = wonderPalette()
    val is24 = clockIs24(context)
    // minutes since midnight per weekday (Mon = 0), or -1 for no alarm that day
    val times = remember { mutableStateListOf(*Array(7) { if (it < 5) 7 * 60 else -1 }) }
    var label by remember { mutableStateOf("Wake up") }
    var editing by remember { mutableStateOf<Int?>(null) }
    fun fmt(m: Int) = if (is24) "%02d:%02d".format(m / 60, m % 60) else "%d:%02d %s".format(if (m / 60 % 12 == 0) 12 else m / 60 % 12, m % 60, if (m / 60 < 12) "AM" else "PM")
    WonderDialog(onDismissRequest = onDismiss, title = { Text("Weekly wake-up") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Choose a time for each day, or leave a day off. Timepiece makes one repeating alarm per day.", style = MaterialTheme.typography.bodySmall, color = p.secondaryText)
            OutlinedTextField(label, { label = it }, label = { Text("Label") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Panel {
                DayOfWeek.entries.forEachIndexed { i, d ->
                    if (i > 0) PanelDivider()
                    ListRow(d.getDisplayName(TextStyle.FULL, Locale.getDefault()), subtitle = if (times[i] < 0) "Off" else fmt(times[i]), icon = WonderIcons.Alarm, tint = if (times[i] < 0) Color(0xFF7A7F87) else Color(0xFFE5483C),
                        trailing = { Switch(times[i] >= 0, { on -> times[i] = if (on) 7 * 60 else -1; if (on) editing = i }) }, onClick = { editing = i })
                }
            }
        }
    }, confirmButton = {
        TextButton(onClick = {
            var id = nextId
            onCreate(times.withIndex().filter { it.value >= 0 }.map { (i, m) -> Alarm(id++, m / 60, m % 60, label.trim(), true, days = 1 shl i) })
        }, enabled = times.any { it >= 0 }) { Text("Create alarms") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
    editing?.let { i ->
        val st = rememberTimePickerState((times[i].coerceAtLeast(0)) / 60, (times[i].coerceAtLeast(0)) % 60, is24)
        WonderDialog(onDismissRequest = { editing = null }, title = { Text(DayOfWeek.of(i + 1).getDisplayName(TextStyle.FULL, Locale.getDefault())) }, text = {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TimePicker(st, colors = TimePickerDefaults.colors(clockDialSelectedContentColor = p.onAccent, selectorColor = p.accent))
            }
        }, confirmButton = { TextButton(onClick = { times[i] = st.hour * 60 + st.minute; editing = null }) { Text("Set") } }, dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } })
    }
}
