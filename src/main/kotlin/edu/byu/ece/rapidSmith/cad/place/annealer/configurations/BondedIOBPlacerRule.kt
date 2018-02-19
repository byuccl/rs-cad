package edu.byu.ece.rapidSmith.cad.place.annealer.configurations

import edu.byu.ece.rapidSmith.cad.cluster.site.SiteClusterSite
import edu.byu.ece.rapidSmith.cad.place.annealer.MoveComponent
import edu.byu.ece.rapidSmith.cad.place.annealer.PlacerRule
import edu.byu.ece.rapidSmith.cad.place.annealer.PlacerState
import edu.byu.ece.rapidSmith.device.BondedType

class BondedIOBPlacerRule : PlacerRule<SiteClusterSite> {
	override fun validate(
		state: PlacerState<SiteClusterSite>,
		component: MoveComponent<SiteClusterSite>
	): Boolean {
		val newAnchor = component.newAnchor ?: return true
		return newAnchor.site.bondedType != BondedType.UNBONDED
	}
}

