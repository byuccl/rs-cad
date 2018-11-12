package edu.byu.ece.rapidSmith.cad.pack.rsvpack.prepackers

import edu.byu.ece.rapidSmith.cad.cluster.*
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.*
import edu.byu.ece.rapidSmith.design.subsite.Cell
import edu.byu.ece.rapidSmith.design.subsite.CellDesign
import edu.byu.ece.rapidSmith.design.subsite.CellLibrary
import edu.byu.ece.rapidSmith.design.subsite.LibraryCell
import edu.byu.ece.rapidSmith.device.Bel
import java.util.HashMap
import kotlin.streams.toList


// if a lut is driving a single ff and there is a space, pack it.

class Artix7LutFFPrepackerFactory(
	cellLibrary: CellLibrary
) : PrepackerFactory<PackUnit>() {
	private var pairedCells = HashMap<Cell, Cell>()

	private val ffLibCells: Map<LibraryCell, String>
	private val lutLibCells: Map<LibraryCell, String>
	private val f78LibCells: Map<LibraryCell, String>
	private val lutramLibCells: Map<LibraryCell, String>

	init {
		ffLibCells = HashMap()
		ffLibCells[cellLibrary["AND2B1L"]] = "DI"
		ffLibCells[cellLibrary["FDCE"]] = "D"
		ffLibCells[cellLibrary["FDPE"]] = "D"
		ffLibCells[cellLibrary["FDRE"]] = "D"
		ffLibCells[cellLibrary["FDSE"]] = "D"
		ffLibCells[cellLibrary["LDCE"]] = "D"
		ffLibCells[cellLibrary["LDPE"]] = "D"
		ffLibCells[cellLibrary["OR2L"]] = "DI"

		lutLibCells = HashMap()
		lutLibCells[cellLibrary["LUT1"]] = "O"
		lutLibCells[cellLibrary["LUT2"]] = "O"
		lutLibCells[cellLibrary["LUT3"]] = "O"
		lutLibCells[cellLibrary["LUT4"]] = "O"
		lutLibCells[cellLibrary["LUT5"]] = "O"
		lutLibCells[cellLibrary["LUT6"]] = "O"

		f78LibCells = HashMap()
		f78LibCells[cellLibrary.get("MUXF8")] = "O"
		f78LibCells[cellLibrary.get("MUXF7")] = "O"

		lutramLibCells = HashMap()
		lutramLibCells[cellLibrary["RAMD64E"]] = "O"

		// Right now, don't worry about SRLs because Yosys won't make them.
		// Do the same for non-supported LUTRAMs.

		// The following should be removed for Maverick:

		lutramLibCells[cellLibrary["SRLC16E"]] = "Q"
		lutramLibCells[cellLibrary["SRLC32E"]] = "Q"
		lutramLibCells[cellLibrary["SRL16E"]] = "Q"
		lutramLibCells[cellLibrary["RAMD32"]] = "O"
		lutramLibCells[cellLibrary["RAMS32"]] = "O"
		lutramLibCells[cellLibrary["RAMS64E"]] = "O"
	}

	override fun init(design: CellDesign) {
		val pairs = design.inContextLeafCells.filter { it.libCell in ffLibCells }
			.map { it to getFFSource((it)) }
			.filter { it.second != null }
			.map { it.first to it.second!!}
			.toList()
		pairs.associateTo(pairedCells) { it.first to it.second }
		pairs.associateTo(pairedCells) { it.second to it.first }
	}

	private fun getFFSource(ffCell: Cell): Cell? {
		val dPin = ffCell.getPin(ffLibCells[ffCell.libCell])!!
		if (!dPin.isConnectedToNet)
			return null

		val net = dPin.net
		if (net.pins.size == 2) {
			val sourcePin = net.sourcePin
			if (sourcePin != null) {
				val sourceCell = sourcePin.cell
				if (isValidSourceCell(sourceCell)) {
					return sourceCell
				}
			}
		}
		return null
	}

	private fun isValidSourceCell(cell: Cell): Boolean {
		val libraryCell = cell.libCell
		return libraryCell in lutLibCells ||
			libraryCell in f78LibCells ||
			libraryCell in lutramLibCells
	}

	override fun make(): Prepacker<PackUnit> =
		Artix7LutFFPrepacker(pairedCells)

	private inner class Artix7LutFFPrepacker(
		private val pairedCells: Map<Cell, Cell>
	) : Prepacker<PackUnit>() {
		override fun packRequired(
			cluster: Cluster<*, *>,
			changedCells: MutableMap<Cell, Bel>
		): PrepackStatus {
			val pairedCells = checkNotNull(pairedCells) { "init not called" }
			val status = PrepackStatus.UNCHANGED

			val copy = HashMap(changedCells)
			for ((changedCell, bel) in copy) {
				val pairedCell = pairedCells[changedCell]
				if (pairedCell != null) {
					when (pairedCell.libCell) {
						in lutLibCells -> status.meet(packLutCell(cluster, bel, pairedCell, changedCells))
						in lutramLibCells -> status.meet(packLutCell(cluster, bel, pairedCell, changedCells))
						in f78LibCells -> status.meet(packF78Cell(cluster, bel, pairedCell, changedCells))
						in ffLibCells -> status.meet(packFFCell(cluster, bel, pairedCell, changedCells))
						else -> throw AssertionError("Illegal changedCell type")
					}
				}

				if (status == PrepackStatus.INFEASIBLE)
					return status
			}

			return status
		}

		private fun packLutCell(
			cluster: Cluster<*, *>, changedBel: Bel, pairedCell: Cell,
			changedCells: MutableMap<Cell, Bel>
		): PrepackStatus {
			if (pairedCell.getCluster<Cluster<*, *>>() != null)
				return PrepackStatus.UNCHANGED
			val le = changedBel.name[0]
			val index = if (changedBel.name[1] == '5') 5 else 6
			val pairedBel = changedBel.site.getBel("$le${index}LUT")
			val status = addToCluster(cluster, pairedBel, pairedCell, changedCells)
			return if (status == PackStatus.INFEASIBLE)
				PrepackStatus.INFEASIBLE
			else
				PrepackStatus.CHANGED
		}

		private fun packF78Cell(
			cluster: Cluster<*, *>, changedBel: Bel, pairedCell: Cell,
			changedCells: MutableMap<Cell, Bel>
		): PrepackStatus {
			if (pairedCell.getCluster<Cluster<*, *>>() != null)
				return PrepackStatus.UNCHANGED
			val le = changedBel.name[0]
			val index = if (changedBel.name[1] == '5') 5 else 6
			if (index == 5)
				return PrepackStatus.INFEASIBLE
			val pairedBel = when (le) {
				'A' -> changedBel.site.getBel("F7AMUX")
				'B' -> changedBel.site.getBel("F8MUX")
				'C' -> changedBel.site.getBel("F7BMUX")
				else -> return PrepackStatus.INFEASIBLE
			}
			val status = addToCluster(cluster, pairedBel, pairedCell, changedCells)
			return if (status == PackStatus.INFEASIBLE)
				PrepackStatus.INFEASIBLE
			else
				PrepackStatus.CHANGED
		}

		private fun packFFCell(
			cluster: Cluster<*, *>, changedBel: Bel, pairedCell: Cell,
			changedCells: MutableMap<Cell, Bel>
		): PrepackStatus {
			if (pairedCell.getCluster<Cluster<*, *>>() != null)
				return PrepackStatus.UNCHANGED

			val (le, index) = when {
				changedBel.name.matches(Regex("[A-D][56]LUT")) -> {
					Pair(changedBel.name[0], changedBel.name[1])
				}
				changedBel.name.matches(Regex("F7[AB]MUX")) -> {
					val i = changedBel.name[2] - 'A'
					Pair('A' + (i * 2), '6')
				}
				changedBel.name == "F8MUX" -> {
					Pair('B', '6')
				}
				else -> throw AssertionError("Illegal BEL")
			}
			val pairedBel = changedBel.site.getBel("$le${if (index == '5') "5" else ""}FF")

			val status = addToCluster(cluster, pairedBel, pairedCell, changedCells)
			return if (status == PackStatus.INFEASIBLE)
				PrepackStatus.INFEASIBLE
			else
				PrepackStatus.CHANGED
		}

		private fun addToCluster(
			cluster: Cluster<*, *>, bel: Bel, cell: Cell,
			changedCells: MutableMap<Cell, Bel>
		): PackStatus? {
			val possibleAnchors = cell.libCell.possibleAnchors
			if (bel.id !in possibleAnchors)
				return PackStatus.INFEASIBLE
			val status = addCellToCluster(cluster, cell, bel)
			if (status == PackStatus.VALID)
				changedCells[cell] = bel
			return status
		}
	}
}
