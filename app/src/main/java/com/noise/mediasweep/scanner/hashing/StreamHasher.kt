package com.noise.mediasweep.scanner.hashing

/**
 * Reads the bytes behind a media content URI and returns a hex SHA-256.
 *
 * Returns null when the content cannot be read (file deleted, permission revoked, corrupt
 * provider response) so a single bad file never aborts a scan.
 */
fun interface StreamHasher {
    suspend fun sha256(contentUri: String): String?
}
