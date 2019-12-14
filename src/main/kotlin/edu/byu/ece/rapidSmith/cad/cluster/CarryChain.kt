package edu.byu.ece.rapidSmith.cad.cluster

import edu.byu.ece.rapidSmith.design.subsite.Cell
import edu.byu.ece.rapidSmith.design.subsite.CellDesign
import edu.byu.ece.rapidSmith.design.subsite.CellPin
import edu.byu.ece.rapidSmith.util.Offset
import java.util.*

/**
 *  Structure representing a carry chain in a device.  Carry chains are made up
 *  of one or more carry chain connections connecting two cells requiring a carry
 *  chain in the device.  This class is used to determine how fully built the carry
 *  chain is.
 */
class CarryChain private constructor() {
	private val cells = LinkedHashSet<Cell>()

	private fun addCell(cell: Cell) {
		cells.add(cell)
		cell.carryChain = this
	}

	fun getCells(): Set<Cell> {
		return cells
	}

	companion object {
		fun connect(sourcePin: CellPin, sinkPin: CellPin): CarryChain {
			val cc: CarryChain
			val o: CarryChain?
			val source = sourcePin.cell as Cell
			val sink = sinkPin.cell as Cell

			if (source.carryChain != null) {
				cc = source.carryChain!!
				o = sink.carryChain
				cc.addCell(sink)
			} else if (sink.carryChain != null) {
				cc = sink.carryChain!!
				o = null
				cc.addCell(source)
			} else {
				cc = CarryChain()
				o = null
				cc.addCell(source)
				cc.addCell(sink)
			}

			if (cc !== o && o != null) {
				o.cells.forEach { cc.addCell(it) }
			}

			source.addSinkCarryChain(sourcePin, sinkPin)
			sink.addSourceCarryChain(sinkPin, sourcePin)

			return cc
		}
	}
}

/**
 *
 */
class CarryChainConnection(val clusterPin: CellPin, endPin: CellPin) {
	val endCell = endPin.cell!!

	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (other == null || javaClass != other.javaClass) return false
		val that = other as CarryChainConnection?
		return clusterPin == that!!.clusterPin && endCell == that.endCell
	}

	override fun hashCode(): Int {
		return Objects.hash(clusterPin, endCell)
	}

	override fun toString(): String {
		return "CarryChainConnection{" +
			"clusterPin=" + clusterPin +
			", endCell=" + endCell +
			'}'
	}
}

/**
 *
 */
class ClusterChain<C: Cluster<*, *>>(cluster: C) {
	private val _clusters = LinkedHashMap<C, Offset>()

	init {
		_clusters[cluster] = Offset(0, 0)
	}

	@Suppress("UNCHECKED_CAST")
	val clusters: Collection<C>
		get() = _clusters.keys

	fun getOffsetOf(cluster: C): Offset {
		@Suppress("UNCHECKED_CAST")
		return requireNotNull(_clusters[cluster]) {
			"Cluster not in chain" }
	}

	fun absorbGroup(o: ClusterChain<C>, offset: Offset) {
		for ((cluster, off) in o._clusters) {
			cluster.setChain(this)
			_clusters[cluster] = off + offset
		}
		o._clusters.clear()  // clear this structure for garbage collection
	}
}

/**
 *  Class used to find carry chains in a design.
 *  Carry chains are not explicitly represented in the netlist, so we need to identify them.
 */
class CarryChainFinder {
	fun findCarryChains(packUnits: Collection<PackUnit>, design: CellDesign) {
        for (net in design.nets.sortedBy { it.name }) {
			if (net.isSourced && !net.sourcePin.isPartitionPin) {
				val sourcePin = net.sourcePin
				val dcs = getDirectSinks(packUnits, sourcePin)

				// Identify carry chains by matching them against direct connections
				for (sinkPin in net.sinkPins) {
					for (dc in dcs) {
						val sinkPinTemplate = dc.endPin
						val pinNames = sinkPin.getPossibleBelPinNames(
							sinkPinTemplate.id)
						if (pinNames.contains(sinkPinTemplate.name)) {
							CarryChain.connect(sourcePin, sinkPin)
							break
						}
					}
				}
			}
		}
	}

	private fun getDirectSinks(
		packUnits: Collection<PackUnit>, sourcePin: CellPin
	): List<DirectConnection> {
		val cell = sourcePin.cell
		val possibleBels = LinkedHashSet(cell.possibleLocations)
		return packUnits.flatMap { packUnit ->
			val template = packUnit.template
			template.directSinksOfCluster.map { dc ->
					val sourceBelPin = dc.clusterPin
					val sourceBel = sourceBelPin.bel
					Triple(dc, sourceBelPin, sourceBel)
				}.filter { possibleBels.contains(it.third.id) }
				.filter {
					val possiblePins = sourcePin.getPossibleBelPins(it.third)
					possiblePins.contains(it.second)
				}.map { it.first }
		}
	}
}
