package app.nvdaremote

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** Plays the beeps NVDA asks for ("tone" message). Use from the main thread. */
object Tones {
    private const val RATE = 44100
    private val handler = Handler(Looper.getMainLooper())

    fun beep(hz: Double, lengthMs: Int, left: Int, right: Int) {
        val freq = hz.coerceIn(20.0, 20000.0)
        val frames = (RATE * lengthMs.coerceIn(1, 2000) / 1000)
        val fade = min(frames / 2, RATE / 200) // 5 ms fade in/out avoids clicks
        val l = left.coerceIn(0, 100) / 100.0 * 0.4
        val r = right.coerceIn(0, 100) / 100.0 * 0.4
        val buf = ShortArray(frames * 2)
        for (i in 0 until frames) {
            var s = sin(2 * PI * freq * i / RATE)
            if (fade > 0) {
                if (i < fade) s *= i.toDouble() / fade
                if (i >= frames - fade) s *= (frames - 1 - i).toDouble() / fade
            }
            buf[2 * i] = (s * l * Short.MAX_VALUE).toInt().toShort()
            buf[2 * i + 1] = (s * r * Short.MAX_VALUE).toInt().toShort()
        }
        var track: AudioTrack? = null
        try {
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(buf.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(buf, 0, buf.size)
            track.play()
            val playing: AudioTrack = track
            handler.postDelayed({ playing.release() }, lengthMs.coerceIn(1, 2000) + 200L)
        } catch (_: Exception) {
            // Release the native player too, or a failed beep leaks one for good.
            try {
                track?.release()
            } catch (_: Exception) {
            }
        }
    }
}
