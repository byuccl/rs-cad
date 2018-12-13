package edu.byu.ece.rapidSmith.cad.pack.rsvpack.prepackers

import edu.byu.ece.rapidSmith.cad.cluster.Cluster
import edu.byu.ece.rapidSmith.cad.cluster.PackUnit
import edu.byu.ece.rapidSmith.cad.cluster.getCluster
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.*
import edu.byu.ece.rapidSmith.design.subsite.*
import edu.byu.ece.rapidSmith.device.Bel
import java.lang.RuntimeException
import java.util.*
import kotlin.streams.asSequence

class SRLChainsPrepackerFactory(
	cellLibrary: CellLibrary
) : PrepackerFactory<PackUnit>() {

	private val mc31Sinks = LinkedHashMap<Cell, Cell>()
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
					srlToChainMap.put(cell, chain)
					val drivenPin = cell.getPin("D")
					val drivingPin = drivenPin.net.sourcePin
					var c = cell
					do {
						c = areMoreSRLsInChain(c)?: break
						chain.add(c)
						srlToChainMap.put(c, chain)
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
					val c = it.get(0)
					it.removeAt(0)
					chain.add(c)
					srlToChainMap.put(c, chain)
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

		val q31SourceCells = design.leafCells.asSequence()
			.filter { it.usesPin("Q31") || it.usesPin("Q15") }.toList()

		val srlCells = design.leafCells.asSequence()
			.filter { it.libCell in srlCells }.toList()

		for (source in q31SourceCells) {
			val mc31Pin = source.getPin("Q31") ?: source.getPin("Q15")!!
			val sinks = mc31Pin.net.sinkPins
			if (sinks.size != 1)
				throw AssertionError("Too many or too few sinks on an MC31 pin")
			val sink = sinks.first()!!.cell
			mc31Sinks[source] = sink
		}
	}

	fun areMoreSRLsInChain(cell: Cell): Cell? {
        var n: CellNet? = null
		when (cell.type) {
			"SRL16E", "SRLC16E" ->
				n = cell?.getPin("Q15")?.net ?: return null
			"SRLC32E" ->
				n = cell?.getPin("Q31")?.net ?: return null
		}

		n!!.sinkPins.asSequence().map(CellPin::getCell).forEach {
			if (it.libCell in srlCells)
				return(it)
		}

		return null
	}


	override fun make(): Prepacker<PackUnit> {
		return SRLChainsPrepacker(mc31Sinks, srlToChainMap, srlChains, srlCells)
	}
}

private class SRLChainsPrepacker(val mc31Sinks: Map<Cell, Cell>,
								 val srlToChainMap: HashMap<Cell, ArrayList<Cell>>,
								 val srlChains: ArrayList<ArrayList<Cell>>,
								 val srlCells: Set<LibraryCell>) : Prepacker<PackUnit>() {

	override fun packRequired(
			cluster: Cluster<*, *>,
			changedCells: MutableMap<Cell, Bel>
	): PrepackStatus {
		val mc31sinkCells = changedCells.asSequence()
				.map { it.key }
				.filter { it in mc31Sinks }
				.toList()

		//if (mc31sinkCells.isEmpty())
		//	return PrepackStatus.UNCHANGED

		val changedSRLs = changedCells.map { it.key }.filter { it.libCell in srlCells }.toList()

		// Have some to place?
		var status = PrepackStatus.UNCHANGED
		changedSRLs.forEach { cell ->
			val bel = changedCells.get(cell)!!
			if (srlInWrongPlace(cell, bel))
				return PrepackStatus.INFEASIBLE

			// Go identify the other SRL's in the chain and where they should go and try to place them
			val chain = srlToChainMap.get(cell)!!
			val indx = chain.indexOf(cell)
			val lutLoc = cluster.getCellPlacement(cell)!!.name[0]
			for (i in 0..chain.lastIndex) {
				val c = chain.get(i)
				if (c == cell)
					continue
				if (cluster.getCellPlacement(c) == null) {
					val newlutloc = lutLoc + i - indx
					val sinkBel = bel.site.getBel("${newlutloc}6LUT")
					assert(sinkBel != null)
					val addStatus = addCellToCluster(cluster, c, sinkBel)
					if (addStatus == PackStatus.VALID) {
						changedCells[c] = sinkBel
						println("SRL placement: $c $sinkBel")
						status = PrepackStatus.CHANGED
					} else if (addStatus == PackStatus.INFEASIBLE) {
						print("Infeasible: $cell $lutLoc $c $newlutloc")
						return PrepackStatus.INFEASIBLE
					}
				}
			}
		}
		return status
	}

	private fun srlInWrongPlace(cell: Cell, bel: Bel) : Boolean {
		val chain = srlToChainMap.get(cell)
		assert (chain !=  null)
		val indx = chain!!.indexOf(cell)?:-1
		assert (indx != -1)
		val lutPos = bel.name[0] - 'A'
		if (indx > lutPos)
			return true
		if (indx > lutPos+(4-chain.size)+1)
			return true
		return false
	}
}

private fun Cell.usesPin(name: String): Boolean {
	val pin = getPin(name)
	return pin?.isConnectedToNet ?: false
}

