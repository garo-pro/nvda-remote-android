package app.nvdaremote

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/*
 * NVDA Remote client, controller ("master"/"leader") side.
 *
 * Wire protocol, as implemented by NVDA 2026.3.1 (source/_remoteClient):
 *  - TLS over TCP, default port 6837.
 *  - One JSON object per line, UTF-8, separated by "\n". Every object has a "type".
 *  - After connecting the client sends {"type":"protocol_version","version":2}
 *    followed by {"type":"join","channel":KEY,"connection_type":"master"}.
 *    ("master" = controlling computer, "slave" = controlled computer.)
 *  - Controller -> controlled: key, send_SAS, set_clipboard_text.
 *  - Controlled -> controller: speak, cancel, pause_speech, tone, wave, display,
 *    set_clipboard_text, set_braille_info.
 *  - Server -> client: channel_joined, client_joined, client_left, motd,
 *    version_mismatch, ping, error.
 *
 * This file has no Android dependencies apart from org.json, so it can be tested on a JVM.
 */

enum class State { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING }

interface TrustStore {
    fun fingerprint(hostPort: String): String?
    fun trust(hostPort: String, fingerprint: String)
}

interface RemoteListener {
    // All callbacks may arrive on a background thread.
    fun onState(state: State)
    fun onSpeak(text: String)
    fun onCancelSpeech()
    fun onPauseSpeech(paused: Boolean)
    fun onTone(hz: Double, lengthMs: Int, left: Int, right: Int)
    fun onClipboard(text: String)
    fun onMotd(text: String)
    fun onFollowerCount(count: Int)
    fun onUntrustedCertificate(host: String, port: Int, fingerprint: String, changed: Boolean)
    fun onError(message: String)
}

class CertificateNotTrusted(val fingerprint: String, val changed: Boolean) : Exception("Certificate not trusted")

class RemoteClient(private val listener: RemoteListener, private val trust: TrustStore) {

    companion object {
        const val PROTOCOL_VERSION = 2
        const val DEFAULT_PORT = 6837
        const val VK_NONE = 0xFF
        private const val RECONNECT_DELAY_MS = 5000L

        fun hostPort(host: String, port: Int) = if (host.contains(':')) "[$host]:$port" else "$host:$port"

        /**
         * Splits what the user typed as server address into host and port, accepting the forms NVDA's
         * own dialog accepts: "host", "host:port", "[ipv6]" and "[ipv6]:port". A bare IPv6 address
         * (several colons, no brackets) is returned unchanged. The port is null when none was given
         * or it is not a valid port number.
         */
        fun parseAddress(input: String): Pair<String, Int?> {
            val text = input.trim()
            fun port(s: String) = s.toIntOrNull()?.takeIf { it in 1..65535 }
            if (text.startsWith("[")) {
                val close = text.indexOf(']')
                if (close > 0) {
                    val host = text.substring(1, close)
                    val rest = text.substring(close + 1)
                    return host to (if (rest.startsWith(":")) port(rest.substring(1)) else null)
                }
                return text to null
            }
            val colon = text.indexOf(':')
            if (colon > 0 && colon == text.lastIndexOf(':')) {
                val p = port(text.substring(colon + 1))
                if (p != null) return text.substring(0, colon) to p
            }
            return text to null
        }

        /** Turns the "sequence" of a speak message into utterances. Speech commands are ignored. */
        fun speechUtterances(sequence: JSONArray?): List<String> {
            val result = ArrayList<String>()
            val current = StringBuilder()
            fun flush() {
                val text = current.toString().trim()
                if (text.isNotEmpty()) result.add(text)
                current.setLength(0)
            }
            if (sequence != null) {
                for (i in 0 until sequence.length()) {
                    when (val item = sequence.get(i)) {
                        is String -> {
                            if (current.isNotEmpty()) current.append(' ')
                            current.append(item)
                        }
                        is JSONArray -> if (item.optString(0) == "EndUtteranceCommand") flush()
                        else -> {}
                    }
                }
            }
            flush()
            return result
        }
    }

    @Volatile var state = State.DISCONNECTED
        private set
    @Volatile var followerCount = 0
        private set

    /** The output side of one established connection, tagged with the generation that owns it. */
    private class Link(val gen: Int, val stream: OutputStream)

    // Everything below that a connection attempt and the UI thread both change is guarded by [lock].
    // A generation number identifies one connect() call; anything done for an older generation
    // must not touch the state of a newer one.
    private val lock = Any()
    @Volatile private var generation = 0
    private var socket: Socket? = null
    @Volatile private var link: Link? = null
    private var worker: Thread? = null
    private val writeLock = Any()
    private val sender = Executors.newSingleThreadExecutor { r ->
        Thread(r, "nvdaremote-send").apply { isDaemon = true }
    }

    fun connect(host: String, port: Int, key: String) {
        synchronized(lock) {
            stopLocked()
            val gen = ++generation
            val t = Thread({ runLoop(gen, host, port, key) }, "nvdaremote-net")
            t.isDaemon = true
            worker = t
            setStateLocked(gen, State.CONNECTING)
            t.start()
        }
    }

    fun disconnect() {
        synchronized(lock) { stopLocked() }
    }

    /** Disconnects and releases the sending thread. The client cannot be used afterwards. */
    fun close() {
        disconnect()
        sender.shutdown()
    }

    private fun stopLocked() {
        generation++
        worker?.interrupt()
        worker = null
        val old = socket
        socket = null
        link = null
        followerCount = 0
        // Closing a TLS socket writes to the network, which is forbidden on the main thread.
        if (old != null) Thread({ closeQuietly(old) }, "nvdaremote-close").apply { isDaemon = true }.start()
        setStateLocked(generation, State.DISCONNECTED)
    }

    // ---- sending ----

    fun sendKey(vkCode: Int, extended: Boolean, pressed: Boolean) {
        send(
            JSONObject().put("type", "key").put("vk_code", vkCode)
                .put("extended", extended).put("pressed", pressed)
        )
    }

    fun sendSas() = send(JSONObject().put("type", "send_SAS"))

    fun sendClipboard(text: String) = send(JSONObject().put("type", "set_clipboard_text").put("text", text))

    private fun send(obj: JSONObject) {
        val line = (obj.toString() + "\n").toByteArray(Charsets.UTF_8)
        val gen = generation
        try {
            sender.execute {
                // Only write to the connection that existed when the message was sent.
                val l = link ?: return@execute
                if (l.gen != gen) return@execute
                try {
                    writeRaw(l.stream, line)
                } catch (e: IOException) {
                    // Wake the reader so it reconnects; never touch a newer connection.
                    synchronized(lock) { if (current(gen)) socket else null }?.let { closeQuietly(it) }
                }
            }
        } catch (_: RejectedExecutionException) {
            // close() was called.
        }
    }

    private fun writeRaw(stream: OutputStream, line: ByteArray) {
        synchronized(writeLock) {
            stream.write(line)
            stream.flush()
        }
    }

    // ---- connection management ----

    private fun current(gen: Int) = gen == generation

    private fun setState(gen: Int, s: State) {
        synchronized(lock) { setStateLocked(gen, s) }
    }

    private fun setStateLocked(gen: Int, s: State) {
        if (!current(gen) || state == s) return
        state = s
        listener.onState(s)
    }

    /** Makes [sock] the socket that disconnect() closes, unless this attempt is already obsolete. */
    private fun attach(gen: Int, sock: Socket) {
        val attached = synchronized(lock) {
            if (current(gen)) {
                socket = sock
                true
            } else {
                false
            }
        }
        if (!attached) {
            closeQuietly(sock)
            throw Cancelled()
        }
    }

    private class Cancelled : IOException("Cancelled")

    private fun runLoop(gen: Int, host: String, port: Int, key: String) {
        var lastError: String? = null
        while (current(gen)) {
            try {
                session(gen, host, port, key)
                lastError = null
            } catch (e: CertificateNotTrusted) {
                if (stopFromWorker(gen)) listener.onUntrustedCertificate(host, port, e.fingerprint, e.changed)
                return
            } catch (e: FatalProtocolError) {
                if (stopFromWorker(gen)) listener.onError(e.message ?: "Protocol error")
                return
            } catch (e: Exception) {
                val message = describe(e)
                if (current(gen) && message != lastError) listener.onError(message)
                lastError = message
            } finally {
                synchronized(lock) {
                    if (current(gen)) {
                        socket = null
                        link = null
                        followerCount = 0
                    }
                }
            }
            if (!current(gen)) return
            setState(gen, State.RECONNECTING)
            try {
                Thread.sleep(RECONNECT_DELAY_MS)
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    /** Ends this generation from its own worker. False if the user already moved on. */
    private fun stopFromWorker(gen: Int): Boolean = synchronized(lock) {
        if (!current(gen)) return false
        worker = null // do not interrupt ourselves
        stopLocked()
        true
    }

    private class FatalProtocolError(message: String) : Exception(message)

    private fun describe(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> "Unknown host"
        is java.net.SocketTimeoutException -> "Connection timed out"
        is java.net.ConnectException -> "Cannot connect to server"
        is SSLException -> "Secure connection failed: ${e.message}"
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun session(gen: Int, host: String, port: Int, key: String) {
        val pinned = trust.fingerprint(hostPort(host, port))
        val sock = openTls(gen, host, port, pinned)
        sock.use {
            val stream = it.outputStream
            writeRaw(
                stream,
                (JSONObject().put("type", "protocol_version").put("version", PROTOCOL_VERSION)
                    .toString() + "\n").toByteArray(Charsets.UTF_8)
            )
            writeRaw(
                stream,
                (JSONObject().put("type", "join").put("channel", key).put("connection_type", "master")
                    .toString() + "\n").toByteArray(Charsets.UTF_8)
            )
            synchronized(lock) {
                if (!current(gen)) return
                link = Link(gen, stream)
            }
            val reader = BufferedReader(InputStreamReader(it.inputStream, Charsets.UTF_8))
            val followers = HashSet<Int>()
            while (current(gen)) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val obj = try {
                    JSONObject(line)
                } catch (e: JSONException) {
                    continue
                }
                handle(gen, obj, followers)
            }
        }
    }

    private fun handle(gen: Int, obj: JSONObject, followers: MutableSet<Int>) {
        if (!current(gen)) return
        fun publishFollowers() {
            synchronized(lock) {
                if (!current(gen)) return
                followerCount = followers.size
                listener.onFollowerCount(followers.size)
            }
        }
        fun addClient(client: JSONObject?) {
            if (client != null && client.optString("connection_type") == "slave") followers.add(client.optInt("id"))
        }
        when (obj.optString("type")) {
            "channel_joined" -> {
                followers.clear()
                val clients = obj.optJSONArray("clients")
                if (clients != null) for (i in 0 until clients.length()) addClient(clients.optJSONObject(i))
                setState(gen, State.CONNECTED)
                publishFollowers()
            }
            "client_joined" -> {
                addClient(obj.optJSONObject("client"))
                publishFollowers()
            }
            "client_left" -> {
                obj.optJSONObject("client")?.let { followers.remove(it.optInt("id")) }
                publishFollowers()
            }
            "speak" -> for (u in speechUtterances(obj.optJSONArray("sequence"))) listener.onSpeak(u)
            "cancel" -> listener.onCancelSpeech()
            "pause_speech" -> listener.onPauseSpeech(obj.optBoolean("switch"))
            "tone" -> listener.onTone(
                obj.optDouble("hz", 440.0), obj.optInt("length", 100),
                obj.optInt("left", 50), obj.optInt("right", 50)
            )
            "set_clipboard_text" -> obj.optString("text").let { if (it.isNotEmpty()) listener.onClipboard(it) }
            "motd" -> obj.optString("motd").let { if (it.isNotEmpty()) listener.onMotd(it) }
            "version_mismatch" -> throw FatalProtocolError("The server and this app use different protocol versions")
            "error" -> listener.onError(obj.optString("error", obj.optString("message", "Server error")))
            // ping, wave, display, set_braille_info, nvda_not_connected and unknown types: nothing to do
        }
    }

    // ---- TLS ----

    private fun openTls(gen: Int, host: String, port: Int, pinned: String?): SSLSocket {
        val ctx = SSLContext.getInstance("TLS")
        if (pinned != null) ctx.init(null, arrayOf(PinnedTrustManager(pinned)), null) else ctx.init(null, null, null)
        try {
            return handshake(gen, ctx, host, port, verifyHost = pinned == null)
        } catch (e: SSLException) {
            if (!isCertificateProblem(e)) throw e
            throw CertificateNotTrusted(fetchFingerprint(gen, host, port), changed = pinned != null)
        }
    }

    private fun handshake(gen: Int, ctx: SSLContext, host: String, port: Int, verifyHost: Boolean): SSLSocket {
        val raw = Socket()
        // Registered before connecting so that disconnect() can abort a slow connect or handshake.
        attach(gen, raw)
        try {
            raw.connect(InetSocketAddress(host, port), 10_000)
            raw.keepAlive = true
            raw.tcpNoDelay = true
            val ssl = ctx.socketFactory.createSocket(raw, host, port, true) as SSLSocket
            if (verifyHost) {
                val params = ssl.sslParameters
                params.endpointIdentificationAlgorithm = "HTTPS"
                ssl.sslParameters = params
            }
            ssl.soTimeout = 15_000
            ssl.startHandshake()
            ssl.soTimeout = 0
            attach(gen, ssl)
            return ssl
        } catch (e: Throwable) {
            closeQuietly(raw)
            throw e
        }
    }

    private fun isCertificateProblem(e: Throwable): Boolean {
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth++ < 10) {
            if (t is CertificateException) return true
            t = t.cause
        }
        return false
    }

    /** Reads the server certificate fingerprint without trusting it; nothing is sent over this connection. */
    private fun fetchFingerprint(gen: Int, host: String, port: Int): String {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }), null)
        val ssl = handshake(gen, ctx, host, port, verifyHost = false)
        try {
            return fingerprintOf(ssl.session.peerCertificates[0].encoded)
        } finally {
            closeQuietly(ssl)
        }
    }

    private class PinnedTrustManager(private val fingerprint: String) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
            throw CertificateException("Client certificates are not supported")
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            if (chain.isEmpty() || fingerprintOf(chain[0].encoded) != fingerprint.lowercase()) {
                throw CertificateException("Server certificate does not match the trusted fingerprint")
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}

private fun fingerprintOf(der: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02x".format(it) }

private fun closeQuietly(s: Socket?) {
    try {
        s?.close()
    } catch (_: Exception) {
    }
}
