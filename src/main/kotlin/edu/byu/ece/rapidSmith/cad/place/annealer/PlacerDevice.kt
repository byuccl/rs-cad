package edu.byu.ece.rapidSmith.cad.place.annealer

import edu.byu.ece.rapidSmith.cad.cluster.Cluster
import edu.byu.ece.rapidSmith.cad.cluster.ClusterSite
import edu.byu.ece.rapidSmith.cad.cluster.PackUnit
import edu.byu.ece.rapidSmith.util.put

class PlacerDevice<out S: ClusterSite>(
	csgFactory: ClusterSiteGridFactory<*, S>,
	clustersToPlace: Set<Cluster<*, S>>
) {
	private val clusterSiteGridsMap =
		createClusterSiteGrids(clustersToPlace, csgFactory)

	fun getGrid(type: PackUnit): ClusterSiteGrid<S> {
		return requireNotNull(clusterSiteGridsMap[type]) { "Unknown pack unit $type" }
	}

	val rows: Int = clusterSiteGridsMap.values.map {
		it.validSites.map { it.location.row }.max()!! }.max()!!

	val columns: Int = clusterSiteGridsMap.values.map {
		it.validSites.map { it.location.column }.max()!! }.max()!!
}

/** Create the placement grid for each pack unit used in the design. */
private fun <C: PackUnit, S: ClusterSite> createClusterSiteGrids(
	clusters: Set<Cluster<C, S>>, csgFactory: ClusterSiteGridFactory<*, S>
): Map<PackUnit, ClusterSiteGrid<S>> {
	return clusters.map { it.type }
		.distinct()
		.put {
			@Suppress("UNCHECKED_CAST")
			it to (csgFactory as ClusterSiteGridFactory<C, S>).make(it)
		}
}

