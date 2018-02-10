package edu.byu.ece.rapidSmith.cad.place.annealer

import edu.byu.ece.rapidSmith.cad.cluster.ClusterSite
import edu.byu.ece.rapidSmith.device.Device
import edu.byu.ece.rapidSmith.device.Site
import edu.byu.ece.rapidSmith.device.Tile
import edu.byu.ece.rapidSmith.util.Grid
import edu.byu.ece.rapidSmith.util.Index

typealias Coordinates = Index

interface ClusterSiteGridFactory<S: ClusterSite> {
	fun makeClusterSiteGrid(device: Device): ClusterSiteGrid<S>
}

abstract class ClusterSiteGrid<S: ClusterSite> : Grid<S> {
	abstract fun getSiteAt(location: Coordinates): S?
	abstract val sites: List<S>
	abstract fun getRelatedClusterSites(site: Site): List<S>
	abstract fun getRelatedClusterSites(tile: Tile): List<S>
}
