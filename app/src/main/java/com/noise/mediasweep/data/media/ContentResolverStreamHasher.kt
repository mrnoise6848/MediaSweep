package com.noise.mediasweep.data.media

import android.content.ContentResolver
import android.net.Uri
import com.noise.mediasweep.scanner.hashing.Sha256
import com.noise.mediasweep.scanner.hashing.StreamHasher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Streams file content straight from MediaStore into the hash, with a bounded buffer.
 * Never loads the whole file into memory.
 */
class ContentResolverStreamHasher(
    private val contentResolver: ContentResolver,
) : StreamHasher {

    override suspend fun sha256(contentUri: String): String? = withContext(Dispatchers.IO) {
        try {
            val stream = contentResolver.openInputStream(Uri.parse(contentUri)) ?: return@withContext null
            Sha256.hex(stream)
        } catch (e: Exception) {
            // Deleted, revoked or unreadable content: skip with a reason, keep scanning.
            null
        }
    }
}
