package edu.byu.ece.rapidSmith.cad.place.annealer

import edu.byu.ece.rapidSmith.cad.cluster.Cluster
import edu.byu.ece.rapidSmith.cad.cluster.ClusterSite
import java.util.*

/**
 * Propose a new location for a given PlacementGroup. A range constraint can be used
 * to limit the move within a pre-specified distance from its initial location.
 *
 * This method will continually identify a random locations until it finds a valid
 * location for the group. Once a valid location is found, it calls buildSwapMove to
 * create a move involving a swap. Note that while a valid location for the group may be
 * found, its corresponding swap move may not be legal and the move is not created.
 *
 * Note that this method does not actually perform the move - it simply
 */
fun <S: ClusterSite> proposeSwap(
	state: PlacerState<S>, g: PlacementGroup<S>, rangeLimit: Int,
	design: PlacerDesign<S>, validator: MoveValidator<S>
): PlacerMove<S>? {
	val searchLimit = 10000

	// Find a new site
	var component: MoveComponent<S>?
	val oldSite = state.getAnchorOfGroup(g)!!
	var iteration = 0
	do {
		val newSite = getValidRandomSite(state, oldSite, rangeLimit, state.random, g)
		component = MoveComponent(g, oldSite, newSite)
		if (validator.validate(state, component))
			break
	} while (++iteration < searchLimit)

	if (iteration == searchLimit) {
		println("Can't find a new site")
		return null
	}

	// Create a move for this group at this site
	return buildSwapMove(state, design, component!!, validator)
}

/**
 * Creates a PlacerMove object based on the movement of the initial group to a new group site.
 * This class will see what objects overlap this new site and create a set of moves to swap
 * if it is legal.
 *
 * Note that the initialGroup MUST have an initial placement. This cannot be used for
 * unplaced modules.
 *
 * @param initialMove Move that this swap is being created around.
 */
fun <S: ClusterSite> buildSwapMove(
	state: PlacerState<S>, design: PlacerDesign<S>,
	initialMove: MoveComponent<S>, validator: MoveValidator<S>
): PlacerMove<S>? {
	// Make sure that the suggested site is a valid site for the group. Don't waste
	// any time on checking conflicts if the site is invalid.
	val initialGroup = initialMove.group

	// Determine the set of sites that the initial group will occupy if the move is
	// to take place
	val igTargetSites = state.getSitesForGroup(initialGroup, initialMove.newAnchor!!)!!

	// These data structures hold the set of moves that must be made in order for the initialGroupMove
	// to take place.
	// TODO: cache this member so that it doesn't have to be continually created
	// and destroyed.
	val displacedMainGroups = HashMap<PlacementGroup<S>, MoveComponent<S>>()
	val newMainPlacementSitesMap = HashMap<PlacementGroup<S>, Collection<S>>()

	val canDisplace = displaceOverlappingElements(displacedMainGroups,
		newMainPlacementSitesMap, state, initialGroup, initialMove.newAnchor,
		igTargetSites, design, validator)
	if (!canDisplace)
		return null

	// At this point, we know that the initial group move is valid (the target site is valid and
	// we moved everything out of its way). The proposed moves for the displaced groups
	// all have valid locations (from a possible placement standpoint). Now we need to verify that
	// there are no conflicts between these proposed moves and the existing placement of the circuit.

	val unassociatedGroupsAreAffected = doesMoveAffectUnassociatedGroup(
		displacedMainGroups, newMainPlacementSitesMap,
		state, design, initialGroup)
	if (unassociatedGroupsAreAffected)
		return null

	// If we make it to this point, the move is considered valid and can be made. Create the composite move
	// and return.
	val groupMoves = ArrayList<MoveComponent<S>>()
	groupMoves.add(initialMove)
	groupMoves.addAll(displacedMainGroups.values)
	return PlacerMove(groupMoves)
}


// Iterate over all of the instances of the initial group to move. Find the site that the instance
// will occupy and see if there are any groups (shadow or main) that overlap. Figure
// out which groups need to be moved and create the appropriate move.
//
// The movement of the initial group may displace more than one group. This method will try
// to move all displaced groups together and keep their relative positions with each other.
// This way, displaced groups will not overlap each other. The placement location of displaced groups
// is as follows:
// - Determine the offset of the displaced group from the anchor location of the
//   new site for the initial group
// - Identify the site that has the same offset from the original anchor location
//   of the old site for the initial group
//
// For example, if the anchor of a displaced group is (47,13) and the new anchor of the initial
// group is (45,12), the displaced group has an offset of (+2,+1) from the
// anchor location of the new initial group site. If the old anchor of the initial group
// is (17,32), the new anchor of the displaced group will be offset by (+2,+1) or at
// (19,31).
private fun <S : ClusterSite> displaceOverlappingElements(
	displacedMainGroups: HashMap<PlacementGroup<S>, MoveComponent<S>>,
	newMainPlacementSitesMap: HashMap<PlacementGroup<S>, Collection<S>>,
	state: PlacerState<S>,
	initialGroup: PlacementGroup<S>, newAnchor: S, igTargetSites: Collection<S>,
	design: PlacerDesign<S>,
	validator: MoveValidator<S>
): Boolean {
	for (cluster in initialGroup.clusters) {
		// target site of this instance. This is where we want the instance to go
		val igSiteIndex = initialGroup.getClusterIndex(cluster)
		val igRegion = state.getPlacementRegionForGroup(initialGroup)
		val igSite = igRegion.getLocations(newAnchor)!![igSiteIndex]

		// Now find all of the conflicts of the new site for this instance.
		val overlapCluster = state.getClusterAt(igSite)
		if (overlapCluster != null) {
			val overlapGroup = design.getGroup(overlapCluster)!!

			// If the group is already in the list, a move has been created. No
			// need to create a second move for the group
			if (overlapGroup in displacedMainGroups.keys)
				continue

			// Determine location of this displaced group.
			val displaceMove = getDisplacementMove(state, overlapGroup, overlapCluster, igSite)
			displaceMove ?: return false

			val overlapRegion = state.getPlacementRegionForGroup(overlapGroup)
			val overlapLocations = overlapRegion.getLocations(displaceMove.newAnchor!!)
			overlapLocations ?: return false

			// Check to see if the proposed site for the displaced group is valid.
			// If not, this move cannot happen.
			if (!validator.validate(state, displaceMove))
				return false

			// Determine the new sites of this main move.
			val newSitesForDisplaced = state.getSitesForGroup(overlapGroup, displaceMove.newAnchor)!!

			// See if the displaced group conflicts with the initial group.
			if (!Collections.disjoint(igTargetSites, newSitesForDisplaced))
				return false

			// Create the displacement move and save the information for later checking
			displacedMainGroups[overlapGroup] = displaceMove
			newMainPlacementSitesMap[overlapGroup] = newSitesForDisplaced
		}
	}

	return true
}

// The following checks will investigate the target sites for all of the displaced groups
// and see if they conflict with any groups NOT associated with this move. We do not need to check
// if they conflict with the groups involved with this move because 1) we already checked to see
// if it conflicts with the initial group move and 2) groups involved with the move won't conflict with
// each other because they are all placed the same way relative to each other (we assume they
// were placed without conflict previously).
private fun <S : ClusterSite> doesMoveAffectUnassociatedGroup(
	displacedMainGroups: Map<PlacementGroup<S>, MoveComponent<S>>,
	newMainPlacementSitesMap: Map<PlacementGroup<S>, Collection<S>>,
	state: PlacerState<S>, design: PlacerDesign<S>, initialGroup: PlacementGroup<S>
): Boolean {
	for (mainGroup in displacedMainGroups.keys) {
		// Iterate over the new sites of this group
		val newMainSites = newMainPlacementSitesMap[mainGroup]!!
		for (newMainSite in newMainSites) {
			val overlappingMainCluster = state.getClusterAt(newMainSite)
			if (overlappingMainCluster != null) {
				val overlappingMainGroup = design.getGroup(overlappingMainCluster)!!
				// If the new shadow location overlaps with a main group that is involved with
				// this set of moves, we can ignore it. If it is not a part of the move,
				// we have a conflict and the move is invalid.
				if (overlappingMainGroup !in displacedMainGroups && overlappingMainGroup !== initialGroup) {
					return true
				}
			}
		}
	}
	return false
}

private fun <S : ClusterSite> getDisplacementMove(
	state: PlacerState<S>, overlapGroup: PlacementGroup<S>,
	overlapCluster: Cluster<*, S>, igSite: S
): MoveComponent<S>? {
	val overlapSiteIndex = overlapGroup.getClusterIndex(overlapCluster)
	val overlapAnchor = state.getAnchorOfGroup(overlapGroup)
	val overlapRegion = state.getPlacementRegionForGroup(overlapGroup)
	val newOverlapLocations = overlapRegion.getLocations(igSite) ?: return null
	val newOverlapAnchor = newOverlapLocations[overlapSiteIndex]

	return MoveComponent(overlapGroup, overlapAnchor, newOverlapAnchor)
}

fun <S: ClusterSite> getValidRandomSite(
	state: PlacerState<S>,	center: S, range: Int, rand: Random, g: PlacementGroup<S>
): S? {
	val oldCoord = center.location

	val region = state.getPlacementRegionForGroup(g)
	val validSites = region.getValidSitesAround(oldCoord, range)
	if (validSites.isEmpty())
		return null
	val i = rand.nextInt(validSites.size)
	val site = validSites[i]
	return site
}

////////////////////////////////////////////////////////////////////////////////////////////////////
// Methods for querying about site locations. These use the current state and do NOT
// change the state of this class.
////////////////////////////////////////////////////////////////////////////////////////////////////

/**
 * The placement of groups is "tentatively" stored in the objects
 * contained within the state. When placement is done, this placement
 * information needs to be transferred to the actual cluster objects
 * themselves. This method will transfer this information. This method
 * should be called AFTER completing the placement process.
 */
@Suppress("UNCHECKED_CAST")
fun <S: ClusterSite> finalizePlacement(
	state: PlacerState<S>, design: PlacerDesign<S>
) {
	for (group in design.groups) {
		val instances = group.clusters
		for (i in instances) {
			val site = state.getSiteOfCluster(i, group)
			if (site != null) {
				i.place(site)
				i.commitPlacement()
			}
		}
	}
}

