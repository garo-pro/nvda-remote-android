package app.nvdaremote

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech

/** Thin wrapper around Android TextToSpeech. Use from the main thread only. */
class Speech(context: Context, private var rate: Float) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var counter = 0
    private var paused = false
    private val waiting = ArrayList<Pair<String, Boolean>>() // text, interrupt
    private val held = ArrayList<String>()

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            val engine = tts
            if (status == TextToSpeech.SUCCESS && engine != null) {
                engine.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                engine.setSpeechRate(rate)
                ready = true
                for ((text, interrupt) in waiting) speak(text, interrupt)
                waiting.clear()
            }
        }
    }

    fun speak(text: String, interrupt: Boolean = false) {
        if (!ready) {
            waiting.add(text to interrupt)
            return
        }
        if (paused && !interrupt) {
            held.add(text)
            return
        }
        val mode = if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts?.speak(text, mode, null, "u${counter++}")
    }

    fun cancel() {
        held.clear()
        tts?.stop()
    }

    /** NVDA's pause: stop talking now and hold new speech until resumed. */
    fun setPaused(on: Boolean) {
        if (on == paused) return
        paused = on
        if (on) {
            tts?.stop()
        } else {
            val pending = ArrayList(held)
            held.clear()
            for (text in pending) speak(text)
        }
    }

    fun setRate(newRate: Float) {
        rate = newRate
        tts?.setSpeechRate(newRate)
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}
