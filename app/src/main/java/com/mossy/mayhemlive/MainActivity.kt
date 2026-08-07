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

class MainActivity : AppCompatActivity(), OpenAIRealtimeClient.Listener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService

    private val prefs by lazy { getSharedPreferences("mossy_openai_dev", MODE_PRIVATE) }
    private val narrationHandler = Handler(Looper.getMainLooper())

    private var openAIClient: OpenAIRealtimeClient? = null
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

        binding.startButton.setOnClickListener { ensureOpenAIKeyThenStart() }
        binding.openAISetupButton.setOnClickListener { showOpenAIKeyDialog() }
        binding.recordButton.setOnClickListener { toggleRecording() }
        binding.flipButton.setOnClickListener { flipCamera() }
        binding.muteButton.setOnClickListener { toggleSound() }
        binding.shareButton.setOnClickListener { shareLastVideo() }
    }

    private fun ensureOpenAIKeyThenStart() {
        val key = savedOpenAIKey()
        if (key.isBlank()) {
            showOpenAIKeyDialog { requestPermissionsAndStart() }
        } else {
            requestPermissionsAndStart()
        }
    }

    private fun showOpenAIKeyDialog(onSaved: (() -> Unit)? = null) {
        val input = EditText(this).apply {
            hint = "Paste OpenAI API key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            setPadding(48, 24, 48, 12)
            setText(savedOpenAIKey())
            setSelection(text.length)
        }

        AlertDialog.Builder(this)
            .setTitle("OpenAI Realtime setup — developer test")
            .setMessage(
                "For v0.5 only, your OpenAI API key is stored in this app on this phone and is not built into the APK or GitHub. " +
                    "The public Play Store version will use short-lived secure client tokens instead."
            )
            .setView(input)
            .setPositiveButton("SAVE") { _, _ ->
                val key = input.text.toString().trim()
                if (key.isBlank()) {
                    Toast.makeText(this, "No key saved.", Toast.LENGTH_SHORT).show()
                } else {
                    prefs.edit().putString(KEY_OPENAI_API, key).apply()
                    Toast.makeText(this, "OpenAI key saved on this phone.", Toast.LENGTH_SHORT).show()
                    onSaved?.invoke()
                }
            }
            .setNeutralButton("CLEAR") { _, _ ->
                prefs.edit().remove(KEY_OPENAI_API).apply()
                Toast.makeText(this, "OpenAI key cleared.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun savedOpenAIKey(): String = prefs.getString(KEY_OPENAI_API, "").orEmpty()

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
        binding.statusText.text = "Starting OpenAI Realtime documentary mode…"

        cameraRunning = true
        framesSent = 0
        firstNarration = true
        lastFrameAt = 0L

        startOpenAIDocumentary()
        startCamera()
    }

    private fun startOpenAIDocumentary() {
        openAIClient?.close()
        openAIClient = OpenAIRealtimeClient(savedOpenAIKey(), this).also { client ->
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
                            val scaled = scaleForOpenAI(upright)
                            val bytes = ByteArrayOutputStream().use { stream ->
                                scaled.compress(Bitmap.CompressFormat.JPEG, 55, stream)
                                stream.toByteArray()
                            }

                            if (openAIClient?.sendImageFrame(bytes) == true) {
                                framesSent += 1
                                if (framesSent % 4 == 0) {
                                    runOnUiThread {
                                        binding.statusText.text = "OPENAI REALTIME • Mossy is watching the journey"
                                    }
                                }
                            }

                            if (scaled !== upright) scaled.recycle()
                            if (upright !== source) upright.recycle()
                            source.recycle()
                        } catch (error: Exception) {
                            runOnUiThread {
                                binding.statusText.text = "Camera is live — preparing the next scene"
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
                binding.statusText.text = "Camera live — connecting OpenAI documentary brain"
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
            binding.statusText.text = "OPENAI REALTIME • DOCUMENTARY BRAIN CONNECTED"
            binding.commentaryText.text = "Connected. Give Mossy a few seconds to watch before he starts the story."
            narrationHandler.removeCallbacks(narrationRunnable)
            narrationHandler.postDelayed(narrationRunnable, 4_000L)
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
            binding.statusText.text = "OpenAI Realtime connection failed"
            binding.commentaryText.text = "Mossy's OpenAI documentary brain didn't connect. Read the exact error below."

            if (!connectionErrorDialogShowing && !isFinishing) {
                connectionErrorDialogShowing = true
                AlertDialog.Builder(this)
                    .setTitle("OpenAI didn't connect")
                    .setMessage(
                        "Do not create another key yet. This is the exact OpenAI error:\n\n$message"
                    )
                    .setPositiveButton("CHANGE KEY") { _, _ ->
                        connectionErrorDialogShowing = false
                        showOpenAIKeyDialog {
                            startOpenAIDocumentary()
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
        openAIClient?.setMuted(!soundEnabled)
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
        openAIClient?.close()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val KEY_OPENAI_API = "openai_api_key"
        private const val FRAME_INTERVAL_MS = 2_000L
        private const val NARRATION_INTERVAL_MS = 7_000L
    }
}
