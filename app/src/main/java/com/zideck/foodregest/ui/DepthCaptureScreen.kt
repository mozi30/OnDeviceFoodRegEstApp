package com.zideck.foodregest.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zideck.foodregest.depth.ArDepthCapture

/**
 * Depth benchmark capture: ARCore camera preview, live distance to the table, and a button
 * that saves photo + ARCore depth + table plane + intrinsics (see [ArDepthCapture]).
 */
@Composable
fun DepthCaptureScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = context as Activity
    var hasCamera by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCamera = it }
    LaunchedEffect(Unit) { if (!hasCamera) permission.launch(Manifest.permission.CAMERA) }
    if (!hasCamera) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Camera permission needed") }
        return
    }

    val capture = remember { ArDepthCapture(context.applicationContext) }
    val status by capture.status.collectAsStateWithLifecycle()
    val glView = remember {
        GLSurfaceView(context).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(capture)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        fun start() { capture.resume(activity); glView.onResume() }
        fun stop() { glView.onPause(); capture.pause() }
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> start()
                Lifecycle.Event.ON_PAUSE -> stop()
                else -> {}
            }
        }
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) start()
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            stop()
            capture.close()
        }
    }
    var confirmDelete by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView({ glView }, Modifier.fillMaxSize())
            // aim mark: the table distance is measured along the ray through this point
            Box(Modifier.align(Alignment.Center).padding(0.dp)) {
                Text("+", color = Color.White, style = MaterialTheme.typography.headlineMedium)
            }
            Column(
                Modifier.align(Alignment.TopStart).padding(8.dp)
                    .then(Modifier),
            ) {
                val cm = { m: Float? -> m?.let { "%.1f cm".format(it * 100) } ?: "-" }
                Overlay("Tracking: ${status.tracking}" + if (!status.depthSupported) " | depth not supported" else "")
                Overlay("Table planes: ${status.planes}")
                Overlay("Distance to table (centre): ${cm(status.tableDistanceM)}")
                Overlay("Camera height above table: ${cm(status.heightM)}")
                Overlay("ARCore depth at centre: ${cm(status.depthCenterM)}")
            }
        }
        status.message?.let {
            Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
            LaunchedEffect(it) { kotlinx.coroutines.delay(4000); capture.messageShown() }
        }
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = { confirmDelete = true }, enabled = status.saved > 0) { Text("Delete") }
            Button(onClick = capture::requestCapture, enabled = status.ready, shape = CircleShape) {
                Text("Capture (${status.saved})")
            }
            OutlinedButton(onClick = capture::exportZip, enabled = status.saved > 0) { Text("Export") }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${status.saved} captures?") },
            text = { Text("Export them first; this cannot be undone.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; capture.deleteAll() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Overlay(text: String) {
    Text(
        text,
        color = Color.White,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(vertical = 1.dp),
    )
}
