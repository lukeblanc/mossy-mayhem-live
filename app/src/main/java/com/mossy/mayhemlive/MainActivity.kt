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

class MainActivity : AppCompatActivity(), OpenAIRealtimeClient.Listener {

    private enum class ComedyMode(val apiValue: String, val badge: String) {
        FUNNY("funny", "FUNNY"),
        UNHINGED("unhinged", "UNHINGED 18+")
    }

    private enum class QualityMode(val apiValue: String, val badge: String) {
        FULL("full", "FULL"),
        LOW_COST("low_cost", "LOW COST")
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
    private var qualityMode = QualityMode.LOW_COST
    private var lastFrameAt = 0L
    private var framesSent = 0
    private var firstNarration = true
    private var connectionErrorDialogShowing = false
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

    private val narrationRunnable = object : Runnable {
        override fun run() {
            if (!cameraRunning || commentaryPaused) return

            val client = openAIClient
            if (framesSent >= 2 && client?.isReady == true) {
                val sent = client.requestNarration(firstTurn = firstNarration)
                if (sent) firstNarration = false
            }

            narrationHandler.postDelayed(this, NARRATION_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        binding.funnyModeButton.setOnClickListener { selectComedyMode(ComedyMode.FUNNY) }
        binding.unhingedModeButton.setOnClickListener { selectComedyMode(ComedyMode.UNHINGED) }
        binding.fullQualityButton.setOnClickListener { selectQualityMode(QualityMode.FULL) }
        binding.lowCostButton.setOnClickListener { selectQualityMode(QualityMode.LOW_COST) }
        binding.startButton.setOnClickListener { requestPermissionsAndStart() }
        binding.recordButton.setOnClickListener { toggleRecording() }
        binding.flipButton.setOnClickListener { flipCamera() }
        binding.muteButton.setOnClickListener { toggleSound() }
        binding.shareButton.setOnClickListener { shareLastVideo() }
        binding.pauseButton.setOnClickListener { toggleMossyPause() }

        updateModeButtons()
        updateQualityButtons()
    }

    private fun selectComedyMode(mode: ComedyMode) {
        comedyMode = mode
        updateModeButtons()
    }

    private fun selectQualityMode(mode: QualityMode) {
        qualityMode = mode
        updateQualityButtons()
    }

    private fun updateModeButtons() {
        styleChoiceButton(binding.funnyModeButton, comedyMode == ComedyMode.FUNNY, false)
        styleChoiceButton(binding.unhingedModeButton, comedyMode == ComedyMode.UNHINGED, true)

        binding.funnyModeButton.text = if (comedyMode == ComedyMode.FUNNY) "✓ FUNNY" else "FUNNY"
        binding.unhingedModeButton.text = if (comedyMode == ComedyMode.UNHINGED) "✓ UNHINGED 18+" else "UNHINGED 18+"
        binding.modeHint.text = if (comedyMode == ComedyMode.FUNNY) {
            "Cheeky documentary comedy • mild language"
        } else {
            "Strong language • feral documentary roast • 18+"
        }
    }

    private fun updateQualityButtons() {
        styleChoiceButton(binding.fullQualityButton, qualityMode == QualityMode.FULL, false)
        styleChoiceButton(binding.lowCostButton, qualityMode == QualityMode.LOW_COST, false)

        binding.fullQualityButton.text = if (qualityMode == QualityMode.FULL) "✓ FULL" else "FULL"
        binding.lowCostButton.text = if (qualityMode == QualityMode.LOW_COST) "✓ LOW COST" else "LOW COST"
        binding.qualityHint.text = if (qualityMode == QualityMode.LOW_COST) {
            "Business test • cheaper Mini model"
        } else {
            "Benchmark • full Realtime model"
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
        binding.commentaryText.text = if (comedyMode == ComedyMode.UNHINGED) {
            "Unhinged Mossy is joining the walk…"
        } else {
            "Mossy is joining the walk…"
        }
        binding.statusText.text = "Starting secure documentary mode…"
        binding.modeBadgeText.text = comedyMode.badge
        binding.qualityBadgeText.text = qualityMode.badge
        binding.pauseButton.text = "PAUSE MOSSY"
        binding.costText.text = "AI US$0.000"

        cameraRunning = true
        commentaryPaused = false
        framesSent = 0
        firstNarration = true
        lastFrameAt = 0L
        sessionCostUsd = 0.0
        sessionBilledTokens = 0
        sessionModel = ""

        startOpenAIDocumentary()
        startCamera()
    }

    private fun startOpenAIDocumentary() {
        openAIClient?.close()
        openAIClient = OpenAIRealtimeClient(
            comedyMode.apiValue,
            qualityMode.apiValue,
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
                        if (now - lastFrameAt < FRAME_INTERVAL_MS) {
                            imageProxy.close()
                            return@setAnalyzer
                        }
                        lastFrameAt = now

                        try {
                            val source = imageProxy.toBitmap()
                            val upright = rotateBitmap(source, imageProxy.imageInfo.rotationDegrees)
                            val scaled = scaleForOpenAI(upright)
                            val bytes = ByteArrayOutputStream().use { stream ->
                                scaled.compress(Bitmap.CompressFormat.JPEG, 55, stream)
                                stream.toByteArray()
                            }

                            if (openAIClient?.sendImageFrame(bytes) == true) {
                                framesSent += 1
                                if (framesSent % 4 == 0) {
                                    runOnUiThread {
                                        if (!commentaryPaused) {
                                            binding.statusText.text = "${comedyMode.badge} • Mossy is watching"
                                        }
                                    }
                                }
                            }

                            if (scaled !== upright) scaled.recycle()
                            if (upright !== source) upright.recycle()
                            source.recycle()
                        } catch (_: Exception) {
                            runOnUiThread {
                                if (!commentaryPaused) {
                                    binding.statusText.text = "Camera live — preparing the next scene"
                                }
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
                    binding.statusText.text = "Camera live — connecting secure Mossy brain"
                }
            } catch (error: Exception) {
                binding.statusText.text = "Camera could not start"
                Toast.makeText(this, error.message ?: "Camera error", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun rotateBitmap(source: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return source
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun scaleForOpenAI(source: Bitmap): Bitmap {
        val largest = maxOf(source.width, source.height)
        if (largest <= 640) return source
        val scale = 640f / largest.toFloat()
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, width, height, true)
    }

    override fun onReady() {
        runOnUiThread {
            if (commentaryPaused) {
                binding.statusText.text = "MOSSY PAUSED • camera and recording stay live"
                return@runOnUiThread
            }

            binding.statusText.text = "${comedyMode.badge} • ${qualityMode.badge} • CONNECTED"
            binding.commentaryText.text = "Connected. Give Mossy a few seconds to watch before he starts the story."
            narrationHandler.removeCallbacks(narrationRunnable)
            narrationHandler.postDelayed(narrationRunnable, 4_000L)
        }
    }

    override fun onStatus(message: String) {
        runOnUiThread {
            if (!commentaryPaused) binding.statusText.text = message
        }
    }

    override fun onTranscript(text: String) {
        runOnUiThread {
            if (!commentaryPaused) binding.commentaryText.text = text
        }
    }

    override fun onUsageUpdate(costUsd: Double, billedTokens: Int, model: String) {
        sessionCostUsd = costUsd
        sessionBilledTokens = billedTokens
        sessionModel = model
        runOnUiThread {
            binding.costText.text = formatCost(costUsd)
        }
    }

    override fun onFailure(message: String) {
        runOnUiThread {
            binding.statusText.text = "Mossy connection failed"
            binding.commentaryText.text = "Mossy's documentary brain didn't connect."

            if (!connectionErrorDialogShowing && !isFinishing) {
                connectionErrorDialogShowing = true
                AlertDialog.Builder(this)
                    .setTitle("Mossy didn't connect")
                    .setMessage(message)
                    .setPositiveButton("RETRY") { _, _ ->
                        connectionErrorDialogShowing = false
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
            narrationHandler.removeCallbacks(narrationRunnable)
            openAIClient?.pauseNarration()
            binding.pauseButton.text = "RESUME MOSSY"
            binding.statusText.text = "MOSSY PAUSED • no AI frames being sent"
            binding.commentaryText.text = "Mossy is paused. Camera and recording can keep rolling."
        } else {
            framesSent = 0
            lastFrameAt = 0L
            openAIClient?.resumeNarration(soundEnabled)
            binding.pauseButton.text = "PAUSE MOSSY"
            binding.statusText.text = "${comedyMode.badge} • Mossy is watching again"
            narrationHandler.postDelayed(narrationRunnable, 3_000L)
        }
    }

    private fun toggleRecording() {
        activeRecording?.let {
            it.stop()
            activeRecording = null
            binding.recordButton.setText(R.string.record)
            binding.statusText.text = "Saving • ${formatCost(sessionCostUsd)}"
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
                    binding.statusText.text = "RECORDING • ${qualityMode.badge}"
                }

                is VideoRecordEvent.Finalize -> {
                    activeRecording = null
                    binding.recordButton.setText(R.string.record)
                    if (!event.hasError()) {
                        lastVideoFile = file
                        binding.shareButton.isEnabled = true
                        binding.statusText.text = "Saved • ${formatCost(sessionCostUsd)} • SHARE"
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
        startCamera()
    }

    private fun toggleSound() {
        soundEnabled = !soundEnabled
        binding.muteButton.setText(if (soundEnabled) R.string.mute else R.string.unmute)
        openAIClient?.setMuted(commentaryPaused || !soundEnabled)
        Toast.makeText(
            this,
            if (soundEnabled) "Mossy's back on the documentary mic." else "Mossy's watching silently.",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun formatCost(costUsd: Double): String {
        val decimals = if (costUsd < 0.01) 4 else 3
        return "AI US$${String.format(Locale.US, "%.${decimals}f", costUsd)}"
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
        private const val FRAME_INTERVAL_MS = 2_000L
        private const val NARRATION_INTERVAL_MS = 7_000L
    }
}
