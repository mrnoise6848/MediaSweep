package com.noise.mediasweep.scanner.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GrayImageTest {

    private fun sequential(width: Int, height: Int): GrayImage =
        GrayImage(width, height, FloatArray(width * height) { it.toFloat() })

    @Test
    fun `constructor rejects mismatched pixel buffers`() {
        assertTrue(runCatching { GrayImage(2, 2, FloatArray(3)) }.isFailure)
        assertTrue(runCatching { GrayImage(0, 4, FloatArray(0)) }.isFailure)
    }

    @Test
    fun `argb luminance matches the Rec 601 coefficients`() {
        val image = GrayImage.fromArgb(3, 1, intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt()))
        assertEquals(0.299f * 255f, image[0, 0], 0.01f)
        assertEquals(0.587f * 255f, image[1, 0], 0.01f)
        assertEquals(0.114f * 255f, image[2, 0], 0.01f)
    }

    @Test
    fun `rotate 90 clockwise maps every pixel to its transposed position`() {
        // Source (2 wide, 3 high):
        // 0 1
        // 2 3
        // 4 5
        val rotated = sequential(2, 3).oriented(ExifOrientation.ROTATE_90)

        assertEquals(3, rotated.width)
        assertEquals(2, rotated.height)
        // Expected clockwise rotation:
        // 4 2 0
        // 5 3 1
        assertEquals(4f, rotated[0, 0], 0f)
        assertEquals(2f, rotated[1, 0], 0f)
        assertEquals(0f, rotated[2, 0], 0f)
        assertEquals(5f, rotated[0, 1], 0f)
        assertEquals(3f, rotated[1, 1], 0f)
        assertEquals(1f, rotated[2, 1], 0f)
    }

    @Test
    fun `rotate 90 followed by rotate 270 restores the original pixels`() {
        val original = sequential(2, 3)
        val restored = original.oriented(ExifOrientation.ROTATE_90).oriented(ExifOrientation.ROTATE_270)

        assertEquals(original.width, restored.width)
        assertEquals(original.height, restored.height)
        for (y in 0 until original.height) {
            for (x in 0 until original.width) {
                assertEquals("($x,$y)", original[x, y], restored[x, y], 0f)
            }
        }
    }

    @Test
    fun `every EXIF orientation round-trips back to the original`() {
        val original = sequential(4, 3)
        // ROTATE_180, FLIP_* and TRANSVERSE are their own inverse; the others invert via
        // the complementary transform.
        val inverses = mapOf(
            ExifOrientation.NORMAL to ExifOrientation.NORMAL,
            ExifOrientation.ROTATE_180 to ExifOrientation.ROTATE_180,
            ExifOrientation.FLIP_HORIZONTAL to ExifOrientation.FLIP_HORIZONTAL,
            ExifOrientation.FLIP_VERTICAL to ExifOrientation.FLIP_VERTICAL,
            ExifOrientation.ROTATE_90 to ExifOrientation.ROTATE_270,
            ExifOrientation.TRANSPOSE to ExifOrientation.TRANSPOSE,
            ExifOrientation.TRANSVERSE to ExifOrientation.TRANSVERSE,
        )
        for ((orientation, inverse) in inverses) {
            val restored = original.oriented(orientation).oriented(inverse)
            for (y in 0 until original.height) {
                for (x in 0 until original.width) {
                    assertEquals("$orientation ($x,$y)", original[x, y], restored[x, y], 0f)
                }
            }
        }
    }

    @Test
    fun `horizontal flip mirrors the x axis`() {
        val flipped = sequential(3, 1).oriented(ExifOrientation.FLIP_HORIZONTAL)
        assertEquals(2f, flipped[0, 0], 0f)
        assertEquals(1f, flipped[1, 0], 0f)
        assertEquals(0f, flipped[2, 0], 0f)
    }

    @Test
    fun `fitWithin scales the longest edge down and keeps the aspect ratio`() {
        val fitted = GrayImage(200, 100, FloatArray(200 * 100)).fitWithin(64)
        assertEquals(64, fitted.width)
        assertEquals(32, fitted.height)
    }

    @Test
    fun `fitWithin keeps small images untouched`() {
        val image = GrayImage(32, 16, FloatArray(32 * 16))
        val fitted = image.fitWithin(64)
        assertEquals(32, fitted.width)
        assertEquals(16, fitted.height)
    }

    @Test
    fun `centerCropSquare crops the centered square before resizing`() {
        // 4 wide, 2 high with distinct values; the centered square is columns 1..2.
        val source = sequential(4, 2)
        val cropped = source.centerCropSquare(2)

        assertEquals(2, cropped.width)
        assertEquals(2, cropped.height)
        assertEquals(1f, cropped[0, 0], 0f)
        assertEquals(2f, cropped[1, 0], 0f)
        assertEquals(5f, cropped[0, 1], 0f)
        assertEquals(6f, cropped[1, 1], 0f)
    }

    @Test
    fun `resized keeps constant images constant`() {
        val resized = GrayImage.filled(9, 7, 42f).resized(3, 5)
        assertEquals(3, resized.width)
        assertEquals(5, resized.height)
        for (y in 0 until 5) for (x in 0 until 3) assertEquals(42f, resized[x, y], 0.01f)
    }

    @Test
    fun `mean averages every pixel`() {
        val image = GrayImage(4, 4, FloatArray(16) { if (it < 8) 10f else 30f })
        assertEquals(20f, image.mean(), 0.01f)
    }
}
