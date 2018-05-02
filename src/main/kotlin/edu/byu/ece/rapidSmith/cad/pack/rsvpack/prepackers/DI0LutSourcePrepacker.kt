package edu.byu.ece.rapidSmith.cad.pack.rsvpack.prepackers

import edu.byu.ece.rapidSmith.cad.cluster.*
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.*
import edu.byu.ece.rapidSmith.design.subsite.Cell
import edu.byu.ece.rapidSmith.design.subsite.CellDesign
import edu.byu.ece.rapidSmith.design.subsite.CellLibrary
import edu.byu.ece.rapidSmith.device.Bel
import edu.byu.ece.rapidSmith.device.Site
import java.util.HashMap
import kotlin.streams.toList

// connect carry chain and DI0 LUT

class DI0LutSourcePrepackerFactory(
	cellLibrary: CellLibrary
) : PrepackerFactory<PackUnit>() {
	private var c4ToLutMap = HashMap<Cell, Cell>()
	private var lutToC4Map = HashMap<Cell, Cell>()
	private val carry4 = cellLibrary.get("CARRY4")

	override fun init(design: CellDesign) {
		// Finds all of the LUTs driving the DI0 pin of a CARRY4 which must
		// be packed with the CARRY4.
		val pairs = design.nonPortCells.filter { it.libCell == carry4 }
			.filter { requiresExternalCYInitPin(it) }
			.map { it to it.getPin("DI[0]")!! }
			.filter { it.second.isConnectedToNet }
			.map { it.first to it.second.net!! }
			.filter { !it.second.isStaticNet }
			.map { it.first to it.second.sourcePin.cell!! }
			.toList()
		pairs.associateTo(c4ToLutMap) { it.first to it.second }
		pairs.associateTo(lutToC4Map) { it.second to it.first }
	}

	override fun make(): Prepacker<PackUnit> =
		DI0LutSourcePrepacker(c4ToLutMap, lutToC4Map)
}

/**
 * Packs the DI0 LUT input with the carry chain.
 */
class DI0LutSourcePrepacker(
	private val c4ToLutMap: Map<Cell, Cell>,
	private val lutToC4Map: Map<Cell, Cell>
) : Prepacker<PackUnit>() {
	override fun packRequired(
		cluster: Cluster<*, *>, changedCells: MutableMap<Cell, Bel>
	): PrepackStatus {
		return checkC4Sinks(cluster, changedCells)
	}

	fun checkC4Sinks(
		cluster: Cluster<*, *>, changedCells: MutableMap<Cell, Bel>
	): PrepackStatus {
		val cellsToCheck = changedCells.keys
			.filter { it in lutToC4Map }
			.associate { lutToC4Map[it]!! to it.locationInCluster!!.site }

		var status = PrepackStatus.UNCHANGED
		for ((carryCell, site) in cellsToCheck) {
			if (!carryCell.isInCluster()) {
				val ccBel = getC4Bel(site)
				val packStatus = addCellToCluster(cluster, carryCell, ccBel)
				if (packStatus == PackStatus.INFEASIBLE)
					return PrepackStatus.INFEASIBLE
				changedCells[carryCell] = ccBel
				status = PrepackStatus.CHANGED
			}
		}

		return status
	}

//	fun checkLutSources(
//		cluster: Cluster<*, *>, changedCells: MutableMap<Cell, Bel>
//	): PrepackStatus {
//		val cellsToCheck = changedCells.keys
//			.filter { it in lutToC4Map }
//			.put { lutToC4Map[it]!! to it.locationInCluster }
//
//		var status = PrepackStatus.UNCHANGED
//		for ((carryCell, value) in cellsToCheck) {
//			if (!carryCell.isInCluster()) {
//				val ccBel = getC4Bel(value)
//				val packStatus = addCellToCluster(cluster, carryCell, ccBel)
//				if (packStatus == PackStatus.INFEASIBLE)
//					return PrepackStatus.INFEASIBLE
//				changedCells[carryCell] = ccBel
//				status = PrepackStatus.CHANGED
//			}
//		}
//
//		return status
//	}
}

private fun requiresExternalCYInitPin(carry4Cell: Cell): Boolean {
	val cyinitPin = carry4Cell.getPin("CYINIT")
	if (cyinitPin.isConnectedToNet) {
		val cyinitNet = cyinitPin.net
		if (cyinitNet.isStaticNet)
			return false

		val di0Pin = carry4Cell.getPin("DI[0]")
		if (!di0Pin.isConnectedToNet)
			return false

		val DI0Net = di0Pin.net
		return cyinitNet !== DI0Net
	}

	return false
}

private fun getC4Bel(slice: Site) = slice.getBel("CARRY4")
