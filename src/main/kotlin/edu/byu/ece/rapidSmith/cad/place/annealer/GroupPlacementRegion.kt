package edu.byu.ece.rapidSmith.cad.place.annealer

import edu.byu.ece.rapidSmith.cad.cluster.ClusterSite
import edu.byu.ece.rapidSmith.cad.cluster.PackUnit
import edu.byu.ece.rapidSmith.cad.cluster.site.SiteClusterSite
import edu.byu.ece.rapidSmith.device.Site
import edu.byu.ece.rapidSmith.device.Tile
import edu.byu.ece.rapidSmith.util.Grid

abstract class GroupPlacementRegionFactory<in C: PackUnit, S: ClusterSite> {
	abstract fun make(type: C, device: PlacerDevice<S>): GroupPlacementRegion<S>
}

/**
 * Represents a set of ClusterSites for a given PackUnit on a device. Each
 * PackUnit has its own coordinate system and this class provides an interface to
 * this coordinate system.
 *
 * Note that not all sites in this coordinate system are valid.
 */
abstract class GroupPlacementRegion<out S: ClusterSite>(
	val type: PackUnit
) {
	/** The set of valid sites associated with this coordinate system. */
	abstract val validSites: List<S>

	/**
	 * Determine the area of the placement constraint.
	 */
	abstract val area: Int
}

