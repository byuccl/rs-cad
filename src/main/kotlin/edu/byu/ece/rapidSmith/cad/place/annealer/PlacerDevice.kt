package edu.byu.ece.rapidSmith.cad.place.annealer

import edu.byu.ece.rapidSmith.cad.cluster.Cluster
import edu.byu.ece.rapidSmith.cad.cluster.ClusterSite
import edu.byu.ece.rapidSmith.cad.cluster.PackUnit
import edu.byu.ece.rapidSmith.device.Device
import edu.byu.ece.rapidSmith.device.Site
import edu.byu.ece.rapidSmith.device.Tile
import edu.byu.ece.rapidSmith.util.Offset

class PlacerDevice<S: ClusterSite>(
	device: Device,
	csgFactory: ClusterSiteGridFactory<S>,
	gacFactory: GroupPlacementRegionFactory<*, S>,
	clustersToPlace: Set<Cluster<*, S>>
) {
	val grid = csgFactory.makeClusterSiteGrid(device)
	private val globalRegions =
		createGlobalRegionsMap(clustersToPlace, this, gacFactory)

	fun getGlobalRegion(type: PackUnit): GroupPlacementRegion<S> {
		return requireNotNull(globalRegions[type]) { "Unknown pack unit $type" }
	}

	fun getRelatedClusterSites(site: Site): List<S> =
		grid.getRelatedClusterSites(site)

	 fun getRelatedClusterSites(tile: Tile): List<S> =
		 grid.getRelatedClusterSites(tile)

	val rows: Int = grid.rows
	val columns: Int = grid.columns

	/** Returns the site found at the location of [anchor] offset by [offset]. */
	fun getOffsetSite(anchor: S, offset: Offset): S? =
		grid.getSiteAt(anchor.location + offset)
}

/** Create the placement grid for each pack unit used in the design. */
private fun <C: PackUnit, S: ClusterSite> createGlobalRegionsMap(
	clusters: Set<Cluster<C, S>>, device: PlacerDevice<S>,
	csgFactory: GroupPlacementRegionFactory<*, S>
): Map<PackUnit, GroupPlacementRegion<S>> {
	return clusters.map { it.type }
		.distinct()
		.associate {
			@Suppress("UNCHECKED_CAST")
			it to (csgFactory as GroupPlacementRegionFactory<C, S>).make(it, device)
		}
}

