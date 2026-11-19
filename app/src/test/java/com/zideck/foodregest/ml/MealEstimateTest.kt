package com.zideck.foodregest.ml

import com.zideck.foodregest.settings.ModelVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.exp
import kotlin.math.ln

class MealEstimateTest {

    private val meta = ModelMeta(
        backbone = "test", imageSize = 4, nFixed = 7,
        dishes = listOf("pizza", "salad", "other"), ingredients = listOf("cheese", "oil"),
        z90 = 1.645, modelFile = "m.tflite", precision = "fp16",
    )

    /** Builds a model output vector the way the exported model lays it out. */
    private fun output(mass: Float, protein100: Float, fat100: Float, carb100: Float, logStd: Float,
                       dishes: FloatArray = floatArrayOf(0.9f, 0.1f, 0.5f),
                       ingredients: FloatArray = floatArrayOf(0.6f, 0.2f)): FloatArray {
        val p = protein100 * mass / 100
        val f = fat100 * mass / 100
        val c = carb100 * mass / 100
        val kcal = (4 * p + 9 * f + 4 * c)
        return floatArrayOf(mass, kcal, f, c, p, ln(mass), logStd) + dishes + ingredients
    }

    @Test
    fun singlePhotoKeepsModelValues() {
        val pred = PhotoPrediction.fromOutput(output(200f, 10f, 5f, 20f, 0.1f), meta)
        val e = MealEstimate.combine(listOf(pred), meta)
        assertEquals(200.0, e.massG, 1e-3)
        assertEquals(20.0, e.proteinG, 1e-3)
        assertEquals(10.0, e.fatG, 1e-3)
        assertEquals(40.0, e.carbG, 1e-3)
        assertEquals(4 * 20.0 + 9 * 10.0 + 4 * 40.0, e.kcal, 1e-3)
        assertEquals(200 * exp(-1.645 * 0.1), e.massLowG, 1e-2)
        assertEquals(200 * exp(1.645 * 0.1), e.massHighG, 1e-2)
    }

    @Test
    fun severalPhotosUseTheMedian() {
        val preds = listOf(
            output(100f, 10f, 5f, 20f, 0.1f),
            output(300f, 12f, 6f, 10f, 0.3f),
            output(220f, 30f, 1f, 15f, 0.2f),
        ).map { PhotoPrediction.fromOutput(it, meta) }
        val e = MealEstimate.combine(preds, meta)
        assertEquals(220.0, e.massG, 1e-3)
        assertEquals(12.0, e.proteinPer100, 1e-3)
        assertEquals(5.0, e.fatPer100, 1e-3)
        assertEquals(15.0, e.carbPer100, 1e-3)
        // kcal always consistent with the macros
        assertEquals(Kcal.of(e.proteinG, e.fatG, e.carbG), e.kcal, 1e-9)
        assertEquals(3, e.nPhotos)
    }

    @Test
    fun medianOfEvenCountAveragesTheMiddle() {
        assertEquals(2.5, MealEstimate.median(listOf(4.0, 1.0, 2.0, 3.0)), 1e-12)
    }

    @Test
    fun massCorrectionRescalesMacros() {
        val e = MealEstimate.combine(listOf(PhotoPrediction.fromOutput(output(200f, 10f, 5f, 20f, 0.1f), meta)), meta)
        val c = e.withMass(400.0)
        assertEquals(400.0, c.massG, 1e-9)
        assertEquals(200.0, c.predictedMassG, 1e-3)
        assertEquals(2 * e.kcal, c.kcal, 1e-3)
        assertEquals(e.massG, c.withMass(null).massG, 1e-9)
        assertEquals(e.massG, e.withMass(0.0).massG, 1e-9) // 0 g is ignored
    }

    @Test
    fun labelsAreAveragedFilteredAndSorted() {
        val preds = listOf(
            output(100f, 10f, 5f, 20f, 0.1f, dishes = floatArrayOf(0.2f, 0.8f, 0.9f)),
            output(100f, 10f, 5f, 20f, 0.1f, dishes = floatArrayOf(0.4f, 0.6f, 0.9f)),
        ).map { PhotoPrediction.fromOutput(it, meta) }
        val e = MealEstimate.combine(preds, meta)
        assertEquals(listOf("salad", "pizza"), e.dishes.map { it.name }) // "other" hidden, 0.7 > 0.3
        assertEquals("salad", e.dishName)
        assertEquals("pizza", e.withDish("pizza").dishName)
        assertEquals(listOf("cheese"), e.ingredients.map { it.name }) // oil 0.2 < threshold
    }

    @Test
    fun pixelsAreConvertedToRgbInZeroToOne() {
        val t = Preprocess.pixelsToTensor(intArrayOf(0xFF102030.toInt(), 0xFFFFFFFF.toInt()))
        assertEquals(0x10 / 255f, t[0], 1e-6f)
        assertEquals(0x20 / 255f, t[1], 1e-6f)
        assertEquals(0x30 / 255f, t[2], 1e-6f)
        assertTrue(t.drop(3).all { it == 1f })
    }

    @Test
    fun bundledModelsMatchTheContract() {
        for (v in ModelVariant.entries) {
            val m = ModelMeta.parse(File("src/main/assets/${v.metaFile}").readText())
            assertEquals(7, m.nFixed)
            assertEquals(320, m.imageSize)
            assertEquals(if (v == ModelVariant.CENTER) ModelMeta.CROP_CENTER else ModelMeta.CROP_PAD, m.crop)
            assertTrue(File("src/main/assets/${m.modelFile}").isFile)
            assertTrue(m.dishes.isNotEmpty() && m.ingredients.isNotEmpty())
        }
        val pad = ModelMeta.parse(File("src/main/assets/${ModelVariant.PAD.metaFile}").readText())
        assertEquals(0xFF7C7468.toInt(), pad.padFill) // RGB 124, 116, 104 as in trainer.PadSquare
        val western = ModelMeta.parse(File("src/main/assets/${ModelVariant.WESTERN.metaFile}").readText())
        assertTrue(western.dishes.size > 1000 && "spaghetti bolognese" in western.dishes)
    }
}
