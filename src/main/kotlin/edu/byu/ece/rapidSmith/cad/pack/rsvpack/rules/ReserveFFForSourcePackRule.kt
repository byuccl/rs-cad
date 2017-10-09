//package edu.byu.ece.rapidSmith.cad.packing.rsvpack.rules
//
//import edu.byu.ece.rapidSmith.cad.cluster.Cluster
//import edu.byu.ece.rapidSmith.cad.cluster.getCluster
//import edu.byu.ece.rapidSmith.cad.cluster.getPossibleAnchors
//import edu.byu.ece.rapidSmith.cad.packing.rsvpack.PackRule
//import edu.byu.ece.rapidSmith.cad.packing.rsvpack.PackRuleFactory
//import edu.byu.ece.rapidSmith.cad.packing.rsvpack.PackStatus
//import edu.byu.ece.rapidSmith.design.subsite.*
//import edu.byu.ece.rapidSmith.device.Bel
//import edu.byu.ece.rapidSmith.util.StackedHashMap
//import java.util.HashMap
//import java.util.HashSet
//
///**
// *
// */
//class ReserveFlipFlopForSourcePackRuleFactory(cellLibrary: CellLibrary) : PackRuleFactory {
//	private val ccLibCell: LibraryCell = cellLibrary.get("CARRY4")
//	private val ffLibCells: Set<LibraryCell>
//	private var mergedCells: MutableMap<Cell, Cell>? = null
//
//	init {
//		ffLibCells = HashSet<LibraryCell>()
//		ffLibCells.add(cellLibrary.get("FF_INIT"))
//		ffLibCells.add(cellLibrary.get("REG_INIT"))
//	}
//
//	override fun init(design: CellDesign) {
//		mergedCells = HashMap()
//
//		for (cell in design.cells) {
//			if (cell.libCell === ccLibCell) {
//				val packCell = cell as Cell
//				var le = 'A'
//				while (le <= 'D') {
//					val pinNum = le - 'A'
//					val opin = packCell.getPin("O" + pinNum)
//					val copin = packCell.getPin("CO" + pinNum)
//
//					if (opin.isConnectedToNet && hasExternalConnection(copin)) {
//						if (drivesOnlyFF(opin)) {
//							val sinkPins = opin.net.sinkPins
//							assert(sinkPins.size == 1)
//							val sinkPin = sinkPins.iterator().next()
//							mergedCells!!.put(sinkPin.cell, packCell)
//						} else {
//							assert(drivesOnlyFF(copin))
//							val sinkPins = copin.net.sinkPins
//							assert(sinkPins.size == 1)
//							val sinkPin = sinkPins.iterator().next()
//							mergedCells!!.put(sinkPin.cell, packCell)
//						}
//					}
//					le++
//				}
//			}
//		}
//	}
//
//	private fun hasExternalConnection(sourcePin: CellPin): Boolean {
//		if (!sourcePin.isConnectedToNet)
//			return false
//		val net = sourcePin.net
//		if (net.pins.size == 1)
//			return false
//		if (net.pins.size > 2)
//			return true
//		val sinkPin = net.sinkPins.iterator().next()
//		// tests if this is a carry chain connection
//		if (sinkPin.cell.libCell === ccLibCell) {
//			return sinkPin.name != "CIN"
//		} else {
//			return true
//		}
//	}
//
//	private fun drivesOnlyFF(sourcePin: CellPin): Boolean {
//		val net = sourcePin.net
//		if (net.pins.size > 2)
//			return false
//		val sinkPin = net.sinkPins.iterator().next()
//		return isFlipflopDInput(sinkPin)
//	}
//
//	private fun isFlipflopDInput(sinkPin: CellPin): Boolean {
//		return ffLibCells.contains(sinkPin.cell.libCell) && sinkPin.name == "D"
//	}
//
//	override fun make(cluster: Cluster<*, *>): PackRule {
//		return Rule(cluster)
//	}
//
//	private inner class Rule(private val cluster: Cluster<*, *>) : PackRule {
//		private val cellsToCheck = StackedHashMap<Cell, Cell>()
//
//		override fun validate(changedCells: Collection<Cell>): PackStatus {
//			cellsToCheck.checkPoint()
//			val mergedCells = checkNotNull(mergedCells)
//			val newMergedCells = changedCells.filter(mergedCells::contains)
//			for (newMergedCell in newMergedCells) {
//				cellsToCheck.put(newMergedCell, mergedCells[newMergedCell]!!)
//			}
//
//			var status = PackStatus.VALID
//			val it = cellsToCheck.iterator()
//			while (it.hasNext()) {
//				val e = it.next()
//				val sourceCell = e.value
//				if (sourceCell.getCluster<Cluster<*, *>>() != null) {
//					it.remove()
//				} else {
//					status = PackStatus.CONDITIONAL
//					val possibles = getAvailableBels(sourceCell)
//					if (possibles.isEmpty()) {
//						status = PackStatus.INFEASIBLE
//						break
//					}
//				}
//			}
//			return status
//		}
//
//		override fun revert() {
//			cellsToCheck.rollBack()
//		}
//
//		override val conditionals: Map<Cell, Set<Bel>>
//			get() {
//				return cellsToCheck.values.map { it to HashSet(getAvailableBels(it)) }.toMap()
//			}
//
//		private fun getAvailableBels(cell: Cell): List<Bel> {
//			return cell.getPossibleAnchors(cluster.packUnit.template)
//				.filter { b -> !cluster.isBelOccupied(b) }
//		}
//	}
//}
