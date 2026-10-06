package com.noise.mediasweep.scanner.image

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * The three classic 64-bit perceptual hashes computed from one reduced image.
 *
 * [pHash] (DCT-based) is the primary signal for grouping; [dHash] and [aHash] are kept
 * because they are cheap to derive from the same decode and are useful as secondary
 * votes / diagnostics (specification §41: configurable thresholds, no hardcoded values).
 */
data class ImageHashes(
    val pHash: ULong,
    val dHash: ULong,
    val aHash: ULong,
)

/** Hamming distance over 64-bit hashes. */
object HashDistance {
    fun hamming(a: ULong, b: ULong): Int = (a xor b).countOneBits()
}

/** Computes perceptual hashes from a reduced grayscale image. */
interface ImageHasher {
    fun hash(image: GrayImage): ImageHashes
}

/**
 * Perceptual hashing over a reduced grayscale decode (specification §7.2 pipeline:
 * decode reduced image → normalize size → grayscale → perceptual hash → Hamming distance).
 *
 * - pHash: 32x32 center crop, separable DCT-II, 8x8 low-frequency block thresholded at
 *   the median of the AC coefficients (rotation/scale/quality tolerant).
 * - dHash: 9x8 gradient comparison (robust to brightness ramps).
 * - aHash: 8x8 mean threshold (fast, most sensitive).
 */
class DctImageHasher : ImageHasher {

    override fun hash(image: GrayImage): ImageHashes = ImageHashes(
        pHash = perceptualHash(image),
        dHash = differenceHash(image),
        aHash = averageHash(image),
    )

    fun perceptualHash(image: GrayImage): ULong {
        val normalized = image.centerCropSquare(DCT_SIZE)
        val coefficients = dct2(normalized)

        // The 8x8 low-frequency block lives at the top-left of the transform, stored
        // row-major with a stride of DCT_SIZE.
        fun lowFrequency(bit: Int): Float = coefficients[(bit / BLOCK) * DCT_SIZE + (bit % BLOCK)]

        // Median over the AC coefficients only: the DC coefficient is a large positive
        // outlier that would skew a mean threshold toward "all bits set".
        val acValues = FloatArray(BLOCK * BLOCK - 1)
        var write = 0
        for (bit in 1 until BLOCK * BLOCK) acValues[write++] = lowFrequency(bit)
        val sorted = acValues.sorted()
        val threshold = sorted[sorted.size / 2]

        var hash = 0UL
        for (bit in 0 until HASH_BITS) {
            hash = hash shl 1
            if (lowFrequency(bit) > threshold) hash = hash or 1UL
        }
        return hash
    }

    fun differenceHash(image: GrayImage): ULong {
        val normalized = image.resized(DHASH_WIDTH, DHASH_HEIGHT)
        var hash = 0UL
        for (y in 0 until DHASH_HEIGHT) {
            for (x in 0 until DHASH_WIDTH - 1) {
                hash = hash shl 1
                if (normalized[x + 1, y] > normalized[x, y]) hash = hash or 1UL
            }
        }
        return hash
    }

    fun averageHash(image: GrayImage): ULong {
        val normalized = image.centerCropSquare(AHASH_SIZE)
        val threshold = normalized.mean()
        var hash = 0UL
        for (y in 0 until AHASH_SIZE) {
            for (x in 0 until AHASH_SIZE) {
                hash = hash shl 1
                if (normalized[x, y] > threshold) hash = hash or 1UL
            }
        }
        return hash
    }

    /**
     * Separable orthonormal 2-D DCT-II; returns the coefficients row-major. Only the
     * 8x8 low-frequency block is ever read, but computing the full transform keeps the
     * implementation simple and is still trivial at 32x32.
     */
    private fun dct2(image: GrayImage): FloatArray {
        val n = image.width
        val rows = FloatArray(n * n)
        for (y in 0 until n) {
            val rowOffset = y * n
            for (u in 0 until n) {
                var sum = 0f
                val tableOffset = u * n
                for (x in 0 until n) sum += image[x, y] * COSINE[tableOffset + x]
                rows[rowOffset + u] = sum
            }
        }
        val out = FloatArray(n * n)
        for (u in 0 until n) {
            for (v in 0 until n) {
                var sum = 0f
                val tableOffset = v * n
                for (y in 0 until n) sum += rows[y * n + u] * COSINE[tableOffset + y]
                out[v * n + u] = sum
            }
        }
        return out
    }

    companion object {
        /** DCT / low-frequency block size. */
        const val DCT_SIZE = 32
        /** Side length of the square low-frequency block packed into the pHash. */
        const val BLOCK = 8
        /** Number of coefficients packed into the pHash. */
        const val HASH_BITS = BLOCK * BLOCK
        const val DHASH_WIDTH = 9
        const val DHASH_HEIGHT = 8
        const val AHASH_SIZE = 8

        private const val TABLE_SIZE = DCT_SIZE * DCT_SIZE

        /** Orthonormal DCT-II basis: cos((2x+1)u pi / 2N) with the usual scaling. */
        private val COSINE: FloatArray = FloatArray(TABLE_SIZE) { index ->
            val u = index / DCT_SIZE
            val x = index % DCT_SIZE
            val scale = if (u == 0) sqrt(1.0 / DCT_SIZE) else sqrt(2.0 / DCT_SIZE)
            (scale * cos(((2 * x + 1) * u * PI) / (2 * DCT_SIZE))).toFloat()
        }
    }
}
