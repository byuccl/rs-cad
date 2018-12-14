package edu.byu.ece.rapidSmith.cad.pack.rsvpack.prepackers

import edu.byu.ece.rapidSmith.cad.cluster.Cluster
import edu.byu.ece.rapidSmith.cad.cluster.PackUnit
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.*
import edu.byu.ece.rapidSmith.design.subsite.*
import edu.byu.ece.rapidSmith.device.Bel
import java.util.*


class SRLChainsPrepackerFactory(
	cellLibrary: CellLibrary
) : PrepackerFactory<PackUnit>() {

	private val srlToChainMap = HashMap<Cell, ArrayList<Cell>>()
	private val srlChains = ArrayList<ArrayList<Cell>>()

    private val srlCells = setOf(
			cellLibrary["SRL16E"],
			cellLibrary["SRLC16E"],
			cellLibrary["SRLC32E"]
	)
	private val srlOutPins  = setOf("Q31", "Q15")


	override fun init(design: CellDesign) {

		// Build chains of SRL cells and map into them
		design.cells.filter { it.libCell in srlCells }
				// Find first in chain
				.filter { it.getPin("D").net.sourcePin.name !in srlOutPins }
				.forEach { cell ->
					val chain = ArrayList<Cell>()
					srlChains.add(chain)
					chain.add(cell)
					srlToChainMap[cell] = chain
					var c = cell
					do {
						c = areMoreSRLsInChain(c)?: break
						chain.add(c)
						srlToChainMap[c] = chain
					} while (true)
				}

		println("Chains:")
		srlChains.forEach{
			println(" ${srlChains.indexOf(it)} $it")
		}
		println()
		println("Mappings:")
		srlToChainMap.forEach{ println("${it.key} --> ${srlChains.indexOf(it.value)} ") }

		// Now cut them into groups of 4
		srlChains.filter { it.size > 4 }.forEach {
			while (it.size > 4) {
				val chain = ArrayList<Cell>()
				srlChains.add(chain)
				for (i in 0..3) {
					val c = it[0]
					it.removeAt(0)
					chain.add(c)
					srlToChainMap[c] = chain
				}
			}
		}

		// Reverse order so first element in SRL chain is at end of list
		srlChains.forEach{ it.reverse() }

		println()
		println("Chains:")
		srlChains.forEach{
			println(" ${srlChains.indexOf(it)} $it")
		}
		println()
		println("Mappings:")
		srlToChainMap.forEach{ println("${it.key} --> ${srlChains.indexOf(it.value)} ") }

	}

	private fun areMoreSRLsInChain(cell: Cell): Cell? {
        var n: CellNet? = null
		when (cell.type) {
			"SRL16E", "SRLC16E" ->
				n = cell.getPin("Q15")?.net ?: return null
			"SRLC32E" ->
				n = cell.getPin("Q31")?.net ?: return null
		}

		n!!.sinkPins.asSequence().map(CellPin::getCell).forEach {
			if (it.libCell in srlCells)
				return(it)
		}

		return null
	}


	override fun make(): Prepacker<PackUnit> {
		return SRLChainsPrepacker(srlToChainMap, srlCells)
	}
}

private class SRLChainsPrepacker(val srlToChainMap: HashMap<Cell, ArrayList<Cell>>,
								 val srlCells: Set<LibraryCell>) : Prepacker<PackUnit>() {

	override fun packRequired(
			cluster: Cluster<*, *>,
			changedCells: MutableMap<Cell, Bel>
	): PrepackStatus {

		val changedSRLs = changedCells.map { it.key }.filter { it.libCell in srlCells }.toList()

		// Have some to place?
		var status = PrepackStatus.UNCHANGED
		changedSRLs.forEach { cell ->
			val bel = changedCells[cell]!!
			if (srlInWrongPlace(cell, bel))
				return PrepackStatus.INFEASIBLE

			// Go identify the other SRL's in the chain and where they should go and try to place them
			val chain = srlToChainMap[cell]!!
			val indx = chain.indexOf(cell)
			val lutLoc = cluster.getCellPlacement(cell)!!.name[0]
			for (i in 0..chain.lastIndex) {
				val c = chain[i]
				if (c == cell)
					continue
				if (cluster.getCellPlacement(c) == null) {
					val newlutloc = lutLoc + i - indx
					val newLutName = if (c.libCell.name=="SRLC32E") "${newlutloc}6LUT" else "${newlutloc}5LUT"
					val sinkBel = bel.site.getBel(newLutName)?: throw AssertionError("sinkBel Assertion failed")
					val addStatus = addCellToCluster(cluster, c, sinkBel)
					if (addStatus == PackStatus.VALID) {
						changedCells[c] = sinkBel
						status = PrepackStatus.CHANGED
					} else if (addStatus == PackStatus.INFEASIBLE) {
						return PrepackStatus.INFEASIBLE
					}
				}
			}
		}
		return status
	}

	private fun srlInWrongPlace(cell: Cell, bel: Bel) : Boolean {
		val chain = srlToChainMap[cell]?: throw AssertionError("Unknown chain")
		val indx = chain.indexOf(cell)
		assert(indx != -1)
		val lutPos = bel.name[0] - 'A'
		val min = lutPos - indx
		val max = min + chain.size
		if (min >= 0 && max <= 3)
			return false
		return true
	}
}

