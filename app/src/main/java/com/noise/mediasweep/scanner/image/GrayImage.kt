package com.noise.mediasweep.scanner.image

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * EXIF orientation codes (value of `ExifInterface.TAG_ORIENTATION`) reduced to the
 * transforms that matter before perceptual hashing.
 *
 * Two images of the same photo taken with different device orientations differ only by
 * one of these transforms, so the orientation MUST be applied before hashing or they
 * would never be recognized as near-duplicates (specification §22).
 */
enum class ExifOrientation(val code: Int) {
    NORMAL(1),
    FLIP_HORIZONTAL(2),
    ROTATE_180(3),
    FLIP_VERTICAL(4),
    TRANSPOSE(5),
    ROTATE_90(6),
    TRANSVERSE(7),
    ROTATE_270(8),
    ;

    /** True when the transform swaps width and height. */
    val swapsAxes: Boolean
        get() = this == ROTATE_90 || this == ROTATE_270 || this == TRANSPOSE || this == TRANSVERSE

    companion object {
        /** Unknown or corrupt orientation values fall back to [NORMAL]. */
        fun fromExifCode(code: Int): ExifOrientation =
            entries.firstOrNull { it.code == code } ?: NORMAL
    }
}

/**
 * A small grayscale image (luminance in 0..255 per pixel).
 *
 * Deliberately a plain, allocation-conscious value type: perceptual hashing only ever
 * operates on reduced-size decode results (never full-size bitmaps), so this class keeps
 * every pixel operation pure Kotlin and host-testable without Android graphics
 * (specification §21: bounded buffers, reduced-size decoding).
 */
class GrayImage(
    val width: Int,
    val height: Int,
    pixels: FloatArray,
) {
    private val pixels: FloatArray = pixels

    init {
        require(width > 0 && height > 0) { "Invalid image size ${width}x$height" }
        require(pixels.size == width * height) {
            "Pixel buffer ${pixels.size} does not match ${width}x$height"
        }
    }

    val sizeBytes: Int get() = pixels.size * Float.SIZE_BYTES

    operator fun get(x: Int, y: Int): Float {
        require(x in 0 until width && y in 0 until height) { "Out of bounds ($x, $y)" }
        return pixels[y * width + x]
    }

    /** Mean luminance over all pixels. */
    fun mean(): Float {
        var sum = 0.0
        for (value in pixels) sum += value
        return (sum / pixels.size).toFloat()
    }

    /** Applies an EXIF orientation transform, returning a correctly upright image. */
    fun oriented(orientation: ExifOrientation): GrayImage {
        if (orientation == ExifOrientation.NORMAL) return this
        val (dstWidth, dstHeight) = PixelOrientation.destinationSize(width, height, orientation)
        return remap(dstWidth, dstHeight) { x, y ->
            PixelOrientation.sourceIndex(x, y, width, height, orientation)
        }
    }

    /** Bilinear resize to exactly [newWidth] x [newHeight]; area-averaged when shrinking. */
    fun resized(newWidth: Int, newHeight: Int): GrayImage {
        require(newWidth > 0 && newHeight > 0) { "Invalid target size ${newWidth}x$newHeight" }
        if (newWidth == width && newHeight == height) return GrayImage(width, height, pixels.copyOf())
        if (newWidth <= width && newHeight <= height) return areaResized(newWidth, newHeight)

        val out = FloatArray(newWidth * newHeight)
        val xScale = width.toFloat() / newWidth
        val yScale = height.toFloat() / newHeight
        for (y in 0 until newHeight) {
            val sy = ((y + 0.5f) * yScale - 0.5f).coerceIn(0f, (height - 1).toFloat())
            val y0 = sy.toInt()
            val y1 = (y0 + 1).coerceAtMost(height - 1)
            val fy = sy - y0
            for (x in 0 until newWidth) {
                val sx = ((x + 0.5f) * xScale - 0.5f).coerceIn(0f, (width - 1).toFloat())
                val x0 = sx.toInt()
                val x1 = (x0 + 1).coerceAtMost(width - 1)
                val fx = sx - x0
                val top = get(x0, y0) * (1f - fx) + get(x1, y0) * fx
                val bottom = get(x0, y1) * (1f - fx) + get(x1, y1) * fx
                out[y * newWidth + x] = top * (1f - fy) + bottom * fy
            }
        }
        return GrayImage(newWidth, newHeight, out)
    }

    /** Scales the longest edge down to [maxDimension], preserving aspect ratio. */
    fun fitWithin(maxDimension: Int): GrayImage {
        require(maxDimension > 0) { "maxDimension must be positive" }
        val longest = maxOf(width, height)
        if (longest <= maxDimension) return this
        val scale = maxDimension.toFloat() / longest
        return resized(
            (width * scale).roundToInt().coerceAtLeast(1),
            (height * scale).roundToInt().coerceAtLeast(1),
        )
    }

    /** Center-crops the largest square and resizes it to [size] x [size]. */
    fun centerCropSquare(size: Int): GrayImage {
        require(size > 0) { "size must be positive" }
        val side = minOf(width, height)
        val left = (width - side) / 2
        val top = (height - side) / 2
        if (side == width && side == height && side == size) return this

        val cropped = FloatArray(side * side)
        for (y in 0 until side) {
            val sourceRow = (top + y) * width + left
            pixels.copyInto(cropped, destinationOffset = y * side, startIndex = sourceRow, endIndex = sourceRow + side)
        }
        return GrayImage(side, side, cropped).resized(size, size)
    }

    /** Rebuilds the image from a destination-to-source pixel index mapping. */
    private fun remap(dstWidth: Int, dstHeight: Int, sourceIndex: (x: Int, y: Int) -> Int): GrayImage {
        val out = FloatArray(dstWidth * dstHeight)
        for (y in 0 until dstHeight) {
            val rowOffset = y * dstWidth
            for (x in 0 until dstWidth) {
                out[rowOffset + x] = pixels[sourceIndex(x, y)]
            }
        }
        return GrayImage(dstWidth, dstHeight, out)
    }

    /**
     * Separable box-filter resize. Averaging every source pixel that falls into a
     * destination cell (instead of 2-tap bilinear) removes the aliasing that would
     * otherwise make two resolutions of the same photo hash differently — this is what
     * keeps a 64 px and a 128 px render of one image within the strong threshold.
     */
    private fun areaResized(newWidth: Int, newHeight: Int): GrayImage {
        // Horizontal pass.
        val rowCache = FloatArray(newWidth * height)
        for (y in 0 until height) {
            val sourceRow = y * width
            val targetRow = y * newWidth
            for (x in 0 until newWidth) {
                val start = x.toDouble() * width / newWidth
                val end = (x + 1).toDouble() * width / newWidth
                var from = floor(start).toInt().coerceIn(0, width - 1)
                val to = ceil(end).toInt().coerceAtMost(width)
                var weighted = 0.0
                var weightSum = 0.0
                while (from < to) {
                    val overlap = min(end, from + 1.0) - max(start, from.toDouble())
                    if (overlap > 0.0) {
                        weighted += pixels[sourceRow + from] * overlap
                        weightSum += overlap
                    }
                    from++
                }
                rowCache[targetRow + x] = (weighted / weightSum).toFloat()
            }
        }
        // Vertical pass.
        val out = FloatArray(newWidth * newHeight)
        for (y in 0 until newHeight) {
            val start = y.toDouble() * height / newHeight
            val end = (y + 1).toDouble() * height / newHeight
            var from = floor(start).toInt().coerceIn(0, height - 1)
            val to = ceil(end).toInt().coerceAtMost(height)
            val targetRow = y * newWidth
            while (from < to) {
                val overlap = min(end, from + 1.0) - max(start, from.toDouble())
                if (overlap > 0.0) {
                    val sourceRow = from * newWidth
                    for (x in 0 until newWidth) {
                        out[targetRow + x] += rowCache[sourceRow + x] * overlap.toFloat()
                    }
                }
                from++
            }
            val span = (end - start).toFloat()
            if (span > 0f) {
                for (x in 0 until newWidth) out[targetRow + x] /= span
            }
        }
        return GrayImage(newWidth, newHeight, out)
    }

    companion object {
        /**
         * Converts packed ARGB pixels to luminance.
         *
         * Uses the Rec.601 coefficients applied to non-premultiplied channels, matching
         * what the perceptual hash expects.
         */
        fun fromArgb(width: Int, height: Int, argb: IntArray): GrayImage {
            require(argb.size == width * height) {
                "Pixel buffer ${argb.size} does not match ${width}x$height"
            }
            val out = FloatArray(argb.size)
            for (i in argb.indices) {
                val color = argb[i]
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF
                out[i] = 0.299f * r + 0.587f * g + 0.114f * b
            }
            return GrayImage(width, height, out)
        }

        /** Fills an image with [value]; used by tests and synthetic fixtures. */
        fun filled(width: Int, height: Int, value: Float): GrayImage =
            GrayImage(width, height, FloatArray(width * height) { value })
    }
}
