package com.mossy.mayhemlive

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Base64
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class OpenAIRealtimeClient(
    private val listener: Listener
) {
    interface Listener {
        fun onReady()
        fun onStatus(message: String)
        fun onTranscript(text: String)
        fun onFailure(message: String)
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
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

        listener.onStatus("Getting secure Mossy token…")
        requestShortLivedToken()
    }

    private fun requestShortLivedToken() {
        val body = "{}".toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(TOKEN_URL)
            .post(body)
            .addHeader("Accept", "application/json")
            .addHeader("X-Client-Version", "android-v0.6")
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!closing) {
                    reportFailure("Mossy backend could not be reached: ${e.message ?: "network error"}")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val raw = it.body?.string().orEmpty()
                    val json = runCatching { JSONObject(raw) }.getOrNull()

                    if (!it.isSuccessful) {
                        val code = json?.optString("error").orEmpty()
                        val message = json?.optString("message").orEmpty()
                        val detail = listOf(code, message)
                            .filter { value -> value.isNotBlank() }
                            .joinToString(" — ")
                        reportFailure(
                            if (detail.isBlank()) "Mossy backend returned HTTP ${it.code}."
                            else detail
                        )
                        return
                    }

                    val token = json?.optString("client_secret").orEmpty()
                    val model = json?.optString("model").orEmpty().ifBlank { DEFAULT_MODEL }
                    if (token.isBlank()) {
                        reportFailure("Mossy backend returned no short-lived OpenAI token.")
                        return
                    }

                    listener.onStatus("Secure token received — connecting OpenAI…")
                    openRealtimeSocket(token, model)
                }
            }
        })
    }

    private fun openRealtimeSocket(clientSecret: String, model: String) {
        val encodedModel = URLEncoder.encode(model, Charsets.UTF_8.name())
        val request = Request.Builder()
            .url("wss://api.openai.com/v1/realtime?model=$encodedModel")
            .addHeader("Authorization", "Bearer $clientSecret")
            .build()

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                listener.onStatus("OpenAI socket open — starting Mossy…")
                mainHandler.postDelayed({
                    if (!ready && !closing) {
                        reportFailure("OpenAI connected but did not create the Realtime session within 15 seconds.")
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
            "BEGIN_DOCUMENTARY. Start the continuous Mossy documentary from the recent camera frames. Describe this moment as a connected scene, not as a list of objects. Keep it to one or two short sentences."
        } else {
            "CONTINUE_DOCUMENTARY. Continue the SAME documentary from the newest camera frames. Say what changed, where we seem to be moving, or what is happening now. Refer back naturally when useful. Do not restart or list objects. One or two short sentences."
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
                JSONObject().put("output_modalities", JSONArray().put("audio"))
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

    private fun handleMessage(raw: String) {
        try {
            val event = JSONObject(raw)
            when (val type = event.optString("type")) {
                "session.created", "session.updated" -> {
                    if (!ready) {
                        ready = true
                        generating = false
                        failureReported = false
                        mainHandler.removeCallbacksAndMessages(null)
                        listener.onReady()
                    }
                }

                "response.created" -> transcript = StringBuilder()

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
                        val error = details?.optJSONObject("error")
                        reportFailure(
                            error?.optString("message")
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
        private const val TOKEN_URL = "https://mossy-mayhem-live-api.lovable.app/api/public/realtime-token"
        private const val DEFAULT_MODEL = "gpt-realtime"
        private const val SETUP_TIMEOUT_MS = 15_000L
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
