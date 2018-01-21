package edu.byu.ece.rapidSmith.cad.place.annealer

import edu.byu.ece.rapidSmith.cad.cluster.Cluster
import edu.byu.ece.rapidSmith.cad.cluster.ClusterSite
import edu.byu.ece.rapidSmith.cad.cluster.PackUnit
import edu.byu.ece.rapidSmith.util.Dimensions
import edu.byu.ece.rapidSmith.util.Offset

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
abstract class PlacementGroup<S : ClusterSite>(
	val index: Int
) {
	abstract val name: String

	/** The type of the group.  */
	abstract val type: PackUnit

	/** All clusters associated with the group */
	abstract val clusters: Set<Cluster<*, S>>

	/** The anchor cluster of the group */
	abstract val anchor: Cluster<*, S>

	/** The dimensions of the group (size in x and y coordinates) */
	abstract val dimensions: Dimensions

	/** The number of clusters in the group */
	abstract val size: Int

	/** The offset of cluster [i] within the group */
	abstract fun getClusterOffset(i: Cluster<*, S>): Offset

	/**
	 * Returns the set of all offsets of an anchor site (including of the anchor itself)
	 * that the group will occupy.
	 */
	abstract val usedOffsets: Set<Offset>

	/**
	 * Returns true if this placement group can full fit in the grid when
	 * anchored at [anchor].
	 */
	abstract fun fitsAt(device: PlacerDevice<S>, anchor: S): Boolean

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
	override val clusters: Set<Cluster<*, S>> = setOf(cluster)

	override val name: String
		get() = cluster.name

	override val size: Int
		get() = 1

	override val dimensions: Dimensions = Dimensions(1, 1)

	override val type: PackUnit
		get() = cluster.type

	override val anchor: Cluster<*, S>
		get() = cluster

	override fun getClusterOffset(i: Cluster<*, S>): Offset {
		return ZERO_OFFSET
	}

	override val usedOffsets: Set<Offset>
		get() = setOf(ZERO_OFFSET)

	override fun fitsAt(device: PlacerDevice<S>, anchor: S): Boolean {
		val site = device.grid.getSiteAt(anchor.location)
		return site != null && site.isCompatibleWith(cluster.type)

	}

	override fun toString(): String {
		return cluster.name
	}

	private companion object {
		val ZERO_OFFSET = Offset(0, 0)
	}
}

/**
 * This group represents a collection of instances whose placement are relative to
 * each other. All of the instances are of the same type.
 */
class MultipleClusterPlacementGroup<S: ClusterSite>(
	index: Int,
	override var type: PackUnit,
	private val clusterOffsetMap: Map<Cluster<*, S>, Offset>
) : PlacementGroup<S>(index) {
	override val name: String
		get() = anchor.name

	override val clusters: Set<Cluster<*, S>>
		get() = clusterOffsetMap.keys

	override val size: Int
		get() = clusterOffsetMap.size
	
	override val anchor: Cluster<*, S> =
		clusterOffsetMap.entries.first { it.value == Offset(0, 0) }.key

	/**
	 * Indicates the dimensions of the group in both the x and y locations.
	 */
	override var dimensions: Dimensions = let {
		// Determine the shape of the group. This involves searching through the
		// list and finding the largest x value and the largest y value.
		val offsets = clusterOffsetMap.values
		val rows = offsets.maxBy { it.rows }!!.rows + 1
		val columns = offsets.maxBy { it.columns }!!.columns + 1
		Dimensions(rows, columns)
	}

	override fun getClusterOffset(i: Cluster<*, S>): Offset {
		return requireNotNull(clusterOffsetMap[i]) { "Cluster $i not in group."}
	}

	override val usedOffsets: Set<Offset>
		get() = clusterOffsetMap.values.toSet()

	override fun fitsAt(device: PlacerDevice<S>, anchor: S): Boolean {
		val anchorLoc = anchor.location
		return clusterOffsetMap.all { (c, offset) ->
			val site = device.grid.getSiteAt(offset + anchorLoc)
			site != null && site.isCompatibleWith(c.type)
		}
	}

	override fun toString(): String {
		return "${anchor.name} (${clusterOffsetMap.size})"
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
	): List<Pair<PackUnit, Map<Cluster<*, S>, Offset>>> {
		val shapes = findShapes<Cluster<*, S>>(clusters)
		return shapes.map {constructPlacementGroup(it) }
	}

	/** Finds carry chains and builds into shapes */
	private fun <C: Cluster<*, *>> findShapes(
		clusters: List<Cluster<*, S>>
	): Collection<PlacementGroupShape<C>> {
		val chains = clusters.mapNotNull { it.getChain<C>() }.distinct()
		return chains.map { chain ->
			val offsets = chain.clusters.map { it to chain.getOffsetOf(it) }
			val low = offsets.map { it.second.rows }.min()!!
			val high = offsets.map { it.second.rows }.max()!!

			// just error checking?
			val arrayOfClusters = arrayOfNulls<Cluster<*, *>>(high - low + 1)
			offsets.forEach { arrayOfClusters[it.second.rows] = it.first }
			assert(arrayOfClusters.all { it != null })
			assert (arrayOfClusters.all { it!!.isPlaceable })

			PlacementGroupShape(offsets.toMap())
		}
	}

	private fun constructPlacementGroup(
		shape: PlacementGroupShape<Cluster<*, S>>
	): Pair<PackUnit, Map<Cluster<*, S>, Offset>> {
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

class PlacementGroupShape<C: Cluster<*, *>>(val clusters: Map<C, Offset>) {
	val anchor: C = clusters.entries.single { it.value == Offset(0, 0) }.key
}
