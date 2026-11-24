package app.nvdaremote

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager

/** A voice the user can pick; see [Speech.choices]. */
class SpeechChoice(val id: String, val label: String)

/**
 * Speaks the remote computer's speech and the app's own messages. Use from the main thread only.
 *
 * [output] picks the voice. [SCREEN_READER] hands everything to the screen reader that is running
 * (TalkBack, Jieshuo, Prudence...), which speaks it with its own engine, voice, rate and volume; while
 * no screen reader is on, the default text-to-speech engine speaks instead. [DEFAULT_ENGINE], or the
 * package name of an installed text-to-speech engine, speaks through that engine.
 */
class Speech(context: Context, private var rate: Float, private var output: String) {
    private interface Sink {
        fun say(text: String, flush: Boolean)
        fun stop()
        fun shutdown()
    }

    private val app = context.applicationContext
    private val access = app.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    private var sink: Sink? = null
    private var closed = false
    private var paused = false
    private val held = ArrayList<String>() // remote speech that arrived while paused

    /** Called when speech moves between the screen reader and a text-to-speech engine. */
    var onRouteChanged: (() -> Unit)? = null

    /** True while speech goes to the screen reader rather than to a text-to-speech engine. */
    val usesScreenReader: Boolean get() = sink is ScreenReaderSink

    // Turning a screen reader on or off changes these states (it requests touch exploration).
    private val stateListener = AccessibilityManager.AccessibilityStateChangeListener { refresh() }
    private val touchListener = AccessibilityManager.TouchExplorationStateChangeListener { refresh() }

    init {
        access.addAccessibilityStateChangeListener(stateListener)
        access.addTouchExplorationStateChangeListener(touchListener)
        refresh()
    }

    /** Picks the voice again. Call it when a screen reader may have been turned on or off. */
    fun refresh() {
        if (closed) return
        val toScreenReader = output == SCREEN_READER && screenReaderOn(access)
        val engine = output.takeUnless { it == SCREEN_READER || it == DEFAULT_ENGINE }
        val current = sink
        val unchanged = if (toScreenReader) current is ScreenReaderSink
        else current is TtsSink && current.engine == engine
        if (unchanged) return
        val wasScreenReader = current is ScreenReaderSink
        current?.shutdown()
        sink = if (toScreenReader) ScreenReaderSink() else TtsSink(engine)
        if (wasScreenReader != toScreenReader) onRouteChanged?.invoke()
    }

    /** Switches to [SCREEN_READER], [DEFAULT_ENGINE] or a text-to-speech engine's package name. */
    fun setOutput(newOutput: String) {
        output = newOutput
        refresh()
    }

    /** Speech from the remote computer: queued, and held while NVDA has paused speech. */
    fun speak(text: String) = enqueue(text, flush = false, holdable = true)

    /** The app's own messages: never held by a pause; by default they interrupt current speech. */
    fun announce(text: String, interrupt: Boolean = true) = enqueue(text, flush = interrupt, holdable = false)

    private fun enqueue(text: String, flush: Boolean, holdable: Boolean) {
        if (closed || text.isBlank()) return
        if (paused && holdable) {
            if (held.size < MAX_WAITING) held.add(text)
            return
        }
        sink?.say(text, flush)
    }

    /** NVDA's cancelSpeech: stops talking, drops held speech and ends a pause. */
    fun cancel() {
        held.clear()
        paused = false
        sink?.stop()
    }

    /** NVDA's pause: stop talking now and hold new speech until resumed. */
    fun setPaused(on: Boolean) {
        if (on == paused) return
        paused = on
        if (on) {
            sink?.stop()
        } else {
            val pending = ArrayList(held)
            held.clear()
            for (text in pending) speak(text)
        }
    }

    /** Rate of the text-to-speech engines; a screen reader keeps its own. */
    fun setRate(newRate: Float) {
        rate = newRate
        (sink as? TtsSink)?.setRate(newRate)
    }

    fun shutdown() {
        closed = true
        held.clear()
        access.removeAccessibilityStateChangeListener(stateListener)
        access.removeTouchExplorationStateChangeListener(touchListener)
        sink?.shutdown()
        sink = null
        onRouteChanged = null
    }

    /** Android text-to-speech through [engine], or through the default engine when it is null. */
    private inner class TtsSink(val engine: String?) : Sink {
        private var tts: TextToSpeech? = null
        private var ready = false
        private var failed = false
        private var stopped = false
        private var counter = 0
        private val waiting = ArrayList<Pair<String, Boolean>>() // spoken once the engine is ready

        init {
            val onInit = TextToSpeech.OnInitListener { status ->
                val t = tts
                if (status == TextToSpeech.SUCCESS && t != null && !stopped) {
                    t.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    t.setSpeechRate(rate)
                    ready = true
                    val pending = ArrayList(waiting)
                    waiting.clear()
                    for ((text, flush) in pending) say(text, flush)
                } else {
                    failed = true
                    waiting.clear()
                }
            }
            // With an engine named, Android falls back to the default engine if it is not installed.
            tts = if (engine == null) TextToSpeech(app, onInit) else TextToSpeech(app, onInit, engine)
        }

        override fun say(text: String, flush: Boolean) {
            if (stopped || failed) return
            if (!ready) {
                if (flush) waiting.clear()
                // If the engine never starts, do not keep every utterance of a long session in memory.
                if (waiting.size < MAX_WAITING) waiting.add(text to flush)
                return
            }
            val t = tts ?: return
            var mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            // Android refuses (silently) anything longer than the engine's input limit.
            for (part in splitForSpeech(text, maxInputLength)) {
                t.speak(part, mode, null, "u${counter++}")
                mode = TextToSpeech.QUEUE_ADD
            }
        }

        override fun stop() {
            waiting.clear()
            tts?.stop()
        }

        fun setRate(newRate: Float) {
            tts?.setSpeechRate(newRate)
        }

        override fun shutdown() {
            stopped = true
            waiting.clear()
            tts?.stop()
            tts?.shutdown()
            tts = null
            ready = false
        }
    }

    /**
     * Hands speech to the screen reader as accessibility announcements, so it is spoken with the
     * screen reader's own engine, voice and settings (and shown on its braille display, if any).
     */
    private inner class ScreenReaderSink : Sink {
        private val recent = ArrayDeque<Pair<String, Long>>() // announced text, uptime when sent

        /** Until when the screen reader is probably still speaking what it was given; 0 after a stop. */
        private var busyUntil = 0L

        override fun say(text: String, flush: Boolean) {
            if (flush) stop()
            if (!access.isEnabled) return
            for (part in splitForSpeech(text, maxInputLength)) {
                val now = SystemClock.uptimeMillis()
                val event = newAnnouncement()
                event.packageName = app.packageName
                event.className = Speech::class.java.name
                event.text.add(distinct(part, now))
                try {
                    access.sendAccessibilityEvent(event)
                } catch (_: IllegalStateException) {
                    return // accessibility was turned off in the meantime
                }
                // Deliberately generous: a slow voice takes about 90 ms per character.
                busyUntil = maxOf(busyUntil, now) + part.length * MS_PER_CHAR + MARGIN_MS
            }
        }

        override fun stop() {
            // interrupt() silences the screen reader whatever it is saying, including what it says
            // about the phone itself, so only use it while speech handed over from here may be playing.
            if (busyUntil == 0L) return
            val playing = SystemClock.uptimeMillis() < busyUntil
            busyUntil = 0L
            if (!playing || !access.isEnabled) return
            try {
                access.interrupt()
            } catch (_: RuntimeException) {
            }
        }

        override fun shutdown() = stop()

        /**
         * TalkBack skips an announcement equal to one it is speaking, has queued or spoke in the last
         * second, yet NVDA often says the same thing twice in a row ("blank", "blank"). Invisible
         * zero-width spaces make a repeat different without changing how it sounds.
         */
        private fun distinct(text: String, now: Long): String {
            while (recent.isNotEmpty()) {
                val expired = now - recent.first().second > DUPLICATE_WINDOW_MS && now >= busyUntil
                if (recent.size >= MAX_RECENT || expired) recent.removeFirst() else break
            }
            var result = text
            while (recent.any { it.first == result }) result += ZERO_WIDTH_SPACE
            recent.addLast(result to now)
            return result
        }
    }

    companion object {
        /** Speak through the running screen reader (the default). */
        const val SCREEN_READER = "screen_reader"

        /** Speak through the system's default text-to-speech engine. */
        const val DEFAULT_ENGINE = "default_engine"

        private const val MAX_WAITING = 200
        private const val MAX_RECENT = 32
        private const val DUPLICATE_WINDOW_MS = 1500L
        private const val MS_PER_CHAR = 90L
        private const val MARGIN_MS = 1000L
        private const val ZERO_WIDTH_SPACE = '​'

        private val maxInputLength: Int
            get() = try {
                TextToSpeech.getMaxSpeechInputLength()
            } catch (_: Throwable) {
                4000
            }

        @Suppress("DEPRECATION")
        private fun newAnnouncement(): AccessibilityEvent =
            if (Build.VERSION.SDK_INT >= 30) AccessibilityEvent(AccessibilityEvent.TYPE_ANNOUNCEMENT)
            else AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT)

        /**
         * Enabled accessibility services that give spoken feedback and can explore by touch: screen
         * readers such as TalkBack, but not tools like Select to Speak, which ignore announcements.
         */
        private fun screenReaders(access: AccessibilityManager): List<AccessibilityServiceInfo> {
            if (!access.isEnabled) return emptyList()
            return try {
                access.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN).filter {
                    (it.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_TOUCH_EXPLORATION) != 0
                }
            } catch (_: RuntimeException) {
                emptyList()
            }
        }

        fun screenReaderOn(access: AccessibilityManager): Boolean =
            access.isEnabled && (access.isTouchExplorationEnabled || screenReaders(access).isNotEmpty())

        /** The running screen reader's name, such as "TalkBack", or null if unknown or none is on. */
        private fun screenReaderName(context: Context, access: AccessibilityManager): String? {
            val pm = context.packageManager
            return screenReaders(access).firstNotNullOfOrNull { info ->
                try {
                    info.resolveInfo?.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() }
                } catch (_: RuntimeException) {
                    null
                }
            }
        }

        /** Installed text-to-speech engines, by package name and name. */
        @Suppress("DEPRECATION")
        private fun engines(context: Context): List<SpeechChoice> {
            val pm = context.packageManager
            return try {
                pm.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
                    .mapNotNull { info ->
                        val pkg = info.serviceInfo?.packageName ?: return@mapNotNull null
                        SpeechChoice(pkg, info.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() } ?: pkg)
                    }
                    .distinctBy { it.id }
                    .sortedBy { it.label.lowercase() }
            } catch (_: RuntimeException) {
                emptyList()
            }
        }

        private fun defaultEngine(context: Context): String? = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.TTS_DEFAULT_SYNTH)
        } catch (_: RuntimeException) {
            null
        }

        /** Every voice the user can pick: the screen reader, the default engine, then each engine. */
        fun choices(context: Context): List<SpeechChoice> {
            val access = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            val engines = engines(context)
            val reader = screenReaderName(context, access)
            val readerLabel = when {
                reader != null -> context.getString(R.string.speech_output_screen_reader, reader)
                screenReaderOn(access) -> context.getString(R.string.speech_output_screen_reader_unnamed)
                else -> context.getString(R.string.speech_output_screen_reader_off)
            }
            val defaultId = defaultEngine(context)
            val defaultName = engines.firstOrNull { it.id == defaultId }?.label
            val defaultLabel = if (defaultName != null) {
                context.getString(R.string.speech_output_default_engine, defaultName)
            } else {
                context.getString(R.string.speech_output_default_engine_unnamed)
            }
            return listOf(SpeechChoice(SCREEN_READER, readerLabel), SpeechChoice(DEFAULT_ENGINE, defaultLabel)) +
                engines
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
