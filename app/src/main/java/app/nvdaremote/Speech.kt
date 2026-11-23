package app.nvdaremote

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech

/** Thin wrapper around Android TextToSpeech. Use from the main thread only. */
class Speech(context: Context, private var rate: Float) {
    private class Pending(val text: String, val flush: Boolean, val holdable: Boolean)

    private var tts: TextToSpeech? = null
    private var ready = false
    private var closed = false
    private var counter = 0
    private var paused = false
    private val waiting = ArrayList<Pending>() // spoken once the engine is ready
    private val held = ArrayList<String>() // remote speech that arrived while paused

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            val engine = tts
            if (status == TextToSpeech.SUCCESS && engine != null && !closed) {
                engine.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                engine.setSpeechRate(rate)
                ready = true
                val pending = ArrayList(waiting)
                waiting.clear()
                for (p in pending) enqueue(p.text, p.flush, p.holdable)
            } else {
                waiting.clear()
            }
        }
    }

    /** Speech from the remote computer: queued, and held while NVDA has paused speech. */
    fun speak(text: String) = enqueue(text, flush = false, holdable = true)

    /** The app's own messages: never held by a pause; by default they interrupt current speech. */
    fun announce(text: String, interrupt: Boolean = true) = enqueue(text, flush = interrupt, holdable = false)

    private fun enqueue(text: String, flush: Boolean, holdable: Boolean) {
        if (closed || text.isBlank()) return
        if (!ready) {
            if (flush) waiting.clear()
            // If the engine never starts, do not keep every utterance of a long session in memory.
            if (waiting.size < MAX_WAITING) waiting.add(Pending(text, flush, holdable))
            return
        }
        if (paused && holdable) {
            if (held.size < MAX_WAITING) held.add(text)
            return
        }
        val engine = tts ?: return
        var mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        // Android refuses (silently) anything longer than the engine's input limit.
        for (part in splitForSpeech(text, maxInputLength)) {
            engine.speak(part, mode, null, "u${counter++}")
            mode = TextToSpeech.QUEUE_ADD
        }
    }

    /** NVDA's cancelSpeech: stops talking, drops held speech and ends a pause. */
    fun cancel() {
        held.clear()
        paused = false
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
        closed = true
        waiting.clear()
        held.clear()
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }

    companion object {
        private const val MAX_WAITING = 200

        private val maxInputLength: Int
            get() = try {
                TextToSpeech.getMaxSpeechInputLength()
            } catch (_: Throwable) {
                4000
            }

        /**
         * Splits [text] into pieces of at most [max] characters, preferring whitespace boundaries and
         * never splitting a surrogate pair.
         */
        fun splitForSpeech(text: String, max: Int): List<String> {
            if (max <= 1 || text.length <= max) return listOf(text)
            val parts = ArrayList<String>()
            var start = 0
            while (start < text.length) {
                var end = minOf(start + max, text.length)
                if (end < text.length) {
                    var cut = end
                    while (cut > start && !text[cut - 1].isWhitespace()) cut--
                    end = if (cut > start) cut else end
                    if (end < text.length && end > start + 1 && Character.isHighSurrogate(text[end - 1])) end--
                }
                val part = text.substring(start, end)
                if (part.isNotBlank()) parts.add(part)
                start = end
            }
            return parts
        }
    }
}
