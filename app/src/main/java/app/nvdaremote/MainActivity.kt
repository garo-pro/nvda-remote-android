package app.nvdaremote

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView

class MainActivity : Activity(), RemoteListener {

    private lateinit var prefs: SharedPreferences
    private lateinit var speech: Speech
    private lateinit var client: RemoteClient
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var hostField: EditText
    private lateinit var portField: EditText
    private lateinit var keyField: EditText
    private lateinit var connectButton: Button
    private lateinit var controlButton: KeyCaptureButton
    private lateinit var clipboardButton: Button
    private lateinit var sasButton: Button
    private lateinit var speechButton: Button
    private lateinit var rateBar: SeekBar
    private lateinit var statusView: TextView

    private var remoteMode = false
    private var speechOutput = Speech.SCREEN_READER
    private var followers = 0
    private var lastState = State.DISCONNECTED

    /** Keys currently held down and forwarded to the remote computer: Android key code -> packed VK. */
    private val held = LinkedHashMap<Int, Int>()

    private val trustStore = object : TrustStore {
        override fun fingerprint(hostPort: String): String? = prefs.getString("fp_$hostPort", null)
        override fun trust(hostPort: String, fingerprint: String) {
            prefs.edit().putString("fp_$hostPort", fingerprint).apply()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("settings", MODE_PRIVATE)

        hostField = findViewById(R.id.host)
        portField = findViewById(R.id.port)
        keyField = findViewById(R.id.key)
        connectButton = findViewById(R.id.connect)
        controlButton = findViewById(R.id.control)
        controlButton.preImeHandler = { handleKey(it) }
        clipboardButton = findViewById(R.id.clipboard)
        sasButton = findViewById(R.id.sas)
        statusView = findViewById(R.id.status)
        speechButton = findViewById(R.id.speech_output)
        rateBar = findViewById(R.id.rate)

        hostField.setText(prefs.getString("host", ""))
        portField.setText(prefs.getInt("port", RemoteClient.DEFAULT_PORT).toString())
        keyField.setText(prefs.getString("key", ""))

        val rateStep = prefs.getInt("rate", 5)
        rateBar.progress = rateStep
        speechOutput = prefs.getString("speech_output", null) ?: Speech.SCREEN_READER
        speech = Speech(this, rateFor(rateStep), speechOutput)
        speech.onRouteChanged = { updateSpeechUi() }
        speechButton.setOnClickListener { chooseSpeechOutput() }
        rateBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                prefs.edit().putInt("rate", progress).apply()
                speech.setRate(rateFor(progress))
            }

            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })

        client = RemoteClient(this, trustStore)

        connectButton.setOnClickListener {
            if (client.state == State.DISCONNECTED) startConnection() else stopConnection()
        }
        controlButton.setOnClickListener { setRemote(!remoteMode) }
        clipboardButton.setOnClickListener { sendClipboard() }
        findViewById<Button>(R.id.accessibility).setOnClickListener {
            startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        sasButton.setOnClickListener {
            client.sendSas()
            say("Control Alt Delete sent")
        }
        updateUi()
    }

    override fun onResume() {
        super.onResume()
        // A screen reader may have been turned on or off while the app was in the background.
        speech.refresh()
        updateSpeechUi()
    }

    private fun rateFor(step: Int) = 0.5f + step * 0.1f

    // ---- connection ----

    private fun startConnection() {
        // NVDA accepts "host:port" in its address field, so people paste it that way.
        val (host, portInAddress) = RemoteClient.parseAddress(hostField.text.toString())
        val key = keyField.text.toString().trim()
        val port = portInAddress
            ?: portField.text.toString().trim().toIntOrNull()?.takeIf { it in 1..65535 }
            ?: RemoteClient.DEFAULT_PORT
        if (host.isEmpty()) {
            say("Enter the server address first")
            hostField.requestFocus()
            return
        }
        if (key.isEmpty()) {
            say("Enter the connection key first")
            keyField.requestFocus()
            return
        }
        if (portInAddress != null) hostField.setText(host)
        portField.setText(port.toString())
        prefs.edit().putString("host", host).putInt("port", port).putString("key", key).apply()
        client.connect(host, port, key)
    }

    private fun stopConnection() {
        setRemote(false, announce = false)
        client.disconnect()
    }

    // ---- RemoteListener (may be called on background threads) ----

    /** Runs [block] on the main thread unless the activity has been destroyed by then. */
    private fun post(block: () -> Unit) {
        ui.post { if (!isDestroyed) block() }
    }

    override fun onState(state: State) {
        post {
            val previous = lastState
            lastState = state
            if (state != State.CONNECTED) {
                followers = 0
                if (remoteMode) setRemote(false, announce = false)
            }
            when (state) {
                State.CONNECTING -> say("Connecting")
                State.CONNECTED -> say("Connected")
                State.RECONNECTING -> if (previous == State.CONNECTED) say("Connection lost. Reconnecting")
                State.DISCONNECTED -> say("Disconnected")
            }
            if (state == State.DISCONNECTED) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            updateUi()
        }
    }

    override fun onFollowerCount(count: Int) {
        post {
            val old = followers
            followers = count
            if (count == 0 && remoteMode) setRemote(false, announce = false)
            if (lastState == State.CONNECTED && count != old) {
                say(
                    when (count) {
                        0 -> "No controlled computers connected"
                        1 -> "1 controlled computer connected"
                        else -> "$count controlled computers connected"
                    },
                    interrupt = false
                )
            }
            updateUi()
        }
    }

    override fun onSpeak(text: String) {
        post { speech.speak(text) }
    }

    override fun onCancelSpeech() {
        post { speech.cancel() }
    }

    override fun onPauseSpeech(paused: Boolean) {
        post { speech.setPaused(paused) }
    }

    override fun onTone(hz: Double, lengthMs: Int, left: Int, right: Int) {
        post { Tones.beep(hz, lengthMs, left, right) }
    }

    override fun onClipboard(text: String) {
        post {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("NVDA Remote", text))
            say("Clipboard received")
        }
    }

    override fun onMotd(text: String) {
        post { say("Message from server: $text") }
    }

    override fun onError(message: String) {
        post { say(message) }
    }

    override fun onUntrustedCertificate(host: String, port: Int, fingerprint: String, changed: Boolean) {
        post {
            if (isFinishing) return@post
            val spaced = fingerprint.chunked(8).joinToString(" ")
            val intro = if (changed) {
                "The certificate of $host changed since you trusted it. This could mean someone is intercepting the connection."
            } else {
                "The certificate of $host is not signed by a trusted authority."
            }
            say(if (changed) "Server certificate changed" else "Server certificate not trusted")
            AlertDialog.Builder(this)
                .setTitle("Untrusted certificate")
                .setMessage("$intro\n\nSHA-256 fingerprint:\n$spaced\n\nTrust this certificate and connect?")
                .setPositiveButton("Trust and connect") { _, _ ->
                    trustStore.trust(RemoteClient.hostPort(host, port), fingerprint)
                    startConnection()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    // ---- keyboard capture ----

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        if (handleKey(event)) true else super.dispatchKeyEvent(event)

    /** True if consumed. Runs before the IME (via the control button) and again as a fallback. */
    private fun handleKey(event: KeyEvent): Boolean {
        // Toggle shortcut: Control+Shift+F11 (like NVDA+F11 on the Windows client).
        if (event.keyCode == KeyEvent.KEYCODE_F11 && event.isCtrlPressed && event.isShiftPressed) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) setRemote(!remoteMode)
            return true
        }
        if (!remoteMode) return false
        if (KeyMap.lookup(event.keyCode) < 0) return false // Back, Home, volume, media...
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                // A key that is already down keeps the code it was pressed with (auto-repeat).
                val packed = held.getOrPut(event.keyCode) { KeyMap.lookup(event.keyCode, event.isNumLockOn) }
                client.sendKey(KeyMap.vk(packed), KeyMap.extended(packed), true)
            }
            KeyEvent.ACTION_UP -> {
                // Release exactly what was pressed, even if Num Lock changed in between.
                // Keys that went down before remote control started are not released remotely.
                val packed = held.remove(event.keyCode)
                if (packed != null) client.sendKey(KeyMap.vk(packed), KeyMap.extended(packed), false)
            }
        }
        return true
    }

    /** Releases every key held on the remote computer, like NVDA's releaseKeys. */
    private fun releaseAll() {
        if (held.isEmpty()) return
        // A reserved key press tells the remote NVDA that something happened between the modifiers,
        // so releasing them alone cannot trigger an action (e.g. Ctrl+Shift switching keyboard layout).
        client.sendKey(RemoteClient.VK_NONE, false, true)
        client.sendKey(RemoteClient.VK_NONE, false, false)
        for ((_, packed) in held) client.sendKey(KeyMap.vk(packed), KeyMap.extended(packed), false)
        held.clear()
    }

    private fun setRemote(on: Boolean, announce: Boolean = true) {
        if (on == remoteMode) return
        if (on) {
            if (client.state != State.CONNECTED) {
                say("Not connected")
                return
            }
            if (client.followerCount < 1) {
                say("No controlled computers are connected")
                return
            }
            held.clear()
            remoteMode = true
            // Keep text fields and the on-screen keyboard from taking key events.
            window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
            // The control button sees keys before the IME, but only while it has focus. It is focusable
            // in touch mode only now: otherwise the first tap on it would just focus it, not press it.
            controlButton.isFocusableInTouchMode = true
            controlButton.requestFocus()
            KeyFilterService.sink = { handleKey(it) }
            // One message: a second interrupting one would cut the first off.
            val warning = if (KeyFilterService.running) "" else
                "Windows and Alt Tab are not captured. Enable NVDA Remote key capture in accessibility settings."
            val message = listOf(if (announce) "Controlling remote computer." else "", warning)
                .filter { it.isNotEmpty() }.joinToString(" ")
            if (message.isNotEmpty()) say(message)
        } else {
            releaseAll()
            remoteMode = false
            KeyFilterService.sink = null
            window.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            controlButton.isFocusableInTouchMode = false
            if (announce) say("Controlling local device")
        }
        updateUi()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) releaseAll() // we will not see the key-up events
    }

    override fun onPause() {
        super.onPause()
        if (remoteMode) setRemote(false, announce = false)
    }

    // ---- speech output ----

    private fun chooseSpeechOutput() {
        if (isFinishing) return
        val choices = Speech.choices(this)
        val labels = choices.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.speech_output_title)
            .setSingleChoiceItems(labels, choices.indexOfFirst { it.id == speechOutput }) { dialog, which ->
                dialog.dismiss()
                val choice = choices[which]
                speechOutput = choice.id
                prefs.edit().putString("speech_output", choice.id).apply()
                speech.setOutput(choice.id)
                updateSpeechUi()
                // A screen reader reads the button's new label anyway; an engine speaks a sample.
                if (!speech.usesScreenReader) say(getString(R.string.speech_output_button, choice.label))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateSpeechUi() {
        val label = Speech.choices(this).firstOrNull { it.id == speechOutput }?.label ?: speechOutput
        speechButton.text = getString(R.string.speech_output_button, label)
        // A screen reader speaks at its own rate.
        rateBar.isEnabled = !speech.usesScreenReader
    }

    // ---- misc ----

    private fun sendClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrEmpty()) {
            say("The clipboard is empty")
        } else {
            client.sendClipboard(text)
            say("Clipboard sent")
        }
    }

    private fun say(text: String, interrupt: Boolean = true) {
        statusView.text = text
        speech.announce(text, interrupt)
    }

    private fun updateUi() {
        val connected = lastState == State.CONNECTED
        connectButton.setText(if (lastState == State.DISCONNECTED) R.string.connect else R.string.disconnect)
        controlButton.setText(if (remoteMode) R.string.control_stop else R.string.control_start)
        controlButton.isEnabled = connected
        clipboardButton.isEnabled = connected && followers > 0
        sasButton.isEnabled = connected && followers > 0
        statusView.text = when (lastState) {
            State.DISCONNECTED -> "Disconnected"
            State.CONNECTING -> "Connecting"
            State.RECONNECTING -> "Reconnecting"
            State.CONNECTED -> (if (remoteMode) "Controlling remote computer. " else "Connected. ") +
                "$followers controlled computer" + (if (followers == 1) "" else "s")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Also when the activity is only being recreated: the new instance makes its own client,
        // so an old connection left running would keep speaking through this dead activity.
        // (onPause has already left remote mode and released the remote keys.)
        KeyFilterService.sink = null
        client.close()
        speech.shutdown()
        ui.removeCallbacksAndMessages(null)
    }
}
