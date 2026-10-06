package com.noise.mediasweep.scanner.image

import com.noise.mediasweep.testing.patternImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PerceptualHashTest {

    private val hasher = DctImageHasher()

    @Test
    fun `hamming distance vectors from the specification`() {
        assertEquals(64, HashDistance.hamming(0xFFFFFFFFFFFFFFFFUL, 0x0000000000000000UL))
        assertEquals(0, HashDistance.hamming(0x0123456789ABCDEFUL, 0x0123456789ABCDEFUL))
        assertEquals(64, HashDistance.hamming(0x0123456789ABCDEFUL, 0xFEDCBA9876543210UL))
        assertEquals(32, HashDistance.hamming(0x1111111111111111UL, 0x2222222222222222UL))
        assertEquals(4, HashDistance.hamming(0x00000000000000FFUL, 0x000000000000000FUL))
        assertEquals(2, HashDistance.hamming(1UL shl 63, 1UL))
    }

    @Test
    fun `the same image always hashes identically`() {
        val image = patternImage(seed = 7L, size = 64)
        val first = hasher.hash(image)
        val second = hasher.hash(image)

        assertEquals(0, HashDistance.hamming(first.pHash, second.pHash))
        assertEquals(first, second)
    }

    @Test
    fun `identical content at different resolutions stays within the strong threshold`() {
        val original = patternImage(seed = 7L, size = 64)
        val upscaled = patternImage(seed = 7L, size = 128)

        val distance = HashDistance.hamming(hasher.hash(original).pHash, hasher.hash(upscaled).pHash)

        assertTrue("distance $distance must be within the strong threshold", distance <= 6)
    }

    @Test
    fun `brightness shifts do not change the perceptual hash`() {
        val base = patternImage(seed = 11L, size = 64)
        val brighter = GrayImage(64, 64, FloatArray(64 * 64) { index ->
            (base[index % 64, index / 64] + 20f).coerceAtMost(255f)
        })

        val distance = HashDistance.hamming(hasher.hash(base).pHash, hasher.hash(brighter).pHash)

        assertTrue("distance $distance should be brightness invariant", distance <= 2)
    }

    @Test
    fun `unrelated images have a large hamming distance`() {
        val first = patternImage(seed = 1L, size = 64)
        val second = patternImage(seed = 2L, size = 64)

        val distance = HashDistance.hamming(hasher.hash(first).pHash, hasher.hash(second).pHash)

        assertTrue("distance $distance must exceed the grouping radius", distance > 10)
    }

    @Test
    fun `difference hash is also brightness invariant`() {
        val base = patternImage(seed = 3L, size = 64)
        val brighter = GrayImage(64, 64, FloatArray(64 * 64) { index ->
            (base[index % 64, index / 64] + 15f).coerceAtMost(255f)
        })

        val distance = HashDistance.hamming(hasher.hash(base).dHash, hasher.hash(brighter).dHash)

        assertTrue("dHash distance $distance should be brightness invariant", distance <= 2)
    }
}
