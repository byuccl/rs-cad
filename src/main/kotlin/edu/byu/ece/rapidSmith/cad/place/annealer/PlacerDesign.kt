package edu.byu.ece.rapidSmith.cad.place.annealer

import edu.byu.ece.rapidSmith.cad.cluster.Cluster
import edu.byu.ece.rapidSmith.cad.cluster.ClusterSite
import edu.byu.ece.rapidSmith.design.subsite.CellDesign
import edu.byu.ece.rapidSmith.design.subsite.CellNet

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
class PlacerDesign<S : ClusterSite>(
	val clusters: List<Cluster<*, S>>,
	val design: CellDesign
) {
	/** The placement groups that can be placed by the placer */
	val groups: List<PlacementGroup<S>>

	// separation of placeable and nonplaceable clusters
	val clustersNotToPlace: Set<Cluster<*, S>>
	val clustersToPlace: Set<Cluster<*, S>>

	private val clusterGroups: Array<PlacementGroup<S>>

	init {
		val (toNotPlace, toPlace) = identifyPlaceableClusters(clusters)
		clustersToPlace = toPlace
		clustersNotToPlace = toNotPlace
		clusterGroups = createPlacementGroups(clusters, toNotPlace)
		groups = clusterGroups.distinct().sortedBy { it.index }
		assert(groups.withIndex().all { it.index == it.value.index })
	}

	/**
	 * Returns the [PlacementGroup<*>] this cluster [i] is in.  Unplaceable
	 * clusters are not in groups and `null` will be returned for such clusters.
	 */
	fun getGroup(i: Cluster<*, S>): PlacementGroup<S>? {
		return clusterGroups[i.index]
	}

	val nets: Collection<CellNet>
		get() = clusters.flatMap { it.getExternalNets() }.toSet()

	fun commit() {
		for (cluster in clusters) {
			require(cluster.isPlaced)
			for (cell in cluster.cells) {
				val cellPlacement = cluster.getCellPlacement(cell)
				design.placeCell(cell, cellPlacement)
			}

			for ((cellPin, belPin) in cluster.getPinMap()) {
				cellPin.mapToBelPins(belPin)
				if (cellPin.isInpin) {
					val net = cellPin.net

					// TODO: Is this right? A sink should only be considered routed if the intersite portion routes to it.
					net.addRoutedSink(cellPin)
				}
			}

			// TODO: Actually check that partition pins can be routed earlier? Like the other pins?
		//	for (net in cluster.getExternalNets()) {
		//		if (net.sinkPins.iterator().hasNext()) {
		///			val sinkPin = net.sinkPins.iterator().next()
		//			if (sinkPin.isPartitionPin)
		//				net.addRoutedSink(sinkPin)
		//		}
		//	}

			for ((net, tree) in cluster.routeTreeMap) {
				for (rt in tree) {
					if (rt.wire.source != null) {
						net.sourceRouteTree = rt

						// TODO: This is inadequate. A static net might be intrasite still.
						// Figure out why GND is sometimes being marked as intrasite and do a proper check.
						if (!net.isStaticNet)
							net.setIsIntrasite(rt.none { it.isLeaf && it.connectedSitePin != null })

						// TODO: If not intrasite, figure out the used SITE PIPs (Routing BELs)
						// so routers can figure out what site pins to route to

					}

					rt.wire.reverseConnectedPin?.let { net.addSinkRouteTree(it, rt) }
					for (t in rt) {
						if (t.isLeaf && t.connectedSitePin != null) {
							net.addSourceSitePin(t.connectedSitePin!!)
						}

						if (t.isLeaf && t.connectedBelPin != null) {
							net.addSinkRouteTree(t.connectedBelPin!!, t)
						}
					}
				}
			}
		}
	}
}

/**
 * Determines which clusters are to be placed with the placer and which are to be
 * saved for special consideration.
 */
private fun <S: ClusterSite> identifyPlaceableClusters(
	clusters: List<Cluster<*, S>>
): Pair<HashSet<Cluster<*, S>>, HashSet<Cluster<*, S>>> {
	val clustersThatCannotBePlaced = LinkedHashSet<Cluster<*, S>>()
	val clustersToPlace = LinkedHashSet<Cluster<*, S>>()

	for (i in clusters) {
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
	clusters: List<Cluster<*, S>>,
	clustersNotToPlace: Set<Cluster<*, S>>
): Array<PlacementGroup<S>> {
	val clusterGroupMap = LinkedHashMap<Cluster<*, S>, PlacementGroup<S>>()
	val remainingClusters = LinkedHashSet(clusters)
	remainingClusters -= clustersNotToPlace

	// Step 1: Go through the remaining clusters and see if they match any of the known
	// patterns.
	var numGroups = 0
	val multiGroups = PlacementGroupFinder<S>().findMultiSitePlacementGroups(clusters)
	multiGroups.forEach { (pu, cs) ->
		val group = MultipleClusterPlacementGroup(numGroups++, pu, cs)
		cs.keys.forEach { c -> clusterGroupMap[c] = group }
		remainingClusters.removeAll(cs.keys)
	}

	// Step 2: take care of single cluster groups
	remainingClusters.associateTo(clusterGroupMap) {
		it to SingleClusterPlacementGroup(numGroups++, it)
	}

	return clusterGroupMap.toSortedMap(Comparator.comparingInt { it.index })
		.values.toTypedArray()
}
