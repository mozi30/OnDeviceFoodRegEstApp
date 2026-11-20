package com.zideck.foodregest.depth

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Build
import android.provider.MediaStore
import android.view.Surface
import com.google.ar.core.ArCoreApk
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Live status shown under the AR preview. */
data class ArStatus(
    val ready: Boolean = false,
    val tracking: String = "starting",
    val depthSupported: Boolean = false,
    val planes: Int = 0,
    val tableDistanceM: Float? = null, // camera to the table along the ray through the screen centre
    val heightM: Float? = null,        // camera height above that table plane
    val depthCenterM: Float? = null,   // ARCore depth at the image centre
    val saved: Int = 0,
    val message: String? = null,
)

/**
 * ARCore capture for the depth benchmark: on each capture it stores the camera image (JPEG),
 * ARCore's smoothed depth and raw depth (16-bit PGM, millimetres), the raw-depth confidence
 * (8-bit PGM) and a JSON file with intrinsics, camera pose and the detected table planes.
 *
 * All images are in the camera sensor orientation (landscape); the intrinsics belong to the
 * camera image, the depth images cover the same field of view at a lower resolution.
 * Captures go to the app's external files dir; [exportZip] copies them to Download/.
 */
class ArDepthCapture(private val context: Context) : GLSurfaceView.Renderer {
    private val _status = MutableStateFlow(ArStatus(saved = captureDirs().size))
    val status: StateFlow<ArStatus> = _status

    private var session: Session? = null
    private var installRequested = false
    private val captureRequested = AtomicBoolean(false)
    private val io = Executors.newSingleThreadExecutor()
    private val background = Background()
    private var viewW = 1
    private var viewH = 1
    private var frameCount = 0

    val baseDir: File get() = File(context.getExternalFilesDir(null), "depth_capture").apply { mkdirs() }

    private fun captureDirs(): List<File> = baseDir.listFiles { f -> f.isDirectory }?.sortedBy { it.name } ?: emptyList()

    // ------------------------------------------------------------------ lifecycle (main thread)
    /** Creates the session on first call (may ask to install Google Play Services for AR). */
    fun resume(activity: Activity) {
        try {
            if (session == null) {
                when (ArCoreApk.getInstance().requestInstall(activity, !installRequested)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> { installRequested = true; return }
                    else -> {}
                }
                val s = Session(context)
                // largest CPU camera image: the photo that is saved
                val configs = s.getSupportedCameraConfigs(CameraConfigFilter(s))
                configs.maxByOrNull { it.imageSize.width * it.imageSize.height }?.let { s.cameraConfig = it }
                val depthOk = s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
                s.configure(Config(s).apply {
                    depthMode = if (depthOk) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
                    planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                    focusMode = Config.FocusMode.AUTO
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                })
                session = s
                _status.update { it.copy(depthSupported = depthOk) }
            }
            session?.resume()
            _status.update { it.copy(ready = true, message = null) }
        } catch (e: Exception) {
            session = null
            _status.update { it.copy(ready = false, message = "ARCore not available: ${e.javaClass.simpleName} ${e.message ?: ""}") }
        }
    }

    fun pause() {
        session?.pause()
    }

    fun close() {
        session?.close()
        session = null
        io.shutdown()
    }

    fun requestCapture() = captureRequested.set(true)

    fun messageShown() = _status.update { it.copy(message = null) }

    // ------------------------------------------------------------------ GL thread
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        background.create()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewW = width
        viewH = height
        session?.setDisplayGeometry(Surface.ROTATION_0, width, height) // the activity is portrait only
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val s = session ?: return
        try {
            s.setCameraTextureName(background.texture)
            s.setDisplayGeometry(Surface.ROTATION_0, viewW, viewH)
            val frame = s.update()
            background.draw(frame)
            val camera = frame.camera
            frameCount++
            val hit = tableHit(frame)
            if (frameCount % 10 == 0) {
                val center = if (_status.value.depthSupported && camera.trackingState == TrackingState.TRACKING)
                    tryImage { frame.acquireDepthImage16Bits() }?.use { centerDepth(it) } else null
                val planes = s.getAllTrackables(Plane::class.java).count { it.trackingState == TrackingState.TRACKING && it.subsumedBy == null }
                _status.update {
                    it.copy(tracking = camera.trackingState.name.lowercase() +
                        (if (camera.trackingState != TrackingState.TRACKING) " (${camera.trackingFailureReason.name.lowercase()})" else ""),
                        planes = planes, tableDistanceM = hit?.first, heightM = hit?.second, depthCenterM = center)
                }
            }
            if (captureRequested.getAndSet(false)) {
                if (camera.trackingState != TrackingState.TRACKING) {
                    _status.update { it.copy(message = "Not tracking yet: move the phone slowly") }
                } else capture(s, frame)
            }
        } catch (e: Exception) {
            _status.update { it.copy(message = "AR error: ${e.javaClass.simpleName} ${e.message ?: ""}") }
        }
    }

    /** Distance to the upward-facing plane hit by the ray through the screen centre, and camera height above it. */
    private fun tableHit(frame: Frame): Pair<Float, Float>? {
        if (frame.camera.trackingState != TrackingState.TRACKING) return null
        for (h in frame.hitTest(viewW / 2f, viewH / 2f)) {
            val p = h.trackable as? Plane ?: continue
            if (p.type != Plane.Type.HORIZONTAL_UPWARD_FACING || !p.isPoseInPolygon(h.hitPose)) continue
            return h.distance to heightAbove(frame.camera.pose, p)
        }
        return null
    }

    private fun heightAbove(cam: Pose, plane: Plane): Float {
        val n = plane.centerPose.getTransformedAxis(1, 1f)
        val c = plane.centerPose
        return (cam.tx() - c.tx()) * n[0] + (cam.ty() - c.ty()) * n[1] + (cam.tz() - c.tz()) * n[2]
    }

    private fun capture(s: Session, frame: Frame) {
        val camera = frame.camera
        val id = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val rgb = tryImage { frame.acquireCameraImage() }?.use { yuvToJpeg(it) }
        if (rgb == null) {
            _status.update { it.copy(message = "No camera image yet, try again") }
            return
        }
        val depth = if (_status.value.depthSupported) tryImage { frame.acquireDepthImage16Bits() }?.use { pgm16(it) } else null
        val raw = if (_status.value.depthSupported) tryImage { frame.acquireRawDepthImage16Bits() }?.use { pgm16(it) } else null
        val conf = if (_status.value.depthSupported) tryImage { frame.acquireRawDepthConfidenceImage() }?.use { pgm8(it) } else null

        val meta = JSONObject()
        meta.put("id", id)
        meta.put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
        meta.put("timestamp_ns", frame.timestamp)
        meta.put("depth_units", "millimetres (uint16 PGM, 0 = no value)")
        meta.put("orientation", "all images in camera sensor orientation; screen is portrait")
        val k = camera.imageIntrinsics
        meta.put("image_intrinsics", JSONObject()
            .put("fx", k.focalLength[0].toDouble()).put("fy", k.focalLength[1].toDouble())
            .put("cx", k.principalPoint[0].toDouble()).put("cy", k.principalPoint[1].toDouble())
            .put("width", k.imageDimensions[0]).put("height", k.imageDimensions[1]))
        meta.put("camera_pose", poseJson(camera.pose)) // OpenGL convention: +X right, +Y up, -Z forward
        meta.put("display_oriented_pose", poseJson(camera.displayOrientedPose))
        val planes = JSONArray()
        for (p in s.getAllTrackables(Plane::class.java)) {
            if (p.trackingState != TrackingState.TRACKING || p.subsumedBy != null) continue
            val n = p.centerPose.getTransformedAxis(1, 1f)
            val poly = p.polygon.let { b -> JSONArray().apply { for (i in 0 until b.limit()) put(b.get(i).toDouble()) } }
            planes.put(JSONObject().put("type", p.type.name).put("center_pose", poseJson(p.centerPose))
                .put("normal", JSONArray(n.map { it.toDouble() })).put("extent_x", p.extentX.toDouble())
                .put("extent_z", p.extentZ.toDouble()).put("polygon_xz", poly)
                .put("camera_height_m", heightAbove(camera.pose, p).toDouble()))
        }
        meta.put("planes", planes)
        tableHit(frame)?.let { (d, h) -> meta.put("center_hit", JSONObject().put("distance_m", d.toDouble()).put("camera_height_m", h.toDouble())) }
        meta.put("files", JSONObject().put("rgb", "rgb.jpg")
            .put("depth", if (depth != null) "depth.pgm" else JSONObject.NULL)
            .put("raw_depth", if (raw != null) "raw_depth.pgm" else JSONObject.NULL)
            .put("raw_confidence", if (conf != null) "raw_confidence.pgm" else JSONObject.NULL))

        io.execute {
            try {
                val dir = File(baseDir, id).apply { mkdirs() }
                File(dir, "rgb.jpg").writeBytes(rgb)
                depth?.let { File(dir, "depth.pgm").writeBytes(it) }
                raw?.let { File(dir, "raw_depth.pgm").writeBytes(it) }
                conf?.let { File(dir, "raw_confidence.pgm").writeBytes(it) }
                File(dir, "meta.json").writeText(meta.toString(1))
                val missing = if (depth == null) " (no depth yet: move the phone a bit)" else ""
                _status.update { it.copy(saved = captureDirs().size, message = "Saved $id$missing") }
            } catch (e: Exception) {
                _status.update { it.copy(message = "Save failed: ${e.message}") }
            }
        }
    }

    /** Zips all captures into Download/ (visible over USB and to file managers / Drive). */
    fun exportZip() {
        io.execute {
            try {
                val dirs = captureDirs()
                if (dirs.isEmpty()) {
                    _status.update { it.copy(message = "Nothing to export") }
                    return@execute
                }
                val name = "FoodDepth_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".zip"
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/")
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("cannot create $name")
                context.contentResolver.openOutputStream(uri)!!.use { out ->
                    ZipOutputStream(out).use { zip ->
                        for (d in dirs) for (f in d.listFiles().orEmpty()) {
                            zip.putNextEntry(ZipEntry("${d.name}/${f.name}"))
                            f.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                }
                _status.update { it.copy(message = "Exported ${dirs.size} captures to Download/$name") }
            } catch (e: Exception) {
                _status.update { it.copy(message = "Export failed: ${e.message}") }
            }
        }
    }

    /** Deletes all captures on the phone (after they were exported). */
    fun deleteAll() {
        io.execute {
            captureDirs().forEach { it.deleteRecursively() }
            _status.update { it.copy(saved = 0, message = "All captures deleted") }
        }
    }

    // ------------------------------------------------------------------ helpers
    private inline fun tryImage(acquire: () -> Image): Image? =
        try { acquire() } catch (_: NotYetAvailableException) { null }

    private fun centerDepth(img: Image): Float? {
        val p = img.planes[0]
        val buf = p.buffer.order(ByteOrder.nativeOrder())
        val v = buf.getShort((img.height / 2) * p.rowStride + (img.width / 2) * p.pixelStride).toInt() and 0xFFFF
        return if (v == 0) null else v / 1000f
    }

    private fun poseJson(p: Pose) = JSONObject()
        .put("t", JSONArray(listOf(p.tx(), p.ty(), p.tz()).map { it.toDouble() }))
        .put("q_xyzw", JSONArray(listOf(p.qx(), p.qy(), p.qz(), p.qw()).map { it.toDouble() }))

    /** 16-bit PGM (big-endian, as the format requires). */
    private fun pgm16(img: Image): ByteArray {
        val w = img.width
        val h = img.height
        val p = img.planes[0]
        val src = p.buffer.order(ByteOrder.nativeOrder())
        val header = "P5\n$w $h\n65535\n".toByteArray()
        val out = ByteBuffer.allocate(header.size + w * h * 2).order(ByteOrder.BIG_ENDIAN)
        out.put(header)
        for (y in 0 until h) for (x in 0 until w) out.putShort(src.getShort(y * p.rowStride + x * p.pixelStride))
        return out.array()
    }

    private fun pgm8(img: Image): ByteArray {
        val w = img.width
        val h = img.height
        val p = img.planes[0]
        val header = "P5\n$w $h\n255\n".toByteArray()
        val out = ByteArray(header.size + w * h)
        header.copyInto(out)
        var i = header.size
        for (y in 0 until h) for (x in 0 until w) out[i++] = p.buffer.get(y * p.rowStride + x * p.pixelStride)
        return out
    }

    private fun yuvToJpeg(img: Image): ByteArray {
        val w = img.width
        val h = img.height
        val nv21 = ByteArray(w * h * 3 / 2)
        val yP = img.planes[0]
        var i = 0
        for (y in 0 until h) for (x in 0 until w) nv21[i++] = yP.buffer.get(y * yP.rowStride + x * yP.pixelStride)
        val uP = img.planes[1]
        val vP = img.planes[2]
        for (y in 0 until h / 2) for (x in 0 until w / 2) {
            nv21[i++] = vP.buffer.get(y * vP.rowStride + x * vP.pixelStride)
            nv21[i++] = uP.buffer.get(y * uP.rowStride + x * uP.pixelStride)
        }
        val out = ByteArrayOutputStream()
        YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), 95, out)
        return out.toByteArray()
    }
}

/** Draws the ARCore camera texture as the full-screen background. */
private class Background {
    var texture = 0
        private set
    private var program = 0
    private val quad = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val uv = floatBuffer(FloatArray(8))

    fun create() {
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        texture = t[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        for ((k, v) in listOf(GLES20.GL_TEXTURE_WRAP_S to GLES20.GL_CLAMP_TO_EDGE, GLES20.GL_TEXTURE_WRAP_T to GLES20.GL_CLAMP_TO_EDGE,
            GLES20.GL_TEXTURE_MIN_FILTER to GLES20.GL_LINEAR, GLES20.GL_TEXTURE_MAG_FILTER to GLES20.GL_LINEAR))
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, k, v)
        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, shader(GLES20.GL_VERTEX_SHADER, VERTEX))
            GLES20.glAttachShader(it, shader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT))
            GLES20.glLinkProgram(it)
        }
    }

    fun draw(frame: Frame) {
        if (frame.hasDisplayGeometryChanged())
            frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, quad,
                Coordinates2d.TEXTURE_NORMALIZED, uv)
        if (frame.timestamp == 0L) return
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        val pos = GLES20.glGetAttribLocation(program, "a_Position")
        val tex = GLES20.glGetAttribLocation(program, "a_TexCoord")
        quad.position(0)
        uv.position(0)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glVertexAttribPointer(tex, 2, GLES20.GL_FLOAT, false, 0, uv)
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glEnableVertexAttribArray(tex)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(pos)
        GLES20.glDisableVertexAttribArray(tex)
    }

    private fun shader(type: Int, src: String) = GLES20.glCreateShader(type).also {
        GLES20.glShaderSource(it, src)
        GLES20.glCompileShader(it)
    }

    companion object {
        fun floatBuffer(a: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(a); position(0) }

        val VERTEX = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() { gl_Position = a_Position; v_TexCoord = a_TexCoord; }
        """.trimIndent()
        val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 v_TexCoord;
            uniform samplerExternalOES sTexture;
            void main() { gl_FragColor = texture2D(sTexture, v_TexCoord); }
        """.trimIndent()
    }
}
