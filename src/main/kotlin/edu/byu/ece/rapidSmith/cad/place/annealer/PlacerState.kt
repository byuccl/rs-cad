package edu.byu.ece.rapidSmith.cad.place.annealer

import edu.byu.ece.rapidSmith.cad.cluster.Cluster
import edu.byu.ece.rapidSmith.cad.cluster.ClusterSite
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
 * is managed by two objects that must be consistent. The first is the groupAnchorList:
 *
 * protected Map<PlacementGroup></PlacementGroup>, PrimitiveSite> groupAnchorList;
 *
 * This stores the anchor location of each group in the design. A group is
 * considered "unplaced" when it does not have an entry in this map. This object
 * also stores a map between sites and instances:
 *
 * protected Map<PrimitiveSite></PrimitiveSite>, Instance> siteInstanceMap;
 *
 * This provides the ability to go from groups to sites or from
 * sites to groups(instances). This object must be consistent with the
 * groupAnchorList.
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
	val design: PlacerDesign<S>,
	val device: PlacerDevice<S>,
	gprFactory: GroupPlacementRegionFactory<S>,
	val random: Random,
	private val costFunction: CostFunction<S>
) {
	private val groupAnchorList = MutableList<S?>(design.groups.size) { null }
	private val siteInstanceMap = HashMap<S, Cluster<*, S>>()
	private val _placedGroups = ArrayList<PlacementGroup<S>>()
	private val _unplacedGroups = ArrayList(design.groups)

	private val groupRegions = createPlacementRegions(design, device, gprFactory)

	/** Groups that are currently placed */
	val placedGroups: List<PlacementGroup<S>> get() = _placedGroups

	/** Groups that are currently unplaced */
	val unplacedGroups: List<PlacementGroup<S>> get() = _unplacedGroups

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
		val utilizations = calculateUtilizations(design.groups, groupRegions)
		val overutilized = utilizations.filter { it.validSites < it.siteUsage }
		overutilized.forEach {
			println("\tWarning: over utilized - cannot place " +
			"${it.siteUsage.toFloat() / it.validSites * 100}% utilization")
		}

		canBePlaced = overutilized.isEmpty()
	}

	/**
	 * Returns the anchor site of a placement group [g].
	 */
	fun getAnchorOfGroup(g: PlacementGroup<S>): S? {
		return groupAnchorList[g.index]
	}

	/** Return the current site of the given instance.  */
	fun getSiteOfCluster(i: Cluster<*, S>, g: PlacementGroup<S>): S? {
		val anchor = getAnchorOfGroup(g) ?: return null
		val region = groupRegions[g.index]
		return region.getLocations(anchor)!![g.getClusterIndex(i)]
	}

	/** Returns the area constraint for the placement group [g].  */
	fun getPlacementRegionForGroup(g: PlacementGroup<S>): GroupPlacementRegion<S> {
		return groupRegions[g.index]
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
	 * - groupAnchorList
	 * - siteInstanceMap.
	 */
	fun placeGroup(group: PlacementGroup<S>, newSite: S) {
		// Update the new anchor location of the group
		groupAnchorList[group.index] = newSite

		// Update the siteInstanceMap with the Instances of the group
		val region = groupRegions[group.index]
		val locations = requireNotNull(region.getLocations(newSite)) { "Illegal site for group" }
		for ((i, site) in locations.withIndex()) {
			val cluster = group.clusters[i]
			siteInstanceMap[site] = cluster
			currentCost += costFunction.place(cluster, site)
		}
	}

	/**
	 * Remove the placement information for a given group. The group is
	 * considered "unplaced" with no location after calling this method.
	 *
	 * Both placement state objects are updated in this method: the groupAnchorList
	 * and the siteInstanceMap.
	 */
	fun unplaceGroup(group: PlacementGroup<S>) {
		// get the old location.  If the group was not already placed, just return
		val oldAnchor = getAnchorOfGroup(group) ?: return

		// unplace each of the clusters in the group
		val region = groupRegions[group.index]
		val locations = region.getLocations(oldAnchor)!!
		for ((i, site) in locations.withIndex()) {
			val cluster = group.clusters[i]
			siteInstanceMap.remove(site)
			currentCost += costFunction.unplace(cluster, site)
		}

		// Remove group anchor
		groupAnchorList[group.index] = null
	}

	/**
	 * Returns a set of all sites used by this group or null if the group is not placed.
	 */
	fun getGroupSites(g: PlacementGroup<S>): Collection<S>? {
		val anchor = getAnchorOfGroup(g) ?: return null
		return getSitesForGroup(g, anchor)!!
	}

	/**
	 * Returns a set of groups that overlap with group [g] if it is placed at site [anchor].
	 */
	fun getOverlappingGroups(
		g: PlacementGroup<S>, anchor: S
	): Set<PlacementGroup<S>> {
		val targetSites = requireNotNull(getSitesForGroup(g, anchor)) { "Invalid anchor for g" }
		return targetSites.mapNotNull { getGroupAt(it) }.toSet()
	}

	/** Determines whether there is an overlapping group if the given group is
	 * placed at the given anchor.
	 */
	fun willGroupOverlap(g: PlacementGroup<S>, anchor: S): Boolean {
		val targetSites = requireNotNull(getSitesForGroup(g, anchor)) { "Invalid anchor for g" }
		return targetSites.any { getGroupAt(it) != null }
	}

	/**
	 * Returns the sites that group [g] will use if placed at site [anchor] or null
	 * if [g] will not fit within this grid if placed at [anchor].
	 */
	fun getSitesForGroup(g: PlacementGroup<S>, anchor: S): Collection<S>? {
		val region = groupRegions[g.index]
		return region.getLocations(anchor)
	}
}

private fun <S: ClusterSite> createPlacementRegions(
	design: PlacerDesign<S>,
	device: PlacerDevice<S>,
	gprFactory: GroupPlacementRegionFactory<S>
): Array<GroupPlacementRegion<S>> {
	// A temporary map between each type and the constraint used for that type
	return design.groups.map { gprFactory.make(it, device) }.toTypedArray()
}

private fun <S: ClusterSite> calculateUtilizations(
	groups: Iterable<PlacementGroup<S>>,
	placementRegion: Array<GroupPlacementRegion<S>>
): List<Utilization> {
	val groupedGroups = groups.groupBy { it.type }

	// Check to see if the constraints are ok
	return groupedGroups.map { (_, gs) ->
		val sites = gs.asSequence()
			.flatMap { placementRegion[it.index].validSites.asSequence() }
			.distinct()
			.count()
		// Figure out how many instances are allocated to this grid
		val siteUsage = gs.sumBy { it.clusters.size }
		Utilization(siteUsage, sites)
	}
}

private data class Utilization(
	val siteUsage: Int, val validSites: Int)

