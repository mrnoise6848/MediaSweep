package com.noise.mediasweep.scanner.grouping

import com.noise.mediasweep.scanner.image.HashDistance

/**
 * Exact clustering of 64-bit hashes by Hamming distance.
 *
 * A naive pairwise scan is O(n^2) which is not acceptable for large libraries
 * (specification §21), while prefix bucketing loses recall (two hashes within a small
 * Hamming radius can differ in any bit, including the bucketed prefix). A BK-tree gives
 * exact, sublinear queries in Hamming space, and the transitive clusters are built with
 * union-find, so grouping is O(n log n) in practice and never misses a pair.
 */
object HammingGrouping {

    /**
     * Returns clusters of indices such that every item is connected to at least one
     * other item within [radius] Hamming distance. Clusters are ordered by their first
     * member; singletons are included so callers can decide what to surface.
     */
    fun group(hashes: List<ULong>, radius: Int): List<List<Int>> {
        if (hashes.isEmpty()) return emptyList()
        require(radius >= 0) { "radius must not be negative" }

        val parent = IntArray(hashes.size) { it }

        fun find(index: Int): Int {
            var root = index
            while (parent[root] != root) root = parent[root]
            var cursor = index
            while (parent[cursor] != cursor) {
                val next = parent[cursor]
                parent[cursor] = root
                cursor = next
            }
            return root
        }

        fun union(a: Int, b: Int) {
            val rootA = find(a)
            val rootB = find(b)
            if (rootA != rootB) parent[rootB] = rootA
        }

        val tree = HammingBKTree(radius)
        hashes.forEachIndexed { id, hash ->
            tree.query(hash) { other -> union(id, other) }
            tree.insert(hash, id)
        }

        val clusters = LinkedHashMap<Int, MutableList<Int>>(hashes.size)
        for (id in hashes.indices) clusters.getOrPut(find(id)) { ArrayList(2) }.add(id)
        return clusters.values.toList()
    }
}

/**
 * BK-tree over 64-bit hashes with the Hamming metric.
 *
 * Children are keyed by distance to their parent, so a radius query only descends into
 * subtrees whose edge distance lies in `[d - radius, d + radius]` (triangle inequality).
 */
internal class HammingBKTree(private val radius: Int) {

    private class Node(val hash: ULong, val id: Int) {
        val children = HashMap<Int, Node>()
    }

    private var root: Node? = null

    fun insert(hash: ULong, id: Int) {
        val rootNode = root
        if (rootNode == null) {
            root = Node(hash, id)
        } else {
            insertChild(rootNode, hash, id)
        }
    }

    private fun insertChild(parent: Node, hash: ULong, id: Int) {
        val distance = HashDistance.hamming(hash, parent.hash)
        val child = parent.children[distance]
        if (child == null) {
            parent.children[distance] = Node(hash, id)
        } else {
            insertChild(child, hash, id)
        }
    }

    /** Invokes [onMatch] for every stored id whose hash is within [radius] of [hash]. */
    fun query(hash: ULong, onMatch: (id: Int) -> Unit) {
        val currentNode = root ?: return
        val stack = ArrayDeque<Node>()
        stack.addLast(currentNode)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            val distance = HashDistance.hamming(hash, node.hash)
            if (distance <= radius) onMatch(node.id)
            for ((edge, child) in node.children) {
                if (edge >= distance - radius && edge <= distance + radius) stack.addLast(child)
            }
        }
    }
}
