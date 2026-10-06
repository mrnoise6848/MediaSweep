package com.noise.mediasweep.core.common

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.TimeZone

class FormatTest {

    private lateinit var originalTimeZone: TimeZone

    @Before
    fun pinTimeZone() {
        originalTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreTimeZone() {
        TimeZone.setDefault(originalTimeZone)
    }

    @Test
    fun `zero and negative bytes render as zero`() {
        assertEquals("0 B", formatBytes(0L))
        assertEquals("0 B", formatBytes(-5L))
    }

    @Test
    fun `bytes below one kibibyte are not scaled`() {
        assertEquals("512 B", formatBytes(512L))
        assertEquals("1023 B", formatBytes(1023L))
    }

    @Test
    fun `larger values use readable units with trimmed decimals`() {
        assertEquals("1 KB", formatBytes(1024L))
        assertEquals("1.5 MB", formatBytes((1.5 * 1024 * 1024).toLong()))
        assertEquals("1.76 GB", formatBytes(1_887_436_800L))
        assertEquals("500 MB", formatBytes(500L * 1024 * 1024))
    }

    @Test
    fun `counts are grouped by thousands`() {
        assertEquals("12,240", formatCount(12240L))
        assertEquals("0", formatCount(0L))
        assertEquals("4,832 / 12,240", formatProgress(4832L, 12240L))
    }

    @Test
    fun `age is measured in whole days`() {
        val now = 1_700_000_000L
        assertEquals(0, ageInDays(now, now))
        assertEquals(180, ageInDays(now - 180L * 24 * 60 * 60, now))
    }

    @Test
    fun `media dates render as a readable calendar date`() {
        // 1_700_000_000 == 2023-11-14T22:13:20Z; the test pins UTC above.
        assertEquals("Nov 14, 2023", formatDate(1_700_000_000L))
        assertEquals("Jan 1, 1970", formatDate(0L))
    }
}
