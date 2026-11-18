package com.zideck.foodregest.ml

import org.json.JSONObject

/**
 * Contract of the exported model, read from the JSON file written by
 * `training/export_litert.py` (training repository).
 *
 * Input: float32 [1, imageSize, imageSize, 3], RGB in 0..1, prepared with [crop]:
 * "center" (shorter side to imageSize, centre crop) or "pad" (pad to a square with [padFill], resize).
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
    val crop: String = CROP_CENTER,
    val padFill: Int = 0xFF7C7468.toInt(),
) {
    val outputSize: Int get() = nFixed + dishes.size + ingredients.size

    companion object {
        const val CROP_CENTER = "center"
        const val CROP_PAD = "pad"

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
                crop = o.optString("crop", CROP_CENTER),
                padFill = o.optJSONArray("pad_fill")?.let { a ->
                    (0xFF shl 24) or (a.getInt(0) shl 16) or (a.getInt(1) shl 8) or a.getInt(2)
                } ?: 0xFF7C7468.toInt(),
            )
            require(meta.crop == CROP_CENTER || meta.crop == CROP_PAD) { "unknown crop ${meta.crop}" }
            val layout = o.getJSONArray("output_layout").length()
            require(layout == meta.outputSize) { "output layout has $layout entries, expected ${meta.outputSize}" }
            return meta
        }
    }
}
