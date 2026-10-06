package com.noise.mediasweep.scanner.grouping

import com.noise.mediasweep.testing.mediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CheapGrouperTest {

    private val grouper = CheapGrouper()

    @Test
    fun `items with matching metadata share a bucket`() {
        val buckets = grouper.buckets(
            listOf(
                mediaItem(1L, sizeBytes = 1_000L),
                mediaItem(2L, sizeBytes = 1_000L),
                mediaItem(3L, sizeBytes = 9_999L),
            ),
        )

        assertEquals(1, buckets.size)
        assertEquals(setOf(1L, 2L), buckets.first().items.map { it.id }.toSet())
    }

    @Test
    fun `single items are dropped because they cannot be duplicates`() {
        val buckets = grouper.buckets(
            listOf(
                mediaItem(1L, sizeBytes = 10L),
                mediaItem(2L, sizeBytes = 20L),
                mediaItem(3L, sizeBytes = 30L),
            ),
        )

        assertTrue(buckets.isEmpty())
    }

    @Test
    fun `dimensions separate otherwise identical metadata`() {
        val buckets = grouper.buckets(
            listOf(
                mediaItem(1L, sizeBytes = 500L, width = 100, height = 100),
                mediaItem(2L, sizeBytes = 500L, width = 200, height = 200),
                mediaItem(3L, sizeBytes = 500L, width = 100, height = 100),
            ),
        )

        assertEquals(1, buckets.size)
        assertEquals(setOf(1L, 3L), buckets.first().items.map { it.id }.toSet())
    }

    @Test
    fun `a bucket is only a candidate, never a duplicate declaration`() {
        // Same size and dimensions: must land in one bucket, but nothing here says they match.
        val buckets = grouper.buckets(
            listOf(
                mediaItem(1L, sizeBytes = 777L),
                mediaItem(2L, sizeBytes = 777L),
            ),
        )

        assertEquals(1, buckets.size)
        assertEquals(2, buckets.first().items.size)
    }
}
