package com.eddies.app.domain

import com.eddies.app.data.db.entity.CustodyType
import java.math.BigDecimal

/** One place, with everything kept there and what it is worth. */
data class CustodyGroup(
    val type: CustodyType,
    val label: String,
    val holdings: List<Holding>,
    val value: BigDecimal,
)

/**
 * Groups a portfolio by where the coins are kept.
 *
 * The point of the feature: "which of my things are on the hardware wallet, and
 * how much is that". Pure, so the grouping and the totals are unit-tested.
 */
object CustodyGrouper {

    /** The label used for anything with no recorded location. */
    const val UNASSIGNED = "Not recorded"

    /**
     * [custodyByAsset] maps an asset id to its (type, label). Assets with no
     * entry land in a trailing "Not recorded" group rather than being dropped,
     * because a coin missing from this screen is the exact thing the feature is
     * meant to prevent.
     */
    fun group(
        holdings: List<Holding>,
        custodyByAsset: Map<String, Pair<CustodyType, String>>,
    ): List<CustodyGroup> {
        val grouped = LinkedHashMap<Pair<CustodyType, String>, MutableList<Holding>>()
        val unassigned = mutableListOf<Holding>()

        for (holding in holdings) {
            val key = custodyByAsset[holding.asset.id]
            if (key == null) unassigned += holding else grouped.getOrPut(key) { mutableListOf() } += holding
        }

        val groups = grouped.map { (key, list) ->
            CustodyGroup(
                type = key.first,
                label = key.second,
                holdings = list.sortedByDescending { it.marketValue },
                value = list.fold(BigDecimal.ZERO) { acc, h -> acc + h.marketValue },
            )
        }.sortedByDescending { it.value }

        if (unassigned.isEmpty()) return groups

        return groups + CustodyGroup(
            type = CustodyType.OTHER,
            label = UNASSIGNED,
            holdings = unassigned.sortedByDescending { it.marketValue },
            value = unassigned.fold(BigDecimal.ZERO) { acc, h -> acc + h.marketValue },
        )
    }
}
