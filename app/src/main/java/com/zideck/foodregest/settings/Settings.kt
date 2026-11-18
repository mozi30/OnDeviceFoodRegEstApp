package com.zideck.foodregest.settings

import android.content.Context
import com.google.ai.edge.litert.Accelerator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Which LiteRT accelerator to use. AUTO tries the GPU and falls back to the CPU. */
enum class AcceleratorChoice(val label: String, val order: List<Accelerator>) {
    AUTO("Auto (GPU, CPU fallback)", listOf(Accelerator.GPU, Accelerator.CPU)),
    GPU("GPU only", listOf(Accelerator.GPU)),
    CPU("CPU only", listOf(Accelerator.CPU)),
}

/** The two Stage 1 models; they differ in how the photo is made square (as in training). */
enum class ModelVariant(val label: String, val short: String, val metaFile: String) {
    CENTER("Center crop", "crop", "food_stage1_center.json"),
    PAD("Pad to square", "pad", "food_stage1_pad.json"),
}

/** Which model(s) run on each photo. BOTH runs both and shows the two estimates side by side. */
enum class CropMode(val label: String, val description: String, val variants: List<ModelVariant>) {
    CENTER("Center crop", "Square from the middle of the photo; the edges are cut off.", listOf(ModelVariant.CENTER)),
    PAD("Pad to square", "Whole photo, padded with grey to a square.", listOf(ModelVariant.PAD)),
    BOTH("Both (compare)", "Runs both models on every photo and shows both estimates.", listOf(ModelVariant.CENTER, ModelVariant.PAD)),
}

data class AppSettings(
    /** Timings, raw model outputs, memory and model details on the meal screens. */
    val developerInfo: Boolean = false,
    /** Runs the model continuously on the camera preview and shows ms per frame (developer tool). */
    val liveEstimate: Boolean = false,
    val accelerator: AcceleratorChoice = AcceleratorChoice.AUTO,
    val cropMode: CropMode = CropMode.CENTER,
)

/** Settings kept in SharedPreferences, exposed as a StateFlow. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings

    private fun load() = AppSettings(
        developerInfo = prefs.getBoolean(KEY_DEV, false),
        liveEstimate = prefs.getBoolean(KEY_LIVE, false),
        accelerator = prefs.getString(KEY_ACC, null)
            ?.let { name -> AcceleratorChoice.entries.firstOrNull { it.name == name } } ?: AcceleratorChoice.AUTO,
        cropMode = prefs.getString(KEY_CROP, null)
            ?.let { name -> CropMode.entries.firstOrNull { it.name == name } } ?: CropMode.CENTER,
    )

    fun update(change: (AppSettings) -> AppSettings) {
        _settings.update(change)
        val s = _settings.value
        prefs.edit()
            .putBoolean(KEY_DEV, s.developerInfo)
            .putBoolean(KEY_LIVE, s.liveEstimate)
            .putString(KEY_ACC, s.accelerator.name)
            .putString(KEY_CROP, s.cropMode.name)
            .apply()
    }

    private companion object {
        const val KEY_DEV = "developer_info"
        const val KEY_LIVE = "live_estimate"
        const val KEY_ACC = "accelerator"
        const val KEY_CROP = "crop_mode"
    }
}
