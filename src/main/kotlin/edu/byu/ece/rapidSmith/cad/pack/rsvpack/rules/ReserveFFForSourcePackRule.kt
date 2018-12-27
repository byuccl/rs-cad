package edu.byu.ece.rapidSmith.cad.pack.rsvpack.rules

import edu.byu.ece.rapidSmith.cad.cluster.Cluster
import edu.byu.ece.rapidSmith.cad.cluster.getCluster
import edu.byu.ece.rapidSmith.cad.cluster.getPossibleAnchors
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.PackRule
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.PackRuleFactory
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.PackRuleResult
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.PackStatus
import edu.byu.ece.rapidSmith.design.subsite.*
import edu.byu.ece.rapidSmith.device.Bel
import edu.byu.ece.rapidSmith.util.StackedHashMap
import java.util.HashMap
import java.util.HashSet
import kotlin.streams.asSequence

/**
 * Postpones flip flop packing until carry chains taken care of, etc.
 * May need similar rule for LUTs.
 */
class ReserveFFForSourcePackRuleFactory(cellLibrary: CellLibrary) : PackRuleFactory {
	private val ccLibCell: LibraryCell = cellLibrary.get("CARRY4")
	private val ffLibCells: Set<LibraryCell>
	private var mergedCells: MutableMap<Cell, Cell>? = null

	init {
		ffLibCells = LinkedHashSet()
		ffLibCells.add(cellLibrary.get("FF_INIT"))
		ffLibCells.add(cellLibrary.get("REG_INIT"))
	}

	override fun init(design: CellDesign) {
		mergedCells = LinkedHashMap()

		for (cell in design.inContextLeafCells.asSequence().sortedBy { it.name }) {
			if (cell.libCell === ccLibCell) {
				val packCell = cell as Cell
				var le = 'A'
				while (le <= 'D') {
					val pinNum = le - 'A'
					val opin = packCell.getPin("O" + pinNum)
					val copin = packCell.getPin("CO" + pinNum)

					if (opin.isConnectedToNet && hasExternalConnection(copin)) {
						if (drivesOnlyFF(opin)) {
							val sinkPins = opin.net.sinkPins
							assert(sinkPins.size == 1)
							val sinkPin = sinkPins.iterator().next()
							mergedCells!!.put(sinkPin.cell, packCell)
						} else {
							assert(drivesOnlyFF(copin))
							val sinkPins = copin.net.sinkPins
							assert(sinkPins.size == 1)
							val sinkPin = sinkPins.iterator().next()
							mergedCells!!.put(sinkPin.cell, packCell)
						}
					}
					le++
				}
			}
		}
	}

	private fun hasExternalConnection(sourcePin: CellPin): Boolean {
		if (!sourcePin.isConnectedToNet)
			return false
		val net = sourcePin.net
		if (net.pins.size == 1)
			return false
		if (net.pins.size > 2)
			return true
		val sinkPin = net.sinkPins.iterator().next()
		// tests if this is a carry chain connection
		return if (sinkPin.cell.libCell === ccLibCell) {
			sinkPin.name != "CIN"
		} else {
			true
		}
	}

	private fun drivesOnlyFF(sourcePin: CellPin): Boolean {
		val net = sourcePin.net
		if (net.pins.size > 2)
			return false
		val sinkPin = net.sinkPins.iterator().next()
		return isFlipflopDInput(sinkPin)
	}

	private fun isFlipflopDInput(sinkPin: CellPin): Boolean {
		return ffLibCells.contains(sinkPin.cell.libCell) && sinkPin.name == "D"
	}

	override fun make(cluster: Cluster<*, *>): PackRule {
		return Rule(cluster)
	}

	private inner class Rule(private val cluster: Cluster<*, *>) : PackRule {
		private val cellsToCheck = StackedHashMap<Cell, Cell>()

		override fun validate(changedCells: Collection<Cell>): PackRuleResult {
			cellsToCheck.checkPoint()
			val mergedCells = checkNotNull(mergedCells)
			val newMergedCells = changedCells.filter(mergedCells::contains)
			for (newMergedCell in newMergedCells) {
				cellsToCheck.put(newMergedCell, mergedCells[newMergedCell]!!)
			}

			var status = PackStatus.VALID
			val it = cellsToCheck.iterator()
			while (it.hasNext()) {
				val e = it.next()
				val sourceCell = e.value
				if (sourceCell.getCluster<Cluster<*, *>>() != null) {
					it.remove()
				} else {
					status = PackStatus.CONDITIONAL
					val possibles = getAvailableBels(sourceCell)
					if (possibles.isEmpty()) {
						status = PackStatus.INFEASIBLE
						break
					}
				}
			}

			return if (status == PackStatus.CONDITIONAL) {
				val conditionals = cellsToCheck.values
					.map { it to HashSet(getAvailableBels(it)) }
					.toMap()
				PackRuleResult(status, conditionals)
			} else {
				PackRuleResult(status, null)
			}
		}

		override fun revert() {
			cellsToCheck.rollBack()
		}

		private fun getAvailableBels(cell: Cell): List<Bel> {
			return cell.getPossibleAnchors(cluster.type.template)
				.filter { b -> !cluster.isBelOccupied(b) }
		}
	}
}
