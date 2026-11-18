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
import com.zideck.foodregest.settings.CropMode
import com.zideck.foodregest.settings.ModelVariant
import com.zideck.foodregest.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    data class Ready(val infos: List<ModelInfo>) : ModelState
    data class Failed(val message: String) : ModelState
}

/** Static facts about one loaded model, shown in the settings and the developer info. */
data class ModelInfo(
    val variant: ModelVariant,
    val backbone: String,
    val imageSize: Int,
    val precision: String,
    val file: String,
    val fileMb: Double,
    val accelerator: String,
    val loadMs: Long,
) {
    val summary: String get() = "$backbone · $imageSize px · ${variant.short} · ${precision.uppercase()} · $accelerator"
}

/**
 * One photo of the current meal. [predictions] holds one entry per active model variant
 * (in the order of the crop mode, the first is the main one) once the models have run.
 */
data class MealPhoto(
    val id: Long,
    val thumbnail: Bitmap,
    val predictions: Map<ModelVariant, PhotoPrediction> = emptyMap(),
    val accelerator: String? = null,
    val error: String? = null,
) {
    val prediction: PhotoPrediction? get() = predictions.values.firstOrNull()
}

/** Live estimate on camera frames (developer tool): one value per active variant. */
data class LiveValue(val variant: ModelVariant, val massG: Double, val kcal: Double, val inferenceMs: Long)

data class LiveStats(val values: List<LiveValue>, val frameMs: Long, val fps: Double)

data class BenchmarkResult(
    val variant: ModelVariant,
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
    /** Main estimate (first variant), carries the user's corrections. */
    val estimate: MealEstimate? = null,
    /** Estimate of every active variant (more than one in the "both" crop mode). */
    val estimates: Map<ModelVariant, MealEstimate> = emptyMap(),
    val showResult: Boolean = false,
    val message: String? = null,
    val live: LiveStats? = null,
    val benchmark: List<BenchmarkResult> = emptyList(),
    val benchmarking: Boolean = false,
) {
    val pending: Boolean get() = photos.any { it.predictions.isEmpty() && it.error == null }
    val ready: List<PhotoPrediction> get() = photos.mapNotNull { it.prediction }
    val modelInfos: List<ModelInfo> get() = (model as? ModelState.Ready)?.infos.orEmpty()
    val modelInfo: ModelInfo? get() = modelInfos.firstOrNull()
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsStore = SettingsStore(app)
    val settings: StateFlow<AppSettings> = settingsStore.settings

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    /** Guards [estimators]: they are swapped when a setting changes while other threads may use them. */
    private val lock = Any()
    private var estimators: Map<ModelVariant, FoodEstimator> = emptyMap()
    private val ids = AtomicLong()
    private var lastLiveFrame = 0L
    private var loadJob: Job? = null

    init {
        loadModels()
    }

    // ------------------------------------------------------------------ models --

    private fun loadModels() {
        val s = settings.value
        _state.update { it.copy(model = ModelState.Loading, live = null, benchmark = emptyList()) }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val loaded = LinkedHashMap<ModelVariant, FoodEstimator>()
                    try {
                        for (v in s.cropMode.variants) {
                            loaded[v] = FoodEstimator.create(getApplication(), v.metaFile, s.accelerator.order)
                        }
                    } catch (t: Throwable) {
                        loaded.values.forEach { it.close() }
                        throw t
                    }
                    loaded
                }
            }
            result.onSuccess { loaded ->
                val old = synchronized(lock) { estimators.also { estimators = loaded } }
                old.values.forEach { it.close() }
                val infos = loaded.map { (v, e) ->
                    ModelInfo(v, e.meta.backbone, e.meta.imageSize, e.meta.precision, e.meta.modelFile,
                        e.modelBytes / 1048576.0, e.accelerator.name, e.loadMs)
                }
                _state.update { it.copy(model = ModelState.Ready(infos)) }
                repredictAll()
            }.onFailure { t ->
                val old = synchronized(lock) { estimators.also { estimators = emptyMap() } }
                old.values.forEach { it.close() }
                _state.update { it.copy(model = ModelState.Failed(t.message ?: t.javaClass.simpleName)) }
            }
        }
    }

    private fun <T> withEstimators(block: (Map<ModelVariant, FoodEstimator>) -> T): T? =
        synchronized(lock) { estimators.takeIf { it.isNotEmpty() }?.let(block) }

    private suspend fun awaitModels() {
        while (_state.value.model is ModelState.Loading) delay(50)
    }

    /** Runs every active model on [bitmap]; the map keeps the order of the crop mode. */
    private fun predictAll(bitmap: Bitmap): Pair<Map<ModelVariant, PhotoPrediction>, String> =
        withEstimators { es ->
            es.mapValues { (_, e) -> e.predict(bitmap) } to es.values.map { it.accelerator.name }.distinct().joinToString("/")
        } ?: error("model not loaded")

    /** After a model change: run the new models on the photos of the current meal. */
    private fun repredictAll() {
        val photos = _state.value.photos
        if (photos.isEmpty()) return
        _state.update { s -> s.copy(photos = s.photos.map { it.copy(predictions = emptyMap(), error = null) }) }
        photos.forEach { predict(it.id, it.thumbnail) }
    }

    // ---------------------------------------------------------------- settings --

    fun setDeveloperInfo(on: Boolean) = settingsStore.update { it.copy(developerInfo = on) }

    fun setLiveEstimate(on: Boolean) {
        settingsStore.update { it.copy(liveEstimate = on) }
        if (!on) _state.update { it.copy(live = null) }
    }

    fun setAccelerator(choice: AcceleratorChoice) {
        if (choice == settings.value.accelerator) return
        settingsStore.update { it.copy(accelerator = choice) }
        loadModels()
    }

    fun setCropMode(mode: CropMode) {
        if (mode == settings.value.cropMode) return
        settingsStore.update { it.copy(cropMode = mode) }
        loadModels()
    }

    // ------------------------------------------------------------------ photos --

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
            predict(id, bitmap)
        }
    }

    private fun predict(id: Long, bitmap: Bitmap) {
        viewModelScope.launch {
            awaitModels()
            val result = withContext(Dispatchers.Default) { runCatching { predictAll(bitmap) } }
            _state.update { s ->
                val photos = s.photos.map { p ->
                    if (p.id != id) p
                    else result.fold(
                        { (preds, acc) -> p.copy(predictions = preds, accelerator = acc, error = null) },
                        { p.copy(error = it.message ?: "inference failed") },
                    )
                }
                s.copy(photos = photos).withEstimates()
            }
        }
    }

    fun removePhoto(id: Long) = _state.update { s -> s.copy(photos = s.photos.filterNot { it.id == id }).withEstimates() }

    fun showResult(show: Boolean) = _state.update { it.copy(showResult = show && it.estimate != null) }

    fun correctMass(grams: Double?) = _state.update { it.copy(estimate = it.estimate?.withMass(grams)) }

    fun chooseDish(name: String?) = _state.update { it.copy(estimate = it.estimate?.withDish(name)) }

    fun newMeal() = _state.update { UiState(model = it.model, benchmark = it.benchmark) }

    fun showMessage(text: String) = _state.update { it.copy(message = text) }

    fun messageShown() = _state.update { it.copy(message = null) }

    /** Recomputes the estimate of every variant; the main one keeps the user's corrections. */
    private fun UiState.withEstimates(): UiState {
        val metas = withEstimators { es -> es.mapValues { it.value.meta } } ?: return this
        val done = photos.filter { it.predictions.isNotEmpty() }
        if (done.isEmpty()) return copy(estimate = null, estimates = emptyMap(), showResult = false)
        val all = metas.mapNotNull { (v, meta) ->
            val preds = done.mapNotNull { it.predictions[v] }
            if (preds.isEmpty()) null else v to MealEstimate.combine(preds, meta)
        }.toMap()
        val main = all.values.firstOrNull()
        return copy(
            estimates = all,
            estimate = main?.copy(massCorrectedG = estimate?.massCorrectedG, chosenDish = estimate?.chosenDish),
        )
    }

    // ------------------------------------------------------- developer tools --

    /**
     * Live estimate on one camera frame; called on the camera's analysis thread. Frames that
     * arrive while the models are busy are dropped by CameraX (keep-only-latest).
     */
    fun analyzeFrame(frame: Bitmap) {
        val now = SystemClock.elapsedRealtime()
        val preds = withEstimators { es -> es.mapValues { (_, e) -> e.predict(frame) } } ?: return
        val frameMs = SystemClock.elapsedRealtime() - now
        val interval = if (lastLiveFrame == 0L) 0L else now - lastLiveFrame
        lastLiveFrame = now
        _state.update { s ->
            val fps = if (interval > 0) 1000.0 / interval else 0.0
            val smooth = s.live?.fps?.takeIf { it > 0 }?.let { 0.8 * it + 0.2 * fps } ?: fps
            val values = preds.map { (v, p) -> LiveValue(v, p.massG, p.kcal, p.inferenceMs) }
            s.copy(live = LiveStats(values, frameMs, smooth))
        }
    }

    fun liveStopped() {
        lastLiveFrame = 0L
        _state.update { it.copy(live = null) }
    }

    /** Times [runs] runs of every active model on the last photo (or a grey image when there is none). */
    fun runBenchmark(runs: Int = 50) {
        if (_state.value.benchmarking) return
        _state.update { it.copy(benchmarking = true) }
        viewModelScope.launch {
            awaitModels()
            val photo = _state.value.photos.lastOrNull()?.thumbnail
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    withEstimators { es ->
                        es.map { (v, e) ->
                            val size = e.meta.imageSize
                            val tensor = photo?.let { Preprocess.toTensor(it, e.meta) } ?: FloatArray(size * size * 3) { 0.5f }
                            val ms = e.benchmark(tensor, runs).sorted()
                            BenchmarkResult(
                                variant = v, accelerator = e.accelerator.name, runs = runs, meanMs = ms.average(),
                                p50Ms = ms[ms.size / 2], p90Ms = ms[(ms.size * 9) / 10], minMs = ms.first(),
                                input = if (photo != null) "last photo" else "grey image",
                            )
                        }
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
        synchronized(lock) { estimators.values.forEach { it.close() }; estimators = emptyMap() }
    }

    companion object {
        const val MAX_PHOTOS = 5
        /**
         * Photos are decoded with the shorter side at the model's input size, so the decoder does the
         * main downscale; centre crop then only crops, pad-to-square pads and scales once more.
         * Must match `image_size` in the model JSON; other sizes still work (rescaled bilinearly).
         */
        const val DECODE_SHORT_SIDE = 320
    }
}
