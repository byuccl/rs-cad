package edu.byu.ece.rapidSmith.cad.cluster

import edu.byu.ece.rapidSmith.design.subsite.CellDesign
import edu.byu.ece.rapidSmith.util.Exceptions.DesignAssemblyException
import java.util.*

/**
 * A design comprised of clusters.
 */
class ClusterDesign<T: PackUnit, S : ClusterSite> {
	private val clusterMap = HashMap<String, Cluster<T, S>>()
	/** A map used to keep track of all used primitive sites used by the design  */
	private val placementMap = HashMap<S, Cluster<T, S>>()

	/**
	 * Returns all of the clusters in this design.
	 */
	val clusters: Collection<Cluster<T, S>>
		get() = clusterMap.values

	/**
	 * Adds [cluster] to this design.  The name of this added cluster should be unique
	 * to this design.  The cluster should not be part of another design and should
	 * not have any placement information.  Returns the added cluster for convenience.
	 */
	fun addCluster(cluster: Cluster<T, S>): Cluster<T, S> {
		if (cluster.name in clusterMap)
			throw DesignAssemblyException("Cluster with name already exists in design.")

		clusterMap.put(cluster.name, cluster)
		return cluster
	}

	/**
	 * Returns the cluster placed at this [site] or null if [site] is unoccupied.
	 */
	fun getClusterAtLocation(site: S): Cluster<T, S>? {
		return placementMap[site]
	}

	/**
	 * Returns `true` if a cluster is located at location [site] in this design.
	 */
	fun isLocationUsed(site: S): Boolean {
		return placementMap.containsKey(site)
	}

	/** Returns a collection of all of the used locations in this design */
	val usedLocations: Collection<S>
		get() = placementMap.keys

	/**
	 * Places [cluster] at [site].
	 */
	fun placeCluster(cluster: Cluster<T, S>, site: S) {
		if (cluster.isPlaced)
			throw DesignAssemblyException("Cannot re-place cell.")
		if (placementMap.containsKey(site))
			throw DesignAssemblyException("Cell already placed at gridLocation.")

		placementMap.put(site, cluster)
		cluster.place(site)
	}

	/** Swaps the locations of [cluster1] and [cluster2]. */
	fun swap(cluster1: Cluster<T, S>, cluster2: Cluster<T, S>): Boolean {
		require(!cluster1.isPlaced) {"Cluster must be placed to swap." }
		require(!cluster2.isPlaced) {"Cluster must be placed to swap." }

		if (cluster1 === cluster2)
			return false // Swapping with self does nothing

		val site1 = cluster1.placement!!
		val site2 = cluster2.placement!!
		placementMap[site2] = cluster1
		cluster1.place(site2)
		placementMap[site1] = cluster2
		cluster2.place(site1)

		return true
	}

	/** Swaps the location of [cluster1] and the cluster at [site2] */
	fun swap(cluster1: Cluster<T, S>, site2: S): Boolean {
		require(!cluster1.isPlaced) {"Cluster must be placed to swap." }

		if (cluster1.placement == site2)
			return false  // Attempting to swap with self

		val site1 = cluster1.placement!!
		val cluster2 = getClusterAtLocation(site2)

		if (cluster2 != null) {
			placementMap.put(site1, cluster2)
			cluster2.place(site1)
		} else {
			placementMap.remove(site1)
		}
		placementMap.put(site2, cluster1)
		cluster1.place(site2)

		return true
	}

	/** Unplaces [cluster]. */
	fun unplaceCluster(cluster: Cluster<T, S>) {
		placementMap.remove(cluster.placement)
		cluster.unplace()
	}

	/** Flattens the design.  The returned design contains no cluster hierarchy. */
	fun commitPlacement(cellDesign: CellDesign) {
		for (cluster in clusters) {
			require(cluster.isPlaced)
			cluster.commitPlacement()
//			for (cell in cluster.cells) {
//				val copyCell = cellDesign.getCell(cell.name)
//				val cellPlacement = cluster.getCellPlacement(cell)
//				cellDesign.placeCell(copyCell, cellPlacement)
//			}
//
//			for ((cellPin, belPin) in cluster.getPinMap()) {
//				val copyCell = cellDesign.getCell(cellPin.cell.name)
//				val copyPin = copyCell.getPin(cellPin.name)
//				copyPin.mapToBelPins(belPin)
//				if (copyPin.isInpin) {
//					val copyNet = copyPin.net
//					copyNet.addRoutedSink(copyPin)
//				}
//			}
//
//			for ((oldNet, tree) in cluster.routeTreeMap) {
//				val copyNet = cellDesign.getNet(oldNet.name)
//				for (rt in tree) {
//					copyNet.addIntersiteRouteTree(rt)
//					rt.wire.source?.let { copyNet.sourceRouteTree = rt }
//					rt.wire.reverseConnectedPin?.let { copyNet.addSinkRouteTree(it, rt) }
//					for (t in rt) {
//						if (t.isLeaf && t.connectingSitePin != null) {
//							copyNet.addSourceSitePin(t.connectingSitePin!!)
//						}
//						if (t.isLeaf && t.connectingBelPin != null) {
//							copyNet.addSinkRouteTree(t.connectingBelPin!!, t)
//						}
//					}
//				}
//			}
		}
	}
}
