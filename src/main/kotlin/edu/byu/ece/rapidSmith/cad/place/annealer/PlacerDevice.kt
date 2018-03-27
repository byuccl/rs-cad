package edu.byu.ece.rapidSmith.cad.place.annealer

import edu.byu.ece.rapidSmith.cad.cluster.ClusterSite
import edu.byu.ece.rapidSmith.device.Device
import edu.byu.ece.rapidSmith.device.Site
import edu.byu.ece.rapidSmith.device.Tile

class PlacerDevice<S: ClusterSite>(
	device: Device,
	csgFactory: ClusterSiteGridFactory<S>
) {
	val grid = csgFactory.makeClusterSiteGrid(device)

	fun getRelatedClusterSites(site: Site): List<S> =
		grid.getRelatedClusterSites(site)

	 fun getRelatedClusterSites(tile: Tile): List<S> =
		 grid.getRelatedClusterSites(tile)

	val rows: Int = grid.dimensions.rows
	val columns: Int = grid.dimensions.columns
}

