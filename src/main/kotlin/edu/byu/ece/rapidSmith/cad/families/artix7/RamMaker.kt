package edu.byu.ece.rapidSmith.cad.families.artix7

import edu.byu.ece.rapidSmith.cad.cluster.Cluster
import edu.byu.ece.rapidSmith.cad.cluster.getCluster
import edu.byu.ece.rapidSmith.cad.cluster.isValid
import edu.byu.ece.rapidSmith.design.subsite.*
import kotlin.streams.asSequence


class Ram(var parent: Cell) {
	val cells : Collection<Cell>
		get() = parent.internalCells

	val type: String
		get() = parent.type

	internal var _positions: Map<Cell, String>? = null
		set(value) { field = requireNotNull(value) }
	val positions: Map<Cell, String>
		get() = checkNotNull(_positions) { "field positions was never set" }

	internal fun fullyPacked(): Boolean {
		return cells.all { it.getCluster<Cluster<*, *>>() != null }
	}

	internal fun unpackedCells(): List<Cell> {
		return cells.filter { it.isValid }
	}
}

class RamMaker(cellLibrary: CellLibrary) {
	val leafRamCellTypes = setOf(
		cellLibrary.get("RAMD32"),
		cellLibrary.get("RAMD64E"),
		cellLibrary.get("RAMS32"),
		cellLibrary.get("RAMS64E"))

	fun make(design: CellDesign): Map<Cell, Ram> {
		return design.leafCells.asSequence()
			.filter { it.libCell in leafRamCellTypes } // look at just RAM cells
			.map { it to it.parent } // RAM cell to parent cell
			.filter { it.second != null } // Not all RAM cells are part of a larger RAM
			.map { it.first to it.second!! } // casts the parent as not null
			.groupBy { it.second } // groups the leafs by their parent cell
			.mapValues { it.value.map { it.first!! } } // simplifies the value to just the cells
			.mapKeys { Ram(it.key) } // convert the parent to a ram
			.onEach { findCellPositions(it.key, it.value) }
			.flatMap { (parent, children) -> children.map { it to parent } } // reverse the mapping
			.toMap() // collect
	}
}

private fun findCellPositions(ram: Ram, cells: List<Cell>) {
	when (ram.type) {
		"RAM128X1D" -> {
			val positions = cells.associate { cell ->
				val position = when (cell.name) {
					"SP.HIGH", "DP.HIGH" -> "AC"
					"SP.LOW" -> "D"
					"DP.LOW" -> "B"
					else -> error("Unknown type")
				}
				cell to position
			}
			ram._positions = positions
		}
		else -> error("Unsupported RAM type: ${ram.type}")
	}

	// I developed these when I had to pull the locations from the names of the cells
	// Might be useful for building this info
//	val position: String?
//	when (m.group(2)) {
//		"/D" -> position = "D"
//		"/C" -> position = "C"
//		"/B" -> position = "B"
//		"/A" -> position = "A"
//		"/LOW", "/SP.LOW" -> position = "BD"
//		"/DP.LOW" -> position = "B"
//		"/HIGH", "/SP.HIGH", "/DP.HIGH" -> position = "AC"
//		"/DP" -> position = "ABC"
//		"/SP", "" -> position = "ABCD"
//		"_RAMD", "_RAMC", "_RAMB", "_RAMA", "_RAMD_D1", "_RAMC_D1", "_RAMB_D1", "_RAMA_D1" -> position = "ABCD"
//	}
}
