package com.noise.mediasweep.scanner.image

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidReducedImageDecoderTest {

    @Test
    fun `small images decode at full resolution`() {
        assertEquals(1, AndroidReducedImageDecoder.sampleSizeFor(100, 80, 128))
    }

    @Test
    fun `images at exactly the target size are not subsampled`() {
        assertEquals(1, AndroidReducedImageDecoder.sampleSizeFor(128, 128, 128))
    }

    @Test
    fun `subsampling never drops below the target resolution`() {
        // 4000 / 16 = 250 px (>= 128), 4000 / 32 = 125 px (< 128) => 16.
        assertEquals(16, AndroidReducedImageDecoder.sampleSizeFor(4000, 3000, 128))
        assertEquals(2, AndroidReducedImageDecoder.sampleSizeFor(256, 256, 128))
        assertEquals(8, AndroidReducedImageDecoder.sampleSizeFor(1400, 900, 128))
    }

    @Test
    fun `the longest edge drives the subsample even in landscape`() {
        assertEquals(16, AndroidReducedImageDecoder.sampleSizeFor(3000, 500, 128))
    }
}
