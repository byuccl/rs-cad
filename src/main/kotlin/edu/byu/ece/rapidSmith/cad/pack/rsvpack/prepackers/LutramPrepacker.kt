package edu.byu.ece.rapidSmith.cad.pack.rsvpack.prepackers

import edu.byu.ece.rapidSmith.cad.cluster.Cluster
import edu.byu.ece.rapidSmith.cad.cluster.PackUnit
import edu.byu.ece.rapidSmith.cad.cluster.locationInCluster
import edu.byu.ece.rapidSmith.cad.families.artix7.Ram
import edu.byu.ece.rapidSmith.cad.families.artix7.RamMaker
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.*
import edu.byu.ece.rapidSmith.design.subsite.Cell
import edu.byu.ece.rapidSmith.design.subsite.CellDesign
import edu.byu.ece.rapidSmith.device.Bel
import edu.byu.ece.rapidSmith.device.Site
import java.util.*

/**
 * Used to speed up the packing of LUTRAMs.  The getLocations method is
 * outdated and doesn't work properly with RAMs that use both the 5 and 6 LUT
 * components of a LUT.  This is being left in for reference.
 */
class LutramPrepackerFactory(private val ramMaker: RamMaker)
	: PrepackerFactory<PackUnit>() {

	private var rams: Map<Cell, Ram>? = null

	override fun init(design: CellDesign) {
		rams = ramMaker.make(design)
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
			.associateBy { it.ram!! }
			.filter { !it.key.fullyPacked() }

		if (incompleteRams.isEmpty())
			return PrepackStatus.UNCHANGED

		for ((ram, ramCell) in incompleteRams) {
			val (possible, locations) = findLocationsForCells(ramCell, ram, cluster)
			if (!possible)
				return PrepackStatus.INFEASIBLE

			for (cell in ram.cells) {
				val locInCluster = cell.locationInCluster
				if (locInCluster == null) {
					val bel = locations[cell]!!
					val status = addCellToCluster(cluster, cell, bel)
					if (status == PackStatus.VALID)
						changedCells[cell] = bel
					if (status == PackStatus.INFEASIBLE)
						return PrepackStatus.INFEASIBLE
				} else {
					val le = locInCluster.name[0]
					if (le !in cell.ramPosition)
						return PrepackStatus.INFEASIBLE
				}
			}
		}

		return PrepackStatus.CHANGED
	}

	private fun findLocationsForCells(
		ramCell: Cell, ram: Ram, cluster: Cluster<*, *>
	) : Pair<Boolean, Map<Cell, Bel>> {
		val baseBel = ramCell.locationInCluster!!
		val baseSite = baseBel.site
		val le = baseBel.name[0]
		val num = baseBel.name[1]

		val ramSize = ram.cells.size
		return when (ramSize) {
			1 -> Pair(true, emptyMap())
			2 -> matchRamPairs(baseSite, cluster, le, num, ram)
			3,4 -> findLocationForFullUsage(baseSite, cluster, num, ram)
			else -> throw AssertionError("too many rams in group")
		}
	}

	private fun matchRamPairs(
		baseSite: Site, cluster: Cluster<*, *>,
		le: Char, num: Char, ram: Ram
	) : Pair<Boolean, Map<Cell, Bel>> {
		// Select the other location for this cell
		val ole = when (le) {
			'A' -> 'B'
			'B' -> 'A'
			'C' -> 'D'
			'D' -> 'C'
			else -> throw AssertionError("Illegal LE")
		}

		// validate the size of the RAM
		val unplacedCells = ram.unpackedCells()
		assert(unplacedCells.size <= 1)

		// check if it's already packed
		if (unplacedCells.isEmpty())
			return Pair(true, emptyMap())

		// make sure its a valid position for the cell
		val unplacedCell = unplacedCells[0]
		if (ole !in unplacedCell.ramPosition)
			return Pair(false, emptyMap())

		// make sure the BEL is available
		val obel = baseSite.getBel("$ole${num}LUT")
		if (cluster.isBelOccupied(obel))
			return Pair(false, emptyMap())

		val locations = HashMap<Cell, Bel>()
		locations[unplacedCell] = obel
		return Pair(true, locations)
	}

	private fun findLocationForFullUsage(
		baseSite: Site, cluster: Cluster<*, *>,
		num: Char, ram: Ram
	): Pair<Boolean, Map<Cell, Bel>> {
		val openBels = ('D' downTo 'A')
			.map { baseSite.getBel("$it${num}LUT") }
			.filter { !cluster.isBelOccupied(it) }

		var foundValidPermutation = false
		for (permutation in ram.unpackedCells().permutations()) {
			assert(!foundValidPermutation)
			foundValidPermutation = true
			val locations = HashMap<Cell, Bel>()
			val bels = ArrayList(openBels)
			for (cell in permutation) {
				var foundBelForCell = false
				val it = bels.iterator()
				while (it.hasNext()) {
					val obel = it.next()
					val ole = obel.name[0]
					if (ole in cell.ramPosition) {
						it.remove()
						locations[cell] = obel
						foundBelForCell = true
						break
					}
				}

				if (!foundBelForCell) {
					foundValidPermutation = false
					break
				}
			}
			if (foundValidPermutation)
				return Pair(true, locations)
		}
		assert(!foundValidPermutation)
		return Pair(false, emptyMap())
	}

	private val Cell.ram : Ram?
		get() {
			return rams[this]
		}

	private val Cell.ramPosition : String
		get() {
			return rams[this]!!.positions[this]!!
		}
}

private fun <T> List<T>.ringIterator() = RingIterator(this)

private class RingIterator<out T>(private val of: List<T>) {
	private var index = 0

	val value: T
		get() = of[index]

	fun step(): Boolean {
		if (++index == of.size) {
			index = 0
			return true
		}
		return false
	}
}

private class Permutations<out T>(val of: List<T>) : Iterable<List<T>> {
	override fun iterator(): Iterator<List<T>>  = PermutationIterator(of)
}

private class PermutationIterator<out T>(of: List<T>) : Iterator<List<T>> {
	private val state = ArrayList<RingIterator<T>>()
	private var cur: List<T>?

	init {
		for (i in state.size downTo 1) {
			state += of.ringIterator()
		}
		cur = computeNext()
	}

	override fun hasNext(): Boolean = cur != null

	override fun next(): List<T> {
		val next = cur ?: throw NoSuchElementException()
		cur = computeNext()
		return next
	}

	private fun computeNext() : List<T>? {
		val next = ArrayList<T>(state.size)
		var step = true
		for (i in state) {
			if (step)
				step = i.step()
			next += i.value
		}

		return if (step) null else next
	}
}

private fun <T> List<T>.permutations() = Permutations(this)


