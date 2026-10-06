package com.noise.mediasweep.scanner.image

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.noise.mediasweep.domain.model.MediaItem
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Decodes one media item into a reduced grayscale representation for perceptual hashing.
 *
 * Returns `null` when the item cannot be decoded (corrupt data, unsupported format,
 * revoked access); callers must treat that as SKIP_WITH_REASON, never as a scan failure
 * (specification §22).
 */
interface ReducedImageDecoder {
    suspend fun decode(item: MediaItem): GrayImage?
}

/**
 * ContentResolver-backed decoder honoring the image processing rules of §21/§22:
 *
 * - decodes only reduced dimensions (power-of-two subsample, then fit into
 *   [targetSize]), so memory stays bounded no matter how large the source photo is;
 * - applies EXIF orientation before the pixels reach the hash (BitmapFactory does not
 *   apply it), so rotated copies of a photo still match;
 * - opens each stream twice (bounds pass, pixel pass) and closes both deterministically;
 * - any I/O or decode error degrades to `null` instead of failing the scan.
 */
class AndroidReducedImageDecoder(
    private val resolver: ContentResolver,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Longest edge of the reduced decode; 128 px is ample for a 32x32 DCT block. */
    private val targetSize: Int = 128,
) : ReducedImageDecoder {

    override suspend fun decode(item: MediaItem): GrayImage? {
        if (!item.isImage) return null
        return withContext(ioDispatcher) {
            try {
                val uri = Uri.parse(item.contentUri)
                val bounds = readBounds(uri) ?: return@withContext null
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sampleSizeFor(bounds.first, bounds.second, targetSize)
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val bitmap = openStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                    ?: return@withContext null
                try {
                    val gray = GrayImage.fromArgb(
                        width = bitmap.width,
                        height = bitmap.height,
                        argb = IntArray(bitmap.width * bitmap.height).also { pixels ->
                            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                        },
                    )
                    gray.oriented(readOrientation(uri)).fitWithin(targetSize)
                } finally {
                    // The reduced decode is a temporary buffer; release it immediately so
                    // long scans never accumulate bitmaps (§21).
                    bitmap.recycle()
                }
            } catch (_: IOException) {
                null
            } catch (_: RuntimeException) {
                // SecurityException/OOM/decoder errors on hostile or corrupt input: skip.
                null
            }
        }
    }

    private fun openStream(uri: Uri): java.io.InputStream? = resolver.openInputStream(uri)

    private fun readBounds(uri: Uri): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        return if (options.outWidth > 0 && options.outHeight > 0) {
            options.outWidth to options.outHeight
        } else {
            null
        }
    }

    private fun readOrientation(uri: Uri): ExifOrientation =
        ExifOrientationReader.read(resolver, uri)

    companion object {
        /**
         * Largest power-of-two subsample that still decodes at least [targetSize] on the
         * longest edge (two bounded passes, never a full-resolution bitmap).
         */
        fun sampleSizeFor(width: Int, height: Int, targetSize: Int): Int {
            var sample = 1
            val longest = maxOf(width, height)
            while (longest / (sample * 2) >= targetSize) sample *= 2
            return sample
        }
    }
}

/** Reads the EXIF orientation of a content URI; corrupt or absent metadata is NORMAL. */
object ExifOrientationReader {
    fun read(resolver: ContentResolver, uri: Uri): ExifOrientation = try {
        resolver.openInputStream(uri)?.use { stream ->
            val exif = ExifInterface(stream)
            ExifOrientation.fromExifCode(
                exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL),
            )
        } ?: ExifOrientation.NORMAL
    } catch (_: IOException) {
        // Formats without EXIF metadata (PNG/WebP/...) simply have no orientation.
        ExifOrientation.NORMAL
    } catch (_: RuntimeException) {
        ExifOrientation.NORMAL
    }
}
