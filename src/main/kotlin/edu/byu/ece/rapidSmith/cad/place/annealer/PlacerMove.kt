package edu.byu.ece.rapidSmith.cad.place.annealer

import edu.byu.ece.rapidSmith.cad.cluster.Cluster
import edu.byu.ece.rapidSmith.cad.cluster.ClusterSite

/**
 * Represents a potential move of a single group. Contains the new and
 * old locations of the group.
 * <p>
 * Note that the new site may be null. This indicates that the group should be "unplaced"
 * after the move. If the old site is null, it indicates that the group is currently
 * unplaced. Either the old site or the new site should be non-null indicating a change
 * in the placement status of the group.
 */
data class MoveComponent<S: ClusterSite>(
	val group: PlacementGroup<S>,
	val oldAnchor: S?, val newAnchor:S?
) {
	override fun toString(): String =
		"Moving Group $group from $oldAnchor to $newAnchor"
}

/**
 * Represents an atomic move between one or more PlacementGroup objects.
 * In some cases, this will be a single object that is being moved from its
 * current location to an empty, available location. In other cases, this
 * move will involve multiple groups (usually a swap between groups at
 * different locations). The set of moves should have already been
 * checked for validity (i.e., that the atomic set of moves are
 * mutually compatible).
 *
 * @author Mike Wirthlin
 */
class PlacerMove<S : ClusterSite>(
	private val components: List<MoveComponent<S>>
) {
	private var moveMade = false
	private var moveUndone = false

//	/**
//	 * Constructor for a single placement move.
//	 */
//	constructor(
//		state: PlacerState<S>, group: PlacementGroup<S>, newSite: S
//	) : this(let {
//		val oldSite = state.getAnchorOfGroup(group)
//		val move = MoveComponent(group, oldSite, newSite)
//		listOf(move)
//	})

	/**
	 * Actually place all Clusters specified in this move at their new PrimitiveSites
	 */
	fun perform(placerState: PlacerState<S>) {
		check(!moveMade) { "Move already made: $this" }

		////////////////////////////////////////////////////////////////////////////////////////////////////
		// Methods that change the state of the placer (after initialization)
		////////////////////////////////////////////////////////////////////////////////////////////////////
		// unplace all groups involved in this move
		for ((group, oldSite) in components) {
			if (oldSite != null) {
				placerState.unplaceGroup(group)
			}

		}

		// place all groups involved in this move
		for ((group, _, newSite) in components) {
			if (newSite != null)
				placerState.placeGroup(group, newSite)
		}

		moveMade = true
	}

	/**
	 * Place all groups involved in this move back at their previous sites
	 */
	fun undo(placerState: PlacerState<S>) {
		check(moveMade) { "Move not made: $this"}
		check(!moveUndone) { "Move already undone: $this" }
		if (!moveMade) {
			println("Move not made")
			return
		}

		////////////////////////////////////////////////////////////////////////////////////////////////////
		// Methods that change the state of the placer (after initialization)
		////////////////////////////////////////////////////////////////////////////////////////////////////
		// unplace all groups
		components.forEach { placerState.unplaceGroup(it.group) }

		// place all groups
		for ((group, oldSite) in components) {
			if (oldSite != null)
				placerState.placeGroup(group, oldSite)
		}

		moveMade = false
		moveUndone = true
	}

	/**
	 * Returns all of the Clusters that are part of this PlacerMove
	 */
	val cluster: Set<Cluster<*, S>>
		get() = components.flatMap { it.group.clusters }.toSet()

	val groups: Set<PlacementGroup<S>>
		get() = components.map { it.group }.toSet()

	override fun toString(): String {
		val sb = StringBuilder()
		sb.append("Move Made=$moveMade move Undone=$moveUndone\n")
		for (move in components)
			sb.append(" " + move + "\n")
		return sb.toString()
	}
}
