package edu.byu.ece.rapidSmith.cad.place.annealer

import edu.byu.ece.rapidSmith.cad.cluster.ClusterSite

interface PlacerRule<S: ClusterSite> {
	fun validate(
		state: PlacerState<S>,
		component: MoveComponent<S>
	): Boolean
}

class MoveValidator<S: ClusterSite>(val rules: List<PlacerRule<S>>) {
	fun validate(state: PlacerState<S>, component: MoveComponent<S>): Boolean =
		rules.all { it.validate(state, component) }
}

