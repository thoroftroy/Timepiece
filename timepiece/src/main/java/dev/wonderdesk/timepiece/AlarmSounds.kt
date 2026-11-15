package dev.wonderdesk.timepiece

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** Alarm sounds made by Timepiece itself (nothing to download): soft Aqua-style chimes that loop cleanly. */
object AlarmSounds {
    class Tone(val id: String, val name: String, val blurb: String)
    val all = listOf(
        Tone("aqua-drop", "Aqua Drop", "Soft water drops"),
        Tone("glass", "Glass Bell", "A clear bell, ringing out"),
        Tone("morning", "Morning Chime", "A rising four-note chime"),
        Tone("sunrise", "Sunrise", "A slow, warm swell: gentle for waking up"),
        Tone("pulse", "Digital Pulse", "Classic beep-beep-beep, impossible to sleep through"),
    )
    private const val RATE = 22050

    fun uri(c: Context, id: String): Uri {
        val f = File(c.filesDir, "sounds/$id.v1.wav")
        if (!f.exists()) { f.parentFile?.mkdirs(); f.writeBytes(wav(render(id))) }
        return Uri.fromFile(f)
    }
    fun isOurs(sound: String) = sound.startsWith("file:") && sound.contains("/sounds/")
    fun nameOf(sound: String) = all.find { sound.contains("/${it.id}.") }?.name

    private fun note(buf: FloatArray, at: Double, hz: Double, len: Double, amp: Double, partials: List<Pair<Double, Double>>, decay: Double, attack: Double = .004) {
        val s = (at * RATE).toInt(); val n = (len * RATE).toInt()
        for (i in 0 until n) {
            val j = s + i; if (j >= buf.size) break
            val t = i.toDouble() / RATE
            var v = 0.0
            for ((m, a) in partials) v += a * sin(2 * PI * hz * m * t)
            val env = (if (t < attack) t / attack else 1.0) * exp(-t * decay)
            buf[j] += (v * env * amp).toFloat()
        }
    }

    private fun render(id: String): FloatArray {
        val buf = FloatArray(RATE * 4)
        val bell = listOf(1.0 to 1.0, 2.76 to .45, 5.4 to .22, 8.9 to .1)
        val pure = listOf(1.0 to 1.0, 2.0 to .12)
        when (id) {
            "aqua-drop" -> listOf(0.0 to 880.0, .45 to 1175.0, .9 to 988.0, 1.6 to 1319.0, 2.05 to 1047.0, 2.5 to 1568.0).forEach { (t, f) ->
                // a drop's pitch slides down a touch, so use two quick partials
                note(buf, t, f, .9, .45, pure, 7.0); note(buf, t, f * 1.5, .5, .1, pure, 11.0)
            }
            "glass" -> listOf(0.0, 1.35, 2.7).forEach { t -> note(buf, t, 784.0, 1.3, .5, bell, 3.2) }
            "morning" -> listOf(523.25, 659.25, 783.99, 1046.5).forEachIndexed { i, f -> note(buf, i * .35, f, 1.4, .42, bell, 3.5) }
            "sunrise" -> {
                for (k in listOf(0.0, 2.0)) for (f in listOf(261.63, 329.63, 392.0, 523.25)) {
                    val n = (2.0 * RATE).toInt(); val st = (k * RATE).toInt()
                    for (i in 0 until n) { val j = st + i; if (j >= buf.size) break; val t = i.toDouble() / RATE
                        val env = sin(PI * t / 2.0).let { it * it }
                        buf[j] += (sin(2 * PI * f * t) * .16 * env + sin(2 * PI * f * 2 * t) * .04 * env).toFloat() }
                }
            }
            else -> for (g in 0 until 2) for (b in 0 until 3) note(buf, g * 2.0 + b * .22, 1760.0, .12, .5, listOf(1.0 to 1.0, 3.0 to .25), 4.0, .002)
        }
        // normalise and fade the very end so the loop never clicks
        var peak = 0f; for (x in buf) peak = maxOf(peak, kotlin.math.abs(x))
        val g = if (peak > 0) .85f / peak else 1f
        for (i in buf.indices) { val tail = (buf.size - i).coerceAtMost(400) / 400f; buf[i] *= g * tail }
        return buf
    }

    private fun wav(pcm: FloatArray): ByteArray {
        val o = ByteArrayOutputStream(); val dataLen = pcm.size * 2
        fun i32(v: Int) { o.write(v and 255); o.write(v shr 8 and 255); o.write(v shr 16 and 255); o.write(v shr 24 and 255) }
        fun i16(v: Int) { o.write(v and 255); o.write(v shr 8 and 255) }
        o.write("RIFF".toByteArray()); i32(36 + dataLen); o.write("WAVEfmt ".toByteArray()); i32(16); i16(1); i16(1); i32(RATE); i32(RATE * 2); i16(2); i16(16)
        o.write("data".toByteArray()); i32(dataLen)
        for (x in pcm) i16((x.coerceIn(-1f, 1f) * 32767).toInt())
        return o.toByteArray()
    }

    private var preview: MediaPlayer? = null
    fun stopPreview() { runCatching { preview?.stop(); preview?.release() }; preview = null }
    fun play(c: Context, id: String) {
        stopPreview()
        preview = runCatching { MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            setDataSource(c, uri(c, id)); prepare(); start(); setOnCompletionListener { stopPreview() }
        } }.getOrNull()
    }
}
