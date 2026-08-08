package com.mossy.mayhemlive

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.PendingRecording
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import com.mossy.mayhemlive.databinding.ActivityMainBinding
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity : AppCompatActivity(), OpenAIRealtimeClient.Listener {

    private enum class ComedyMode(val apiValue: String, val badge: String) {
        FUNNY("funny", "FUNNY"),
        UNHINGED("unhinged", "UNHINGED 18+")
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private val narrationHandler = Handler(Looper.getMainLooper())

    private var openAIClient: OpenAIRealtimeClient? = null
    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var lastVideoFile: File? = null

    private var cameraRunning = false
    private var soundEnabled = true
    private var commentaryPaused = false
    private var comedyMode = ComedyMode.FUNNY

    private var lastLocalSampleAt = 0L
    private var lastCloudFrameAt = 0L
    private var lastNarrationAt = 0L
    private var previousSceneSignature: IntArray? = null
    private var firstNarration = true
    private var connectionErrorDialogShowing = false

    private var eventsDetected = 0
    private var framesSent = 0
    private var commentsRequested = 0
    private var sessionCostUsd = 0.0
    private var sessionBilledTokens = 0
    private var sessionModel = ""

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results[Manifest.permission.CAMERA] == true) {
            showCameraAndStart()
        } else {
            Toast.makeText(this, "Camera permission is needed to start the mayhem.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        binding.funnyModeButton.setOnClickListener { selectComedyMode(ComedyMode.FUNNY) }
        binding.unhingedModeButton.setOnClickListener { selectComedyMode(ComedyMode.UNHINGED) }
        binding.startButton.setOnClickListener { requestPermissionsAndStart() }
        binding.recordButton.setOnClickListener { toggleRecording() }
        binding.flipButton.setOnClickListener { flipCamera() }
        binding.muteButton.setOnClickListener { toggleSound() }
        binding.shareButton.setOnClickListener { shareLastVideo() }
        binding.pauseButton.setOnClickListener { toggleMossyPause() }

        updateModeButtons()
        updateSmartStats()
    }

    private fun selectComedyMode(mode: ComedyMode) {
        comedyMode = mode
        updateModeButtons()
    }

    private fun updateModeButtons() {
        styleChoiceButton(binding.funnyModeButton, comedyMode == ComedyMode.FUNNY, false)
        styleChoiceButton(binding.unhingedModeButton, comedyMode == ComedyMode.UNHINGED, true)

        binding.funnyModeButton.text = if (comedyMode == ComedyMode.FUNNY) "✓ FUNNY" else "FUNNY"
        binding.unhingedModeButton.text = if (comedyMode == ComedyMode.UNHINGED) "✓ UNHINGED 18+" else "UNHINGED 18+"
        binding.modeHint.text = if (comedyMode == ComedyMode.FUNNY) {
            "Cheeky documentary comedy • smart scene watching"
        } else {
            "18+ feral documentary roast • smart scene watching"
        }
    }

    private fun styleChoiceButton(button: MaterialButton, selected: Boolean, adult: Boolean) {
        val background = when {
            selected && adult -> R.color.record_red
            selected -> R.color.moss_lime
            else -> R.color.control_surface
        }
        val textColor = if (selected) R.color.moss_black else R.color.cream
        val strokeColor = if (adult) R.color.record_red else R.color.moss_lime

        button.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, background))
        button.setTextColor(ContextCompat.getColor(this, textColor))
        button.strokeColor = ColorStateList.valueOf(ContextCompat.getColor(this, strokeColor))
        button.strokeWidth = if (selected) 2 else 1
    }

    private fun requestPermissionsAndStart() {
        val cameraGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (cameraGranted) {
            showCameraAndStart()
        } else {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            )
        }
    }

    private fun showCameraAndStart() {
        binding.welcomePanel.visibility = View.GONE
        binding.cameraPanel.visibility = View.VISIBLE
        binding.statusText.text = "Starting Smart Cost Mossy…"
        binding.modeBadgeText.text = comedyMode.badge
        binding.pauseButton.text = "PAUSE MOSSY"

        cameraRunning = true
        commentaryPaused = false
        firstNarration = true
        lastLocalSampleAt = 0L
        lastCloudFrameAt = 0L
        lastNarrationAt = 0L
        previousSceneSignature = null
        eventsDetected = 0
        framesSent = 0
        commentsRequested = 0
        sessionCostUsd = 0.0
        sessionBilledTokens = 0
        sessionModel = ""
        updateSmartStats()

        startOpenAIDocumentary()
        startCamera()
    }

    private fun startOpenAIDocumentary() {
        openAIClient?.close()
        openAIClient = OpenAIRealtimeClient(
            comedyMode.apiValue,
            "low_cost",
            this
        ).also { client ->
            client.setMuted(!soundEnabled)
            client.connect()
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val cameraProvider = providerFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.fromOrderedList(
                        listOf(Quality.FHD, Quality.HD, Quality.SD),
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                    )
                )
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            val imageAnalysis = ImageAnalysis.Builder()
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        if (commentaryPaused) {
                            imageProxy.close()
                            return@setAnalyzer
                        }

                        val now = SystemClock.elapsedRealtime()
                        if (now - lastLocalSampleAt < LOCAL_SAMPLE_INTERVAL_MS) {
                            imageProxy.close()
                            return@setAnalyzer
                        }
                        lastLocalSampleAt = now

                        try {
                            val source = imageProxy.toBitmap()
                            val upright = rotateBitmap(source, imageProxy.imageInfo.rotationDegrees)
                            val signature = makeSceneSignature(upright)
                            val previous = previousSceneSignature
                            val changed = previous != null && isMeaningfulSceneChange(previous, signature)
                            previousSceneSignature = signature

                            val heartbeatDue = lastCloudFrameAt == 0L ||
                                now - lastCloudFrameAt >= HEARTBEAT_INTERVAL_MS
                            val eventDue = changed &&
                                now - lastCloudFrameAt >= EVENT_CLOUD_COOLDOWN_MS
                            val shouldSend = (eventDue || heartbeatDue) && openAIClient?.isReady == true

                            if (shouldSend) {
                                if (eventDue) eventsDetected += 1
                                val cloudImage = scaleForCloud(upright)
                                val bytes = ByteArrayOutputStream().use { stream ->
                                    cloudImage.compress(Bitmap.CompressFormat.JPEG, 45, stream)
                                    stream.toByteArray()
                                }

                                if (openAIClient?.sendImageFrame(bytes) == true) {
                                    framesSent += 1
                                    lastCloudFrameAt = now
                                    runOnUiThread {
                                        binding.statusText.text = if (eventDue) {
                                            "Mossy spotted a change"
                                        } else {
                                            "Mossy checking the scene"
                                        }
                                        updateSmartStats()
                                    }
                                    scheduleNarrationForKeyMoment()
                                }

                                if (cloudImage !== upright) cloudImage.recycle()
                            }

                            if (upright !== source) upright.recycle()
                            source.recycle()
                        } catch (_: Exception) {
                            runOnUiThread {
                                if (!commentaryPaused) binding.statusText.text = "Mossy watching"
                            }
                        } finally {
                            imageProxy.close()
                        }
                    }
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalysis,
                    videoCapture
                )
                if (!commentaryPaused) {
                    binding.statusText.text = "Camera live • Smart Cost waking up"
                }
            } catch (error: Exception) {
                binding.statusText.text = "Camera could not start"
                Toast.makeText(this, error.message ?: "Camera error", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun scheduleNarrationForKeyMoment() {
        val now = SystemClock.elapsedRealtime()
        if (!firstNarration && now - lastNarrationAt < NARRATION_COOLDOWN_MS) return

        narrationHandler.postDelayed({
            if (!cameraRunning || commentaryPaused) return@postDelayed
            val sent = openAIClient?.requestNarration(firstTurn = firstNarration) == true
            if (sent) {
                firstNarration = false
                commentsRequested += 1
                lastNarrationAt = SystemClock.elapsedRealtime()
                updateSmartStats()
            }
        }, NARRATION_AFTER_EVENT_DELAY_MS)
    }

    private fun makeSceneSignature(source: Bitmap): IntArray {
        val tiny = Bitmap.createScaledBitmap(source, SIGNATURE_WIDTH, SIGNATURE_HEIGHT, true)
        val pixels = IntArray(SIGNATURE_WIDTH * SIGNATURE_HEIGHT)
        tiny.getPixels(pixels, 0, SIGNATURE_WIDTH, 0, 0, SIGNATURE_WIDTH, SIGNATURE_HEIGHT)
        if (tiny !== source) tiny.recycle()

        return IntArray(pixels.size) { index ->
            val color = pixels[index]
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            (r * 30 + g * 59 + b * 11) / 100
        }
    }

    private fun isMeaningfulSceneChange(previous: IntArray, current: IntArray): Boolean {
        if (previous.size != current.size || previous.isEmpty()) return true

        var changedPixels = 0
        var totalDifference = 0L
        for (index in previous.indices) {
            val difference = abs(previous[index] - current[index])
            totalDifference += difference
            if (difference >= PIXEL_CHANGE_THRESHOLD) changedPixels += 1
        }

        val changedFraction = changedPixels.toDouble() / previous.size.toDouble()
        val averageDifference = totalDifference.toDouble() / previous.size.toDouble()

        return changedFraction >= CHANGED_PIXEL_FRACTION ||
            averageDifference >= AVERAGE_CHANGE_THRESHOLD
    }

    private fun rotateBitmap(source: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return source
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun scaleForCloud(source: Bitmap): Bitmap {
        val largest = maxOf(source.width, source.height)
        if (largest <= CLOUD_IMAGE_MAX_EDGE) return source
        val scale = CLOUD_IMAGE_MAX_EDGE.toFloat() / largest.toFloat()
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, width, height, true)
    }

    override fun onReady() {
        runOnUiThread {
            if (commentaryPaused) {
                binding.statusText.text = "MOSSY PAUSED"
                return@runOnUiThread
            }
            binding.statusText.text = "Mossy ready • Smart Cost"
        }
    }

    override fun onStatus(message: String) {
        runOnUiThread {
            if (!commentaryPaused && !cameraRunning) binding.statusText.text = message
        }
    }

    override fun onTranscript(text: String) {
        // Voice-first customer experience: transcript intentionally hidden from the camera view.
    }

    override fun onUsageUpdate(costUsd: Double, billedTokens: Int, model: String) {
        sessionCostUsd = costUsd
        sessionBilledTokens = billedTokens
        sessionModel = model
        runOnUiThread { updateSmartStats() }
    }

    override fun onFailure(message: String) {
        runOnUiThread {
            binding.statusText.text = "Mossy connection failed"

            if (!connectionErrorDialogShowing && !isFinishing) {
                connectionErrorDialogShowing = true
                AlertDialog.Builder(this)
                    .setTitle("Mossy didn't connect")
                    .setMessage(message)
                    .setPositiveButton("RETRY") { _, _ ->
                        connectionErrorDialogShowing = false
                        lastCloudFrameAt = 0L
                        startOpenAIDocumentary()
                    }
                    .setNegativeButton("CLOSE") { _, _ ->
                        connectionErrorDialogShowing = false
                    }
                    .setOnCancelListener { connectionErrorDialogShowing = false }
                    .show()
            }
        }
    }

    private fun toggleMossyPause() {
        commentaryPaused = !commentaryPaused

        if (commentaryPaused) {
            narrationHandler.removeCallbacksAndMessages(null)
            openAIClient?.pauseNarration()
            binding.pauseButton.text = "RESUME MOSSY"
            binding.statusText.text = "MOSSY PAUSED • recording can keep going"
        } else {
            previousSceneSignature = null
            lastLocalSampleAt = 0L
            lastCloudFrameAt = 0L
            openAIClient?.resumeNarration(soundEnabled)
            binding.pauseButton.text = "PAUSE MOSSY"
            binding.statusText.text = "Mossy watching again"
        }
    }

    private fun toggleRecording() {
        activeRecording?.let {
            it.stop()
            activeRecording = null
            binding.recordButton.setText(R.string.record)
            binding.statusText.text = "Saving video…"
            return
        }

        val capture = videoCapture ?: run {
            Toast.makeText(this, "Camera is not ready yet.", Toast.LENGTH_SHORT).show()
            return
        }

        val movieDir = File(getExternalFilesDir(null), "Movies").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(System.currentTimeMillis())
        val file = File(movieDir, "Mossy-Mayhem-$stamp.mp4")
        val output = FileOutputOptions.Builder(file).build()

        var pending: PendingRecording = capture.output.prepareRecording(this, output)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            pending = pending.withAudioEnabled()
        }

        activeRecording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    binding.recordButton.setText(R.string.stop)
                    binding.statusText.text = "● RECORDING • Mossy watching"
                }

                is VideoRecordEvent.Finalize -> {
                    activeRecording = null
                    binding.recordButton.setText(R.string.record)
                    if (!event.hasError()) {
                        lastVideoFile = file
                        binding.shareButton.isEnabled = true
                        binding.statusText.text = "Saved • tap SHARE"
                    } else {
                        file.delete()
                        binding.statusText.text = "Recording failed"
                        Toast.makeText(this, "Recording error: ${event.error}", Toast.LENGTH_LONG).show()
                    }
                }

                else -> Unit
            }
        }
    }

    private fun shareLastVideo() {
        val file = lastVideoFile ?: return
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, "Made with Mossy Mayhem Live — Point. Watch. Laugh.")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "Share the mayhem"))
    }

    private fun flipCamera() {
        if (activeRecording != null) {
            Toast.makeText(this, "Stop recording before flipping the camera.", Toast.LENGTH_SHORT).show()
            return
        }

        cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        previousSceneSignature = null
        lastCloudFrameAt = 0L
        startCamera()
    }

    private fun toggleSound() {
        soundEnabled = !soundEnabled
        binding.muteButton.setText(if (soundEnabled) R.string.mute else R.string.unmute)
        openAIClient?.setMuted(commentaryPaused || !soundEnabled)
        Toast.makeText(
            this,
            if (soundEnabled) "Mossy's voice is on." else "Mossy's watching silently.",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun updateSmartStats() {
        if (!::binding.isInitialized) return
        binding.smartStatsText.text =
            "E $eventsDetected • F $framesSent • C $commentsRequested • ${formatCost(sessionCostUsd)}"
    }

    private fun formatCost(costUsd: Double): String {
        val decimals = if (costUsd < 0.01) 4 else 3
        return "US$${String.format(Locale.US, "%.${decimals}f", costUsd)}"
    }

    override fun onDestroy() {
        cameraRunning = false
        narrationHandler.removeCallbacksAndMessages(null)
        activeRecording?.stop()
        openAIClient?.close()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val LOCAL_SAMPLE_INTERVAL_MS = 750L
        private const val EVENT_CLOUD_COOLDOWN_MS = 4_500L
        private const val HEARTBEAT_INTERVAL_MS = 15_000L
        private const val NARRATION_COOLDOWN_MS = 5_000L
        private const val NARRATION_AFTER_EVENT_DELAY_MS = 650L

        private const val SIGNATURE_WIDTH = 48
        private const val SIGNATURE_HEIGHT = 36
        private const val PIXEL_CHANGE_THRESHOLD = 34
        private const val CHANGED_PIXEL_FRACTION = 0.12
        private const val AVERAGE_CHANGE_THRESHOLD = 18.0

        private const val CLOUD_IMAGE_MAX_EDGE = 384
    }
}
