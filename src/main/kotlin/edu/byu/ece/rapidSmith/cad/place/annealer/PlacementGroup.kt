package edu.byu.ece.rapidSmith.cad.place.annealer

import edu.byu.ece.rapidSmith.cad.cluster.Cluster
import edu.byu.ece.rapidSmith.cad.cluster.ClusterSite
import edu.byu.ece.rapidSmith.cad.cluster.PackUnit

/**
 * Represents the atomic unit for placement. Because of placement constraints
 * between instances (i.e., instances of a carry chain), multiple instances
 * may be part of an atomic placement group. In other words, all instances
 * of a given placement group have a specific relative placement constraints
 * and when one of the instances are placed, all of the instances in the group
 * are correspondingly placed.
 *
 * This class does not maintain any placement information. All of the
 * placement information is stored in the PlacerShapeState.
 */
sealed class PlacementGroup<S : ClusterSite>(
	val index: Int
) {
	abstract val name: String

	/** The type of the group.  */
	abstract val type: PackUnit

	/** All clusters associated with the group */
	abstract val clusters: List<Cluster<*, S>>

	/** The anchor cluster of the group */
	abstract val anchor: Cluster<*, S>

	/** The number of clusters in the group */
	abstract val size: Int

	/** The index of cluster [i] within the group */
	abstract fun getClusterIndex(i: Cluster<*, S>): Int

	/**
	 * Indicates whether the group's placement is fixed.
	 */
	val fixedPlacement: Boolean
		get() {
			return false
		}
}


/**
 * A placement group that consists of a single instance.
 */
class SingleClusterPlacementGroup<S : ClusterSite>(
	index: Int,
	val cluster: Cluster<*, S>
) : PlacementGroup<S>(index) {
	override val clusters: List<Cluster<*, S>> = listOf(cluster)

	override val name: String
		get() = cluster.name

	override val size: Int
		get() = 1

	override val type: PackUnit
		get() = cluster.type

	override val anchor: Cluster<*, S>
		get() = cluster

	override fun getClusterIndex(i: Cluster<*, S>): Int {
		require(i == cluster)
		return 0
	}

	override fun toString(): String {
		return cluster.name
	}
}

/**
 * This group represents a collection of instances whose placement are relative to
 * each other. All of the instances are of the same type.
 */
class MultipleClusterPlacementGroup<S: ClusterSite>(
	index: Int,
	override var type: PackUnit,
	private val clusterIndexMap: Map<Cluster<*, S>, Int>
) : PlacementGroup<S>(index) {
	override val name: String
		get() = anchor.name

	override val clusters: List<Cluster<*, S>>
		get() = clusterIndexMap.entries.sortedBy { it.value }.map { it.key }

	override val size: Int
		get() = clusterIndexMap.size
	
	override val anchor: Cluster<*, S> =
		clusterIndexMap.entries.first { it.value == 0 }.key

	override fun getClusterIndex(i: Cluster<*, S>): Int {
		return requireNotNull(clusterIndexMap[i]) { "Cluster $i not in group."}
	}

	override fun toString(): String {
		return "${anchor.name} (${clusterIndexMap.size})"
	}
}

/**
 * This class contains routines for finding placement groups within a design based
 * on the topology.  This should create only multiple instance groups.
 *
 * @author wirthlin
 */
class PlacementGroupFinder<S : ClusterSite> {
	fun findMultiSitePlacementGroups(
		clusters: List<Cluster<*, S>>
	): List<Pair<PackUnit, Map<Cluster<*, S>, Int>>> {
		val shapes = findShapes<Cluster<*, S>>(clusters)
		return shapes.map {constructPlacementGroup(it) }
	}

	/** Finds carry chains and builds into shapes */
	private fun <C: Cluster<*, *>> findShapes(
		clusters: List<Cluster<*, S>>
	): Collection<PlacementGroupShape<C>> {
		val chains = clusters.mapNotNull { it.getChain<C>() }.distinct()
		return chains.map { chain ->
			val indices = chain.clusters.map {
				val offset = chain.getOffsetOf(it)
				val index = if (offset.rows > 0 && offset.columns > 0)
					error("Cannot handle multicolumn shapes")
				else if (offset.rows > 0)
					offset.rows
				else
					offset.columns

				it to index
			}

			PlacementGroupShape(indices.toMap())
		}
	}

	private fun constructPlacementGroup(
		shape: PlacementGroupShape<Cluster<*, S>>
	): Pair<PackUnit, Map<Cluster<*, S>, Int>> {
		val clusters = shape.clusters.keys
		
		// Determine the placeable sites of this shape based on its type.
		var groupType = shape.anchor.type
		for (i in clusters) {
			if (i.type !== groupType) {
				System.out.println("Warning: mixing types where anchor is of type ${shape.anchor.type} " +
					"and member is of type ${i.type}")

				// TODO need a more elegant solution here
				if ("CLBLL" in groupType.toString() && "CLBLM" in i.type.toString()) {
					groupType = i.type
				} else if ("SLICEL" in groupType.toString() && "SLICEM" in i.type.toString()) {
					groupType = i.type
				}
				break
			}
		}

		return Pair(groupType, shape.clusters)
	}
}

class PlacementGroupShape<C: Cluster<*, *>>(val clusters: Map<C, Int>) {
	val anchor: C = clusters.entries.single { it.value == 0 }.key
}
