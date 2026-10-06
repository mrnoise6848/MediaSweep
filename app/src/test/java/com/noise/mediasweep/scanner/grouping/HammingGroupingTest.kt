package com.noise.mediasweep.scanner.grouping

import com.noise.mediasweep.scanner.image.HashDistance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class HammingGroupingTest {

    /** Reference implementation: transitive closure by exhaustive pairwise comparison. */
    private fun bruteForce(hashes: List<ULong>, radius: Int): Set<Set<Int>> {
        val parent = IntArray(hashes.size) { it }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            return r
        }
        for (i in hashes.indices) for (j in i + 1 until hashes.size) {
            if ((hashes[i] xor hashes[j]).countOneBits() <= radius) parent[find(j)] = find(i)
        }
        return hashes.indices.groupBy { find(it) }.values.map { it.toSet() }.toSet()
    }

    private fun asSets(clusters: List<List<Int>>): Set<Set<Int>> = clusters.map { it.toSet() }.toSet()

    @Test
    fun `identical hashes cluster together even at radius zero`() {
        val clusters = HammingGrouping.group(listOf(0UL, 0UL, 1UL), radius = 0)

        assertEquals(setOf(setOf(0, 1), setOf(2)), asSets(clusters))
    }

    @Test
    fun `an empty input produces no clusters`() {
        assertTrue(HammingGrouping.group(emptyList(), radius = 6).isEmpty())
    }

    @Test
    fun `chain of near hashes groups transitively`() {
        // 0 <-> 2 bits <-> 4 bits: 1 and 3 are 8 apart but connected through 2.
        val base = 0UL
        val twoBits = 0b11UL
        val fourBits = 0b1111UL
        val clusters = HammingGrouping.group(listOf(base, twoBits, fourBits), radius = 4)

        assertEquals(setOf(setOf(0, 1, 2)), asSets(clusters))
    }

    @Test
    fun `bk-tree grouping matches brute force exactly on random hashes`() {
        val random = Random(42)
        val hashes = ArrayList<ULong>(400)
        repeat(300) { hashes.add(random.nextLong().toULong()) }
        // Sprinkle guaranteed near-neighbours (0..4 flipped bits) around some bases.
        repeat(100) {
            val base = random.nextLong().toULong()
            hashes.add(base)
            repeat(3) {
                var variant = base
                repeat(random.nextInt(0, 5)) {
                    variant = variant xor (1UL shl random.nextInt(64))
                }
                hashes.add(variant)
            }
        }

        for (radius in listOf(0, 2, 4, 6)) {
            assertEquals(
                "radius $radius",
                bruteForce(hashes, radius),
                asSets(HammingGrouping.group(hashes, radius)),
            )
        }
    }

    @Test
    fun `every cluster member has a neighbour within the radius`() {
        val random = Random(7)
        val hashes = (0 until 200).map { random.nextLong().toULong() }
        val radius = 6
        val clusters = HammingGrouping.group(hashes, radius)

        for (cluster in clusters.filter { it.size > 1 }) {
            for (member in cluster) {
                val nearest: Int = cluster.filter { it != member }
                    .minOf { other -> HashDistance.hamming(hashes[member], hashes[other]) }
                assertTrue("member $member has no neighbour within $radius", nearest <= radius)
            }
        }
    }
}
