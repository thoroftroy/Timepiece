package dev.wonderdesk.shared

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.json.JSONArray

fun elapsedLabel(millis: Long): String = String.format(Locale.ROOT, "%02d:%02d.%01d", millis / 60000, millis / 1000 % 60, millis / 100 % 10)
fun stopwatchElapsed(accumulated: Long, started: Long, now: Long): Long =
    accumulated + if (started > 0L) (now - started).coerceAtLeast(0L) else 0L
fun recoverStopwatchStart(savedStart: Long, savedBoot: Int, currentBoot: Int, now: Long): Long =
    if (savedStart < 0L || savedStart > now || (savedBoot >= 0 && currentBoot >= 0 && savedBoot != currentBoot)) 0L else savedStart

@Composable
fun ClockScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("stopwatch", Context.MODE_PRIVATE) }
    val bootCount = remember { runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1) }.getOrDefault(-1) }
    val op = rememberOperation()
    var now by remember { mutableStateOf(Instant.now()) }
    var started by remember { mutableLongStateOf(recoverStopwatchStart(prefs.getLong("started", 0L), prefs.getInt("boot", -1), bootCount, SystemClock.elapsedRealtime())) }
    var accumulated by remember { mutableLongStateOf(prefs.getLong("accumulated", 0L)) }
    var elapsed by remember { mutableLongStateOf(accumulated) }
    var laps by remember {
        mutableStateOf(runCatching {
            val array = JSONArray(prefs.getString("laps", "[]"))
            (0 until minOf(array.length(), 100)).map { array.getLong(it) }.filter { it >= 0L }
        }.getOrDefault(emptyList()))
    }
    var timerMinutes by rememberSaveable { mutableStateOf("5") }
    var zoneText by rememberSaveable { mutableStateOf("Europe/London") }
    var zones by remember { mutableStateOf(prefs.getStringSet("zones", setOf(ZoneId.systemDefault().id))!!.sorted()) }
    fun saveStopwatch() { prefs.edit().putLong("started", started).putLong("accumulated", accumulated).putInt("boot", bootCount).apply() }
    fun saveLaps() { prefs.edit().putString("laps", JSONArray(laps).toString()).apply() }
    LaunchedEffect(started, accumulated) {
        while (true) {
            now = Instant.now()
            elapsed = stopwatchElapsed(accumulated, started, SystemClock.elapsedRealtime())
            delay(if (started > 0) 100 else 1000)
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("World clocks", style = MaterialTheme.typography.titleMedium)
        zones.forEach { zone ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) { Text(zone); Text(now.atZone(ZoneId.of(zone)).format(DateTimeFormatter.ofPattern("EEE, d MMM · HH:mm:ss")), style = MaterialTheme.typography.titleLarge) }
                TextButton(onClick = { zones = zones - zone; prefs.edit().putStringSet("zones", zones.toSet()).apply() }) { Text("Remove") }
            }
        }
        OutlinedTextField(zoneText, { zoneText = it }, Modifier.fillMaxWidth(), label = { Text("Time zone (e.g. America/New_York)") }, singleLine = true)
        TextButton(onClick = {
            runCatching { ZoneId.of(zoneText) }.onSuccess { zones = (zones + it.id).distinct(); prefs.edit().putStringSet("zones", zones.toSet()).apply(); op.notice("Clock added") }.onFailure { op.notice("Unknown time zone; use a region/city such as Asia/Tokyo.") }
        }) { Text("Add world clock") }
        HorizontalDivider()
        Text("Stopwatch", style = MaterialTheme.typography.titleMedium)
        Text(elapsedLabel(elapsed), style = MaterialTheme.typography.displayMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AquaButton(onClick = {
                val now = SystemClock.elapsedRealtime()
                if (started == 0L) started = now else { accumulated = stopwatchElapsed(accumulated, started, now); elapsed = accumulated; started = 0L }
                saveStopwatch()
            }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp)) { Text(if (started == 0L) "Start" else "Pause") }
            AquaSecondaryButton(onClick = {
                laps = laps + stopwatchElapsed(accumulated, started, SystemClock.elapsedRealtime()); saveLaps()
            }, modifier = Modifier.weight(1f), enabled = started != 0L && laps.size < 100, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("Lap") }
            AquaSecondaryButton(onClick = { started = 0L; accumulated = 0L; elapsed = 0L; laps = emptyList(); saveStopwatch(); saveLaps() }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp)) { Text("Reset") }
        }
        if (laps.isNotEmpty()) {
            Text("Laps · Reset clears all laps", style = MaterialTheme.typography.labelMedium)
            laps.indices.reversed().forEach { index ->
                val split = laps[index] - if (index == 0) 0L else laps[index - 1]
                Text("Lap ${index + 1} · ${elapsedLabel(laps[index])} · split ${elapsedLabel(split.coerceAtLeast(0L))}", style = MaterialTheme.typography.bodySmall)
            }
        }
        HorizontalDivider()
        Text("Alarms", style = MaterialTheme.typography.titleMedium)
        val alarmList = remember { mutableStateOf(loadAlarms(context)) }
        var newHour by rememberSaveable { mutableStateOf("8") }
        var newMinute by rememberSaveable { mutableStateOf("00") }
        var newLabel by rememberSaveable { mutableStateOf("") }
        alarmList.value.sortedWith(compareBy({ it.hour }, { it.minute })).forEach { alarm ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(10.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("%02d:%02d  %s".format(alarm.hour, alarm.minute, alarm.label.ifBlank { "Alarm" }))
                        Text(if (alarm.enabled) "On" else "Off", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = {
                        val updated = alarm.copy(enabled = !alarm.enabled)
                        alarmList.value = alarmList.value.map { if (it.id == alarm.id) updated else it }
                        saveAlarms(context, alarmList.value)
                        scheduleAlarm(context, updated)
                    }) { Text(if (alarm.enabled) "Turn off" else "Turn on") }
                    TextButton(onClick = {
                        alarmList.value = alarmList.value.filterNot { it.id == alarm.id }
                        saveAlarms(context, alarmList.value)
                        val am = context.getSystemService(AlarmManager::class.java)
                        am.cancel(PendingIntent.getBroadcast(context, alarm.id.hashCode(), Intent(context, AlarmReceiver::class.java), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) ?: return@TextButton)
                    }) { Text("Delete") }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(newHour, { newHour = it.take(2) }, Modifier.weight(1f), label = { Text("Hour (0-23)") }, singleLine = true)
            OutlinedTextField(newMinute, { newMinute = it.take(2) }, Modifier.weight(1f), label = { Text("Minute (0-59)") }, singleLine = true)
        }
        OutlinedTextField(newLabel, { newLabel = it.take(60) }, Modifier.fillMaxWidth(), label = { Text("Label (optional)") }, singleLine = true)
        AquaButton(onClick = {
            val hour = newHour.toIntOrNull(); val minute = newMinute.toIntOrNull()
            if (hour == null || minute == null || hour !in 0..23 || minute !in 0..59) { op.notice("Enter hour 0-23 and minute 0-59"); return@AquaButton }
            val alarm = AlarmEntry("alarm-${System.currentTimeMillis()}", hour, minute, newLabel, true)
            alarmList.value = alarmList.value + alarm
            saveAlarms(context, alarmList.value)
            scheduleAlarm(context, alarm)
            op.notice("Alarm set for %02d:%02d. It fires from this app with sound and a notification, even if the app is closed.".format(hour, minute))
        }) { Text("Add alarm") }
        TextButton(onClick = { context.launchSafely(Intent(AlarmClock.ACTION_SHOW_ALARMS), op::notice) }) { Text("Open system alarms") }
        OutlinedTextField(timerMinutes, { timerMinutes = it.take(5) }, Modifier.fillMaxWidth(), label = { Text("Timer minutes (1–1440)") }, singleLine = true)
        Row(Modifier.fillMaxWidth()) {
            listOf("5", "10", "15", "30").forEach { preset ->
                TextButton(onClick = { timerMinutes = preset }, modifier = Modifier.weight(1f)) { Text(if (timerMinutes == preset) "• $preset" else preset) }
            }
        }
        AquaSecondaryButton(onClick = {
            val minutes = timerMinutes.toIntOrNull()
            if (minutes == null || minutes !in 1..1440) op.notice("Enter 1 to 1440 minutes")
            else context.launchSafely(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, minutes * 60).putExtra(AlarmClock.EXTRA_MESSAGE, "Wonderdesk timer").putExtra(AlarmClock.EXTRA_SKIP_UI, false), op::notice)
        }) { Text("Set in system clock…") }
        OperationStatus(op)
        Text("Alarms and timer delivery use the installed system clock app, with its own confirmation and permissions. Stopwatch uses monotonic time and is not a background alarm. A running segment is discarded after a reboot when Android's boot count is available; accumulated paused time and laps remain.", style = MaterialTheme.typography.bodySmall)
    }
}
