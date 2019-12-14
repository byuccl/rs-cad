package edu.byu.ece.rapidSmith.cad.pack.rsvpack.prepackers

import edu.byu.ece.rapidSmith.cad.cluster.Cluster
import edu.byu.ece.rapidSmith.cad.cluster.PackUnit
import edu.byu.ece.rapidSmith.cad.cluster.locationInCluster
import edu.byu.ece.rapidSmith.cad.families.Ram
import edu.byu.ece.rapidSmith.cad.families.RamMaker
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.*
import edu.byu.ece.rapidSmith.design.subsite.Cell
import edu.byu.ece.rapidSmith.design.subsite.CellDesign
import edu.byu.ece.rapidSmith.device.Bel

/**
 * Used to speed up the packing of LUTRAMs.  The getLocations method is
 * outdated and doesn't work properly with RAMs that use both the 5 and 6 LUT
 * components of a LUT.  This is being left in for reference.
 */
class LutramPrepackerFactory(private val ramMaker: RamMaker)
	: PrepackerFactory<PackUnit>() {

	private var rams: Map<Cell, Ram>? = null
	private var heads = LinkedHashMap<Ram, Cell>()

	override fun init(design: CellDesign) {
		val rams = ramMaker.make(design)
		this.rams = rams
		heads.putAll(ramMaker.buildHeads(rams))
	}

	override fun make(): Prepacker<PackUnit> =
		LutramPrepacker(checkNotNull(rams))
}

private class LutramPrepacker(
	val rams: Map<Cell, Ram>
) : Prepacker<PackUnit>() {

	override fun packRequired(
		cluster: Cluster<*, *>, changedCells: MutableMap<Cell, Bel>
	): PrepackStatus {
		val changedRamCells = changedCells.keys.filter { it -> it in rams }
		val incompleteRams = changedRamCells
			.groupBy { it.ram!! }
			.filter { !it.key.fullyPacked() }

		if (incompleteRams.isEmpty())
			return PrepackStatus.UNCHANGED

		var changed = false
		for (ram in incompleteRams.keys) {
			val mapping = when (ram.type) {
				"RAM32X1S" -> {
					val (_, _) = getBaseOffset(ram) ?:
						return PrepackStatus.INFEASIBLE

					mapOf("SP" to ram.cells.single().locationInCluster!!.name + "LUT")
				}
				"RAM64X1S" -> {
					mapOf("SP" to ram.cells.single().locationInCluster!!.name + "LUT")
				}
				"RAM128X1S" -> {
					val (c, _) = getBaseOffset(ram) ?:
						return PrepackStatus.INFEASIBLE

					mapOf("LOW" to "${c}6LUT", "HIGH" to "${c-1}6LUT")
				}
				"RAM256X1S" -> {
					// based off of name, place cells
					mapOf("RAMA" to "A6LUT", "RAMB" to "B6LUT", "RAMC" to "C6LUT", "RAMD" to "D6LUT")
				}
				"RAM32X1D" -> {
					val (c, i) = getBaseOffset(ram) ?:
						return PrepackStatus.INFEASIBLE

					// check the names to indices map
					mapOf("SP" to "$c${i}LUT", "DP" to "${c-1}${i}LUT")
				}
				"RAM64X1D" -> {
					val (c, _) = getBaseOffset(ram) ?:
						return PrepackStatus.INFEASIBLE

					mapOf("SP" to "${c}6LUT", "DP" to "${c-1}6LUT")
				}
				"RAM128X1D" -> {
					// check the names to indices map
					mapOf("SP.LOW" to "D6LUT", "SP.HIGH" to "C6LUT", "DP.LOW" to "B6LUT", "DP.HIGH" to "A6LUT", "F7.SP" to "F7BMUX", "F7.DP" to "F7AMUX")
				}
				"RAM32M" -> {
					mapOf("RAMD" to "D5LUT", "RAMC" to "C5LUT", "RAMB" to "B5LUT", "RAMA" to "A5LUT",
							"RAMD_D1" to "D6LUT", "RAMC_D1" to "C6LUT", "RAMB_D1" to "B6LUT", "RAMA_D1" to "A6LUT")
				}
				"RAM64M" -> {
					mapOf("RAMD" to "D6LUT", "RAMC" to "C6LUT", "RAMB" to "B6LUT", "RAMA" to "A6LUT",
						"RAMD_D1" to "D5LUT", "RAMC_D1" to "C5LUT", "RAMB_D1" to "B5LUT", "RAMA_D1" to "A5LUT")
				}

				else -> error("Unsupported RAM type: ${ram.type}")
			}

			for (cell in ram.cells) {
				val ext = cell.name.substringAfterLast("/")
				val expected = mapping[ext]!!
				if (cell.locationInCluster == null) {
					val bel = getBel(cluster, expected)
					val res = addCellToCluster(cluster, cell, bel)
					changedCells[cell] = bel
					if (res == PackStatus.INFEASIBLE)
						return PrepackStatus.INFEASIBLE
					changed = true
				} else if (cell.locationInCluster!!.name != expected) {
					return PrepackStatus.INFEASIBLE
				}
			}

			if (ram.cells.any { it.locationInCluster!!.name[1] == '5'} && !dLUTOccupied(cluster, 5))
				return PrepackStatus.INFEASIBLE
			if (ram.cells.any { it.locationInCluster!!.name[1] == '6'} && !dLUTOccupied(cluster, 6))
				return PrepackStatus.INFEASIBLE
		}

		return if (changed) PrepackStatus.CHANGED else PrepackStatus.UNCHANGED
	}

	private fun getBel(cluster: Cluster<*, *>, expected: String) =
		cluster.type.template.bels.single { it.name == expected }

	private fun getBaseOffset(ram: Ram): Pair<Char, Int>? {
		return when (ram.type) {
			"RAM32X1S" -> {
				val cell = ram.cells.single()
				val bel = cell.locationInCluster!!
				val ch = bel.name[0]
				val index = bel.name[1] - '0'
				Pair(ch, index)
			}
			"RAM128X1S" -> {
				val ch = arrayOfNulls<Char>(1)
				for (cell in ram.cells) {
					val bel = cell.locationInCluster
					if (bel != null) {
						when {
							cell.name.endsWith(("LOW")) -> {
								when (bel.name[0]) {
									'B', 'D' -> ch.matchOrNull(bel.name[0]) ?: return null
									else -> return null
								}
							}
							cell.name.endsWith(("HIGH")) -> {
								when (bel.name[0]) {
									'A', 'C' -> ch.matchOrNull(bel.name[0] + 1) ?: return null
									else -> return null
								}
							}
							else -> error("bad name")
						}
					}
				}
				Pair(ch[0]!!, 6)
			}
			"RAM32X1D" -> {
				val ch = arrayOfNulls<Char>(1)
				val index = arrayOfNulls<Char>(1)
				for (cell in ram.cells) {
					val bel = cell.locationInCluster
					if (bel != null) {
						when {
							cell.name.endsWith(("SP")) -> {
								when (bel.name[0]) {
									'B', 'D' -> ch.matchOrNull(bel.name[0]) ?: return null
									else -> return null
								}
								index.matchOrNull(bel.name[1]) ?: return null
							}
							cell.name.endsWith(("DP")) -> {
								when (bel.name[0]) {
									'A', 'C' -> ch.matchOrNull(bel.name[0] + 1) ?: return null
									else -> return null
								}
								index.matchOrNull(bel.name[1]) ?: return null
							}
							else -> error("bad name")
						}
					}
				}
				Pair(ch[0]!!, index[0]!! - '0')
			}
			"RAM64X1D" -> {
				val ch = arrayOfNulls<Char>(1)
				for (cell in ram.cells) {
					val bel = cell.locationInCluster
					if (bel != null) {
						when {
							cell.name.endsWith(("SP")) -> {
								when (bel.name[0]) {
									'B', 'D' -> ch.matchOrNull(bel.name[0]) ?: return null
									else -> return null
								}
							}
							cell.name.endsWith(("DP")) -> {
								when (bel.name[0]) {
									'A', 'C' -> ch.matchOrNull(bel.name[0] + 1) ?: return null
									else -> return null
								}
							}
							else -> error("bad name")
						}
					}
				}
				Pair(ch[0]!!, 6)
			}
			else -> error("invalid type")
		}
	}

	private fun dLUTOccupied(cluster: Cluster<*, *>, index: Int): Boolean {
		val bel = if (index == 6) cluster.D6LUT else cluster.D5LUT
		return cluster.isBelOccupied(bel)
	}

	private fun <T> Array<T>.matchOrNull(ch: T): Array<T>? {
		if (this[0] == null)
			this[0] = ch
		else if (this[0] != ch)
			return null

		return this
	}

	private val Cell.ram : Ram?
		get() {
			return rams[this]
		}
}

private val Cluster<*, *>.D6LUT
	get() = type.template.bels.single { it.name == "D6LUT" }

private val Cluster<*, *>.D5LUT
	get() = type.template.bels.single { it.name == "D5LUT" }

