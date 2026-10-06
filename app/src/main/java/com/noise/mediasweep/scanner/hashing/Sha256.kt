package com.noise.mediasweep.scanner.hashing

import java.io.InputStream
import java.security.MessageDigest

/**
 * Streaming SHA-256.
 *
 * Reads in a fixed-size buffer so a 2 GB video never becomes a 2 GB `byte[]`.
 */
object Sha256 {

    private const val BUFFER_BYTES = 8 * 1024

    fun hex(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_BYTES)
        input.use { stream ->
            while (true) {
                val read = stream.read(buffer)
                if (read == -1) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    fun hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .toHex()

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        ((byte.toInt() and 0xFF) + 0x100).toString(16).substring(1)
    }
}
