package edu.byu.ece.rapidSmith.cad.place.annealer.configurations

import edu.byu.ece.rapidSmith.cad.cluster.ClusterSite
import edu.byu.ece.rapidSmith.cad.place.annealer.*
import java.util.*

/**
 * A Random initial placer. This is a quick and dirty initial placer that will attempt
 * to place a design randomly using a vary naive approach.
 */
class DisplacementRandomInitialPlacer<S: ClusterSite>(
	private val moveValidator: MoveValidator<S>,
	private val random: Random
) : InitialPlacer<S>() {

	override fun initialPlace(
		design: PlacerDesign<S>,
		device: PlacerDevice<S>,
		state: PlacerState<S>
	): Boolean {
		val randomPlacer = SimpleRandomInitialPlacer(moveValidator, random)

		// Attempt a simple place first. If it is successful, we're done.
		val initialPlace = randomPlacer.initialPlace(design, device, state)
		if (initialPlace)
			return true

		// Check if the device has enough of each site to even be placeable
		if (!state.canBePlaced)
			return false

		// Must displace some instances to make room for other instances
		System.out.println("Displacement initial placement required " +
			"(${state.unplacedGroups.size} of ${design.groups.size} " +
			"groups displaced)")

		// Create a priority queue of placement groups that need to be placed.
		val unplacedGroupQueue = PriorityQueue(state.unplacedGroups.size,
			PlacementGroupPlacementComparator(state))
		unplacedGroupQueue.addAll(state.unplacedGroups)

		// Create an object representing the cost of sites.  Costs of sites increase
		// as more elements contend for them
		val siteCost = SiteCost(state)

		// While the queue is not empty, grab the top element in the queue,
		// choose a location for the element, and place the element around that
		// location ripping up any elements already at those locations.  Any ripped
		// up elements are inserted into the queue

		var iteration = 0
		// Place a maximum to help terminate either unplaceable or
		// near-unplaceable designs
		val maxIterations = unplacedGroupQueue.size * 1000
		val groupReplacementCountMap = HashMap<PlacementGroup<S>, Int>()
		do {
			placeNextGroup(unplacedGroupQueue, groupReplacementCountMap, state, siteCost)

			// Increment iteration count
			iteration++
		} while (!unplacedGroupQueue.isEmpty() && iteration < maxIterations)

		return if (unplacedGroupQueue.isEmpty()) {
			// everything is placed
			println(" Displacement iterations = " + iteration)
			true
		} else {
			println(" Failed to place after $iteration iterations")
			false
		}
	}

	private fun placeNextGroup(
		unplacedGroupQueue: PriorityQueue<PlacementGroup<S>>,
		groupReplacementCountMap: HashMap<PlacementGroup<S>, Int>,
		state: PlacerState<S>, siteCost: SiteCost
	) {
		// Get the seemingly hardest to place group in the list, we'll place this and then
		// try to place everything else around it.
		val g = unplacedGroupQueue.remove()
		groupReplacementCountMap.compute(g) { _, v -> v?.plus(1) ?: 1 }
		val grid = state.getPlacementRegionForGroup(g)

		val (movesToConsider, displacementRequired) = getMovesToConsider(g, grid, state)

		val moveCostMap = calculateMovePriorities(movesToConsider, state, siteCost)

		// Select a site randomly using this probability map.
		val selectedMove = chooseRandomMove(moveCostMap)

		// Build the move the move
		val (moveList, toDisplace) = buildDisplaceMove(
			selectedMove, state, displacementRequired)

		// perform the move
		val move = PlacerMove(moveList)
		move.perform(state)
		for (displacedGroup in toDisplace) {
			if (state.getAnchorOfGroup(displacedGroup) != null)
				throw AssertionError("Displaced group still placed: " + displacedGroup)
			unplacedGroupQueue += displacedGroup
		}

		// update cost of sites used
		val currentSites = state.getGroupSites(g)
		for (site in currentSites!!)
			siteCost.incrementSiteCost(site)
	}

	private fun getMovesToConsider(
		g: PlacementGroup<S>, region: GroupPlacementRegion<S>, state: PlacerState<S>
	): Pair<List<MoveComponent<S>>, Boolean> {
		// Determine all of the valid placement sites for this group
		val anchorSites = region.validSites
		val validAnchorSites = anchorSites
			.filter { g.fitsAt(state.device, it) }
			.map { MoveComponent(g, null, it) }
			.filter { moveValidator.validate(state, it) }

		// TODO change this to filter overlapping sites
		// Determine "sites to consider" for placement. First look for all unoccupied sites
		// and consider these first. If there are no free sites, consider all valid sites.
		val unoccupiedValidAnchors = validAnchorSites
			.filter { !state.willGroupOverlap(g, it.newAnchor!!) }

		// Choose whether to limit options to unoccupied or occupied anchors
		return if (unoccupiedValidAnchors.isNotEmpty()) {
			Pair(unoccupiedValidAnchors, false)
		} else {
			Pair(validAnchorSites, true)
		}
	}

	private fun calculateMovePriorities(
		movesToConsider: List<MoveComponent<S>>,
		state: PlacerState<S>, siteCost: SiteCost
	): Map<MoveComponent<S>, Double> {
		// Determine the probabilities of each site considered for placement. This probability
		//  map is used to influence the displacement placer to chose those that are more likely
		//  going to lead to a successful placement.
		return movesToConsider.map {
			// Find the cost of placing this group at this site
			var anchorSiteCost = state.getSitesForGroup(it.group, it.newAnchor!!)!!
				.sumByDouble { siteCost.getSiteCost(it).toDouble() }
			// we've already confirmed it is placeable at this location
			if (anchorSiteCost == 0.0)
				anchorSiteCost = 1.0
			it to anchorSiteCost
		}.toMap()
	}

	private fun chooseRandomMove(moveCostMap: Map<MoveComponent<S>, Double>): MoveComponent<S> {
		// Determine the site probability (1/cost). Those with higher cost have lower probability
		val totalSiteProbability = moveCostMap.values.sumByDouble { 1.0 / it }

		var selectedMove: MoveComponent<S>? = null
		// Choose a random number between zero and the total site probability
		val randomNum = random.nextDouble() * totalSiteProbability
		var curVal = 0.0
		// Iterate through all of the sites to consider and stop when it matches the random number
		for ((move, cost) in moveCostMap) {
			val selectionProbability = 1 / cost
			if (randomNum >= curVal && randomNum < curVal + selectionProbability) {
				selectedMove = move
				break
			}
			curVal += selectionProbability
		}

		return checkNotNull(selectedMove) { "No site selected" }
	}

	private fun buildDisplaceMove(
		initial: MoveComponent<S>,
		s: PlacerState<S>,
		displacementRequired: Boolean
	): Pair<ArrayList<MoveComponent<S>>, HashSet<PlacementGroup<S>>> {
		val moveList = ArrayList<MoveComponent<S>>()
		moveList += initial

		val toDisplace = HashSet<PlacementGroup<S>>()
		// add moves for displacement
		if (displacementRequired) {
			val targetSites = s.getSitesForGroup(initial.group, initial.newAnchor!!)!!
			val movedGroups = HashSet<PlacementGroup<S>>()
			for (targetSite in targetSites) {
				// Check for an overlapping group at this site
				val displacedGroup = s.getGroupAt(targetSite)
				if (displacedGroup != null) {
					val oldSite = s.getAnchorOfGroup(displacedGroup)
					// See if group has already been moved
					if (displacedGroup !in movedGroups) {
						// create a displaced move component
						movedGroups += displacedGroup
						moveList += MoveComponent(displacedGroup, oldSite, null)
					}
					toDisplace += displacedGroup
				}
			}
		}
		return Pair(moveList, toDisplace)
	}
}

// TODO make ClusterSite have an index
private class SiteCost(state: PlacerState<*>) {
	private val siteUseMap = HashMap<ClusterSite, Int>()

	init {
		state.usedSites.associateTo(siteUseMap) { it to 1}
	}

	fun incrementSiteCost(s: ClusterSite) {
		siteUseMap[s] = siteUseMap[s]?.plus(1) ?: 1
	}

	fun getSiteCost(s: ClusterSite): Int {
		return siteUseMap[s] ?: return 0
	}
}

/**
 * This is a comparator in which each group has a probability associated with it.
 * The comparator keeps this probability state. The probability of a placement group
 * is 1/# of valid placement sites. Those groups with fewer possible sites have a higher
 * probability than those with more possible sites. This comparator is used to prioritize
 * placement - those groups with fewer possible placement locations should be given
 * priority in placement over those with more possible placement locations.
 */
private class PlacementGroupPlacementComparator<S : ClusterSite>(
	s: PlacerState<S>
) : Comparator<PlacementGroup<S>> {
	private val groupProbabilities: Map<PlacementGroup<S>, Double>
	init {
		groupProbabilities = HashMap(2 * s.placedGroups.size)
		for (g in s.placedGroups) {
			val grid = s.getPlacementRegionForGroup(g)
			val possibleAnchorSites = grid.validSites.filter { g.fitsAt(s.device, it) }
			val placementProbability: Double
			placementProbability = if (possibleAnchorSites.isEmpty()) {
				println("Warning: no placement sites f or group " + g)
				java.lang.Double.MIN_VALUE
			} else {
				(1.0 / possibleAnchorSites.size)
			}
			groupProbabilities[g] = placementProbability
		}

	}

	override fun compare(a: PlacementGroup<S>, b: PlacementGroup<S>): Int {
		val aProbability = getGroupProbability(a)
		val bProbability = getGroupProbability(b)
		return when {
			aProbability < bProbability -> 1
			aProbability > bProbability -> -1
			else -> 0
		}
	}

	fun getGroupProbability(g: PlacementGroup<S>)=
		groupProbabilities[g] ?: Double.MIN_VALUE
}

