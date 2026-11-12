@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package dev.wonderdesk.timepiece

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import dev.wonderdesk.shared.*
import kotlinx.coroutines.delay
import org.json.JSONArray
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : WonderActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        // "Set an alarm for 7" from Google Assistant or another app.
        AlarmIntents.handle(this, intent)?.let { msg ->
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
            if (intent.getBooleanExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, false)) { super.onCreate(savedInstanceState); finish(); return }
            intent.putExtra("tab", if (intent.action == android.provider.AlarmClock.ACTION_SET_TIMER) "timer" else "alarms")
        }
        super.onCreate(savedInstanceState)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        AlarmIntents.handle(this, intent)?.let { android.widget.Toast.makeText(this, it, android.widget.Toast.LENGTH_SHORT).show(); recreate() }
    }
    @Composable override fun Content() = TimepieceApp(intent?.getStringExtra("tab"))
}

private const val ABOUT = "Timepiece is a clock for your phone: world clocks, alarms that ring even when the app is closed, " +
    "countdown timers and a stopwatch with laps. Alarms are set again automatically after a restart."

@Composable
fun TimepieceApp(startTab: String?) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(startTab ?: context.prefs("timepiece").getString("tab", "clock") ?: "clock") }
    var addCity by remember { mutableStateOf(false) }
    var editAlarm by remember { mutableStateOf<Alarm?>(null) }
    var newTimer by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    val alarms = remember { mutableStateListOf<Alarm>().apply { addAll(Clocks.alarms(context)) } }
    val timers = remember { mutableStateListOf<CountdownTimer>().apply { addAll(Clocks.timers(context)) } }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    // Re-arm everything on open: survives force-stops and app updates, which clear Android's alarms.
    LaunchedEffect(Unit) { Clocks.rescheduleAll(context) }
    fun saveAlarms() { Clocks.saveAlarms(context, alarms.toList()) }
    fun saveTimers() { Clocks.saveTimers(context, timers.toList()) }
    fun needPermissions() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    WonderApp(
        appName = "Timepiece", about = ABOUT,
        tabs = listOf(
            WonderTab("clock", "World", WonderIcons.Public),
            WonderTab("alarms", "Alarms", WonderIcons.Alarm),
            WonderTab("timer", "Timers", WonderIcons.HourglassBottom),
            WonderTab("stopwatch", "Stopwatch", WonderIcons.Timer),
        ),
        selectedTab = tab, onTab = { tab = it; context.prefs("timepiece").edit().putString("tab", it).apply() },
        title = when (tab) { "alarms" -> "Alarms"; "timer" -> "Timers"; "stopwatch" -> "Stopwatch"; else -> "Timepiece" },
        actions = {
            when (tab) {
                "clock" -> ToolbarButton(WonderIcons.Add, "Add city", { addCity = true })
                "alarms" -> {
                    ToolbarButton(WonderIcons.Download, "Import from Clock", { importing = true })
                    ToolbarButton(WonderIcons.Add, "New alarm", { editAlarm = Alarm(id = (alarms.maxOfOrNull { it.id } ?: 0) + 1, hour = 7, minute = 0) })
                }
                "timer" -> ToolbarButton(WonderIcons.Add, "New timer", { newTimer = true })
            }
        },
        settings = {
            Panel(title = "Alarms") {
                ListRow("Alarm sound and vibration", subtitle = "Choose the sound in Android's notification settings", icon = WonderIcons.VolumeUp, tint = Color(0xFFE5483C), chevron = true, onClick = {
                    Clocks.ensureChannel(context)
                    context.launchSafely(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, Clocks.RING_CHANNEL)) {}
                })
                if (Build.VERSION.SDK_INT >= 31) {
                    PanelDivider()
                    ListRow("Exact alarms", subtitle = if (Clocks.canExact(context)) "Allowed: alarms ring on time" else "Off: alarms may ring late. Tap to allow", icon = WonderIcons.Schedule, tint = Color(0xFF1E7FE8), chevron = true, onClick = {
                        context.launchSafely(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) {}
                    })
                }
            }
        }
    ) {
        when (tab) {
            "alarms" -> AlarmsTab(alarms, onEdit = { editAlarm = it }, onToggle = { a, on ->
                val n = a.copy(enabled = on, skipUntil = 0); alarms[alarms.indexOf(a)] = n; saveAlarms(); Clocks.schedule(context, n); if (on) needPermissions()
            }, onUpdate = { n -> val i = alarms.indexOfFirst { it.id == n.id }; if (i >= 0) { alarms[i] = n; saveAlarms(); Clocks.schedule(context, n) } })
            "timer" -> TimersTab(timers, onNew = { newTimer = true }, save = { saveTimers() })
            "stopwatch" -> StopwatchTab()
            else -> WorldTab(onAdd = { addCity = true })
        }
        val toast = LocalToast.current
        editAlarm?.let { a ->
            AlarmEditor(a, isNew = alarms.none { it.id == a.id }, onDismiss = { editAlarm = null }, onSave = { n ->
                val i = alarms.indexOfFirst { it.id == n.id }
                if (i >= 0) alarms[i] = n else alarms.add(n)
                alarms.sortBy { it.hour * 60 + it.minute }
                saveAlarms(); Clocks.schedule(context, n); needPermissions()
                toast(untilText(nextTrigger(n)))
                editAlarm = null
            }, onDelete = { Clocks.cancel(context, a); alarms.removeAll { it.id == a.id }; saveAlarms(); editAlarm = null })
        }
    }
    if (addCity) CityPicker(onDismiss = { addCity = false })
    if (importing) ImportFromClock(alarms, onDismiss = { importing = false; ClockImport.stop() }) { picked ->
        var next = (alarms.maxOfOrNull { it.id } ?: 0) + 1
        picked.forEach { f ->
            if (alarms.none { it.hour == f.hour && it.minute == f.minute && it.days == f.days }) {
                val a = Alarm(id = next++, hour = f.hour, minute = f.minute, label = f.label, enabled = f.enabled, days = f.days)
                alarms.add(a); Clocks.schedule(context, a)
            }
        }
        alarms.sortBy { it.hour * 60 + it.minute }; saveAlarms(); needPermissions(); importing = false; ClockImport.stop()
    }
    if (newTimer) TimerSetup(onDismiss = { newTimer = false }) { label, ms ->
        val t = CountdownTimer((timers.maxOfOrNull { it.id } ?: 0) + 1, label, ms, endAt = System.currentTimeMillis() + ms, remainingMs = ms)
        timers.add(t); saveTimers(); Clocks.scheduleTimer(context, t); needPermissions(); newTimer = false
    }
}

fun untilText(at: Long): String {
    val mins = ((at - System.currentTimeMillis()) / 60_000).coerceAtLeast(0) + 1
    val h = mins / 60; val m = mins % 60
    return "Alarm set for " + when { h == 0L -> "$m minute${if (m == 1L) "" else "s"}"; m == 0L -> "$h hour${if (h == 1L) "" else "s"}"; else -> "$h h $m min" } + " from now"
}

@Composable
private fun tickingNow(periodMs: Long = 1000): Long {
    // Pauses while Timepiece is in the background.
    return rememberNow(periodMs)
}

/* --------------------------------------------- world ----------------------------------------- */

val CITIES = listOf(
    "Honolulu" to "Pacific/Honolulu", "Anchorage" to "America/Anchorage", "Los Angeles" to "America/Los_Angeles", "Vancouver" to "America/Vancouver",
    "Denver" to "America/Denver", "Phoenix" to "America/Phoenix", "Chicago" to "America/Chicago", "Mexico City" to "America/Mexico_City",
    "New York" to "America/New_York", "Toronto" to "America/Toronto", "Bogotá" to "America/Bogota", "Lima" to "America/Lima",
    "Santiago" to "America/Santiago", "Buenos Aires" to "America/Argentina/Buenos_Aires", "São Paulo" to "America/Sao_Paulo",
    "Reykjavík" to "Atlantic/Reykjavik", "London" to "Europe/London", "Dublin" to "Europe/Dublin", "Lisbon" to "Europe/Lisbon",
    "Paris" to "Europe/Paris", "Madrid" to "Europe/Madrid", "Berlin" to "Europe/Berlin", "Rome" to "Europe/Rome", "Amsterdam" to "Europe/Amsterdam",
    "Stockholm" to "Europe/Stockholm", "Warsaw" to "Europe/Warsaw", "Athens" to "Europe/Athens", "Istanbul" to "Europe/Istanbul",
    "Kyiv" to "Europe/Kyiv", "Moscow" to "Europe/Moscow", "Cairo" to "Africa/Cairo", "Lagos" to "Africa/Lagos", "Nairobi" to "Africa/Nairobi",
    "Johannesburg" to "Africa/Johannesburg", "Dubai" to "Asia/Dubai", "Tehran" to "Asia/Tehran", "Karachi" to "Asia/Karachi",
    "Delhi" to "Asia/Kolkata", "Mumbai" to "Asia/Kolkata", "Kathmandu" to "Asia/Kathmandu", "Dhaka" to "Asia/Dhaka", "Bangkok" to "Asia/Bangkok",
    "Jakarta" to "Asia/Jakarta", "Singapore" to "Asia/Singapore", "Hong Kong" to "Asia/Hong_Kong", "Beijing" to "Asia/Shanghai",
    "Taipei" to "Asia/Taipei", "Manila" to "Asia/Manila", "Seoul" to "Asia/Seoul", "Tokyo" to "Asia/Tokyo", "Perth" to "Australia/Perth",
    "Adelaide" to "Australia/Adelaide", "Brisbane" to "Australia/Brisbane", "Sydney" to "Australia/Sydney", "Melbourne" to "Australia/Melbourne",
    "Auckland" to "Pacific/Auckland", "Fiji" to "Pacific/Fiji",
)

private fun loadCities(c: Context): List<Pair<String, String>> = runCatching {
    val a = JSONArray(c.prefs("timepiece").getString("cities", null) ?: return listOf("London" to "Europe/London", "New York" to "America/New_York", "Tokyo" to "Asia/Tokyo"))
    (0 until a.length()).map { a.getJSONArray(it).let { p -> p.getString(0) to p.getString(1) } }
}.getOrDefault(emptyList())

private fun saveCities(c: Context, list: List<Pair<String, String>>) =
    c.prefs("timepiece").edit().putString("cities", JSONArray(list.map { JSONArray(listOf(it.first, it.second)) }).toString()).apply()

private val citiesState = mutableStateOf<List<Pair<String, String>>?>(null)

@Composable
private fun WorldTab(onAdd: () -> Unit) {
    val context = LocalContext.current
    val now = tickingNow()
    val p = wonderPalette()
    if (citiesState.value == null) citiesState.value = loadCities(context)
    val cities = citiesState.value.orEmpty()
    val local = ZonedDateTime.now()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item {
            Column(Modifier.fillMaxWidth().padding(bottom = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                AquaClockFace(now, Modifier.size(220.dp))
                Spacer(Modifier.height(12.dp))
                Text(local.format(DateTimeFormatter.ofPattern(if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm:ss" else "h:mm:ss a")),
                    style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Light, fontFamily = FontFamily.SansSerif))
                Text(local.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)) + " · " + ZoneId.systemDefault().id.substringAfterLast('/').replace('_', ' '),
                    style = MaterialTheme.typography.bodyMedium, color = p.secondaryText)
            }
        }
        if (cities.isEmpty()) item { Panel { EmptyState("No world clocks", "Add cities to see their time at a glance.", icon = WonderIcons.Public, action = { WButton("Add city", onAdd, icon = WonderIcons.Add, kind = ButtonKind.Primary) }) } }
        else {
            item { SectionLabel("World clocks", Modifier.padding(start = 6.dp, bottom = 6.dp)) }
            itemsIndexed(cities, key = { j, c -> "${c.first}|${c.second}|$j" }) { i, (name, zone) ->
                val z = runCatching { ZonedDateTime.now(ZoneId.of(zone)) }.getOrNull() ?: return@itemsIndexed
                val diffH = (z.offset.totalSeconds - local.offset.totalSeconds) / 3600.0
                val dayDiff = z.toLocalDate().compareTo(local.toLocalDate())
                val rel = (if (dayDiff > 0) "Tomorrow" else if (dayDiff < 0) "Yesterday" else "Today") + ", " +
                    (if (diffH == 0.0) "same time" else (if (diffH > 0) "+" else "") + (if (diffH % 1.0 == 0.0) diffH.toInt().toString() else diffH.toString()) + " h")
                val night = z.hour < 6 || z.hour >= 19
                ListRow(name, Modifier.panelItem(i, cities.size), subtitle = rel, icon = if (night) WonderIcons.NightsStay else WonderIcons.WbSunny,
                    tint = if (night) Color(0xFF3B4A8C) else Color(0xFFF2A516),
                    trailing = {
                        Text(z.format(DateTimeFormatter.ofPattern(if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a")),
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Light))
                    },
                    onLongClick = { citiesState.value = cities - (name to zone); saveCities(context, citiesState.value!!) })
            }
            item { Text("Long-press a city to remove it.", Modifier.padding(6.dp, 8.dp), style = MaterialTheme.typography.bodySmall, color = p.secondaryText) }
        }
    }
}

@Composable
private fun CityPicker(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var q by remember { mutableStateOf("") }
    val all = remember {
        (CITIES + ZoneId.getAvailableZoneIds().filter { it.contains('/') && !it.startsWith("Etc") && !it.startsWith("SystemV") }
            .map { it.substringAfterLast('/').replace('_', ' ') to it }).distinctBy { it.first }.sortedBy { it.first }
    }
    val shown = all.filter { q.isBlank() || it.first.contains(q, true) || it.second.contains(q, true) }.take(60)
    WonderDialog(onDismissRequest = onDismiss, title = { Text("Add city") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SearchField(q, { q = it }, "Search cities")
            LazyColumn(Modifier.height(380.dp)) {
                itemsIndexed(shown) { _, (name, zone) ->
                    val now = runCatching { ZonedDateTime.now(ZoneId.of(zone)).format(DateTimeFormatter.ofPattern("HH:mm")) }.getOrDefault("")
                    ListRow(name, subtitle = zone.replace('_', ' '), trailing = { Text(now, color = wonderPalette().secondaryText) }, onClick = {
                        citiesState.value = (citiesState.value.orEmpty() + (name to zone)).distinct(); saveCities(context, citiesState.value!!); onDismiss()
                    })
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

/** Snow Leopard-style analog clock: white glass face, black hands, red seconds. */
@Composable
fun AquaClockFace(now: Long, modifier: Modifier) {
    val p = wonderPalette()
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
    val sec = cal.get(java.util.Calendar.SECOND); val min = cal.get(java.util.Calendar.MINUTE) + sec / 60f; val hour = cal.get(java.util.Calendar.HOUR) + min / 60f
    Canvas(modifier) {
        val r = size.minDimension / 2; val c = center
        drawCircle(Brush.verticalGradient(listOf(Color(0xFFD8DDE3), Color(0xFF8E979F))), r, c)
        drawCircle(Brush.radialGradient(listOf(Color.White, if (p.dark) Color(0xFFDDE2E8) else Color(0xFFF1F4F7)), c, r), r * .9f, c)
        for (i in 0 until 60) {
            val a = Math.toRadians(i * 6.0 - 90); val big = i % 5 == 0
            val r1 = r * (if (big) .72f else .79f); val r2 = r * .84f
            drawLine(Color(0xFF2A2D31), Offset(c.x + r1 * cos(a).toFloat(), c.y + r1 * sin(a).toFloat()), Offset(c.x + r2 * cos(a).toFloat(), c.y + r2 * sin(a).toFloat()),
                if (big) r * .035f else r * .012f, StrokeCap.Round)
        }
        fun hand(deg: Float, len: Float, w: Float, color: Color, tail: Float = .12f) = rotate(deg, c) {
            drawLine(color, Offset(c.x, c.y + r * tail), Offset(c.x, c.y - r * len), w, StrokeCap.Round)
        }
        hand(hour * 30f, .48f, r * .06f, Color(0xFF1B1D21))
        hand(min * 6f, .7f, r * .04f, Color(0xFF1B1D21))
        hand(sec * 6f, .78f, r * .015f, Color(0xFFE0312B), .2f)
        drawCircle(Color(0xFFE0312B), r * .045f, c)
        // glass highlight
        drawArc(Brush.verticalGradient(listOf(Color.White.copy(alpha = .55f), Color.Transparent), startY = c.y - r * .9f, endY = c.y), 180f, 180f, true,
            Offset(c.x - r * .8f, c.y - r * .86f), androidx.compose.ui.geometry.Size(r * 1.6f, r * .9f))
    }
}

/* -------------------------------------------- alarms ------------------------------------------ */

@Composable
private fun AlarmsTab(alarms: List<Alarm>, onEdit: (Alarm) -> Unit, onToggle: (Alarm, Boolean) -> Unit, onUpdate: (Alarm) -> Unit = {}) {
    val context = LocalContext.current
    val p = wonderPalette()
    tickingNow(30_000)
    val calendarPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val firstEvent = remember(alarms.size) { tomorrowFirstEvent(context) }
    if (alarms.isEmpty() && firstEvent == null) {
        EmptyState("No alarms", "Alarms ring at the time you choose, even when Timepiece is closed. Already have alarms in your phone's Clock app? Tap ⤓ above to bring them over.",
            action = { WButton("New alarm", { onEdit(Alarm(1, 7, 0)) }, icon = WonderIcons.Add, kind = ButtonKind.Primary) })
        return
    }
    val nextOn = alarms.filter { it.enabled }.minByOrNull { nextTrigger(it) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        if (nextOn != null) item {
            Text(untilText(nextTrigger(nextOn)).replace("Alarm set for", "Next alarm in").replace(" from now", ""), Modifier.padding(6.dp, 0.dp, 6.dp, 12.dp),
                style = MaterialTheme.typography.bodyMedium, color = p.secondaryText)
        }
        if (!Clocks.canExact(context)) item {
            Panel(Modifier.padding(bottom = 12.dp)) {
                ListRow("Allow exact alarms", subtitle = "Without this, Android may delay your alarms", icon = WonderIcons.Warning, tint = p.warning, chevron = true, onClick = {
                    if (Build.VERSION.SDK_INT >= 31) context.launchSafely(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) {}
                })
            }
        }
        item {
            val hasCal = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
            if (firstEvent != null) Panel(Modifier.padding(bottom = 12.dp), title = "From your calendar") {
                val (title, start) = firstEvent
                val wake = start - 3_600_000L
                val cal = java.util.Calendar.getInstance().apply { timeInMillis = wake }
                ListRow("Tomorrow: $title", subtitle = "Starts " + java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(start)) + ". Tap for an alarm an hour before.",
                    icon = WonderIcons.Event, tint = Color(0xFFE5483C), chevron = true, onClick = {
                        onEdit(Alarm((alarms.maxOfOrNull { it.id } ?: 0) + 1, cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE), label = "Before $title",
                            date = java.time.LocalDate.now().plusDays(1).toEpochDay()))
                    })
            } else if (!hasCal) Panel(Modifier.padding(bottom = 12.dp)) {
                ListRow("Wake up for your first event", subtitle = "Let Timepiece read your calendar to suggest alarms", icon = WonderIcons.CalendarMonth, tint = Color(0xFFE5483C), chevron = true,
                    onClick = { calendarPerm.launch(Manifest.permission.READ_CALENDAR) })
            }
        }
        itemsIndexed(alarms, key = { _, a -> a.id }) { i, a -> Box(Modifier.animateItemPlacement()) {
            val is24 = android.text.format.DateFormat.is24HourFormat(context)
            val h = if (is24) a.hour else ((a.hour + 11) % 12 + 1)
            val skipping = a.enabled && a.skipUntil > System.currentTimeMillis()
            var menu by remember { mutableStateOf(false) }
            Box {
                Row(Modifier.panelItem(i, alarms.size).combinedClickableCompat(onClick = { onEdit(a) }, onLongClick = { menu = true }).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("%d:%02d".format(h, a.minute), style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Light, fontSize = 42.sp), color = if (a.enabled) p.text else p.secondaryText)
                            if (!is24) Text(if (a.hour < 12) " AM" else " PM", style = MaterialTheme.typography.titleMedium, color = p.secondaryText, modifier = Modifier.padding(bottom = 6.dp))
                        }
                        Text(listOf(a.label, repeatLabel(a)).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = p.secondaryText)
                        if (skipping) Text("Skipping until " + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(nextTrigger(a))) + " · tap and hold to resume",
                            style = MaterialTheme.typography.labelSmall, color = p.warning)
                    }
                    IconButton(onClick = { menu = true }) { Icon(WonderIcons.MoreVert, "Alarm options", tint = p.secondaryText) }
                    Switch(a.enabled, { onToggle(a, it) }, colors = SwitchDefaults.colors(checkedTrackColor = if (p.look == WonderLook.AQUA) Color(0xFF34C759) else p.accent))
                }
                DropdownMenu(menu, { menu = false }, Modifier.background(p.panel)) {
                    if (a.enabled && a.repeating) DropdownMenuItem({ Text(if (skipping) "Resume" else "Skip next ring") }, {
                        menu = false; onUpdate(if (skipping) a.copy(skipUntil = 0) else a.copy(skipUntil = nextTrigger(a) + 1000))
                    }, leadingIcon = { Icon(WonderIcons.Snooze, null) })
                    if (a.enabled) DropdownMenuItem({ Text("Pause for today") }, {
                        menu = false
                        val tomorrow = java.time.LocalDate.now().plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                        onUpdate(a.copy(skipUntil = tomorrow))
                    }, leadingIcon = { Icon(WonderIcons.Bedtime, null) })
                    DropdownMenuItem({ Text("Duplicate") }, { menu = false; onEdit(a.copy(id = (alarms.maxOfOrNull { it.id } ?: 0) + 1)) }, leadingIcon = { Icon(WonderIcons.ContentCopy, null) })
                    DropdownMenuItem({ Text("Edit") }, { menu = false; onEdit(a) }, leadingIcon = { Icon(WonderIcons.Edit, null) })
                }
            }
        }}
        item { Text("Tap an alarm to edit it. Use ⋮ to skip the next ring or pause it for today; it switches back on by itself.", Modifier.padding(6.dp, 10.dp),
            style = MaterialTheme.typography.bodySmall, color = p.secondaryText) }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit, onLongClick: () -> Unit) = this.then(Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick))

/** Title and start of tomorrow's first timed event, if calendar access is granted. */
fun tomorrowFirstEvent(c: Context): Pair<String, Long>? {
    if (ContextCompat.checkSelfPermission(c, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return null
    val zone = java.time.ZoneId.systemDefault()
    val start = java.time.LocalDate.now().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val end = start + 86_400_000L
    val uri = android.provider.CalendarContract.Instances.CONTENT_URI.buildUpon().also { android.content.ContentUris.appendId(it, start); android.content.ContentUris.appendId(it, end) }.build()
    return runCatching {
        c.contentResolver.query(uri, arrayOf(android.provider.CalendarContract.Instances.TITLE, android.provider.CalendarContract.Instances.BEGIN, android.provider.CalendarContract.Instances.ALL_DAY),
            "${android.provider.CalendarContract.Instances.ALL_DAY} = 0", null, "${android.provider.CalendarContract.Instances.BEGIN} ASC")?.use { cur ->
            if (cur.moveToFirst()) (cur.getString(0) ?: "Event") to cur.getLong(1) else null
        }
    }.getOrNull()
}

@Composable
private fun AlarmEditor(a: Alarm, isNew: Boolean, onDismiss: () -> Unit, onSave: (Alarm) -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val p = wonderPalette()
    val is24 = android.text.format.DateFormat.is24HourFormat(context)
    @OptIn(ExperimentalMaterial3Api::class)
    val state = rememberTimePickerState(a.hour, a.minute, is24)
    var label by remember { mutableStateOf(a.label) }
    var mode by remember { mutableIntStateOf(when { a.everyDays > 1 -> 2; a.days != 0 -> 1; a.date >= 0 -> 3; else -> 0 }) }
    var days by remember { mutableIntStateOf(if (a.days == 0) 0b0011111 else a.days) }
    var every by remember { mutableIntStateOf(a.everyDays.coerceAtLeast(2)) }
    var date by remember { mutableLongStateOf(if (a.date >= 0) a.date else java.time.LocalDate.now().plusDays(1).toEpochDay()) }
    var vibrate by remember { mutableStateOf(a.vibrate) }
    var gradual by remember { mutableStateOf(a.gradual) }
    var snooze by remember { mutableIntStateOf(a.snoozeMinutes) }
    var sound by remember { mutableStateOf(a.sound) }
    var soundName by remember { mutableStateOf(a.soundName) }
    val ringtone = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        @Suppress("DEPRECATION")
        val uri = r.data?.getParcelableExtra<Uri>(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        if (r.resultCode == android.app.Activity.RESULT_OK) {
            sound = uri?.toString() ?: ""
            soundName = uri?.let { android.media.RingtoneManager.getRingtone(context, it)?.getTitle(context) } ?: ""
        }
    }
    val custom = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            sound = uri.toString(); soundName = context.displayName(uri)
        }
    }
    var pickDate by remember { mutableStateOf(false) }
    WonderDialog(onDismissRequest = onDismiss, title = { Text(if (isNew) "New alarm" else "Edit alarm") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            @OptIn(ExperimentalMaterial3Api::class)
            TimePicker(state, colors = TimePickerDefaults.colors(clockDialSelectedContentColor = p.onAccent, selectorColor = p.accent, timeSelectorSelectedContainerColor = p.accent.copy(alpha = .2f)))
            Field(label, { label = it.take(60) }, "Label")
            Segmented(listOf("Once", "Days", "Every…", "Date"), mode) { mode = it }
            when (mode) {
                1 -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        DAY_NAMES.forEachIndexed { i, d ->
                            val on = days and (1 shl i) != 0
                            Box(Modifier.size(36.dp).clip(CircleShape).background(if (on) p.accent else p.window).border(1.dp, if (on) p.accent else p.panelBorder, CircleShape)
                                .clickable { days = days xor (1 shl i) }, contentAlignment = Alignment.Center) {
                                Text(d.take(1), color = if (on) p.onAccent else p.text, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip("Weekdays", days == 0b0011111, { days = 0b0011111 }); Chip("Weekends", days == 0b1100000, { days = 0b1100000 }); Chip("Every day", days == 0b1111111, { days = 0b1111111 })
                    }
                }
                2 -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Every", Modifier.padding(end = 8.dp))
                    IconButton(onClick = { every = (every - 1).coerceAtLeast(2) }) { Icon(WonderIcons.RemoveCircle, "Fewer days", tint = p.accent) }
                    Text("$every days", style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { every = (every + 1).coerceAtMost(60) }) { Icon(WonderIcons.AddCircle, "More days", tint = p.accent) }
                    Text("from today", color = p.secondaryText, style = MaterialTheme.typography.bodySmall)
                }
                3 -> WButton(java.time.LocalDate.ofEpochDay(date).format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM yyyy")), { pickDate = true }, icon = WonderIcons.Event)
                else -> Text("Rings once, then switches off.", style = MaterialTheme.typography.bodySmall, color = p.secondaryText)
            }
            Panel {
                ListRow("Sound", subtitle = soundName.ifBlank { "Default alarm sound" }, icon = WonderIcons.MusicNote, tint = Color(0xFFE5483C), chevron = true, onClick = {
                    ringtone.launch(Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER)
                        .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE, android.media.RingtoneManager.TYPE_ALARM or android.media.RingtoneManager.TYPE_RINGTONE)
                        .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                        .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, sound.takeIf { it.isNotBlank() }?.let(Uri::parse)))
                })
                PanelDivider()
                ListRow("Use a music file", subtitle = "Pick any song or sound on your phone", icon = WonderIcons.UploadFile, tint = Color(0xFF8E44AD), chevron = true, onClick = { custom.launch(arrayOf("audio/*")) })
                PanelDivider()
                SwitchRow("Gradually louder", gradual, { gradual = it }, icon = WonderIcons.VolumeUp, tint = Color(0xFF1E7FE8))
                PanelDivider()
                SwitchRow("Vibrate", vibrate, { vibrate = it }, icon = WonderIcons.PhoneAndroid, tint = Color(0xFF7A7F87))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Snooze", Modifier.weight(1f))
                listOf(5, 10, 15, 20).forEach { m -> Chip("$m", snooze == m, { snooze = m }); Spacer(Modifier.width(6.dp)) }
                Text("min", color = p.secondaryText)
            }
            if (!isNew) WButton("Delete alarm", onDelete, icon = WonderIcons.Delete, kind = ButtonKind.Plain)
        }
    }, confirmButton = {
        @OptIn(ExperimentalMaterial3Api::class)
        TextButton(onClick = {
            onSave(a.copy(hour = state.hour, minute = state.minute, label = label.trim(), vibrate = vibrate, gradual = gradual, snoozeMinutes = snooze, enabled = true,
                days = if (mode == 1) days else 0, everyDays = if (mode == 2) every else 0, anchorDay = if (mode == 2 && a.everyDays != every) java.time.LocalDate.now().toEpochDay() else a.anchorDay,
                date = if (mode == 3) date else -1, skipUntil = 0, sound = sound, soundName = soundName))
        }) { Text("Save") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
    @OptIn(ExperimentalMaterial3Api::class)
    if (pickDate) {
        val ds = rememberDatePickerState(initialSelectedDateMillis = date * 86_400_000L)
        DatePickerDialog(onDismissRequest = { pickDate = false }, confirmButton = {
            TextButton(onClick = { ds.selectedDateMillis?.let { date = it / 86_400_000L }; pickDate = false }) { Text("OK") }
        }) { DatePicker(ds) }
    }
}

/* -------------------------------------------- timers ------------------------------------------- */

private fun hms(ms: Long, tenths: Boolean = false): String {
    val total = (ms + (if (tenths) 0 else 999)) / 1000
    val h = total / 3600; val m = total / 60 % 60; val s = total % 60
    val base = if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    return if (tenths) base + ".%d".format(ms / 100 % 10) else base
}

@Composable
private fun TimersTab(timers: MutableList<CountdownTimer>, onNew: () -> Unit, save: () -> Unit) {
    val context = LocalContext.current
    val p = wonderPalette()
    val now = tickingNow(250)
    LaunchedEffect(now / 1000) {
        // Reflect timers that finished while we were watching.
        val fresh = Clocks.timers(context)
        fresh.forEach { f -> val i = timers.indexOfFirst { it.id == f.id }; if (i >= 0 && timers[i] != f && !f.running && timers[i].running && timers[i].remaining() == 0L) timers[i] = f }
    }
    if (timers.isEmpty()) {
        Column(Modifier.fillMaxSize()) {
            EmptyState("No timers", "Start a countdown. It rings even if you leave the app.", icon = WonderIcons.HourglassBottom)
            Panel(Modifier.padding(16.dp), title = "Quick start") {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 3, 5, 10, 15, 30).forEach { m ->
                        Box(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(10.dp)).background(p.window).border(1.dp, p.panelBorder, RoundedCornerShape(10.dp)).clickable {
                            val t = CountdownTimer((timers.maxOfOrNull { it.id } ?: 0) + 1, "", m * 60_000L, System.currentTimeMillis() + m * 60_000L)
                            timers.add(t); save(); Clocks.scheduleTimer(context, t)
                        }, contentAlignment = Alignment.Center) { Text("${m}m", style = MaterialTheme.typography.titleMedium) }
                    }
                }
            }
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        itemsIndexed(timers, key = { _, t -> t.id }) { idx, t -> Box(Modifier.animateItemPlacement()) {
            val left = t.remaining(now)
            val frac = if (t.durationMs > 0) left.toFloat() / t.durationMs else 0f
            Panel {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(110.dp), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.fillMaxSize()) {
                            drawArc(p.separator, 0f, 360f, false, style = Stroke(10.dp.toPx()))
                            drawArc(if (left == 0L) p.danger else p.accent, -90f, 360f * frac, false, style = Stroke(10.dp.toPx(), cap = StrokeCap.Round))
                        }
                        Text(hms(left), style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Medium))
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(t.label.ifBlank { hms(t.durationMs) + " timer" }, style = MaterialTheme.typography.titleMedium)
                        Text(when { left == 0L -> "Done"; t.running -> "Ends " + java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(t.endAt)); else -> "Paused" },
                            style = MaterialTheme.typography.bodySmall, color = p.secondaryText)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RoundIcon(if (t.running) WonderIcons.Pause else WonderIcons.PlayArrow, if (t.running) "Pause" else "Start", primary = true) {
                                val n = if (t.running) t.copy(endAt = 0L, remainingMs = left) else t.copy(endAt = System.currentTimeMillis() + (if (left == 0L) t.durationMs else left))
                                timers[idx] = n; save(); Clocks.scheduleTimer(context, n)
                            }
                            RoundIcon(WonderIcons.Replay10, "Reset") { val n = t.copy(endAt = 0L, remainingMs = t.durationMs); timers[idx] = n; save(); Clocks.scheduleTimer(context, n) }
                            RoundIcon(WonderIcons.Add, "Add a minute") {
                                val n = if (t.running) t.copy(endAt = t.endAt + 60_000, durationMs = t.durationMs + 60_000) else t.copy(remainingMs = t.remainingMs + 60_000, durationMs = t.durationMs + 60_000)
                                timers[idx] = n; save(); Clocks.scheduleTimer(context, n)
                            }
                            RoundIcon(WonderIcons.Delete, "Delete") { Clocks.scheduleTimer(context, t.copy(endAt = 0)); Clocks.stopRinging(context, "timer", t.id); timers.removeAt(idx); save() }
                        }
                    }
                }
            }
        }}
        item { WButton("New timer", onNew, Modifier.fillMaxWidth(), icon = WonderIcons.Add) }
    }
}

@Composable
private fun RoundIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, primary: Boolean = false, onClick: () -> Unit) {
    val p = wonderPalette()
    Box(Modifier.size(40.dp).clip(CircleShape).background(if (primary) p.accent else p.window).border(1.dp, if (primary) p.accent else p.panelBorder, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) { Icon(icon, desc, tint = if (primary) p.onAccent else p.text, modifier = Modifier.size(22.dp)) }
}

@Composable
private fun TimerSetup(onDismiss: () -> Unit, onStart: (String, Long) -> Unit) {
    var h by remember { mutableIntStateOf(0) }
    var m by remember { mutableIntStateOf(5) }
    var s by remember { mutableIntStateOf(0) }
    var label by remember { mutableStateOf("") }
    val p = wonderPalette()
    WonderDialog(onDismissRequest = onDismiss, title = { Text("New timer") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spinner(h, 0..23, "h") { h = it }; Text(":", style = MaterialTheme.typography.headlineMedium)
                Spinner(m, 0..59, "m") { m = it }; Text(":", style = MaterialTheme.typography.headlineMedium)
                Spinner(s, 0..59, "s") { s = it }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(1, 5, 10, 25, 45).forEach { q -> Chip("${q}m", h == 0 && m == q && s == 0, { h = 0; m = q; s = 0 }) } }
            Field(label, { label = it.take(40) }, "Label (optional)")
        }
    }, confirmButton = { TextButton(enabled = h + m + s > 0, onClick = { onStart(label.trim(), (h * 3600L + m * 60L + s) * 1000L) }) { Text("Start", color = p.accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun Spinner(value: Int, range: IntRange, unit: String, onChange: (Int) -> Unit) {
    val p = wonderPalette()
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 4.dp)) {
        IconButton(onClick = { onChange(if (value == range.last) range.first else value + 1) }) { Icon(WonderIcons.KeyboardArrowUp, "More $unit", tint = p.accent) }
        Text("%02d".format(value), style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Light))
        Text(unit, style = MaterialTheme.typography.labelSmall, color = p.secondaryText)
        IconButton(onClick = { onChange(if (value == range.first) range.last else value - 1) }) { Icon(WonderIcons.KeyboardArrowDown, "Less $unit", tint = p.accent) }
    }
}

/* ------------------------------------------- stopwatch ------------------------------------------ */

@Composable
private fun StopwatchTab() {
    val context = LocalContext.current
    val meta = remember { context.prefs("stopwatches") }
    var names by remember { mutableStateOf(runCatching { JSONArray(meta.getString("names", "[\"Stopwatch\"]")).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrDefault(listOf("Stopwatch"))) }
    var current by rememberSaveable { mutableIntStateOf(meta.getInt("current", 0)) }
    if (current > names.lastIndex) current = 0
    var naming by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            names.forEachIndexed { i, n -> Chip(n, i == current, { current = i; meta.edit().putInt("current", i).apply() }, WonderIcons.Timer) }
            Chip("Add", false, { naming = true }, WonderIcons.Add)
            if (names.size > 1) Chip("Remove", false, {
                context.prefs("stopwatch_" + names[current]).edit().clear().apply()
                names = names - names[current]; current = 0
                meta.edit().putString("names", JSONArray(names).toString()).putInt("current", 0).apply()
            }, WonderIcons.Delete)
        }
        key(names.getOrNull(current)) { StopwatchFace(if (current == 0 && names[0] == "Stopwatch") "stopwatch2" else "stopwatch_" + names[current]) }
    }
    if (naming) InputDialog("New stopwatch", "Name", initial = "Stopwatch ${names.size + 1}", confirm = "Add", onDismiss = { naming = false }) { n ->
        if (n !in names) { names = names + n; current = names.lastIndex; meta.edit().putString("names", JSONArray(names).toString()).putInt("current", current).apply() }
    }
}

@Composable
private fun StopwatchFace(prefsName: String) {
    val context = LocalContext.current
    val prefs = remember(prefsName) { context.prefs(prefsName) }
    val p = wonderPalette()
    // Wall-clock based so it keeps counting while the app is closed.
    var startedAt by remember { mutableLongStateOf(prefs.getLong("startedAt", 0L)) }
    var accumulated by remember { mutableLongStateOf(prefs.getLong("accumulated", 0L)) }
    val laps = remember { mutableStateListOf<Long>().apply { runCatching { val a = JSONArray(prefs.getString("laps", "[]")); for (i in 0 until a.length()) add(a.getLong(i)) } } }
    val running = startedAt > 0
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val visible = rememberIsResumed()
    // Smooth on screen (every frame), nothing at all in the background; the time comes from the clock, so nothing is lost.
    LaunchedEffect(running, visible) { while (running && visible) { withFrameMillis { now = System.currentTimeMillis() } } }
    val elapsed = accumulated + if (running) (now - startedAt).coerceAtLeast(0) else 0
    fun persist() { prefs.edit().putLong("startedAt", startedAt).putLong("accumulated", accumulated).putString("laps", JSONArray(laps.toList()).toString()).apply() }
    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(24.dp))
        Text(hms(elapsed, tenths = true), style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.Light, fontFamily = FontFamily.Monospace, fontSize = 64.sp), textAlign = TextAlign.Center)
        val lastLap = elapsed - (laps.lastOrNull() ?: 0L)
        if (laps.isNotEmpty()) Text("Lap ${laps.size + 1}: ${hms(lastLap, true)}", color = p.secondaryText, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            BigRound(if (running) "Lap" else "Reset", enabled = running || elapsed > 0, color = p.window, textColor = p.text) {
                if (running) laps.add(elapsed) else { accumulated = 0; laps.clear() }
                persist()
            }
            BigRound(if (running) "Stop" else "Start", color = if (running) Color(0xFFE5483C) else Color(0xFF2E9E44), textColor = Color.White) {
                if (running) { accumulated += System.currentTimeMillis() - startedAt; startedAt = 0 } else startedAt = System.currentTimeMillis()
                now = System.currentTimeMillis(); persist()
            }
        }
        Spacer(Modifier.height(24.dp))
        if (laps.isNotEmpty()) {
            val splits = laps.mapIndexed { i, t -> t - (if (i == 0) 0 else laps[i - 1]) }
            val best = splits.minOrNull(); val worst = splits.maxOrNull()
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                val rows = splits.indices.reversed().toList()
                itemsIndexed(rows) { pos, i ->
                    Row(Modifier.panelItem(pos, rows.size).padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text("Lap ${i + 1}", Modifier.weight(1f), color = when { splits.size > 1 && splits[i] == best -> p.success; splits.size > 1 && splits[i] == worst -> p.danger; else -> p.text })
                        Text(hms(splits[i], true), fontFamily = FontFamily.Monospace)
                        Text("   " + hms(laps[i], true), fontFamily = FontFamily.Monospace, color = p.secondaryText)
                    }
                }
            }
        }
    }
}

@Composable
private fun BigRound(text: String, color: Color, textColor: Color, enabled: Boolean = true, onClick: () -> Unit) {
    val p = wonderPalette()
    Box(Modifier.size(92.dp).clip(CircleShape).background(Brush.verticalGradient(listOf(color.lighten(.3f), color, color.darken(.12f))))
        .border(1.dp, p.panelBorder, CircleShape).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, color = textColor.copy(alpha = if (enabled) 1f else .4f), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
    }
}

/* ------------------------------------------ ringing screen --------------------------------------- */

class RingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val kind = intent.getStringExtra(Clocks.EXTRA_KIND) ?: "alarm"
        val id = intent.getIntExtra(Clocks.EXTRA_ID, 0)
        val label = intent.getStringExtra("label") ?: "Alarm"
        setContent {
            WonderTheme {
                val now = tickingNow()
                Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0E1B3D), Color(0xFF2A3F7A), Color(0xFF5C3D8F)))).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceEvenly) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        AquaClockFace(now, Modifier.size(200.dp))
                        Spacer(Modifier.height(20.dp))
                        Text(java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(now)), color = Color.White,
                            style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Light))
                        Text(label, color = Color.White.copy(alpha = .85f), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                    }
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (kind == "alarm") Box(Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(32.dp)).background(Color.White.copy(alpha = .18f)).clickable {
                            Clocks.stopRinging(this@RingActivity, kind, id); RingService.stop(this@RingActivity)
                            val a = Clocks.alarms(this@RingActivity).find { it.id == id }
                            Clocks.snooze(this@RingActivity, id, a?.label ?: "", a?.snoozeMinutes ?: 10)
                            finish()
                        }, contentAlignment = Alignment.Center) {
                            Text("Snooze ${Clocks.alarms(this@RingActivity).find { it.id == id }?.snoozeMinutes ?: 10} min", color = Color.White, style = MaterialTheme.typography.titleLarge)
                        }
                        Box(Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(32.dp)).background(Brush.verticalGradient(listOf(Color(0xFFFF7A6E), Color(0xFFE0312B)))).clickable {
                            Clocks.stopRinging(this@RingActivity, kind, id); RingService.stop(this@RingActivity); finish()
                        }, contentAlignment = Alignment.Center) {
                            Text(if (kind == "timer") "Stop" else "Dismiss", color = Color.White, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold))
                        }
                    }
                }
            }
        }
    }
}

/** Brings alarms over from the phone's Clock app: the next one directly, or all of them by reading its list. */
@Composable
private fun ImportFromClock(existing: List<Alarm>, onDismiss: () -> Unit, onImport: (List<FoundAlarm>) -> Unit) {
    val context = LocalContext.current
    val p = wonderPalette()
    val resumed = rememberIsResumed()
    val clock = remember { ClockImport.clockApp(context) }
    val clockName = remember(clock) { clock?.let { runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(it, 0)).toString() }.getOrNull() } ?: "Clock" }
    var next by remember { mutableStateOf(ClockImport.nextSystemAlarm(context)) }
    var reader by remember { mutableStateOf(SpecialAccess.granted(context, SpecialAccess.Kind.ACCESSIBILITY, ClockReader::class.java)) }
    var guide by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(resumed) {
        if (!resumed) return@LaunchedEffect
        reader = SpecialAccess.granted(context, SpecialAccess.Kind.ACCESSIBILITY, ClockReader::class.java)
        next = ClockImport.nextSystemAlarm(context)
        chosen = chosen + ClockImport.found.keys
    }
    val found = ClockImport.found.values.sortedBy { it.hour * 60 + it.minute }
    fun fmt(f: FoundAlarm) = java.time.LocalTime.of(f.hour, f.minute).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
    WonderDialog(onDismissRequest = onDismiss, title = { Text("Bring alarms from $clockName") }, text = {
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (found.isEmpty()) {
                next?.let { (f, from) ->
                    Panel(title = "Next alarm") {
                        ListRow(fmt(f), subtitle = "Set in $from", icon = WonderIcons.Alarm, tint = Color(0xFFE5483C), trailing = {
                            TextButton(enabled = existing.none { it.hour == f.hour && it.minute == f.minute }, onClick = { onImport(listOf(f)) }) { Text("Import") }
                        })
                    }
                }
                Text("To bring over every alarm, Timepiece reads the list in $clockName while you scroll it. It only reads clock apps, only during this import, and keeps nothing else.",
                    style = MaterialTheme.typography.bodyMedium)
                Panel {
                    ListRow("1. Turn on “Timepiece alarm import”", subtitle = if (reader) "On" else "An accessibility switch Android asks you to allow", icon = WonderIcons.Accessibility, tint = Color(0xFF1E7FE8), chevron = !reader,
                        trailing = if (reader) ({ Icon(WonderIcons.CheckCircle, null, tint = p.success) }) else null, onClick = { if (!reader) guide = true })
                    PanelDivider()
                    ListRow("2. Open $clockName and scroll through your alarms", subtitle = "Then come back here", icon = WonderIcons.OpenInNew, tint = Color(0xFF2E9E44), chevron = true,
                        onClick = { if (!reader) guide = true else { ClockImport.start(); ClockImport.openClockAlarms(context, clock) } })
                }
            } else {
                Text("Found ${found.size} alarm${if (found.size == 1) "" else "s"} in $clockName. Choose which to bring over.", style = MaterialTheme.typography.bodyMedium)
                Panel {
                    found.forEachIndexed { i, f ->
                        if (i > 0) PanelDivider()
                        val dup = existing.any { it.hour == f.hour && it.minute == f.minute && it.days == f.days }
                        ListRow(fmt(f) + if (f.label.isNotBlank()) "  ·  ${f.label}" else "",
                            subtitle = (if (f.days == 0) "Once" else if (f.days == 0x7F) "Every day" else repeatText(f.days)) + (if (!f.enabled) " · off" else "") + if (dup) " · already in Timepiece" else "",
                            trailing = { Checkbox(f.key in chosen && !dup, { on -> chosen = if (on) chosen + f.key else chosen - f.key }, enabled = !dup) })
                    }
                }
                TextButton(onClick = { ClockImport.start(); ClockImport.openClockAlarms(context, clock) }) { Text("Scan again") }
                Text("Afterwards you can turn off $clockName in Control Room, so only Timepiece rings.", style = MaterialTheme.typography.bodySmall, color = p.secondaryText)
            }
        }
    }, confirmButton = {
        if (found.isNotEmpty()) TextButton(enabled = found.any { it.key in chosen }, onClick = { onImport(found.filter { it.key in chosen }) }) { Text("Import ${found.count { it.key in chosen }}") }
        else TextButton(onClick = onDismiss) { Text("Close") }
    }, dismissButton = if (found.isNotEmpty()) ({ TextButton(onClick = onDismiss) { Text("Cancel") } }) else null)
    if (guide) AccessGuide(SpecialAccess.Kind.ACCESSIBILITY, "Turn on “Timepiece alarm import”. It reads the alarm list in your Clock app only while you're importing, and nothing else.",
        onDismiss = { guide = false }, component = ClockReader::class.java, onGranted = { reader = true })
}

private fun repeatText(days: Int) = java.time.DayOfWeek.entries.filter { days and (1 shl (it.value - 1)) != 0 }.joinToString(", ") { it.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()) }
