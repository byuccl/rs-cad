package edu.byu.ece.rapidSmith.cad.cluster

import edu.byu.ece.rapidSmith.design.subsite.Cell
import edu.byu.ece.rapidSmith.design.subsite.CellPin
import edu.byu.ece.rapidSmith.design.subsite.PropertyType
import edu.byu.ece.rapidSmith.device.Bel
import java.io.Serializable
import java.util.*

/**
 * Properties of the cells needed for packing.
 */

private val PACKING_PROPERTY = PropertyType.registerType("PACKING")

internal class PackingInfo: Serializable {
	var isValid: Boolean = true
	var cluster: Cluster<*, *>? = null
	var locInCluster: Bel? = null
	var carryChain: CarryChain? = null
	var initialGain: Double = 0.0
	var gain: Double? = Double.MAX_VALUE
	val sinkCarryChains by lazy { HashSet<CarryChainConnection>(2) }
	val sourceCarryChains by lazy { HashSet<CarryChainConnection>(2) }
}

private val PACKING_INFO_KEY = "PACKING_INFO"
internal val Cell.packingInfo: PackingInfo
	get() = properties.getValue(PACKING_INFO_KEY) as PackingInfo

fun Cell.initPackingInfo() {
	properties.update(PACKING_INFO_KEY, PACKING_PROPERTY, PackingInfo())
}

var Cell.isValid: Boolean
	get() = packingInfo.isValid
	set(value) { packingInfo.isValid = value }

/**
 * Returns the cluster this cell exists in.
 */
fun <C: Cluster<*, *>> Cell.getCluster(): C? {
	@Suppress("UNCHECKED_CAST")
	//if (packingInfo.cluster == null)
	//	println("why null")
	return packingInfo.cluster as C?
}

fun <C: Cluster<*, *>> Cell.setCluster(cluster: C?) {
	setCluster(cluster, packingInfo)
}

internal fun <C: Cluster<*, *>> setCluster(cluster: C?, packingInfo: PackingInfo) {
	// update the carry chain info for the cell
	packingInfo.cluster = cluster
}

var Cell.locationInCluster: Bel?
	get() = packingInfo.locInCluster
	set(value) { packingInfo.locInCluster = value }

var Cell.carryChain: CarryChain?
	get() = packingInfo.carryChain
	set(value) { packingInfo.carryChain = value }

var Cell.initialGain: Double
	get() = packingInfo.initialGain
	set(value) { packingInfo.initialGain = value }

var Cell.gain: Double?
	get() = packingInfo.gain
	set(value) { packingInfo.gain = value }

val Cell.sinkCarryChains: Set<CarryChainConnection>
	get() = packingInfo.sinkCarryChains

val Cell.sourceCarryChains: Set<CarryChainConnection>
	get() = packingInfo.sourceCarryChains

fun Cell.addSinkCarryChain(sourcePin: CellPin, sinkPin: CellPin) {
	val ccc = CarryChainConnection(sourcePin, sinkPin)
	packingInfo.sinkCarryChains += ccc
}

fun Cell.addSourceCarryChain(sourcePin: CellPin, sinkPin: CellPin) {
	val ccc = CarryChainConnection(sourcePin, sinkPin)
	packingInfo.sourceCarryChains += ccc
}

fun Cell.isInCluster(): Boolean = packingInfo.cluster != null

fun Cell.getPossibleAnchors(cluster: PackUnitTemplate): List<Bel> {
	return cluster.bels.filter { b -> b.id in possibleLocations }
}

val Cell.numExposedPins: Int
	get() {
		var numNetsLeavingMolecule = 0
		for (pin in pins) {
			if (pin.isConnectedToNet) {
				val net = pin.net
				var netLeavesMolecule = false
				for (oPin in net.pins) {
					val otherCell = oPin.cell
					if (otherCell !== this) {
						netLeavesMolecule = true
						break
					}
				}

				if (netLeavesMolecule)
					numNetsLeavingMolecule++

			}
		}

		return numNetsLeavingMolecule
	}

