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
import java.util.concurrent.TimeUnit

class OpenAIRealtimeClient(
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
    private var transcript = StringBuilder()

    val isReady: Boolean
        get() = ready && webSocket != null

    fun connect() {
        closing = false
        failureReported = false
        ready = false
        generating = false
        transcript = StringBuilder()

        val request = Request.Builder()
            .url("wss://api.openai.com/v1/realtime?model=$MODEL")
            .addHeader("Authorization", "Bearer ${apiKey.trim()}")
            .build()

        listener.onStatus("Connecting to OpenAI Realtime…")

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                listener.onStatus("OpenAI socket open — starting Mossy…")
                mainHandler.postDelayed({
                    if (!ready && !closing) {
                        reportFailure(
                            "OpenAI did not finish session setup within 15 seconds. " +
                                "Check the exact error shown here before changing the key."
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
                    reportFailure("${t.message ?: "OpenAI Realtime connection failed."}$http")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                ready = false
                generating = false
                if (!closing) {
                    reportFailure(
                        "OpenAI closed the connection. Code $code. " +
                            reason.ifBlank { "No reason supplied." }
                    )
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                ready = false
                generating = false
                if (!closing && !failureReported) {
                    reportFailure(
                        "OpenAI connection closed. Code $code. " +
                            reason.ifBlank { "No reason supplied." }
                    )
                }
            }
        })
    }

    fun setMuted(muted: Boolean) {
        audioPlayer.setMuted(muted)
    }

    fun sendImageFrame(jpegBytes: ByteArray): Boolean {
        if (!isReady || jpegBytes.isEmpty()) return false

        val encoded = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        val content = JSONArray().put(
            JSONObject()
                .put("type", "input_image")
                .put("image_url", "data:image/jpeg;base64,$encoded")
        )

        val event = JSONObject()
            .put("type", "conversation.item.create")
            .put(
                "item",
                JSONObject()
                    .put("type", "message")
                    .put("role", "user")
                    .put("content", content)
            )

        return webSocket?.send(event.toString()) == true
    }

    fun requestNarration(firstTurn: Boolean = false): Boolean {
        if (!isReady || generating) return false

        val instruction = if (firstTurn) {
            "BEGIN_DOCUMENTARY. Start the continuous Mossy documentary from the recent camera frames. " +
                "Describe this moment as a connected scene, not as a list of objects. Keep it to one or two short sentences."
        } else {
            "CONTINUE_DOCUMENTARY. Continue the SAME documentary from the newest camera frames. " +
                "Say what changed, where we seem to be moving, or what is happening now. Refer back naturally when useful. " +
                "Do not restart or list objects. One or two short sentences."
        }

        val inputEvent = JSONObject()
            .put("type", "conversation.item.create")
            .put(
                "item",
                JSONObject()
                    .put("type", "message")
                    .put("role", "user")
                    .put(
                        "content",
                        JSONArray().put(
                            JSONObject()
                                .put("type", "input_text")
                                .put("text", instruction)
                        )
                    )
            )

        if (webSocket?.send(inputEvent.toString()) != true) return false

        transcript = StringBuilder()
        generating = true

        val responseEvent = JSONObject()
            .put("type", "response.create")
            .put(
                "response",
                JSONObject()
                    .put("output_modalities", JSONArray().put("audio"))
            )

        val sent = webSocket?.send(responseEvent.toString()) == true
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

    private fun sendSessionUpdate(): Boolean {
        val session = JSONObject()
            .put("type", "realtime")
            .put("model", MODEL)
            .put("output_modalities", JSONArray().put("audio"))
            .put(
                "audio",
                JSONObject().put(
                    "output",
                    JSONObject()
                        .put("format", JSONObject().put("type", "audio/pcm"))
                        .put("voice", VOICE)
                )
            )
            .put("instructions", SYSTEM_PROMPT)

        val event = JSONObject()
            .put("type", "session.update")
            .put("session", session)

        return webSocket?.send(event.toString()) == true
    }

    private fun handleMessage(raw: String) {
        try {
            val event = JSONObject(raw)
            when (val type = event.optString("type")) {
                "session.created" -> {
                    listener.onStatus("OpenAI session created — loading Mossy character…")
                    if (!sendSessionUpdate()) {
                        reportFailure("OpenAI connected, but Mossy's session settings could not be sent.")
                    }
                }

                "session.updated" -> {
                    ready = true
                    generating = false
                    failureReported = false
                    mainHandler.removeCallbacksAndMessages(null)
                    listener.onReady()
                }

                "response.created" -> {
                    transcript = StringBuilder()
                }

                "response.output_audio.delta" -> {
                    val delta = event.optString("delta")
                    if (delta.isNotBlank()) {
                        audioPlayer.play(Base64.decode(delta, Base64.DEFAULT))
                    }
                }

                "response.output_audio_transcript.delta" -> {
                    val delta = event.optString("delta")
                    if (delta.isNotBlank()) {
                        transcript.append(delta)
                        listener.onTranscript(transcript.toString().trim())
                    }
                }

                "response.output_audio_transcript.done" -> {
                    val finalText = event.optString("transcript").ifBlank { transcript.toString() }
                    if (finalText.isNotBlank()) listener.onTranscript(finalText.trim())
                }

                "response.done" -> {
                    generating = false
                    val response = event.optJSONObject("response")
                    val status = response?.optString("status").orEmpty()
                    if (status == "failed") {
                        val details = response?.optJSONObject("status_details")
                        reportFailure(
                            details?.optString("error")
                                ?.takeIf { it.isNotBlank() }
                                ?: "OpenAI returned a failed response."
                        )
                    }
                }

                "error" -> {
                    val error = event.optJSONObject("error")
                    val code = error?.optString("code").orEmpty()
                    val errorType = error?.optString("type").orEmpty()
                    val message = error?.optString("message").orEmpty()
                    val detail = listOf(code, errorType, message)
                        .filter { it.isNotBlank() }
                        .joinToString(" — ")
                    reportFailure(if (detail.isBlank()) "OpenAI returned an unknown error." else detail)
                }

                else -> {
                    if (type.isNotBlank() && type.endsWith(".error")) {
                        reportFailure("OpenAI error event: $type")
                    }
                }
            }
        } catch (error: Exception) {
            listener.onStatus("OpenAI sent a message Mossy couldn't read: ${error.message ?: "unknown format"}")
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
        private const val MODEL = "gpt-realtime-2.1"
        private const val VOICE = "cedar"
        private const val SETUP_TIMEOUT_MS = 15_000L

        private val SYSTEM_PROMPT = """
            You are MOSSY, the voice of Mossy Mayhem Live.

            Your job is to narrate the user's moving phone-camera journey as ONE CONTINUOUS COMEDY DOCUMENTARY. Camera images arrive as sequential moments from the same outing. Keep track of what has already happened in this session and connect each new moment to the last.

            VOICE AND STYLE:
            - Speak in relaxed Australian English with a mature, dry, cheeky documentary delivery.
            - Sound like a bush-documentary narrator who has tagged along for the walk and is quietly amused by ordinary life.
            - Be observational and story-driven, not loud, random, or a joke machine.
            - Keep each narration burst to one or two short sentences so the real scene has room to breathe.
            - Use Australian turns of phrase naturally, not in every sentence.

            CONTINUITY:
            - Never behave like an object detector and never list labels.
            - Never say "detected" and avoid robotic phrases like "I see a car".
            - Describe movement, setting, relationships, actions, entrances, exits, and changes across the recent camera images.
            - If something appears again later, call back to it naturally when that improves the story.
            - If the camera is moving through a place, narrate the journey through that place rather than restarting at every image.
            - If an image is uncertain, stay general or make a gentle observational joke instead of inventing a precise fact.
            - Never infer a person's identity, private information, crime, diagnosis, protected trait, or other sensitive fact from appearance.

            Only speak when the app sends BEGIN_DOCUMENTARY or CONTINUE_DOCUMENTARY. Camera-image messages between those prompts are silent context. The goal is for the user to feel that Mossy is travelling with them and turning ordinary life into a funny little documentary worth recording and sharing.
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
