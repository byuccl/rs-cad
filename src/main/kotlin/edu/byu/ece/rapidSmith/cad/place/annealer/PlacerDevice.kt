package edu.byu.ece.rapidSmith.cad.place.annealer

import edu.byu.ece.rapidSmith.cad.cluster.ClusterSite
import edu.byu.ece.rapidSmith.design.subsite.CellDesign
import edu.byu.ece.rapidSmith.device.Device
import edu.byu.ece.rapidSmith.device.Site
import edu.byu.ece.rapidSmith.device.Tile

class PlacerDevice<S: ClusterSite>(
		device: Device,
		design: CellDesign,
		csgFactory: ClusterSiteGridFactory<S>
) {
	val grid = csgFactory.makeClusterSiteGrid(device, design)

	fun getRelatedClusterSites(site: Site): List<S> =
		grid.getRelatedClusterSites(site)

	 fun getRelatedClusterSites(tile: Tile): List<S> =
		 grid.getRelatedClusterSites(tile)

	val rows: Int get() = grid.dimensions.rows
	val columns: Int get() = grid.dimensions.columns
}

