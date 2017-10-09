package edu.byu.ece.rapidSmith.cad.place.annealer

import edu.byu.ece.rapidSmith.cad.cluster.Cluster
import edu.byu.ece.rapidSmith.cad.cluster.ClusterSite
import edu.byu.ece.rapidSmith.cad.cluster.PackUnit
import java.util.*
import kotlin.collections.HashMap

/**
 * This class manages the dynamic placement state of all placement
 * groups during the placement process. The placement state changes frequently
 * during placement and this state is managed locally. The actually placement
 * state is not set in the XDL file until after placement and by calling the
 * "finalizePlacement" method.
 *
 * The state of the placement (i.e., the locations where the groups are placed)
 * is managed by two objects that must be consistent. The first is the groupAnchorMap:
 *
 * protected Map<PlacementGroup></PlacementGroup>, PrimitiveSite> groupAnchorMap;
 *
 * This stores the anchor location of each group in the design. A group is
 * considered "unplaced" when it does not have an entry in this map. This object
 * also stores a map between sites and instances:
 *
 * protected Map<PrimitiveSite></PrimitiveSite>, Instance> siteInstanceMap;
 *
 * This provides the ability to go from groups to sites or from
 * sites to groups(instances). This object must be consistent with the
 * groupAnchorMap.
 *
 * This object does NOT own the placement groups involved with placement. This
 * information is obtained in the placementGroups (DesignPlacementGroups) object
 *
 * The placement of groups is made using a set of "Moves". A move is an atomic
 * unit of groups and locations in which they should be placed. These moves are
 * created to insure that a valid placement is always maintained.
 *
 * This class also manages the area constraints used by each placement group.
 *
 * This class also holds a debug flag that can be used by any classes related to
 * placement. This consolidates the debug infrastructure.
 *
 * - Methods for determining which groups overlap other groups
 *
 * @param design the design and placement groups being placed
 * @param device the grids of placement sites in the device
 * @param costFunction the cost function used in determining the quality of
 *   the current circuit
 */
class PlacerState<S : ClusterSite>(
	private val design: PlacerDesign<S>,
	private val device: PlacerDevice<S>,
	val random: Random,
	private val costFunction: CostFunction<S>
) {
	private val groupAnchorMap = HashMap<PlacementGroup<S>, S>()
	private val siteInstanceMap = HashMap<S, Cluster<*, S>>()
	private val _unplacedGroups = HashSet(design.groups)
	private val typeGridMap = createAreaConstraints(design, device)

	/** Groups that are currently placed */
	val placedGroups: Set<PlacementGroup<S>> get() = groupAnchorMap.keys

	/** Groups that are currently unplaced */
	val unplacedGroups: Set<PlacementGroup<S>> get() = _unplacedGroups

	////////////////////////////////////////////////////////////////////////////////////////////////////
	// Methods for querying the basic current state of placement. None of these methods change
	// the state.
	////////////////////////////////////////////////////////////////////////////////////////////////////

	var currentCost: Double = 0.0
		private set

	/**
	 * Returns all sites used in the placement
	 */
	val usedSites: Set<S>
		get() = siteInstanceMap.keys

	val canBePlaced: Boolean

	init {
		val utilizations = calculateUtilizations(design.groups, typeGridMap)
		val overutilized = utilizations.filter { it.validSites < it.siteUsage }
		overutilized.forEach {
			println("\tWarning: over utilized - cannot place " +
			"${it.siteUsage.toFloat() / it.validSites * 100}% utilization")
		}
		canBePlaced = overutilized.isNotEmpty()
	}

	/**
	 * Returns the anchor site of a placement group [g].
	 */
	fun getAnchorOfGroup(g: PlacementGroup<S>): S? {
		return groupAnchorMap[g]
	}

	/** Return the current site of the given instance.  */
	fun getSiteOfCluster(i: Cluster<*, S>, g: PlacementGroup<S>): S? {
		val anchor = getAnchorOfGroup(g) ?: return null
		val grid = typeGridMap[g.type]!!
		return grid.getOffsetSite(anchor, g.getClusterOffset(i))
	}

	/** Returns the area constraint for the placement group [g].  */
	fun getGridForGroup(g: PlacementGroup<S>): ClusterSiteGrid<S> {
		return typeGridMap[g.type]!!
	}

	/** Returns the cluster that is placed at a cluster site [site]. */
	fun getClusterAt(site: S): Cluster<*, S>? {
		return siteInstanceMap[site]
	}

	/**
	 * Returns the group placed at cluster site [site].
	 */
	fun getGroupAt(site: S): PlacementGroup<S>? {
		val i = getClusterAt(site)
		return if (i != null) design.getGroup(i) else null
	}

	/** Returns true if the placement group [g] is placed. */
	fun isGroupPlaced(g: PlacementGroup<S>): Boolean {
		return getAnchorOfGroup(g) != null
	}

	/**
	 * Modify placement information of a group. This method assumes that the
	 * PrimitiveSite anchor used for placement is valid. If the new site is
	 * null, unplaceGroup will be called.
	 *
	 * This method will update both placement state objects to keep them
	 * consistent:
	 * - groupAnchorMap
	 * - siteInstanceMap.
	 */
	fun placeGroup(group: PlacementGroup<S>, newSite: S) {
		val grid = getGridForGroup(group)

		// Update the new anchor location of the group
		groupAnchorMap[group] = newSite

		// Update the siteInstanceMap with the Instances of the group
		for (i in group.clusters) {
			val s = grid.getOffsetSite(newSite, group.getClusterOffset(i))!!
			siteInstanceMap[s] = i
			currentCost += costFunction.place(i, s)
		}
	}

	/**
	 * Remove the placement information for a given group. The group is
	 * considered "unplaced" with no location after calling this method.
	 *
	 * Both placement state objects are updated in this method: the groupAnchorMap
	 * and the siteInstanceMap.
	 */
	fun unplaceGroup(group: PlacementGroup<S>) {
		// get the old location.  If the group was not already placed, just return
		val oldAnchor = getAnchorOfGroup(group) ?: return
		val grid = getGridForGroup(group)

		// unplace each of the clusters in the group
		for (i in group.clusters) {
			val offset = group.getClusterOffset(i)
			val s = grid.getOffsetSite(oldAnchor, offset)!!
			siteInstanceMap.remove(s)
			currentCost += costFunction.unplace(i, s)
		}

		// Remove group anchor
		groupAnchorMap.remove(group)
	}

	/**
	 * Returns a set of all sites used by this group or null if the group is not placed.
	 */
	fun getGroupSites(g: PlacementGroup<S>): Set<S>? {
		val anchor = getAnchorOfGroup(g) ?: return null
		return getGridForGroup(g).getSitesForGroup(g, anchor)!!
	}
}

private fun <S: ClusterSite> createAreaConstraints(
	design: PlacerDesign<S>,
	device: PlacerDevice<S>
): Map<PackUnit, ClusterSiteGrid<S>> {
	// A temporary map between each type and the constraint used for that type
	return design.groups.asSequence()
		.map { it.type }
		.distinct()
		.map { it to device.getGrid(it) }
		.toMap()
}

private fun <S: ClusterSite> calculateUtilizations(
	groups: Iterable<PlacementGroup<S>>,
	typeGridMap: Map<PackUnit, ClusterSiteGrid<S>>
): List<Utilization> {
	val groupedGroups = groups.groupBy { it.type }

	// Check to see if the constraints are ok
	return groupedGroups.map { (type, g) ->
		val grid = typeGridMap[type]!!
		// Figure out how many instances are allocated to this grid
		val siteUsage = g.sumBy { it.clusters.size }
		val validSites = grid.validSites.size
		Utilization(siteUsage, validSites)
	}
}

private data class Utilization(
	val siteUsage: Int, val validSites: Int)

/**
 * Returns a set of groups that overlap with group [g] if it is placed at site [anchor].
 */
fun <S: ClusterSite> PlacerState<S>.getOverlappingGroups(
	g: PlacementGroup<S>, anchor: S
): Set<PlacementGroup<S>> {
	val r = getGridForGroup(g)
	val targetSites = requireNotNull(r.getSitesForGroup(g, anchor)) { "Invalid anchor for g" }
	return targetSites.mapNotNull { getGroupAt(it) }.toSet()
}

/** Determines whether there is an overlapping group if the given group is
 * placed at the given anchor.
 */
fun <S: ClusterSite> PlacerState<S>.willGroupOverlap(g: PlacementGroup<S>, anchor: S): Boolean {
	val r = getGridForGroup(g)
	val targetSites = requireNotNull(r.getSitesForGroup(g, anchor)) { "Invalid anchor for g" }
	return targetSites.any { getGroupAt(it) != null }
}

