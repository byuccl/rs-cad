package edu.byu.ece.rapidSmith.cad.place.annealer

import edu.byu.ece.rapidSmith.cad.cluster.ClusterSite

interface GroupPlacementRegionFactory<S: ClusterSite> {
	fun make(group: PlacementGroup<S>, device: PlacerDevice<S>): GroupPlacementRegion<S>
}

/**
 * Represents a set of ClusterSites for a given PackUnit on a device. Each
 * PackUnit has its own coordinate system and this class provides an interface to
 * this coordinate system.
 *
 * Note that not all sites in this coordinate system are valid.
 */
interface GroupPlacementRegion<S: ClusterSite>{
	/** The set of valid sites associated with this coordinate system. */
	val validSites: List<S>

	/**
	 * Determine the area of the placement constraint.
	 */
	val area: Int

	fun getLocations(newAnchor: S): List<S>?

	fun constrain(constraint: AreaConstraint<S>): GroupPlacementRegion<S>
}

