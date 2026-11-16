package com.zideck.foodregest.ml

import org.json.JSONObject

/**
 * Contract of the exported model, read from the JSON file written by
 * `training/export_litert.py` (training repository).
 *
 * Input: float32 [1, imageSize, imageSize, 3], RGB in 0..1.
 * Output: [mass_g, kcal, fat_g, carb_g, protein_g, log_mass, log_std90, dish probs..., ingredient probs...].
 */
data class ModelMeta(
    val backbone: String,
    val imageSize: Int,
    val nFixed: Int,
    val dishes: List<String>,
    val ingredients: List<String>,
    val z90: Double,
    val modelFile: String,
    val precision: String,
) {
    val outputSize: Int get() = nFixed + dishes.size + ingredients.size

    companion object {
        fun parse(json: String, precision: String = "fp16"): ModelMeta {
            val o = JSONObject(json)
            fun strings(key: String) = o.getJSONArray(key).let { a -> List(a.length()) { a.getString(it) } }
            val meta = ModelMeta(
                backbone = o.getString("backbone"),
                imageSize = o.getInt("image_size"),
                nFixed = o.getInt("n_fixed"),
                dishes = strings("dishes"),
                ingredients = strings("ingredients"),
                z90 = o.getDouble("z90"),
                modelFile = o.getJSONObject("files").getString(precision),
                precision = precision,
            )
            val layout = o.getJSONArray("output_layout").length()
            require(layout == meta.outputSize) { "output layout has $layout entries, expected ${meta.outputSize}" }
            return meta
        }
    }
}
