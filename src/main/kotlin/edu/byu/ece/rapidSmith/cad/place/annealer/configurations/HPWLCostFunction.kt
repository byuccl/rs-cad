package edu.byu.ece.rapidSmith.cad.place.annealer.configurations

import edu.byu.ece.rapidSmith.cad.cluster.Cluster
import edu.byu.ece.rapidSmith.cad.cluster.ClusterSite
import edu.byu.ece.rapidSmith.cad.place.annealer.*
import edu.byu.ece.rapidSmith.design.subsite.CellNet
import java.util.*

class HPWLCostFunctionFactory<S: ClusterSite> : CostFunctionFactory<S> {
	override fun make(design: PlacerDesign<S>): CostFunction<S> =
		HPWLCostFunction(design)
}

/**
 * A cost function that determines system cost based on nets and their
 * distances. Based on the VPR cost function.
 */
class HPWLCostFunction<S : ClusterSite>(
	val design: PlacerDesign<S>
) : CostFunction<S> {

	/**
	 * Caches the current cost of each net. This cache is used to speed up the time to compute
	 * the cost of the placement when only a few nets are chanced.
	 */

	private val netToCostMap = HashMap<CellNet, Double>()

	/**
	 * Used to identify those nets that are used within the cost function
	 * (some nets are not used in computing cost, i.e., clock)
	 */
	private var netsForCostFunction = getRealNets(design)
		.map { it to BoundingRect() }.toMap()

	override fun place(cluster: Cluster<*, S>, site: S): Double {
		val affectedNets = cluster.getExternalNets()
		var diffCost = 0.0
		for (n in affectedNets) {
			val brect = netsForCostFunction[n] ?: continue
			val oldNetCost = netToCostMap.getOrDefault(n, 0.0)
			brect.addNewPoint(site.tileLocation)
			val newNetCost = computeNetCost(n, brect)
			diffCost += newNetCost - oldNetCost
			netToCostMap[n] = newNetCost
		}
		return diffCost
	}

	override fun unplace(cluster: Cluster<*, S>, site: S): Double {
		val affectedNets = cluster.getExternalNets()
		var diffCost = 0.0
		for (n in affectedNets) {
			val brect = netsForCostFunction[n] ?: continue
			val oldNetCost = netToCostMap.getOrDefault(n, 0.0)
			brect.removePoint(site.tileLocation)
			val newNetCost = computeNetCost(n, brect)
			diffCost += newNetCost - oldNetCost
			netToCostMap.put(n, newNetCost)
		}
		return diffCost
	}
}


/**
 * Helper class to help more easily determine the bounding box for a given Net
 *
 * @author whowes
 */
private class BoundingRect {
	private val rowElements = TreeMap<Int, Int>()
	private val colElements = TreeMap<Int, Int>()

	fun addNewPoint(loc: Coordinates) {
		addNewPoint(loc.row, loc.column)
	}

	fun addNewPoint(row: Int, column: Int) {
		rowElements.merge(row, 1) { v1, _ -> v1 + 1 }
		colElements.merge(column, 1) { v1, _ -> v1 + 1 }
	}

	fun removePoint(loc: Coordinates) {
		removePoint(loc.row, loc.column)
	}

	fun removePoint(row: Int, column: Int) {
		rowElements.merge(row, -1) { v1, _ -> if (v1 == 1) null else v1 - 1 }
		colElements.merge(column, -1) { v1, _ -> if (v1 == 1) null else v1 - 1 }
	}

	override fun toString(): String {
		return "(" + rowElements.firstKey() + ", " + colElements.firstKey() + ") to " +
			"(" + rowElements.lastKey() + ", " + colElements.lastKey() + ")"
	}

	val halfPerimiter: Int
		get() {
			if (rowElements.isEmpty())
				return 1
			val xRange = rowElements.lastKey() - rowElements.firstKey() + 1
			val yRange = colElements.lastKey() - colElements.firstKey() + 1
			return xRange + yRange
		}
}

/**
 * This is the q(i) term used in the VPR cost function (see p.308 of Reconfigurable Computing
 * and place.c of VPR source)
 */
private val cross_count = doubleArrayOf(/* [0..49] */
	1.0, 1.0, 1.0, 1.0828, 1.1536, 1.2206, 1.2823, 1.3385, 1.3991, 1.4493,
	1.4974, 1.5455, 1.5937, 1.6418, 1.6899, 1.7304, 1.7709, 1.8114, 1.8519,
	1.8924, 1.9288, 1.9652, 2.0015, 2.0379, 2.0743, 2.1061, 2.1379, 2.1698,
	2.2016, 2.2334, 2.2646, 2.2958, 2.3271, 2.3583, 2.3895, 2.4187, 2.4479,
	2.4772, 2.5064, 2.5356, 2.5610, 2.5864, 2.6117, 2.6371, 2.6625, 2.6887,
	2.7148, 2.7410, 2.7671, 2.7933)

private fun getCrossing(n: CellNet): Double {
	return if (n.fanOut > 49) {
		(2.7933f + 0.02616f * (n.fanOut - 49)).toDouble()
	} else {
		cross_count[n.fanOut]
	}
}

/**
 * Determine the cost of a single net.
 */
private fun computeNetCost(n: CellNet, bRect: BoundingRect): Double {
	return getCrossing(n) * bRect.halfPerimiter
}
