package com.eddies.app.domain

/** Two or more assets that are really one instrument, and the one to keep. */
data class DuplicateGroup(
    val keep: Asset,
    val merge: List<Asset>,
) {
    val all: List<Asset> get() = listOf(keep) + merge
}

/**
 * Finds holdings that are the same instrument recorded twice.
 *
 * Pure, so the matching rule can be tested. Merging moves a user's transactions
 * between assets and has no undo, so a wrong group is worse than no group: it
 * welds two real positions together.
 */
object DuplicateFinder {

    /**
     * Groups assets that are the same instrument.
     *
     * [isins] maps asset id to its ISIN, where one is known. An ISIN identifies
     * an instrument exactly, so it outranks every other signal in both
     * directions:
     *
     * - Two assets with **different** ISINs are never grouped, whatever else
     *   they share. Several iShares World ETFs can carry the same ticker on
     *   different lines of a broker statement and are entirely different funds.
     *   This is the guard that makes the suggestion safe to act on.
     * - Two assets with the **same** ISIN are always grouped, even if their
     *   symbols differ, because a naming lookup can answer with a different
     *   listing for one ISIN on different days.
     *
     * Where no ISIN is known, assets are grouped by class and ticker. Not by
     * name: two share classes of one fund read almost identically and are
     * different instruments.
     *
     * [transactionCounts] decides which to keep: the one with the most history,
     * so the fewest rows move. Ties go to the shorter id, which is the plain
     * listing rather than a venue-specific one.
     */
    fun find(
        assets: Collection<Asset>,
        transactionCounts: Map<String, Int> = emptyMap(),
        isins: Map<String, String> = emptyMap(),
    ): List<DuplicateGroup> {
        val byId = assets.filter { it.id.isNotBlank() }.associateBy { it.id }
        val isinOf = { id: String -> isins[id]?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } }

        val parent = HashMap<String, String>()
        byId.keys.forEach { parent[it] = it }

        fun root(id: String): String {
            var r = id
            while (parent[r] != r) r = parent[r]!!
            var walk = id
            while (parent[walk] != r) {
                val next = parent[walk]!!
                parent[walk] = r
                walk = next
            }
            return r
        }

        // The ISINs already present in a set, so a union can be refused before
        // it happens rather than detected afterwards.
        val setIsins = HashMap<String, MutableSet<String>>()
        byId.keys.forEach { id ->
            setIsins[id] = isinOf(id)?.let { mutableSetOf(it) } ?: mutableSetOf()
        }

        /** Joins two assets unless that would put two different ISINs together. */
        fun union(a: String, b: String): Boolean {
            val ra = root(a)
            val rb = root(b)
            if (ra == rb) return true
            val merged = setIsins.getValue(ra) + setIsins.getValue(rb)
            if (merged.size > 1) return false
            parent[rb] = ra
            setIsins.getValue(ra).addAll(merged)
            setIsins.remove(rb)
            return true
        }

        // Same ISIN first, so those sets are established before any weaker
        // signal gets a chance to attach something to the wrong one.
        byId.keys
            .mapNotNull { id -> isinOf(id)?.let { it to id } }
            .groupBy({ it.first }, { it.second })
            .toSortedMap()
            .forEach { (_, ids) ->
                val sorted = ids.sorted()
                sorted.drop(1).forEach { union(sorted.first(), it) }
            }

        // Then ticker, which only ever joins assets that do not contradict.
        byId.values
            .filter { it.symbol.isNotBlank() }
            .groupBy { it.assetClass to it.symbol.uppercase() }
            .toSortedMap(compareBy({ it.first.name }, { it.second }))
            .forEach { (_, group) ->
                val sorted = group.map { it.id }.sorted()
                sorted.drop(1).forEach { union(sorted.first(), it) }
            }

        return parent.keys
            .groupBy { root(it) }
            .values
            .filter { it.size > 1 }
            .map { ids ->
                val ordered = ids.mapNotNull { byId[it] }.sortedWith(
                    compareByDescending<Asset> { transactionCounts[it.id] ?: 0 }
                        .thenBy { it.id.length }
                        .thenBy { it.id },
                )
                DuplicateGroup(keep = ordered.first(), merge = ordered.drop(1))
            }
            // A group where nothing has any history is not worth offering: there
            // is no position to repair, only tidying, and merging is one-way.
            .filter { g -> g.all.any { (transactionCounts[it.id] ?: 0) > 0 } }
            .sortedBy { it.keep.symbol }
    }
}
