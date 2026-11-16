package com.zideck.foodregest.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageProxy
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.zideck.foodregest.settings.AppSettings
import java.io.File
import java.util.concurrent.Executors

@Composable
fun CaptureScreen(state: UiState, settings: AppSettings, vm: MainViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var hasCamera by remember { mutableStateOf(granted(context)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCamera = it }
    LaunchedEffect(Unit) { if (!hasCamera) permission.launch(Manifest.permission.CAMERA) }
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MainViewModel.MAX_PHOTOS)
    ) { uris -> uris.forEach(vm::addPhoto) }

    Column(modifier.fillMaxSize()) {
        ModelStatus(state.model, settings.developerInfo)
        Box(
            Modifier.fillMaxWidth().aspectRatio(3f / 4f).padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(16.dp)).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            if (hasCamera) {
                CameraPreview(
                    onPhoto = vm::addPhoto,
                    onError = { vm.showMessage("Photo failed: $it") },
                    onFrame = if (settings.liveEstimate) vm::analyzeFrame else null,
                    onLiveStopped = vm::liveStopped,
                )
                if (settings.liveEstimate) LiveOverlay(state.live, Modifier.align(Alignment.TopStart))
            }
            else Text(
                "Camera permission is needed to take photos.\nYou can still pick photos from the gallery.",
                color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp),
            )
        }
        Text(
            "Take 1–${MainViewModel.MAX_PHOTOS} photos of one meal, e.g. from above and from the side.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyRow(
            Modifier.fillMaxWidth().height(84.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        ) {
            items(state.photos, key = { it.id }) { photo ->
                Thumbnail(photo, settings.developerInfo, onRemove = { vm.removePhoto(photo.id) })
            }
        }
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalButton(onClick = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Text("Gallery") }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { vm.showResult(true) },
                enabled = state.estimate != null && !state.pending,
            ) {
                if (state.pending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("Estimate (${state.ready.size})")
            }
        }
    }
}

@Composable
private fun CameraPreview(
    onPhoto: (File) -> Unit,
    onError: (String) -> Unit,
    onFrame: ((Bitmap) -> Unit)?,
    onLiveStopped: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_CAPTURE)
            imageCaptureMode = ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
        }
    }
    DisposableEffect(lifecycleOwner) {
        controller.bindToLifecycle(lifecycleOwner)
        onDispose { controller.unbind() }
    }
    // developer tool: run the model on every preview frame (CameraX drops frames while the model is busy)
    val live = onFrame != null
    val currentOnFrame by rememberUpdatedState(onFrame)
    DisposableEffect(live) {
        if (live) {
            val executor = Executors.newSingleThreadExecutor()
            controller.imageAnalysisOutputImageFormat = ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
            controller.imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
            controller.setImageAnalysisAnalyzer(executor) { image ->
                try {
                    currentOnFrame?.invoke(image.toUprightBitmap())
                } finally {
                    image.close()
                }
            }
            controller.setEnabledUseCases(CameraController.IMAGE_CAPTURE or CameraController.IMAGE_ANALYSIS)
            onDispose {
                controller.clearImageAnalysisAnalyzer()
                controller.setEnabledUseCases(CameraController.IMAGE_CAPTURE)
                executor.shutdown()
                onLiveStopped()
            }
        } else onDispose { }
    }
    var busy by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    this.controller = controller
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier.align(Alignment.BottomCenter).padding(16.dp).size(72.dp)
                .border(4.dp, Color.White, CircleShape).padding(8.dp).clip(CircleShape)
                .background(if (busy) Color.Gray else Color.White)
                .clickable(enabled = !busy) {
                    busy = true
                    val file = File.createTempFile("meal_", ".jpg", context.cacheDir)
                    controller.takePicture(
                        ImageCapture.OutputFileOptions.Builder(file).build(),
                        ContextCompat.getMainExecutor(context),
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                busy = false
                                onPhoto(file)
                            }

                            override fun onError(exception: ImageCaptureException) {
                                busy = false
                                file.delete()
                                onError(exception.message ?: "capture failed")
                            }
                        },
                    )
                },
        )
    }
}

@Composable
private fun Thumbnail(photo: MealPhoto, developerInfo: Boolean, onRemove: () -> Unit) {
    Box(Modifier.size(84.dp)) {
        Image(
            photo.thumbnail.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)),
        )
        val p = photo.prediction
        Text(
            when {
                photo.error != null -> "error"
                p == null -> "…"
                developerInfo -> "${p.massG.toInt()} g\n${p.preprocessMs + p.inferenceMs} ms"
                else -> "${p.massG.toInt()} g"
            },
            color = Color.White, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.BottomStart).padding(4.dp)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
        )
        Icon(
            Icons.Filled.Close, contentDescription = "Remove photo", tint = Color.White,
            modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(22.dp)
                .background(Color.Black.copy(alpha = 0.6f), CircleShape).clickable(onClick = onRemove).padding(3.dp),
        )
    }
}

@Composable
private fun LiveOverlay(live: LiveStats?, modifier: Modifier = Modifier) {
    Text(
        if (live == null) "live: waiting for frames…"
        else "live: ${live.massG.toInt()} g · ${live.kcal.toInt()} kcal\n" +
            "${live.frameMs} ms/frame (model ${live.inferenceMs} ms) · %.1f fps".format(live.fps),
        color = Color.White, style = MaterialTheme.typography.labelMedium,
        modifier = modifier.padding(8.dp).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** CameraX frame (RGBA) -> bitmap rotated to how the user sees the preview. */
private fun ImageProxy.toUprightBitmap(): Bitmap {
    val bitmap = toBitmap()
    val degrees = imageInfo.rotationDegrees
    if (degrees == 0) return bitmap
    val m = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true).also { bitmap.recycle() }
}

@Composable
fun ModelStatus(model: ModelState, developerInfo: Boolean) {
    val text = when (model) {
        ModelState.Loading -> "Loading model…"
        is ModelState.Ready -> if (developerInfo) "${model.info.summary} · loaded in ${model.info.loadMs} ms" else return
        is ModelState.Failed -> "Model failed to load: ${model.message}"
    }
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = if (model is ModelState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

private fun granted(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
