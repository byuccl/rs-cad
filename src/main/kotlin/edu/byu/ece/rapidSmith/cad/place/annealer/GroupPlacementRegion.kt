package edu.byu.ece.rapidSmith.cad.place.annealer

import edu.byu.ece.rapidSmith.cad.cluster.ClusterSite
import edu.byu.ece.rapidSmith.util.Index

abstract class GroupPlacementRegionFactory<S: ClusterSite>
{
	abstract fun make(group: PlacementGroup<S>, device: PlacerDevice<S>): GroupPlacementRegion<S>
}

/**
 * Represents a set of ClusterSites for a given PackUnit on a device. Each
 * PackUnit has its own coordinate system and this class provides an interface to
 * this coordinate system.
 *
 * Note that not all sites in this coordinate system are valid.
 */
abstract class GroupPlacementRegion<S: ClusterSite>{
	/** The set of valid sites associated with this coordinate system. */
	abstract val validSites: List<S>

	abstract fun getValidSitesAround(center: Index, range: Int): List<S>

	/**
	 * Determine the area of the placement constraint.
	 */
	abstract val area: Int

	abstract fun getLocations(newAnchor: S): List<S>?
}

