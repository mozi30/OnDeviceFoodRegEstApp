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

/**
 * The bundled Stage 1 models. CENTER and PAD are the first models (49 dishes, 170 ingredients),
 * which differ only in how the photo is made square. WESTERN (pad to square) was also trained on
 * MM-Food-100K and Food-101 for recognition (about 1,300 dishes incl. Western ones, 860 ingredients)
 * and has its own recognition layer; it is the default and the most accurate for weight and nutrition.
 */
enum class ModelVariant(val label: String, val short: String, val metaFile: String) {
    CENTER("v1 center crop", "v1 crop", "food_stage1_center.json"),
    PAD("v1 pad to square", "v1 pad", "food_stage1_pad.json"),
    WESTERN("Western (1,300 dishes)", "western", "food_stage1_western.json"),
}

/** Which model(s) run on each photo. The compare modes run two models and show both estimates. */
enum class CropMode(val label: String, val description: String, val variants: List<ModelVariant>) {
    WESTERN("Western model (default)",
        "Whole photo padded to a square; recognises about 1,300 dishes incl. European ones.", listOf(ModelVariant.WESTERN)),
    COMPARE_WESTERN("Western vs. v1 pad (compare)",
        "Runs the Western and the first pad model on every photo and shows both estimates.",
        listOf(ModelVariant.PAD, ModelVariant.WESTERN)),
    CENTER("v1 center crop", "First model; square from the middle of the photo, the edges are cut off.", listOf(ModelVariant.CENTER)),
    PAD("v1 pad to square", "First model; whole photo, padded with grey to a square.", listOf(ModelVariant.PAD)),
    BOTH("v1 both crops (compare)", "Runs both first models on every photo and shows both estimates.",
        listOf(ModelVariant.CENTER, ModelVariant.PAD)),
}

data class AppSettings(
    /** Timings, raw model outputs, memory and model details on the meal screens. */
    val developerInfo: Boolean = false,
    /** Runs the model continuously on the camera preview and shows ms per frame (developer tool). */
    val liveEstimate: Boolean = false,
    val accelerator: AcceleratorChoice = AcceleratorChoice.AUTO,
    val cropMode: CropMode = CropMode.WESTERN,
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
            ?.let { name -> CropMode.entries.firstOrNull { it.name == name } } ?: CropMode.WESTERN,
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
        const val KEY_CROP = "model_mode" // was "crop_mode" before the Western model; new key = new default
    }
}
