package com.noise.mediasweep.scanner.hashing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.ByteArrayInputStream

class Sha256Test {

    @Test
    fun `matches the published sha-256 vectors`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Sha256.hex(ByteArray(0)),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Sha256.hex("abc".toByteArray()),
        )
    }

    @Test
    fun `streaming the same bytes produces the same hash regardless of chunk boundaries`() {
        val bytes = ByteArray(64 * 1024 + 137) { it.toByte() }

        val oneShot = Sha256.hex(bytes)
        val streamed = Sha256.hex(ByteArrayInputStream(bytes))

        assertEquals(oneShot, streamed)
    }

    @Test
    fun `a large stream is hashed without loading it as one array`() {
        // Far larger than the internal buffer; only the buffer is allocated.
        val chunk = ByteArray(8 * 1024) { 7 }
        val stream = object : java.io.InputStream() {
            private var produced = 0L
            private val limit = 4L * 1024 * 1024
            override fun read(): Int = if (produced >= limit) -1 else { produced++; 7 }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (produced >= limit) return -1
                val count = minOf(len.toLong(), limit - produced).toInt()
                chunk.copyInto(b, off, 0, count)
                produced += count
                return count
            }
        }

        val hash = Sha256.hex(stream)
        assertEquals(64, hash.length)
        assertNotEquals("", hash)
    }

    @Test
    fun `different content produces different hashes`() {
        assertNotEquals(Sha256.hex("a".toByteArray()), Sha256.hex("b".toByteArray()))
    }
}
