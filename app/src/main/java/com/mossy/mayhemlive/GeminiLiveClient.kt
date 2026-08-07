package com.mossy.mayhemlive

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class GeminiLiveClient(
    private val apiKey: String,
    private val listener: Listener
) {
    interface Listener {
        fun onReady()
        fun onStatus(message: String)
        fun onTranscript(text: String)
        fun onFailure(message: String)
    }

    private val httpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioPlayer = PcmAudioPlayer()
    private var webSocket: WebSocket? = null
    private var ready = false
    private var generating = false
    private var closing = false
    private var failureReported = false

    val isReady: Boolean
        get() = ready && webSocket != null

    fun connect() {
        closing = false
        failureReported = false
        ready = false
        generating = false
        openSocket()
    }

    fun setMuted(muted: Boolean) {
        audioPlayer.setMuted(muted)
    }

    fun sendVideoFrame(jpegBytes: ByteArray): Boolean {
        if (!isReady) return false
        val encoded = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        val message = JSONObject()
            .put(
                "realtimeInput",
                JSONObject().put(
                    "video",
                    JSONObject()
                        .put("data", encoded)
                        .put("mimeType", "image/jpeg")
                )
            )
        return webSocket?.send(message.toString()) == true
    }

    fun requestNarration(firstTurn: Boolean = false): Boolean {
        if (!isReady || generating) return false
        generating = true

        val instruction = if (firstTurn) {
            "BEGIN_DOCUMENTARY. Begin the ongoing comedy documentary from the live camera frames you have received. Treat this as one continuous place and journey. One or two short sentences only."
        } else {
            "CONTINUE_DOCUMENTARY. Continue the SAME documentary from the latest live camera frames. Mention what changed, where we are moving, or what is happening now. Never list objects or restart the story. One or two short sentences only."
        }

        val message = JSONObject()
            .put("realtimeInput", JSONObject().put("text", instruction))

        val sent = webSocket?.send(message.toString()) == true
        if (!sent) generating = false
        return sent
    }

    fun close() {
        closing = true
        ready = false
        generating = false
        mainHandler.removeCallbacksAndMessages(null)
        webSocket?.close(1000, "App closed")
        webSocket = null
        audioPlayer.release()
        httpClient.dispatcher.executorService.shutdown()
    }

    private fun openSocket() {
        val encodedKey = URLEncoder.encode(apiKey.trim(), Charsets.UTF_8.name())
        val url = "wss://generativelanguage.googleapis.com/ws/" +
            "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent" +
            "?key=$encodedKey"

        val request = Request.Builder().url(url).build()
        listener.onStatus("Connecting to Gemini Live…")

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                listener.onStatus("Gemini socket open — sending setup…")
                val sent = webSocket.send(buildSetupMessage().toString())
                if (!sent) {
                    reportFailure("Gemini socket opened but the setup message could not be sent.")
                    return
                }

                mainHandler.postDelayed({
                    if (!ready && !closing) {
                        reportFailure(
                            "Gemini did not confirm setup within 12 seconds. " +
                                "The API key, project access, or Live model access may be blocking the connection."
                        )
                    }
                }, SETUP_TIMEOUT_MS)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                ready = false
                generating = false
                if (!closing) {
                    val http = response?.let { " HTTP ${it.code} ${it.message}." }.orEmpty()
                    reportFailure("${t.message ?: "Gemini Live connection failed."}$http")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                ready = false
                generating = false
                if (!closing) {
                    reportFailure("Gemini closed the connection. Code $code. ${reason.ifBlank { "No reason supplied." }}")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                ready = false
                generating = false
                if (!closing && !failureReported) {
                    reportFailure("Gemini connection closed. Code $code. ${reason.ifBlank { "No reason supplied." }}")
                }
            }
        })
    }

    private fun buildSetupMessage(): JSONObject {
        // Deliberately mirrors Google's minimal Gemini 3.1 Flash Live WebSocket setup.
        // Fancy voice/transcription/session-resumption options come back only after this works.
        val setup = JSONObject()
            .put("model", "models/gemini-3.1-flash-live-preview")
            .put("responseModalities", JSONArray().put("AUDIO"))
            .put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT))
                )
            )

        return JSONObject().put("setup", setup)
    }

    private fun handleMessage(raw: String) {
        try {
            val message = JSONObject(raw)

            message.optJSONObject("error")?.let { error ->
                val code = error.optInt("code", 0)
                val status = error.optString("status")
                val detail = error.optString("message")
                val prefix = if (code > 0) "Google error $code" else "Google error"
                reportFailure(
                    listOf(prefix, status, detail)
                        .filter { it.isNotBlank() }
                        .joinToString(" — ")
                )
                return
            }

            if (message.has("setupComplete")) {
                ready = true
                generating = false
                failureReported = false
                mainHandler.removeCallbacksAndMessages(null)
                listener.onReady()
                return
            }

            val serverContent = message.optJSONObject("serverContent") ?: return

            serverContent.optJSONObject("outputTranscription")
                ?.optString("text")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let(listener::onTranscript)

            val modelTurn = serverContent.optJSONObject("modelTurn")
            val parts = modelTurn?.optJSONArray("parts")
            if (parts != null) {
                for (index in 0 until parts.length()) {
                    val part = parts.optJSONObject(index) ?: continue
                    val inlineData = part.optJSONObject("inlineData") ?: continue
                    val mimeType = inlineData.optString("mimeType")
                    val data = inlineData.optString("data")
                    if (data.isNotBlank() && mimeType.startsWith("audio/")) {
                        audioPlayer.play(Base64.decode(data, Base64.DEFAULT))
                    }
                }
            }

            if (serverContent.optBoolean("interrupted", false)) {
                audioPlayer.flush()
                generating = false
            }

            if (serverContent.optBoolean("turnComplete", false) ||
                serverContent.optBoolean("generationComplete", false)
            ) {
                generating = false
            }
        } catch (error: Exception) {
            listener.onStatus("Gemini sent a message Mossy couldn't read: ${error.message ?: "unknown format"}")
        }
    }

    private fun reportFailure(message: String) {
        if (failureReported || closing) return
        failureReported = true
        ready = false
        generating = false
        mainHandler.removeCallbacksAndMessages(null)
        listener.onFailure(message)
    }

    companion object {
        private const val SETUP_TIMEOUT_MS = 12_000L

        private val SYSTEM_PROMPT = """
            You are MOSSY, the voice of Mossy Mayhem Live.

            Narrate the user's moving phone camera as ONE CONTINUOUS COMEDY DOCUMENTARY. Treat new camera frames as later moments in the same journey. Remember what was visible earlier in the live session and connect new events naturally.

            Speak in relaxed Australian English with a mature, dry, cheeky documentary delivery. Be observational and story-driven. Keep each narration burst to one or two short sentences so the scene can breathe.

            Never behave like an object detector. Never say "detected" or list labels. Describe movement, setting, actions, entrances, exits and changes. Refer back to earlier things when useful. If uncertain, stay general instead of inventing details. Never infer identity, private information, crime, diagnosis or other sensitive facts from appearance.

            Only narrate when the app sends BEGIN_DOCUMENTARY or CONTINUE_DOCUMENTARY. Between those prompts, silently observe the incoming video frames and maintain continuity.
        """.trimIndent()
    }
}

private class PcmAudioPlayer {
    private var track: AudioTrack? = null
    @Volatile private var muted = false

    fun setMuted(value: Boolean) {
        muted = value
        if (muted) flush()
    }

    @Synchronized
    fun play(bytes: ByteArray) {
        if (muted || bytes.isEmpty()) return
        val audioTrack = track ?: createTrack().also {
            track = it
            it.play()
        }
        audioTrack.write(bytes, 0, bytes.size, AudioTrack.WRITE_BLOCKING)
    }

    @Synchronized
    fun flush() {
        track?.let {
            try {
                it.pause()
                it.flush()
                if (!muted) it.play()
            } catch (_: Exception) {
            }
        }
    }

    @Synchronized
    fun release() {
        track?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }
        track = null
    }

    private fun createTrack(): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(
            24_000,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(24_000)

        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(24_000)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBuffer * 2)
            .build()
    }
}
