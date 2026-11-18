package com.zideck.foodregest.ml

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer

/**
 * Runs the Stage 1 model with LiteRT: GPU first, CPU as fallback.
 * Not thread-safe by itself; [predict] is synchronized.
 */
class FoodEstimator private constructor(
    private val model: CompiledModel,
    val meta: ModelMeta,
    val accelerator: Accelerator,
) : AutoCloseable {

    /** Model loading + first (warm-up) run, in ms; includes GPU kernel compilation. */
    var loadMs: Long = 0
        private set

    private val inputs: List<TensorBuffer> = model.createInputBuffers()
    private val outputs: List<TensorBuffer> = model.createOutputBuffers()

    @Synchronized
    fun predict(bitmap: Bitmap): PhotoPrediction {
        val t0 = SystemClock.elapsedRealtime()
        val tensor = Preprocess.toTensor(bitmap, meta)
        val t1 = SystemClock.elapsedRealtime()
        val out = run(tensor)
        val t2 = SystemClock.elapsedRealtime()
        return PhotoPrediction.fromOutput(out, meta, preprocessMs = t1 - t0, inferenceMs = t2 - t1)
    }

    @Synchronized
    fun run(tensor: FloatArray): FloatArray {
        inputs[0].writeFloat(tensor)
        model.run(inputs, outputs)
        return outputs[0].readFloat()
    }

    /** Model file size in bytes (from the APK assets), for the developer info. */
    var modelBytes: Long = 0
        private set

    /** Runs the model [runs] times on [tensor] (after one warm-up run); returns the latency of each run in ms. */
    @Synchronized
    fun benchmark(tensor: FloatArray, runs: Int): DoubleArray {
        run(tensor)
        return DoubleArray(runs) {
            val t0 = System.nanoTime()
            run(tensor)
            (System.nanoTime() - t0) / 1e6
        }
    }

    override fun close() {
        inputs.forEach { it.close() }
        outputs.forEach { it.close() }
        model.close()
    }

    companion object {
        private const val TAG = "FoodEstimator"

        fun create(
            context: Context,
            metaFile: String,
            accelerators: List<Accelerator> = listOf(Accelerator.GPU, Accelerator.CPU),
            precision: String = "fp16",
        ): FoodEstimator {
            val meta = ModelMeta.parse(context.assets.open(metaFile).bufferedReader().use { it.readText() }, precision)
            var lastError: Exception? = null
            for (acc in accelerators) {
                val t0 = SystemClock.elapsedRealtime()
                var estimator: FoodEstimator? = null
                try {
                    val model = CompiledModel.create(context.assets, meta.modelFile, CompiledModel.Options(acc))
                    estimator = FoodEstimator(model, meta, acc)
                    // warm-up: compiles the GPU kernels and fails early if the accelerator cannot run the model
                    val out = estimator.run(FloatArray(meta.imageSize * meta.imageSize * 3) { 0.5f })
                    check(out.size == meta.outputSize && out.all { it.isFinite() }) { "invalid warm-up output" }
                    estimator.loadMs = SystemClock.elapsedRealtime() - t0
                    estimator.modelBytes = runCatching {
                        context.assets.openFd(meta.modelFile).use { it.length }
                    }.getOrDefault(0)
                    Log.i(TAG, "${meta.modelFile} on $acc, loaded + warm-up in ${estimator.loadMs} ms")
                    return estimator
                } catch (e: Exception) {
                    runCatching { estimator?.close() }
                    Log.w(TAG, "$acc not usable: ${e.message}")
                    lastError = e
                }
            }
            throw IllegalStateException("model could not be loaded", lastError)
        }
    }

}
