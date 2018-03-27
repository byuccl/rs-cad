package edu.byu.ece.rapidSmith.cad.place.annealer

import edu.byu.ece.rapidSmith.cad.cluster.ClusterSite

interface AreaConstraint<in S: ClusterSite> {
	operator fun contains(s: S): Boolean
}

class SquareAreaConstraint<in S: ClusterSite>(
	private val grid: ClusterSiteGrid<S>
) : AreaConstraint<S> {
	override fun contains(s: S): Boolean {
		return s.location in grid.absolute
	}
}
