package edu.byu.ece.rapidSmith.cad.place.annealer

import edu.byu.ece.rapidSmith.cad.cluster.Cluster
import edu.byu.ece.rapidSmith.cad.cluster.ClusterSite
import edu.byu.ece.rapidSmith.design.subsite.CellNet

/**
 * Implements a cost function for placement.
 */
interface CostFunction<S : ClusterSite> {
	fun place(cluster: Cluster<*, S>, site: S): Double

	fun unplace(cluster: Cluster<*, S>, site: S): Double
}

interface CostFunctionFactory<S: ClusterSite> {
	fun make(design: PlacerDesign<S>): CostFunction<S>
}

fun getRealNets(d: PlacerDesign<*>): Set<CellNet> {
	return d.nets.filter { !isFilteredNet(it) }.toSet()
}

// TODO make fan out filter parameterizable
private fun isFilteredNet(n: CellNet): Boolean {
	return n.isClkNet || n.isStaticNet || n.fanOut == 0 || n.fanOut > 500
}
