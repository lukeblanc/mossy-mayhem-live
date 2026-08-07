package com.mossy.mayhemlive

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
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

    private val audioPlayer = PcmAudioPlayer()
    private var webSocket: WebSocket? = null
    private var ready = false
    private var generating = false
    private var closing = false
    private var latestResumeHandle: String? = null

    val isReady: Boolean
        get() = ready && webSocket != null

    fun connect() {
        closing = false
        openSocket(latestResumeHandle)
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
            "BEGIN_DOCUMENTARY. We have enough live camera frames now. Begin the ongoing documentary in character. Describe the place and movement as a connected moment, not as detected objects. Keep it to one or two short sentences."
        } else {
            "CONTINUE_DOCUMENTARY. Continue the SAME documentary story from the recent live camera frames. Say what changed, where we seem to be moving, or what is now happening. Refer back naturally when useful. Never list objects or restart the story. One or two short sentences, then stop and watch again."
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
        webSocket?.close(1000, "App closed")
        webSocket = null
        audioPlayer.release()
        httpClient.dispatcher.executorService.shutdown()
    }

    private fun openSocket(resumeHandle: String?) {
        ready = false
        generating = false
        val encodedKey = URLEncoder.encode(apiKey.trim(), Charsets.UTF_8.name())
        val url = "wss://generativelanguage.googleapis.com/ws/" +
            "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent" +
            "?key=$encodedKey"

        val request = Request.Builder().url(url).build()
        listener.onStatus(if (resumeHandle == null) "Connecting Mossy's documentary brain…" else "Reconnecting Mossy's memory…")
        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(buildSetupMessage(resumeHandle).toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                ready = false
                generating = false
                if (!closing) {
                    listener.onFailure(t.message ?: "Gemini Live connection failed")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                ready = false
                generating = false
                if (!closing) listener.onStatus("Documentary connection closed")
            }
        })
    }

    private fun buildSetupMessage(resumeHandle: String?): JSONObject {
        val generationConfig = JSONObject()
            .put("responseModalities", org.json.JSONArray().put("AUDIO"))
            .put(
                "speechConfig",
                JSONObject().put(
                    "voiceConfig",
                    JSONObject().put(
                        "prebuiltVoiceConfig",
                        JSONObject().put("voiceName", "Gacrux")
                    )
                )
            )

        val setup = JSONObject()
            .put("model", "models/gemini-3.1-flash-live-preview")
            .put("generationConfig", generationConfig)
            .put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    org.json.JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT))
                )
            )
            .put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject()))
            .put("outputAudioTranscription", JSONObject())

        val resumption = JSONObject()
        if (!resumeHandle.isNullOrBlank()) resumption.put("handle", resumeHandle)
        setup.put("sessionResumption", resumption)

        return JSONObject().put("setup", setup)
    }

    private fun handleMessage(raw: String) {
        try {
            val message = JSONObject(raw)

            if (message.has("setupComplete")) {
                ready = true
                generating = false
                listener.onReady()
                return
            }

            message.optJSONObject("sessionResumptionUpdate")?.let { update ->
                if (update.optBoolean("resumable", false)) {
                    val handle = update.optString("newHandle")
                    if (handle.isNotBlank()) latestResumeHandle = handle
                }
            }

            if (message.has("goAway")) {
                listener.onStatus("Gemini is refreshing the live session…")
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
                    val inlineData = parts.optJSONObject(index)?.optJSONObject("inlineData") ?: continue
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
            listener.onStatus("Mossy received a strange live message")
        }
    }

    companion object {
        private val SYSTEM_PROMPT = """
            You are MOSSY, the voice of Mossy Mayhem Live. You are not an object detector and you must never sound like one.

            Your job is to narrate the user's moving phone camera as ONE CONTINUOUS COMEDY DOCUMENTARY. Treat every new camera frame as the next moment in the same journey. Remember what was visible earlier in this live session and naturally connect the new moment to it.

            STYLE:
            - Speak in relaxed Australian English with a mature, dry, cheeky documentary delivery.
            - Sound like a bush-documentary narrator who has come along for the walk and is quietly amused by everything.
            - Be observational and story-driven rather than loud, random, or joke-machine-like.
            - Use phrases such as "righto", "we're heading", "over here", "and now", or "hang on" only when they fit naturally. Do not force slang into every line.
            - Keep each narration burst to roughly 1-2 short sentences so the real scene has room to breathe.

            CONTINUITY RULES:
            - Never say "detected", "I see a car", "I see a tree", or list labels.
            - Do not reset the documentary merely because a different object appears.
            - Describe movement, setting, relationships, actions, entrances, exits, and changes across recent frames.
            - If something from earlier appears again, refer back to it when that makes the story better.
            - If the camera is walking through a place, narrate the journey through that place.
            - If the view is uncertain, make a gentle observational joke instead of inventing a precise fact.
            - Never invent a person's identity, private information, dangerous situation, crime, diagnosis, or other sensitive fact from appearance.

            PRODUCT GOAL:
            The listener should feel that Mossy is physically coming along with them, watching the same moment unfold and turning ordinary life into a funny little documentary worth recording and sharing.

            Only narrate when the app sends BEGIN_DOCUMENTARY or CONTINUE_DOCUMENTARY. Between those prompts, silently watch the incoming video frames and maintain context.
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
