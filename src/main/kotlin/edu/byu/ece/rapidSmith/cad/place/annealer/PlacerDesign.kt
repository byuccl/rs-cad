package edu.byu.ece.rapidSmith.cad.place.annealer

import edu.byu.ece.rapidSmith.cad.cluster.Cluster
import edu.byu.ece.rapidSmith.cad.cluster.ClusterDesign
import edu.byu.ece.rapidSmith.cad.cluster.ClusterSite
import edu.byu.ece.rapidSmith.design.subsite.CellNet
import edu.byu.ece.rapidSmith.util.putTo

/**
 * Creates all of the "static" information necessary for a Design to be placed on a particular
 * Device. This static information can be created from the Design and Device and does NOT need
 * to be serialized. This static information includes:
 *
 * - PlacementGroup objects of a design. The PlacementGroup objects are the atomic units of
 * placement within a design and need to be identified before placement can begin.
 * - PlacmentGroup objects that CANNOT be placed by this placer. This is based on the type
 * of the cluster.
 * - Placement alignment information for each group
 *
 * This class also manages a Map between clusters in the design and the PlacementGroup
 * object that they belong to. This
 * facilitates the identification of PlacementGroups during placement.
 *
 * @author Mike Wirthlin
 * Created on: May 30, 2012
 */
class PlacerDesign<S : ClusterSite>(val design: ClusterDesign<*, S>) {
	/** The placement groups that can be placed by the placer */
	val groups: Set<PlacementGroup<S>>

	/** Clusters that should not be placed by the placer */
	val clustersNotToPlace: Set<Cluster<*, S>>

	/** Clusters to be placed by the placer */
	val clustersToPlace: Set<Cluster<*, S>>

	private val clusterGroupMap: Map<Cluster<*, S>, PlacementGroup<S>>

	init {
		val (toNotPlace, toPlace) = identifyPlaceableClusters(design)
		clustersToPlace = toPlace
		clustersNotToPlace = toNotPlace
		clusterGroupMap = createPlacementGroups(design, toNotPlace)
		groups = clusterGroupMap.values.toSet()
	}

	/**
	 * Returns the [PlacementGroup<*>] this cluster [i] is in.  Unplaceable
	 * clusters are not in groups and `null` will be returned for such clusters.
	 */
	fun getGroup(i: Cluster<*, S>): PlacementGroup<S>? {
		return clusterGroupMap[i]
	}

	val nets: Collection<CellNet>
		get() = design.clusters.flatMap { it.getExternalNets() }.toSet()
}

/**
 * Determines which clusters are to be placed with the placer and which are to be
 * saved for special consideration.
 */
private fun <S: ClusterSite> identifyPlaceableClusters(
	design: ClusterDesign<*, S>
): Pair<HashSet<Cluster<*, S>>, HashSet<Cluster<*, S>>> {
	val clustersThatCannotBePlaced = HashSet<Cluster<*, S>>()
	val clustersToPlace = HashSet<Cluster<*, S>>()

	for (i in design.clusters) {
		if (i.isPlaceable) {
			clustersToPlace.add(i)
		} else {
			clustersThatCannotBePlaced.add(i)
		}
	}

	return Pair(clustersThatCannotBePlaced, clustersToPlace)
}

/**
 * Identify all of the atomic placement groups and create the
 * data structure for each group.
 */
private fun <S: ClusterSite> createPlacementGroups(
	design: ClusterDesign<*, S>, clustersNotToPlace: Set<Cluster<*, S>>
): Map<Cluster<*, S>, PlacementGroup<S>> {
	val clusterGroupMap = HashMap<Cluster<*, S>, PlacementGroup<S>>()
	val remainingClusters = HashSet(design.clusters)
	remainingClusters -= clustersNotToPlace

	// Step 1: Go through the remaining clusters and see if they match any of the known
	// patterns.
	val multiGroups = PlacementGroupFinder<S>().findMultiSitePlacementGroups(design)
	multiGroups.forEach {
		it.clusters.forEach { c -> clusterGroupMap[c] = it }
		remainingClusters.removeAll(it.clusters)
	}

	// Step 2: take care of single cluster groups
	remainingClusters.putTo(clusterGroupMap) { it to SingleClusterPlacementGroup(it) }

	return clusterGroupMap
}
