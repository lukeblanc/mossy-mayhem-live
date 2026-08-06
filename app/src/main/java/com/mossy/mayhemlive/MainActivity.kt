package com.mossy.mayhemlive

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.PendingRecording
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MossyMayhemApp() }
    }
}

private class CameraRecorder {
    var videoCapture: VideoCapture<Recorder>? = null
    var activeRecording: Recording? = null
}

@Composable
private fun MossyMayhemApp() {
    val context = LocalContext.current
    val permissions = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    var granted by remember {
        mutableStateOf(permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED })
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        granted = result.values.all { it }
    }

    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(permissions)
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            if (granted) LiveCameraScreen() else PermissionScreen { launcher.launch(permissions) }
        }
    }
}

@Composable
private fun PermissionScreen(onRequest: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("MOSSY MAYHEM LIVE", color = Color.White, fontSize = 28.sp)
        Text("Camera and microphone access are needed to film the mayhem.", color = Color.LightGray, modifier = Modifier.padding(vertical = 20.dp))
        Button(onClick = onRequest) { Text("Allow camera") }
    }
}

@Composable
private fun LiveCameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val recorder = remember { CameraRecorder() }
    var isRecording by remember { mutableStateOf(false) }
    var commentaryOn by remember { mutableStateOf(true) }
    var caption by remember { mutableStateOf("Point the camera at something. Mossy is watching…") }

    val jokes = remember {
        listOf(
            "Crikey, look at this magnificent specimen pretending it knows what it's doing.",
            "Breaking news: absolutely nothing sensible is happening here.",
            "Observe the wild creature in its natural habitat, avoiding all responsibility.",
            "That deserves a round of applause and possibly a safety inspection.",
            "Mossy has reviewed the situation and officially declared it hilarious.",
            "Experts remain baffled, but the camera crew is loving it.",
            "There it is: confidence well ahead of actual ability."
        )
    }

    val tts = remember {
        TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // Initialisation callback may run before assignment is complete; language is set below when used.
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            recorder.activeRecording?.stop()
            tts.stop()
            tts.shutdown()
        }
    }

    LaunchedEffect(commentaryOn) {
        var index = 0
        while (commentaryOn) {
            delay(5500)
            val line = jokes[index % jokes.size]
            index++
            caption = line
            tts.language = Locale("en", "AU")
            tts.speak(line, TextToSpeech.QUEUE_FLUSH, null, "mossy-$index")
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    val future = ProcessCameraProvider.getInstance(ctx)
                    future.addListener({
                        val provider = future.get()
                        val preview = Preview.Builder().build().also { it.setSurfaceProvider(surfaceProvider) }
                        val cameraRecorder = Recorder.Builder()
                            .setQualitySelector(QualitySelector.from(Quality.HD))
                            .build()
                        val capture = VideoCapture.withOutput(cameraRecorder)
                        recorder.videoCapture = capture
                        provider.unbindAll()
                        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                    }, ContextCompat.getMainExecutor(ctx))
                }
            }
        )

        Column(
            modifier = Modifier.fillMaxSize().padding(18.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "MOSSY MAYHEM LIVE",
                color = Color.White,
                fontSize = 22.sp,
                modifier = Modifier.background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(12.dp)).padding(12.dp)
            )

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    caption,
                    color = Color.White,
                    fontSize = 18.sp,
                    modifier = Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(14.dp)).padding(14.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)
                ) {
                    Button(onClick = { commentaryOn = !commentaryOn }) {
                        Text(if (commentaryOn) "Mossy ON" else "Mossy OFF")
                    }
                    Button(
                        colors = ButtonDefaults.buttonColors(containerColor = if (isRecording) Color.DarkGray else Color.Red),
                        onClick = {
                            if (isRecording) {
                                recorder.activeRecording?.stop()
                                recorder.activeRecording = null
                                isRecording = false
                                return@Button
                            }
                            val capture = recorder.videoCapture ?: return@Button
                            val values = ContentValues().apply {
                                put(MediaStore.MediaColumns.DISPLAY_NAME, "MossyMayhem_${System.currentTimeMillis()}")
                                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Mossy Mayhem Live")
                            }
                            val output = MediaStoreOutputOptions.Builder(
                                context.contentResolver,
                                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                            ).setContentValues(values).build()
                            var pending: PendingRecording = capture.output.prepareRecording(context, output)
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                pending = pending.withAudioEnabled()
                            }
                            recorder.activeRecording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
                                when (event) {
                                    is VideoRecordEvent.Start -> isRecording = true
                                    is VideoRecordEvent.Finalize -> {
                                        isRecording = false
                                        val message = if (event.hasError()) "Recording failed" else "Saved to Movies/Mossy Mayhem Live"
                                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        }
                    ) { Text(if (isRecording) "STOP" else "RECORD") }
                }
            }
        }
    }
}
