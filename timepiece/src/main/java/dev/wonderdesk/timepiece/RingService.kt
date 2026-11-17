package dev.wonderdesk.timepiece

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Plays an alarm's chosen sound in a loop until dismissed, snoozed or 10 minutes pass. */
class RingService : Service() {
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val handler = Handler(Looper.getMainLooper())
    private var kind = "alarm"
    private var id = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopEverything(); return START_NOT_STICKY }
        // A new ring replaces whatever is ringing now.
        stopSound()
        kind = intent?.getStringExtra(Clocks.EXTRA_KIND) ?: "alarm"
        id = intent?.getIntExtra(Clocks.EXTRA_ID, 0) ?: 0
        ringing = Triple(kind, id, intent?.getStringExtra("label").orEmpty())
        val label = intent?.getStringExtra("label").orEmpty()
        val sound = intent?.getStringExtra("sound").orEmpty()
        val gradual = intent?.getBooleanExtra("gradual", false) ?: false
        val vibrate = intent?.getBooleanExtra("vibrate", true) ?: true
        val sp = getSharedPreferences("timepiece", Context.MODE_PRIVATE)
        val notification = RingReceiver.buildRing(this, kind, id, label)
        if (Build.VERSION.SDK_INT >= 29) startForeground(Clocks.ringNotificationId(kind, id), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(Clocks.ringNotificationId(kind, id), notification)
        play(sound, gradual)
        if (vibrate) vibrate()
        showFullScreen(label)
        handler.postDelayed({
            // Nobody answered: snooze an alarm once, silence a timer.
            if (kind == "alarm" && sp.getBoolean("autoSnooze", true)) Clocks.snooze(this, id, label, Clocks.alarms(this).find { it.id == id }?.snoozeMinutes ?: 10)
            stopEverything()
        }, sp.getInt("ringMin", 10) * 60_000L)
        return START_NOT_STICKY
    }

    /**
     * Android only shows the full-screen alarm by itself when the phone is locked or the screen is off; while you're
     * using the phone it shrinks to a banner. With "Display over other apps" allowed, Timepiece opens the ring screen
     * itself so an alarm always takes the whole screen.
     */
    private fun showFullScreen(label: String) {
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (pm.isInteractive && Build.VERSION.SDK_INT >= 23 && !android.provider.Settings.canDrawOverlays(this)) return
        runCatching {
            startActivity(Intent(this, RingActivity::class.java).putExtra(Clocks.EXTRA_KIND, kind).putExtra(Clocks.EXTRA_ID, id)
                .putExtra("label", label.ifBlank { if (kind == "timer") "Timer" else "Alarm" })
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION))
        }
    }

    private fun play(sound: String, gradual: Boolean) {
        val candidates = listOfNotNull(
            sound.takeIf { it.isNotBlank() }?.let(Uri::parse),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
        )
        for (uri in candidates) {
            val p = runCatching {
                MediaPlayer().apply {
                    setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    setDataSource(this@RingService, uri)
                    isLooping = true
                    prepare()
                    start()
                }
            }.getOrNull()
            if (p != null) { player = p; break }
        }
        val p = player ?: return
        val sp = getSharedPreferences("timepiece", Context.MODE_PRIVATE)
        val top = sp.getInt("maxVol", 100) / 100f
        val rampSec = sp.getInt("rampSec", 30).coerceAtLeast(1)
        if (gradual) {
            var step = 0
            p.setVolume(0.05f * top, 0.05f * top)
            val ramp = object : Runnable {
                override fun run() {
                    step++
                    val v = ((0.05f + step.toFloat() / rampSec) * top).coerceAtMost(top)
                    runCatching { p.setVolume(v, v) }
                    if (v < top) handler.postDelayed(this, 1000)
                }
            }
            handler.postDelayed(ramp, 1000)
        } else runCatching { p.setVolume(top, top) }
    }

    private fun vibrate() {
        val v = if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java).defaultVibrator
                else @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
        vibrator = v
        runCatching {
            val pat = when (getSharedPreferences("timepiece", Context.MODE_PRIVATE).getInt("vibPattern", 0)) {
                1 -> longArrayOf(0, 200, 200); 2 -> longArrayOf(0, 120, 120, 120, 700); 3 -> longArrayOf(0, 1500, 400); else -> longArrayOf(0, 700, 500) }
            v.vibrate(VibrationEffect.createWaveform(pat, 0),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
    }

    private fun stopSound() {
        handler.removeCallbacksAndMessages(null)
        runCatching { player?.stop() }; runCatching { player?.release() }; player = null
        runCatching { vibrator?.cancel() }
    }

    private fun stopEverything() {
        ringing = null
        stopSound()
        Clocks.stopRinging(this, kind, id)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { ringing = null; stopSound(); super.onDestroy() }

    companion object {
        const val ACTION_STOP = "dev.wonderdesk.timepiece.STOP_RING"

        fun start(c: Context, kind: String, id: Int, label: String, alarm: Alarm?) {
            val i = Intent(c, RingService::class.java).putExtra(Clocks.EXTRA_KIND, kind).putExtra(Clocks.EXTRA_ID, id).putExtra("label", label)
                .putExtra("sound", alarm?.sound ?: c.getSharedPreferences("timepiece", Context.MODE_PRIVATE).getString("timer_sound", "").orEmpty())
                .putExtra("gradual", alarm?.gradual ?: false).putExtra("vibrate", alarm?.vibrate ?: true)
            runCatching { c.startForegroundService(i) }
        }

        fun stop(c: Context) {
            ringing?.let { (k, i, _) -> Clocks.stopRinging(c, k, i) }
            ringing = null
            runCatching { c.startService(Intent(c, RingService::class.java).setAction(ACTION_STOP)) }
                .onFailure { runCatching { c.stopService(Intent(c, RingService::class.java)) } }
        }
        /** What's ringing right now (kind, id, label), or null. */
        @Volatile var ringing: Triple<String, Int, String>? = null
    }
}
