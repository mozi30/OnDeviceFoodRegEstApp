package com.zideck.foodregest.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.os.Debug
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zideck.foodregest.ml.FoodEstimator
import com.zideck.foodregest.ml.MealEstimate
import com.zideck.foodregest.ml.PhotoPrediction
import com.zideck.foodregest.ml.Preprocess
import com.zideck.foodregest.settings.AcceleratorChoice
import com.zideck.foodregest.settings.AppSettings
import com.zideck.foodregest.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

sealed interface ModelState {
    data object Loading : ModelState
    data class Ready(val info: ModelInfo) : ModelState
    data class Failed(val message: String) : ModelState
}

/** Static facts about the loaded model, shown in the settings and the developer info. */
data class ModelInfo(
    val backbone: String,
    val imageSize: Int,
    val precision: String,
    val file: String,
    val fileMb: Double,
    val accelerator: String,
    val loadMs: Long,
) {
    val summary: String get() = "$backbone · $imageSize px · ${precision.uppercase()} · $accelerator"
}

/** One photo of the current meal; [prediction] is filled as soon as the model has run on it. */
data class MealPhoto(
    val id: Long,
    val thumbnail: Bitmap,
    val prediction: PhotoPrediction? = null,
    val accelerator: String? = null,
    val error: String? = null,
)

/** Rolling statistics of the live estimate on camera frames (developer tool). */
data class LiveStats(
    val massG: Double,
    val kcal: Double,
    val frameMs: Long,
    val inferenceMs: Long,
    val fps: Double,
)

data class BenchmarkResult(
    val accelerator: String,
    val runs: Int,
    val meanMs: Double,
    val p50Ms: Double,
    val p90Ms: Double,
    val minMs: Double,
    val input: String,
)

data class MemoryInfo(val javaHeapMb: Double, val nativeHeapMb: Double)

data class UiState(
    val model: ModelState = ModelState.Loading,
    val photos: List<MealPhoto> = emptyList(),
    val estimate: MealEstimate? = null,
    val showResult: Boolean = false,
    val message: String? = null,
    val live: LiveStats? = null,
    val benchmark: BenchmarkResult? = null,
    val benchmarking: Boolean = false,
) {
    val pending: Boolean get() = photos.any { it.prediction == null && it.error == null }
    val ready: List<PhotoPrediction> get() = photos.mapNotNull { it.prediction }
    val modelInfo: ModelInfo? get() = (model as? ModelState.Ready)?.info
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsStore = SettingsStore(app)
    val settings: StateFlow<AppSettings> = settingsStore.settings

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    /** Guards [estimator]: it is swapped when the accelerator changes while other threads may use it. */
    private val lock = Any()
    private var estimator: FoodEstimator? = null
    private val ids = AtomicLong()
    private var lastLiveFrame = 0L

    init {
        loadModel(settings.value.accelerator)
    }

    // ------------------------------------------------------------------ model --

    private fun loadModel(choice: AcceleratorChoice) {
        _state.update { it.copy(model = ModelState.Loading, live = null, benchmark = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching { FoodEstimator.create(getApplication(), choice.order) }
            }
            result.onSuccess { e ->
                val old = synchronized(lock) { estimator.also { estimator = e } }
                old?.close()
                val info = ModelInfo(
                    backbone = e.meta.backbone, imageSize = e.meta.imageSize, precision = e.meta.precision,
                    file = e.meta.modelFile, fileMb = e.modelBytes / 1048576.0,
                    accelerator = e.accelerator.name, loadMs = e.loadMs,
                )
                _state.update { it.copy(model = ModelState.Ready(info)) }
            }.onFailure { t ->
                val old = synchronized(lock) { estimator.also { estimator = null } }
                old?.close()
                _state.update { it.copy(model = ModelState.Failed(t.message ?: t.javaClass.simpleName)) }
            }
        }
    }

    private fun <T> withEstimator(block: (FoodEstimator) -> T): T? = synchronized(lock) { estimator?.let(block) }

    private suspend fun awaitModel() {
        while (_state.value.model is ModelState.Loading) delay(50)
    }

    // --------------------------------------------------------------- settings --

    fun setDeveloperInfo(on: Boolean) = settingsStore.update { it.copy(developerInfo = on) }

    fun setLiveEstimate(on: Boolean) {
        settingsStore.update { it.copy(liveEstimate = on) }
        if (!on) _state.update { it.copy(live = null) }
    }

    fun setAccelerator(choice: AcceleratorChoice) {
        if (choice == settings.value.accelerator) return
        settingsStore.update { it.copy(accelerator = choice) }
        loadModel(choice)
    }

    // ----------------------------------------------------------------- photos --

    /** Photo taken with the camera (JPEG in the cache directory, deleted after decoding). */
    fun addPhoto(file: File) = add { Preprocess.decode(file, DECODE_SHORT_SIDE).also { file.delete() } }

    /** Photo picked from the gallery. */
    fun addPhoto(uri: Uri) = add { Preprocess.decode(getApplication<Application>().contentResolver, uri, DECODE_SHORT_SIDE) }

    private fun add(decode: () -> Bitmap) {
        if (_state.value.photos.size >= MAX_PHOTOS) {
            showMessage("At most $MAX_PHOTOS photos per meal")
            return
        }
        viewModelScope.launch {
            val id = ids.incrementAndGet()
            val bitmap = withContext(Dispatchers.IO) { runCatching(decode) }.getOrElse { t ->
                showMessage("Could not read the photo: ${t.message}")
                return@launch
            }
            _state.update { it.copy(photos = it.photos + MealPhoto(id, bitmap)) }
            awaitModel()
            val result = withContext(Dispatchers.Default) {
                runCatching { withEstimator { e -> e.predict(bitmap) to e.accelerator.name } ?: error("model not loaded") }
            }
            _state.update { s ->
                val photos = s.photos.map { p ->
                    if (p.id != id) p
                    else result.fold(
                        { (pred, acc) -> p.copy(prediction = pred, accelerator = acc) },
                        { p.copy(error = it.message ?: "inference failed") },
                    )
                }
                s.copy(photos = photos).withEstimate()
            }
        }
    }

    fun removePhoto(id: Long) = _state.update { s -> s.copy(photos = s.photos.filterNot { it.id == id }).withEstimate() }

    fun showResult(show: Boolean) = _state.update { it.copy(showResult = show && it.estimate != null) }

    fun correctMass(grams: Double?) = _state.update { it.copy(estimate = it.estimate?.withMass(grams)) }

    fun chooseDish(name: String?) = _state.update { it.copy(estimate = it.estimate?.withDish(name)) }

    fun newMeal() = _state.update { UiState(model = it.model, benchmark = it.benchmark) }

    fun showMessage(text: String) = _state.update { it.copy(message = text) }

    fun messageShown() = _state.update { it.copy(message = null) }

    /** Recomputes the combined estimate; keeps the user's corrections. */
    private fun UiState.withEstimate(): UiState {
        val meta = withEstimator { it.meta } ?: return this
        val preds = ready
        if (preds.isEmpty()) return copy(estimate = null, showResult = false)
        val fresh = MealEstimate.combine(preds, meta)
        return copy(estimate = fresh.copy(massCorrectedG = estimate?.massCorrectedG, chosenDish = estimate?.chosenDish))
    }

    // ------------------------------------------------------- developer tools --

    /**
     * Live estimate on one camera frame; called on the camera's analysis thread. Frames that
     * arrive while the model is busy are dropped by CameraX (keep-only-latest).
     */
    fun analyzeFrame(frame: Bitmap) {
        val now = SystemClock.elapsedRealtime()
        val pred = withEstimator { it.predict(frame) } ?: return
        val frameMs = SystemClock.elapsedRealtime() - now
        val interval = if (lastLiveFrame == 0L) 0L else now - lastLiveFrame
        lastLiveFrame = now
        _state.update { s ->
            val fps = if (interval > 0) 1000.0 / interval else 0.0
            val smooth = s.live?.fps?.takeIf { it > 0 }?.let { 0.8 * it + 0.2 * fps } ?: fps
            s.copy(live = LiveStats(pred.massG, pred.kcal, frameMs, pred.inferenceMs, smooth))
        }
    }

    fun liveStopped() {
        lastLiveFrame = 0L
        _state.update { it.copy(live = null) }
    }

    /** Times [runs] model runs on the last photo (or a grey image when there is none). */
    fun runBenchmark(runs: Int = 50) {
        if (_state.value.benchmarking) return
        _state.update { it.copy(benchmarking = true) }
        viewModelScope.launch {
            awaitModel()
            val photo = _state.value.photos.lastOrNull()?.thumbnail
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    withEstimator { e ->
                        val size = e.meta.imageSize
                        val tensor = photo?.let { Preprocess.toTensor(it, size) } ?: FloatArray(size * size * 3) { 0.5f }
                        val ms = e.benchmark(tensor, runs).sorted()
                        BenchmarkResult(
                            accelerator = e.accelerator.name, runs = runs, meanMs = ms.average(),
                            p50Ms = ms[ms.size / 2], p90Ms = ms[(ms.size * 9) / 10], minMs = ms.first(),
                            input = if (photo != null) "last photo" else "grey image",
                        )
                    } ?: error("model not loaded")
                }
            }
            _state.update { s ->
                result.fold(
                    { s.copy(benchmark = it, benchmarking = false) },
                    { s.copy(benchmarking = false, message = "Benchmark failed: ${it.message}") },
                )
            }
        }
    }

    fun memory(): MemoryInfo {
        val rt = Runtime.getRuntime()
        return MemoryInfo(
            javaHeapMb = (rt.totalMemory() - rt.freeMemory()) / 1048576.0,
            nativeHeapMb = Debug.getNativeHeapAllocatedSize() / 1048576.0,
        )
    }

    override fun onCleared() {
        synchronized(lock) { estimator?.close(); estimator = null }
    }

    companion object {
        const val MAX_PHOTOS = 5
        /**
         * Photos are decoded straight to the model's input size (shorter side), so the decoder does the
         * one high-quality downscale and [com.zideck.foodregest.ml.Preprocess.toTensor] only crops.
         * Must match `image_size` in food_stage1.json; other sizes still work (rescaled bilinearly).
         */
        const val DECODE_SHORT_SIDE = 320
    }
}
