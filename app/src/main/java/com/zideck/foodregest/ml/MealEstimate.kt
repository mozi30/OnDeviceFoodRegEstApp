package com.zideck.foodregest.ml

import kotlin.math.exp

/** kcal per gram, as used for the training labels (Atwater factors). */
object Kcal {
    const val PROTEIN = 4.0
    const val FAT = 9.0
    const val CARB = 4.0
    fun of(proteinG: Double, fatG: Double, carbG: Double) = PROTEIN * proteinG + FAT * fatG + CARB * carbG
}

/** Model output for one photo. */
class PhotoPrediction(
    val massG: Double,
    val kcal: Double,
    val fatG: Double,
    val carbG: Double,
    val proteinG: Double,
    val logMass: Double,
    val logStd90: Double,
    val dishProbs: FloatArray,
    val ingredientProbs: FloatArray,
    val preprocessMs: Long,
    val inferenceMs: Long,
) {
    /** Composition per 100 g: protein, fat, carbs (what the factorized head predicts). */
    val proteinPer100: Double get() = proteinG / massG * 100
    val fatPer100: Double get() = fatG / massG * 100
    val carbPer100: Double get() = carbG / massG * 100

    companion object {
        fun fromOutput(out: FloatArray, meta: ModelMeta, preprocessMs: Long = 0, inferenceMs: Long = 0): PhotoPrediction {
            require(out.size == meta.outputSize) { "model output has ${out.size} values, expected ${meta.outputSize}" }
            val nd = meta.dishes.size
            return PhotoPrediction(
                massG = out[0].toDouble(), kcal = out[1].toDouble(), fatG = out[2].toDouble(),
                carbG = out[3].toDouble(), proteinG = out[4].toDouble(),
                logMass = out[5].toDouble(), logStd90 = out[6].toDouble(),
                dishProbs = out.copyOfRange(meta.nFixed, meta.nFixed + nd),
                ingredientProbs = out.copyOfRange(meta.nFixed + nd, meta.outputSize),
                preprocessMs = preprocessMs, inferenceMs = inferenceMs,
            )
        }
    }
}

data class Label(val name: String, val probability: Float)

/**
 * Estimate for one meal from one or more photos.
 *
 * Several photos are combined by the median of the per-photo predictions (mass and
 * composition per 100 g); macros and kcal are then computed from both, so they stay
 * consistent. [massCorrectedG] is set when the user corrected the weight.
 */
data class MealEstimate(
    val predictedMassG: Double,
    val massLowG: Double,
    val massHighG: Double,
    val proteinPer100: Double,
    val fatPer100: Double,
    val carbPer100: Double,
    val dishes: List<Label>,
    val ingredients: List<Label>,
    val nPhotos: Int,
    val massCorrectedG: Double? = null,
    val chosenDish: String? = null,
) {
    val massG: Double get() = massCorrectedG ?: predictedMassG
    val proteinG: Double get() = proteinPer100 * massG / 100
    val fatG: Double get() = fatPer100 * massG / 100
    val carbG: Double get() = carbPer100 * massG / 100
    val kcal: Double get() = Kcal.of(proteinG, fatG, carbG)
    val dishName: String? get() = chosenDish ?: dishes.firstOrNull()?.name

    fun withMass(grams: Double?) = copy(massCorrectedG = grams?.takeIf { it > 0 })
    fun withDish(name: String?) = copy(chosenDish = name)

    companion object {
        const val LABEL_THRESHOLD = 0.25f
        const val MAX_LABELS = 5

        fun combine(photos: List<PhotoPrediction>, meta: ModelMeta): MealEstimate {
            require(photos.isNotEmpty()) { "no photo" }
            val logMass = median(photos.map { it.logMass })
            val logStd = median(photos.map { it.logStd90 })
            return MealEstimate(
                predictedMassG = median(photos.map { it.massG }),
                massLowG = exp(logMass - meta.z90 * logStd),
                massHighG = exp(logMass + meta.z90 * logStd),
                proteinPer100 = median(photos.map { it.proteinPer100 }),
                fatPer100 = median(photos.map { it.fatPer100 }),
                carbPer100 = median(photos.map { it.carbPer100 }),
                dishes = topLabels(photos.map { it.dishProbs }, meta.dishes),
                ingredients = topLabels(photos.map { it.ingredientProbs }, meta.ingredients),
                nPhotos = photos.size,
            )
        }

        /** Labels by mean probability over the photos; "other" is not shown. */
        fun topLabels(probs: List<FloatArray>, names: List<String>): List<Label> =
            names.indices
                .map { i -> Label(names[i], probs.map { it[i] }.average().toFloat()) }
                .filter { it.name != "other" && it.probability >= LABEL_THRESHOLD }
                .sortedByDescending { it.probability }
                .take(MAX_LABELS)

        fun median(values: List<Double>): Double {
            val s = values.sorted()
            val n = s.size
            return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
        }
    }
}
