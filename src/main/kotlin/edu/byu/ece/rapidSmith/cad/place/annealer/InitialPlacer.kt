package edu.byu.ece.rapidSmith.cad.place.annealer

import edu.byu.ece.rapidSmith.cad.cluster.ClusterSite

abstract class InitialPlacer<S: ClusterSite> {
	/**
	 * Perform an initial placement on a design.
	 * Returns true if the initial placement is complete.
	 */
	abstract fun initialPlace(
		design: PlacerDesign<S>,
		device: PlacerDevice<S>,
		state: PlacerState<S>
	): Boolean
}
