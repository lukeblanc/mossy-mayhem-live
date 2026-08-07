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
import java.util.ArrayDeque
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
            .setConfidenceThreshold(0.50f)
            .build()
    )

    private val sceneMemory = SceneMemory(maxFrames = 7)
    private val recentCommentary = ArrayDeque<String>()

    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var lastVideoFile: File? = null
    private var soundEnabled = true
    private var ttsReady = false
    private var hasIntroducedMossy = false
    private var lastAnalysisAt = 0L
    private var lastSpokenAt = 0L
    private var lastSceneSignature = ""

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
        if (status != TextToSpeech.SUCCESS) return

        val result = textToSpeech.setLanguage(Locale("en", "AU"))
        ttsReady = result != TextToSpeech.LANG_MISSING_DATA &&
            result != TextToSpeech.LANG_NOT_SUPPORTED

        if (ttsReady) {
            val australianVoices = textToSpeech.voices
                ?.filter { voice ->
                    voice.locale.language == "en" &&
                        voice.locale.country == "AU" &&
                        !voice.isNetworkConnectionRequired
                }
                .orEmpty()

            val preferredVoice = australianVoices.maxByOrNull { voice ->
                val name = voice.name.lowercase(Locale.US)
                when {
                    name.contains("male") -> 4
                    name.contains("australia") || name.contains("australian") -> 3
                    name.contains("au") -> 2
                    else -> 1
                }
            }

            preferredVoice?.let { textToSpeech.voice = it }
            textToSpeech.setSpeechRate(0.91f)
            textToSpeech.setPitch(0.84f)
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
        binding.commentaryText.text = "Righto. Point me at something and let's see what sort of nonsense is going on."
        startCamera()

        if (!hasIntroducedMossy) {
            hasIntroducedMossy = true
            speakCharacter("Righto... camera's live. Let's see what sort of nonsense we're dealing with.")
        }
    }

    private fun startCamera() {
        sceneMemory.clear()
        lastSceneSignature = ""

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
                        if (now - lastAnalysisAt < 950L) {
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
                                    binding.statusText.text = "Mossy's still having a squiz…"
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
                binding.statusText.text = "Camera live — Mossy's sizing up the scene"
            } catch (error: Exception) {
                binding.statusText.text = "Camera could not start"
                Toast.makeText(this, error.message ?: "Camera error", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleLabels(labels: List<ImageLabel>) {
        val useful = labels
            .filter { it.confidence >= 0.50f }
            .sortedByDescending { it.confidence }
            .take(6)

        sceneMemory.add(useful)
        val scene = sceneMemory.snapshot()

        runOnUiThread {
            binding.statusText.text = when {
                scene.frameCount < 3 -> "Mossy's sizing up the whole scene…"
                scene.topLabels.isEmpty() -> "Mossy's looking… something dodgy is happening"
                else -> "Mossy sees: ${scene.topLabels.take(3).joinToString(" • ") { it.text.lowercase(Locale.US) }}"
            }
        }

        if (scene.frameCount < 3) return

        val now = SystemClock.elapsedRealtime()
        val sceneChanged = scene.signature.isNotBlank() && scene.signature != lastSceneSignature
        val minimumGap = if (sceneChanged) 4600L else 8500L
        if (now - lastSpokenAt < minimumGap) return

        val commentary = MossyComedy.comment(scene, recentCommentary.toSet())
        if (commentary.isBlank()) return

        lastSpokenAt = now
        lastSceneSignature = scene.signature
        rememberCommentary(commentary)

        runOnUiThread {
            binding.commentaryText.text = commentary
            speakCharacter(commentary)
        }
    }

    private fun rememberCommentary(line: String) {
        recentCommentary.addLast(line)
        while (recentCommentary.size > 7) {
            recentCommentary.removeFirst()
        }
    }

    private fun speakCharacter(line: String) {
        if (!soundEnabled || !ttsReady) return
        textToSpeech.speak(
            line,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "mossy-${System.currentTimeMillis()}"
        )
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
                    binding.statusText.text = "Recording — give Mossy something to work with"
                }

                is VideoRecordEvent.Finalize -> {
                    activeRecording = null
                    binding.recordButton.setText(R.string.record)
                    if (!event.hasError()) {
                        lastVideoFile = file
                        binding.shareButton.isEnabled = true
                        binding.statusText.text = "Saved — tap SHARE"
                        binding.commentaryText.text = "There it is. Evidence that none of this was properly supervised."
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
            if (soundEnabled) "Mossy's back on the mic." else "Mossy's silently judging now.",
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

private data class SceneLabel(
    val text: String,
    val key: String,
    val score: Float,
    val category: String
)

private data class SceneSnapshot(
    val frameCount: Int,
    val topLabels: List<SceneLabel>,
    val categories: Set<String>,
    val signature: String
) {
    fun has(vararg wanted: String): Boolean = wanted.any { it in categories }
}

private class SceneMemory(private val maxFrames: Int) {
    private data class Observation(val text: String, val key: String, val confidence: Float)

    private val frames = ArrayDeque<List<Observation>>()

    fun clear() = frames.clear()

    fun add(labels: List<ImageLabel>) {
        val frame = labels.mapNotNull { label ->
            val text = label.text.trim()
            if (text.isBlank()) null else Observation(
                text = text,
                key = text.lowercase(Locale.US),
                confidence = label.confidence
            )
        }

        frames.addLast(frame)
        while (frames.size > maxFrames) frames.removeFirst()
    }

    fun snapshot(): SceneSnapshot {
        if (frames.isEmpty()) return SceneSnapshot(0, emptyList(), emptySet(), "")

        val scores = mutableMapOf<String, Float>()
        val displayNames = mutableMapOf<String, String>()
        val frameList = frames.toList()

        frameList.forEachIndexed { index, frame ->
            val recency = 0.72f + (0.28f * ((index + 1).toFloat() / frameList.size.toFloat()))
            frame.forEach { observation ->
                scores[observation.key] = (scores[observation.key] ?: 0f) +
                    (observation.confidence * recency)
                displayNames[observation.key] = observation.text
            }
        }

        val top = scores.entries
            .sortedByDescending { it.value }
            .take(8)
            .map { (key, score) ->
                SceneLabel(
                    text = displayNames[key] ?: key,
                    key = key,
                    score = score,
                    category = SceneTaxonomy.categoryFor(key)
                )
            }

        val categories = top.map { it.category }.filter { it != "other" }.toSet()
        val meaningful = top.filterNot { it.key in SceneTaxonomy.lowValueLabels }.take(4)
        val signature = meaningful.joinToString("|") { "${it.category}:${it.key}" }

        return SceneSnapshot(
            frameCount = frames.size,
            topLabels = top,
            categories = categories,
            signature = signature
        )
    }
}

private object SceneTaxonomy {
    val lowValueLabels = setOf(
        "material", "pattern", "font", "rectangle", "circle", "line", "texture", "design"
    )

    fun categoryFor(label: String): String = when {
        label.containsAny("dog", "puppy", "canine") -> "dog"
        label.containsAny("cat", "kitten", "feline") -> "cat"
        label.containsAny("bird", "duck", "chicken", "poultry", "goose", "parrot") -> "bird"
        label.containsAny("horse", "cow", "sheep", "goat", "kangaroo", "animal", "wildlife") -> "animal"
        label.containsAny("person", "people", "human", "face", "man", "woman", "child", "boy", "girl") -> "person"
        label.containsAny("car", "vehicle", "truck", "ute", "motorcycle", "wheel", "tractor") -> "vehicle"
        label.containsAny("food", "meal", "dish", "fruit", "vegetable", "bread", "meat", "drink", "coffee") -> "food"
        label.containsAny("tool", "hammer", "drill", "saw", "equipment", "machine") -> "tool"
        label.containsAny("chair", "table", "couch", "sofa", "furniture", "bed") -> "furniture"
        label.containsAny("phone", "computer", "laptop", "screen", "television", "electronics") -> "tech"
        label.containsAny("water", "river", "lake", "ocean", "sea", "pool") -> "water"
        label.containsAny("sand", "beach", "shore", "coast") -> "beach"
        label.containsAny("plant", "flower", "tree", "garden", "grass", "lawn", "vegetation", "forest", "field") -> "outdoor"
        label.containsAny("sky", "cloud", "landscape", "outdoor", "nature", "yard", "farm") -> "outdoor"
        label.containsAny("room", "house", "home", "kitchen", "wall", "floor", "ceiling", "interior") -> "indoor"
        else -> "other"
    }

    private fun String.containsAny(vararg words: String): Boolean = words.any { contains(it) }
}

private object MossyComedy {
    private val generic = listOf(
        "Righto... I've studied the evidence, and somehow this has become my problem.",
        "Well, something's definitely happening here. Whether it should be is a completely different question.",
        "Mossy's official report: confidence is high, planning appears to be optional.",
        "Ahh yes. Another perfectly normal scene that immediately gets stranger the longer you look at it.",
        "I've got eyes on the situation. I don't have answers, but I've definitely got concerns."
    )

    fun comment(scene: SceneSnapshot, recentlyUsed: Set<String>): String {
        val candidates = when {
            scene.has("person") && scene.has("dog") -> listOf(
                "Righto, we've got a human and a dog together. One of them knows exactly what's going on, and I'm not backing the human.",
                "Here we see the classic partnership: one person pretending to be in charge, and one dog allowing the fantasy to continue.",
                "A human has arrived with their canine supervisor. Performance review could get ugly."
            )

            scene.has("dog") && scene.has("outdoor") -> listOf(
                "Ahh beautiful... dog in the great outdoors, conducting a full security inspection of absolutely everything.",
                "Here we have a dog patrolling the territory like the mortgage is somehow in its name.",
                "The yard looks peaceful, but the dog has clearly received intelligence we haven't been briefed on."
            )

            scene.has("person") && scene.has("tool") -> listOf(
                "Righto, we've got a human, some tools, and enough confidence to turn a five-minute job into a three-day project.",
                "A person with tools. Excellent. Nothing has gone wrong yet, which is exactly when you should start worrying.",
                "Observe the weekend engineer: no visible plan, several tools, and absolutely magnificent self-belief."
            )

            scene.has("person") && scene.has("vehicle") -> listOf(
                "We've got a human near a vehicle, which is how most expensive noises begin.",
                "A person and a vehicle have entered the same scene. Somewhere, a warning light is preparing for duty.",
                "Classic motoring documentary: one machine, one human, and a financial decision waiting to happen."
            )

            scene.has("person") && scene.has("food") -> listOf(
                "Righto, there's a human and food in the same frame. We are seconds away from somebody claiming they weren't that hungry.",
                "A culinary situation is developing. The person looks confident; the food has declined to comment.",
                "Here we see humanity's oldest ritual: standing near food and pretending patience is an option."
            )

            scene.has("bird") && scene.has("outdoor") -> listOf(
                "A feathered local has entered the outdoor broadcast and is already acting like it owns the joint.",
                "Here we observe the bird in its natural habitat: busy, suspicious, and completely unimpressed with the camera crew.",
                "The countryside is calm, the bird is alert, and apparently I'm the only one taking this documentary seriously."
            )

            scene.has("vehicle") && scene.has("outdoor") -> listOf(
                "Righto, vehicle in the wild. A magnificent machine bravely converting fuel into noise and questionable confidence.",
                "There she is out in the open: wheels, machinery, and at least one future conversation about maintenance.",
                "A vehicle has appeared in its natural environment, where dashboard lights are traditionally ignored until Monday."
            )

            scene.has("water") && scene.has("beach") -> listOf(
                "Look at this... sand, water, open air. Bloody paradise, right up until somebody drops a phone in it.",
                "Beautiful coastal scene. Nature has supplied the view; humans will be along shortly with plastic chairs and poor decisions.",
                "Water, sand, serenity... give it five minutes and someone will lose a thong."
            )

            scene.has("dog") -> listOf(
                "There it is: four legs, zero bills, and the confidence of senior management.",
                "Mossy's got visual on the dog. Clearly busy with important work nobody else has clearance to understand.",
                "A magnificent dog has entered frame, powered almost entirely by snacks, loyalty, and private investigations."
            )

            scene.has("cat") -> listOf(
                "The cat has reviewed the situation and, unsurprisingly, found everyone else disappointing.",
                "A tiny landlord has appeared to check why you're still occupying the premises.",
                "Observe the cat: calm, elegant, and already planning something that will happen at three in the morning."
            )

            scene.has("bird") -> listOf(
                "The bird has arrived with the body language of someone who knows exactly where you left the snacks.",
                "A feathered supervisor has entered frame. Productivity has not improved, but judgement levels are excellent.",
                "That bird is carrying itself like it owns three properties and has a meeting at four."
            )

            scene.has("animal") -> listOf(
                "Wildlife on screen. Everybody behave naturally, which of course means nobody will.",
                "We've got an animal in frame, and already it appears better organised than the production team.",
                "Nature documentary mode engaged. The creature is majestic; the camera operator remains under investigation."
            )

            scene.has("person") -> listOf(
                "A human has entered the scene, apparently unsupervised. We'll continue monitoring the situation.",
                "Here we observe a person doing their best with the information currently available. Brave stuff.",
                "Human detected—nah, forget that. Human observed in the wild, confidence excellent, plan still loading."
            )

            scene.has("food") -> listOf(
                "We've got food in frame. Strong presentation, big ambitions, seasoning status still classified.",
                "A culinary event is underway. Emergency snacks remain on standby just in case.",
                "The camera eats first, because apparently that's the civilisation we've built."
            )

            scene.has("vehicle") -> listOf(
                "A vehicle appears, bravely turning money into movement and occasionally mysterious noises.",
                "Four wheels, several thousand moving parts, and one dashboard light nobody wants to discuss.",
                "Engineering meets optimism. Beautiful. What could possibly go wrong?"
            )

            scene.has("furniture") && scene.has("indoor") -> listOf(
                "Interior scene. Furniture is in position, dignity is optional, and Mossy has questions about the decorating committee.",
                "A room full of furniture and character. Some of it may even belong where it is.",
                "Home sweet home: chairs, tables, and enough evidence to prove people definitely live here."
            )

            scene.has("outdoor") -> listOf(
                "Ahh, the great outdoors. Fresh air, open space, and absolutely no guarantee anyone knows what they're doing.",
                "Mossy's field report: nature looks calm, which usually means the humans haven't arrived yet.",
                "Beautiful outdoor scene. Everything appears peaceful, so naturally I'm suspicious."
            )

            scene.has("indoor") -> listOf(
                "We're indoors now. Walls, floor, civilisation... allegedly.",
                "Interior operations are underway. The room looks innocent, but I've seen enough to stay alert.",
                "Mossy's inside report: shelter confirmed, organisation still being assessed."
            )

            else -> generic
        }

        val fresh = candidates.filterNot { it in recentlyUsed }
        val pool = if (fresh.isNotEmpty()) fresh else candidates
        return pool[Random.nextInt(pool.size)]
    }
}

private fun String.containsAny(vararg words: String): Boolean = words.any { contains(it) }
