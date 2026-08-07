package com.mossy.mayhemlive

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.Size
import android.view.View
import android.widget.EditText
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
import com.mossy.mayhemlive.databinding.ActivityMainBinding
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), GeminiLiveClient.Listener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService

    private val prefs by lazy { getSharedPreferences("mossy_dev", MODE_PRIVATE) }
    private val narrationHandler = Handler(Looper.getMainLooper())

    private var geminiClient: GeminiLiveClient? = null
    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var lastVideoFile: File? = null

    private var cameraRunning = false
    private var soundEnabled = true
    private var lastFrameAt = 0L
    private var framesSent = 0
    private var firstNarration = true
    private var connectionErrorDialogShowing = false

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
            if (!cameraRunning) return

            val client = geminiClient
            if (framesSent >= 3 && client?.isReady == true) {
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

        binding.startButton.setOnClickListener { ensureGeminiKeyThenStart() }
        binding.geminiSetupButton.setOnClickListener { showGeminiKeyDialog() }
        binding.recordButton.setOnClickListener { toggleRecording() }
        binding.flipButton.setOnClickListener { flipCamera() }
        binding.muteButton.setOnClickListener { toggleSound() }
        binding.shareButton.setOnClickListener { shareLastVideo() }
    }

    private fun ensureGeminiKeyThenStart() {
        val key = savedGeminiKey()
        if (key.isBlank()) {
            showGeminiKeyDialog { requestPermissionsAndStart() }
        } else {
            requestPermissionsAndStart()
        }
    }

    private fun showGeminiKeyDialog(onSaved: (() -> Unit)? = null) {
        val input = EditText(this).apply {
            hint = "Paste Gemini API key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            setPadding(48, 24, 48, 12)
            setText(savedGeminiKey())
            setSelection(text.length)
        }

        AlertDialog.Builder(this)
            .setTitle("Gemini Live setup — developer test")
            .setMessage(
                "For v0.4 only, your Gemini API key is stored on this phone and is not built into the APK or GitHub. " +
                    "The public Play Store version will use short-lived secure tokens instead."
            )
            .setView(input)
            .setPositiveButton("SAVE") { _, _ ->
                val key = input.text.toString().trim()
                if (key.isBlank()) {
                    Toast.makeText(this, "No key saved.", Toast.LENGTH_SHORT).show()
                } else {
                    prefs.edit().putString(KEY_GEMINI_API, key).apply()
                    Toast.makeText(this, "Gemini key saved on this phone.", Toast.LENGTH_SHORT).show()
                    onSaved?.invoke()
                }
            }
            .setNeutralButton("CLEAR") { _, _ ->
                prefs.edit().remove(KEY_GEMINI_API).apply()
                Toast.makeText(this, "Gemini key cleared.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun savedGeminiKey(): String = prefs.getString(KEY_GEMINI_API, "").orEmpty()

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
        binding.commentaryText.text = "Mossy is joining the walk…"
        binding.statusText.text = "Starting Gemini Live documentary mode…"

        cameraRunning = true
        framesSent = 0
        firstNarration = true

        startGeminiDocumentary()
        startCamera()
    }

    private fun startGeminiDocumentary() {
        geminiClient?.close()
        geminiClient = GeminiLiveClient(savedGeminiKey(), this).also { client ->
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
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastFrameAt < FRAME_INTERVAL_MS) {
                            imageProxy.close()
                            return@setAnalyzer
                        }
                        lastFrameAt = now

                        try {
                            val source = imageProxy.toBitmap()
                            val upright = rotateBitmap(source, imageProxy.imageInfo.rotationDegrees)
                            val scaled = scaleForGemini(upright)
                            val bytes = ByteArrayOutputStream().use { stream ->
                                scaled.compress(Bitmap.CompressFormat.JPEG, 58, stream)
                                stream.toByteArray()
                            }

                            if (geminiClient?.sendVideoFrame(bytes) == true) {
                                framesSent += 1
                                if (framesSent % 5 == 0) {
                                    runOnUiThread {
                                        binding.statusText.text = "Documentary live — Mossy is watching the journey"
                                    }
                                }
                            }

                            if (scaled !== upright) scaled.recycle()
                            if (upright !== source) upright.recycle()
                            source.recycle()
                        } catch (error: Exception) {
                            runOnUiThread {
                                binding.statusText.text = "Camera is live — preparing the next frame"
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
                binding.statusText.text = "Camera live — connecting Mossy's documentary brain"
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

    private fun scaleForGemini(source: Bitmap): Bitmap {
        val largest = maxOf(source.width, source.height)
        if (largest <= 640) return source
        val scale = 640f / largest.toFloat()
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, width, height, true)
    }

    override fun onReady() {
        runOnUiThread {
            binding.statusText.text = "GEMINI LIVE • DOCUMENTARY BRAIN CONNECTED"
            binding.commentaryText.text = "Connected. Give Mossy a few seconds to watch before he starts the story."
            narrationHandler.removeCallbacks(narrationRunnable)
            narrationHandler.postDelayed(narrationRunnable, 3_200L)
        }
    }

    override fun onStatus(message: String) {
        runOnUiThread { binding.statusText.text = message }
    }

    override fun onTranscript(text: String) {
        runOnUiThread {
            binding.commentaryText.text = text
        }
    }

    override fun onFailure(message: String) {
        runOnUiThread {
            binding.statusText.text = "Gemini Live connection failed"
            binding.commentaryText.text = "Mossy's documentary brain didn't connect. The API key may need checking."

            if (!connectionErrorDialogShowing && !isFinishing) {
                connectionErrorDialogShowing = true
                AlertDialog.Builder(this)
                    .setTitle("Gemini didn't connect")
                    .setMessage("Check the Gemini API key for this developer test.\n\n$message")
                    .setPositiveButton("CHANGE KEY") { _, _ ->
                        connectionErrorDialogShowing = false
                        showGeminiKeyDialog {
                            startGeminiDocumentary()
                        }
                    }
                    .setNegativeButton("CLOSE") { _, _ ->
                        connectionErrorDialogShowing = false
                    }
                    .setOnCancelListener { connectionErrorDialogShowing = false }
                    .show()
            }
        }
    }

    private fun toggleRecording() {
        activeRecording?.let {
            it.stop()
            activeRecording = null
            binding.recordButton.setText(R.string.record)
            binding.statusText.text = "Saving your documentary…"
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
                    binding.statusText.text = "RECORDING • Mossy documentary live"
                }

                is VideoRecordEvent.Finalize -> {
                    activeRecording = null
                    binding.recordButton.setText(R.string.record)
                    if (!event.hasError()) {
                        lastVideoFile = file
                        binding.shareButton.isEnabled = true
                        binding.statusText.text = "Saved — tap SHARE"
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
        geminiClient?.setMuted(!soundEnabled)
        Toast.makeText(
            this,
            if (soundEnabled) "Mossy's back on the documentary mic." else "Mossy's watching silently.",
            Toast.LENGTH_SHORT
        ).show()
    }

    override fun onDestroy() {
        cameraRunning = false
        narrationHandler.removeCallbacksAndMessages(null)
        activeRecording?.stop()
        geminiClient?.close()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val KEY_GEMINI_API = "gemini_api_key"
        private const val FRAME_INTERVAL_MS = 1_000L
        private const val NARRATION_INTERVAL_MS = 7_000L
    }
}
