package com.noise.mediasweep.scanner.image

/**
 * Pure destination-to-source index mapping for the EXIF orientation transforms.
 *
 * Shared by the perceptual-hash pipeline ([GrayImage.oriented]) and the thumbnail loader
 * so both apply exactly the same, host-tested orientation math to raw pixels — no
 * android.graphics.Matrix involved.
 */
object PixelOrientation {

    /** Destination size after applying [orientation] to an image of [width] x [height]. */
    fun destinationSize(width: Int, height: Int, orientation: ExifOrientation): Pair<Int, Int> =
        if (orientation.swapsAxes) height to width else width to height

    /**
     * Source pixel index (row-major over the source image) that provides the color of
     * destination pixel ([x], [y]).
     */
    fun sourceIndex(x: Int, y: Int, width: Int, height: Int, orientation: ExifOrientation): Int =
        when (orientation) {
            ExifOrientation.NORMAL -> y * width + x
            ExifOrientation.FLIP_HORIZONTAL -> y * width + (width - 1 - x)
            ExifOrientation.FLIP_VERTICAL -> (height - 1 - y) * width + x
            ExifOrientation.ROTATE_180 -> (height - 1 - y) * width + (width - 1 - x)
            ExifOrientation.ROTATE_90 -> (height - 1 - x) * width + y
            ExifOrientation.ROTATE_270 -> x * width + (width - 1 - y)
            ExifOrientation.TRANSPOSE -> x * width + y
            ExifOrientation.TRANSVERSE -> (height - 1 - x) * width + (width - 1 - y)
        }

    /** Reorders packed ARGB pixels into their upright orientation. */
    fun orientArgb(width: Int, height: Int, pixels: IntArray, orientation: ExifOrientation): IntArray {
        if (orientation == ExifOrientation.NORMAL) return pixels
        require(pixels.size == width * height) { "Pixel buffer ${pixels.size} does not match ${width}x$height" }
        val (dstWidth, dstHeight) = destinationSize(width, height, orientation)
        val out = IntArray(dstWidth * dstHeight)
        for (y in 0 until dstHeight) {
            val rowOffset = y * dstWidth
            for (x in 0 until dstWidth) {
                out[rowOffset + x] = pixels[sourceIndex(x, y, width, height, orientation)]
            }
        }
        return out
    }
}
