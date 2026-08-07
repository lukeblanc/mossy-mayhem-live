package com.mossy.mayhemlive

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabel
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.mossy.mayhemlive.databinding.ActivityMainBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.random.Random

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var textToSpeech: TextToSpeech

    private val imageLabeler = ImageLabeling.getClient(
        ImageLabelerOptions.Builder()
            .setConfidenceThreshold(0.58f)
            .build()
    )

    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var lastVideoFile: File? = null
    private var soundEnabled = true
    private var ttsReady = false
    private var lastAnalysisAt = 0L
    private var lastSpokenAt = 0L
    private var lastLabel = ""

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
        textToSpeech = TextToSpeech(this, this)

        binding.startButton.setOnClickListener { requestPermissionsAndStart() }
        binding.recordButton.setOnClickListener { toggleRecording() }
        binding.flipButton.setOnClickListener { flipCamera() }
        binding.muteButton.setOnClickListener { toggleSound() }
        binding.shareButton.setOnClickListener { shareLastVideo() }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = textToSpeech.setLanguage(Locale("en", "AU"))
            ttsReady = result != TextToSpeech.LANG_MISSING_DATA &&
                result != TextToSpeech.LANG_NOT_SUPPORTED
            textToSpeech.setSpeechRate(1.02f)
            textToSpeech.setPitch(0.95f)
        }
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
        startCamera()
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
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastAnalysisAt < 1100L) {
                            imageProxy.close()
                            return@setAnalyzer
                        }
                        lastAnalysisAt = now

                        val mediaImage = imageProxy.image
                        if (mediaImage == null) {
                            imageProxy.close()
                            return@setAnalyzer
                        }

                        val inputImage = InputImage.fromMediaImage(
                            mediaImage,
                            imageProxy.imageInfo.rotationDegrees
                        )

                        imageLabeler.process(inputImage)
                            .addOnSuccessListener { labels -> handleLabels(labels) }
                            .addOnFailureListener {
                                runOnUiThread {
                                    binding.statusText.text = "Still looking…"
                                }
                            }
                            .addOnCompleteListener { imageProxy.close() }
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
                binding.statusText.text = "Camera live — point it at anything"
            } catch (error: Exception) {
                binding.statusText.text = "Camera could not start"
                Toast.makeText(this, error.message ?: "Camera error", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleLabels(labels: List<ImageLabel>) {
        val useful = labels
            .filter { it.confidence >= 0.58f }
            .sortedByDescending { it.confidence }
            .take(3)

        val top = useful.firstOrNull()?.text?.trim().orEmpty()
        val confidence = useful.firstOrNull()?.confidence ?: 0f

        runOnUiThread {
            binding.statusText.text = if (top.isBlank()) {
                "Mossy sees something mysterious"
            } else {
                "Seeing: $top · ${(confidence * 100).toInt()}%"
            }
        }

        val now = SystemClock.elapsedRealtime()
        val labelChanged = top.isNotBlank() && !top.equals(lastLabel, ignoreCase = true)
        if (now - lastSpokenAt < 6500L && !labelChanged) return

        lastSpokenAt = now
        lastLabel = top
        val commentary = ComedyEngine.comment(top, useful.map { it.text })

        runOnUiThread {
            binding.commentaryText.text = commentary
            if (soundEnabled && ttsReady) {
                textToSpeech.speak(
                    commentary,
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    "mossy-${System.currentTimeMillis()}"
                )
            }
        }
    }

    private fun toggleRecording() {
        activeRecording?.let {
            it.stop()
            activeRecording = null
            binding.recordButton.setText(R.string.record)
            binding.statusText.text = "Saving your masterpiece…"
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
                    binding.statusText.text = "Recording — do something ridiculous"
                }

                is VideoRecordEvent.Finalize -> {
                    activeRecording = null
                    binding.recordButton.setText(R.string.record)
                    if (!event.hasError()) {
                        lastVideoFile = file
                        binding.shareButton.isEnabled = true
                        binding.statusText.text = "Saved — tap SHARE"
                        binding.commentaryText.text = "That belongs on the internet. Probably."
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
        if (!soundEnabled) textToSpeech.stop()
        Toast.makeText(
            this,
            if (soundEnabled) "Mossy can talk again." else "Mossy is now silently judging.",
            Toast.LENGTH_SHORT
        ).show()
    }

    override fun onDestroy() {
        activeRecording?.stop()
        imageLabeler.close()
        textToSpeech.stop()
        textToSpeech.shutdown()
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}

private object ComedyEngine {
    private val generic = listOf(
        "We have visual contact. Nobody panic, especially whatever that thing is.",
        "Nature is healing. Or making a very questionable decision.",
        "Look at that absolute professional pretending this was planned.",
        "Breaking news: something is happening, and confidence is dangerously high.",
        "A bold performance from an object with no apparent qualifications."
    )

    fun comment(primaryLabel: String, allLabels: List<String>): String {
        val label = primaryLabel.lowercase(Locale.getDefault())
        val alternatives = allLabels.joinToString(" ").lowercase(Locale.getDefault())
        val scene = "$label $alternatives"

        val lines = when {
            scene.containsAny("dog", "puppy", "canine") -> listOf(
                "Here we see the household manager conducting another surprise inspection.",
                "That dog has the confidence of someone who has never paid a bill.",
                "A magnificent creature, powered almost entirely by snacks and suspicion."
            )

            scene.containsAny("cat", "kitten", "feline") -> listOf(
                "The cat has reviewed your performance and will not be providing feedback.",
                "A tiny landlord appears, wondering why you are still on the property.",
                "Observe the cat: calm, elegant, and plotting something expensive."
            )

            scene.containsAny("bird", "duck", "chicken", "poultry") -> listOf(
                "A feathered supervisor has arrived, and frankly morale has improved.",
                "That bird is walking like it owns three investment properties.",
                "David Attenborough never warned us they would be this judgemental."
            )

            scene.containsAny("person", "people", "human", "face", "man", "woman") -> listOf(
                "A human has entered the scene, apparently unsupervised.",
                "Here we observe a person doing their best with the available information.",
                "Confidence: excellent. Plan: still loading."
            )

            scene.containsAny("food", "meal", "dish", "fruit", "vegetable", "bread") -> listOf(
                "This meal has ambition. Whether it has seasoning remains under investigation.",
                "A culinary event is underway. Emergency snacks remain on standby.",
                "The camera eats first, because apparently we live like this now."
            )

            scene.containsAny("car", "vehicle", "truck", "wheel", "motorcycle") -> listOf(
                "A vehicle appears, bravely converting money into mysterious noises.",
                "Four wheels, several opinions, and one dashboard light nobody wants to discuss.",
                "Engineering meets optimism. What could possibly go wrong?"
            )

            scene.containsAny("plant", "flower", "tree", "garden", "grass") -> listOf(
                "The plant is thriving quietly, which feels unnecessarily smug.",
                "A botanical success story, achieved without one motivational podcast.",
                "Photosynthesis: still the hardest worker in the yard."
            )

            scene.containsAny("furniture", "chair", "table", "couch", "room", "house") -> listOf(
                "Interior design has occurred. The investigation continues.",
                "That furniture has seen things and signed a confidentiality agreement.",
                "A room full of character, most of it refusing to pay rent."
            )

            primaryLabel.isNotBlank() -> listOf(
                "The system identifies $primaryLabel. Mossy identifies an opportunity for chaos.",
                "$primaryLabel detected. The documentary budget has immediately doubled.",
                "And here we have $primaryLabel, giving absolutely everything for the camera."
            )

            else -> generic
        }

        return lines[Random.nextInt(lines.size)]
    }

    private fun String.containsAny(vararg words: String): Boolean = words.any { contains(it) }
}
