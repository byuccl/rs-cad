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

	fun buildHeads(rams: Map<Cell, Ram>): Map<Ram, Cell> =
		rams.values.distinct().associate { it to findHead(it) }
}

private fun findCellPositions(ram: Ram, cells: List<Cell>) {
	when (ram.type) {
		"RAM32X1S" -> {
			val positions = cells.associate { cell ->
				val matched = Regex(".+/(.+)").matchEntire(cell.name)!!
				val position = when (matched.groupValues[1]) {
					"SP" -> "ABCD"
					else -> error("Unknown type: ${matched.groupValues[1]}")
				}
				cell to position
			}
			ram._positions = positions
		}
		"RAM64X1S" -> {
			val positions = cells.associate { cell ->
				val matched = Regex(".+/(.+)").matchEntire(cell.name)!!
				val position = when (matched.groupValues[1]) {
					"SP" -> "ABCD"
					else -> error("Unknown type: ${matched.groupValues[1]}")
				}
				cell to position
			}
			ram._positions = positions
		}
		"RAM128X1S" -> {
			val positions = cells.associate { cell ->
				val matched = Regex(".+/(.+)").matchEntire(cell.name)!!
				val position = when (matched.groupValues[1]) {
					"HIGH" -> "AC"
					"LOW" -> "BD"
					else -> error("Unknown type: ${matched.groupValues[1]}")
				}
				cell to position
			}
			ram._positions = positions
		}
		"RAM256X1S" -> {
			val positions = cells.associate { cell ->
				val matched = Regex(".+/(.+)").matchEntire(cell.name)!!
				val position = when (matched.groupValues[1]) {
					"RAMS64E_D" -> "D"
					"RAMS64E_C" -> "C"
					"RAMS64E_B" -> "B"
					"RAMS64E_A" -> "A"
					else -> error("Unknown type: ${matched.groupValues[1]}")
				}
				cell to position
			}
			ram._positions = positions
		}
		"RAM32X1D" -> {
			val positions = cells.associate { cell ->
				val matched = Regex(".+/(.+)").matchEntire(cell.name)!!
				val position = when (matched.groupValues[1]) {
					"SP", "DP" -> "ABCD"
					else -> error("Unknown type: ${matched.groupValues[1]}")
				}
				cell to position
			}
			ram._positions = positions
		}
		"RAM64X1D" -> {
			val positions = cells.associate { cell ->
				val matched = Regex(".+/(.+)").matchEntire(cell.name)!!
				val position = when (matched.groupValues[1]) {
					"SP", "DP" -> "ABCD"
					else -> error("Unknown type: ${matched.groupValues[1]}")
				}
				cell to position
			}
			ram._positions = positions
		}
		"RAM128X1D" -> {
			val positions = cells.associate { cell ->
				val matched = Regex(".+/(.+)").matchEntire(cell.name)!!
				val position = when (matched.groupValues[1]) {
					"SP.HIGH", "DP.HIGH" -> "AC"
					"SP.LOW" -> "D"
					"DP.LOW" -> "B"
					else -> error("Unknown type: ${matched.groupValues[1]}")
				}
				cell to position
			}
			ram._positions = positions
		}
		"RAM32M" -> {
			val positions = cells.associate { cell ->
				val matched = Regex(".+/(.+)").matchEntire(cell.name)!!
				val position = when (matched.groupValues[1]) {
					"RAMD", "RAMC", "RAMB", "RAMA",
					"RAMD_D1", "RAMC_D1", "RAMB_D1", "RAMA_D1" -> "ABCD"
					else -> error("Unknown type: ${matched.groupValues[1]}")
				}
				cell to position
			}
			ram._positions = positions
		}
		"RAM64M" -> {
			val positions = cells.associate { cell ->
				val matched = Regex(".+/(.+)").matchEntire(cell.name)!!
				val position = when (matched.groupValues[1]) {
					"RAMD", "RAMC", "RAMB", "RAMA" -> "ABCD"
					else -> error("Unknown type: ${matched.groupValues[1]}")
				}
				cell to position
			}
			ram._positions = positions
		}
		else -> error("Unsupported RAM type: ${ram.type}")
	}
}

private fun findHead(ram: Ram): Cell {
	return when (ram.type) {
		"RAM32X1S" -> ram.cells.single { it.name.endsWith("SP") }
		"RAM64X1S" -> ram.cells.single { it.name.endsWith("SP") }
		"RAM128X1S" -> ram.cells.single { it.name.endsWith("LOW") }
		"RAM256X1S" -> ram.cells.single { it.name.endsWith("RAMS64E_D") }
		"RAM32X1D" -> ram.cells.single { it.name.endsWith("SP") }
		"RAM64X1D" -> ram.cells.single { it.name.endsWith("SP") }
		"RAM128X1D" -> ram.cells.single { it.name.endsWith("SP.LOW") }
		"RAM32M" -> ram.cells.single { it.name.endsWith("RAMD") }
		"RAM64M" -> ram.cells.single { it.name.endsWith("RAMD") }
		else -> error("Unsupported RAM type: ${ram.type}")
	}
}
