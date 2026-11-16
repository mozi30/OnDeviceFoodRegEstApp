package com.zideck.foodregest.ml

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Same preprocessing as the val/test transform of the training code:
 * resize so that the shorter side is [size] px, centre crop [size] x [size], RGB in 0..1.
 * ImageNet normalisation is part of the exported model.
 */
object Preprocess {

    /** Decodes a photo (EXIF orientation applied) with its shorter side scaled to [shortSide] px. */
    fun decode(source: ImageDecoder.Source, shortSide: Int): Bitmap =
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val scale = shortSide.toFloat() / min(w, h)
            if (scale < 1f) decoder.setTargetSize(max(1, (w * scale).roundToInt()), max(1, (h * scale).roundToInt()))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
        }

    fun decode(file: File, shortSide: Int): Bitmap = decode(ImageDecoder.createSource(file), shortSide)

    fun decode(resolver: ContentResolver, uri: Uri, shortSide: Int): Bitmap =
        decode(ImageDecoder.createSource(resolver, uri), shortSide)

    /** Resize (shorter side = [size]) + centre crop + NHWC float tensor in 0..1. */
    fun toTensor(bitmap: Bitmap, size: Int): FloatArray {
        val scale = size.toFloat() / min(bitmap.width, bitmap.height)
        val w = max(size, (bitmap.width * scale).roundToInt())
        val h = max(size, (bitmap.height * scale).roundToInt())
        val scaled = if (w == bitmap.width && h == bitmap.height) bitmap else Bitmap.createScaledBitmap(bitmap, w, h, true)
        val pixels = IntArray(size * size)
        scaled.getPixels(pixels, 0, size, (w - size) / 2, (h - size) / 2, size, size)
        if (scaled !== bitmap) scaled.recycle()
        return pixelsToTensor(pixels)
    }

    /** ARGB ints -> RGB floats in 0..1, row-major (NHWC without the batch dimension). */
    fun pixelsToTensor(pixels: IntArray): FloatArray {
        val out = FloatArray(pixels.size * 3)
        for (i in pixels.indices) {
            val p = pixels[i]
            out[3 * i] = ((p shr 16) and 0xFF) / 255f
            out[3 * i + 1] = ((p shr 8) and 0xFF) / 255f
            out[3 * i + 2] = (p and 0xFF) / 255f
        }
        return out
    }
}
