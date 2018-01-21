package edu.byu.ece.rapidSmith.cad.place.annealer.configurations

import edu.byu.ece.rapidSmith.cad.cluster.ClusterSite
import edu.byu.ece.rapidSmith.cad.place.annealer.*
import java.util.*

/**
 * A Random initial placer. This is a quick and dirty initial placer that will attempt
 * to place a design randomly using a naive approach. It will place each instance
 * one at a time at a random location. It will place every group that has a "Free"
 * anchor site. For congested situations, this initial placer may not find a
 * free spot. In this case, it will leave these groups unplaced.
 *
 * @param moveValidator validator determining validity of a location
 * @param random the random number generator used to determine random placements
 */
class SimpleRandomInitialPlacer<S: ClusterSite>(
	private val moveValidator: MoveValidator<S>,
	private val random: Random
) : InitialPlacer<S>() {
	override fun initialPlace(
		design: PlacerDesign<S>, device: PlacerDevice<S>, state: PlacerState<S>
	): Boolean {
		val groupsToPlace = state.unplacedGroups

		// See if placement is necessary
		if (groupsToPlace.isEmpty())
			return true

		// Create a list of groups that need to be placed
		val orderedGroupsToPlace = ArrayList(groupsToPlace)
		Collections.shuffle(orderedGroupsToPlace, this.random)

		// Flag for indicating that all the groups were successfully placed
		var allGroupsPlaced = true
		for (group in orderedGroupsToPlace) {
			// Determine the number of possible placement anchors and thus the placement probability
			val placementRegion = state.getPlacementRegionForGroup(group)
			val possibleAnchorSites = placementRegion.getValidAnchorSitesForGroup(state, group)
			if (possibleAnchorSites.isEmpty()) {
				println("Warning: no placeable sites for group " + group)
				println("Constraint:" + placementRegion.toString())
				allGroupsPlaced = false
				continue
			}

			// Iterate until a free size has been selected or there are no
			// free sites.
			val orderedAnchorSites = ArrayList<S>(possibleAnchorSites)
			var component: MoveComponent<S>?
			do {
				val i = random.nextInt(orderedAnchorSites.size)
				val selectedSite = orderedAnchorSites[i]
				component = MoveComponent(group, null, selectedSite)
				// See if selected site is occupied
				if (!validatePlacement(state, component)) {
					orderedAnchorSites.remove(selectedSite)
					component = null
				}
			} while (component == null && orderedAnchorSites.size > 0)

			// If no site was selected, move on
			if (component == null) {
				allGroupsPlaced = false
				continue
			}

			// A free site was found. Make the move.
			val move = PlacerMove(listOf(component))
			move.perform(state)
		}

		return allGroupsPlaced
	}

	private fun validatePlacement(
		state: PlacerState<S>, component: MoveComponent<S>
	): Boolean {
		return component.group.fitsAt(state.device, component.newAnchor!!) &&
			!state.willGroupOverlap(component.group, component.newAnchor) &&
			moveValidator.validate(state, component)
	}
}

private fun <S: ClusterSite> GroupPlacementRegion<S>.getValidAnchorSitesForGroup(
	state: PlacerState<S>, group: PlacementGroup<S>
) = validSites.filter { group.fitsAt(state.device, it) }
