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

data class AppSettings(
    /** Timings, raw model outputs, memory and model details on the meal screens. */
    val developerInfo: Boolean = false,
    /** Runs the model continuously on the camera preview and shows ms per frame (developer tool). */
    val liveEstimate: Boolean = false,
    val accelerator: AcceleratorChoice = AcceleratorChoice.AUTO,
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
    )

    fun update(change: (AppSettings) -> AppSettings) {
        _settings.update(change)
        val s = _settings.value
        prefs.edit()
            .putBoolean(KEY_DEV, s.developerInfo)
            .putBoolean(KEY_LIVE, s.liveEstimate)
            .putString(KEY_ACC, s.accelerator.name)
            .apply()
    }

    private companion object {
        const val KEY_DEV = "developer_info"
        const val KEY_LIVE = "live_estimate"
        const val KEY_ACC = "accelerator"
    }
}
