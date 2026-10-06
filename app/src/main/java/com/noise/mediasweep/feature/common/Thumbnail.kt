package com.noise.mediasweep.feature.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.noise.mediasweep.scanner.image.AndroidReducedImageDecoder
import com.noise.mediasweep.scanner.image.ExifOrientationReader
import com.noise.mediasweep.scanner.image.PixelOrientation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads a bounded, EXIF-upright thumbnail for one media item.
 *
 * Follows the memory rules of specification §21: never a full-size decode (power-of-two
 * subsample first), one item decoded at a time per composable, released as soon as the
 * list scrolls it away, and any failure degrades to a placeholder instead of crashing.
 */
internal fun loadThumbnail(context: Context, contentUri: String, targetSize: Int): ImageBitmap? {
    val resolver = context.contentResolver
    val uri = Uri.parse(contentUri)
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = AndroidReducedImageDecoder.sampleSizeFor(
                bounds.outWidth,
                bounds.outHeight,
                targetSize,
            )
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return null
        try {
            val (width, height) = decoded.width to decoded.height
            val pixels = IntArray(width * height)
            decoded.getPixels(pixels, 0, width, 0, 0, width, height)

            val orientation = ExifOrientationReader.read(resolver, uri)
            val oriented = PixelOrientation.orientArgb(width, height, pixels, orientation)
            val (orientedWidth, orientedHeight) =
                PixelOrientation.destinationSize(width, height, orientation)

            val bitmap = Bitmap.createBitmap(orientedWidth, orientedHeight, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(oriented, 0, orientedWidth, 0, 0, orientedWidth, orientedHeight)

            val longest = maxOf(bitmap.width, bitmap.height)
            if (longest <= targetSize) {
                bitmap.asImageBitmap()
            } else {
                val scale = targetSize.toFloat() / longest
                val scaled = Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
                if (scaled !== bitmap) bitmap.recycle()
                scaled.asImageBitmap()
            }
        } finally {
            decoded.recycle()
        }
    } catch (_: Exception) {
        // Corrupt/missing/unreadable media: SKIP_WITH_REASON, never a crash (§22/§24).
        null
    }
}

/**
 * Thumbnail with a lightweight placeholder. Decorative by default (row labels carry the
 * meaning); pass [contentDescription] when the image itself conveys information.
 */
@Composable
fun MediaThumbnail(
    contentUri: String,
    modifier: Modifier = Modifier,
    targetSize: Int = 128,
    size: Dp = 64.dp,
    contentDescription: String? = null,
) {
    val context = LocalContext.current
    val thumbnail by produceState<ImageBitmap?>(initialValue = null, contentUri, targetSize) {
        value = withContext(Dispatchers.IO) { loadThumbnail(context, contentUri, targetSize) }
    }

    Box(
        modifier = modifier
            .size(size)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val image = thumbnail
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size),
            )
        } else {
            Text(
                text = placeholderGlyph(contentUri),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun placeholderGlyph(contentUri: String): String =
    contentUri.lastOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"
