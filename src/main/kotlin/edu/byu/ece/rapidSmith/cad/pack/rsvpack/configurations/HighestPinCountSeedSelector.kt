package edu.byu.ece.rapidSmith.cad.pack.rsvpack.configurations

import edu.byu.ece.rapidSmith.cad.cluster.*
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.CadException
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.SeedSelector
import edu.byu.ece.rapidSmith.design.subsite.Cell
import edu.byu.ece.rapidSmith.design.subsite.CellDesign
import edu.byu.ece.rapidSmith.design.subsite.CellPin
import edu.byu.ece.rapidSmith.design.subsite.ImplementationMode
import java.util.*
import java.util.stream.Stream

/**
 *
 */
class HighestPinCountSeedSelector : SeedSelector<PackUnit> {
	/* different lists for clustering */
	private var maxCellInputs: Int = 0
	private var unclusteredCellsMap: HashMap<Int, ArrayList<Cell>>? = null
	private val carryChains = HashSet<Cell>()

	override fun init(packUnits: Collection<PackUnit>, design: CellDesign) {
		unclusteredCellsMap = HashMap()

		// Add all the cells to the appropriate location
		maxCellInputs = 0

		val cells: Stream<Cell>
		//if (design.implementationMode.equals(ImplementationMode.RECONFIG_MODULE))
		//	cells = design.leafCells
		//else
		cells = design.inContextLeafCells

		for (cell in cells) {
			val numInputPins = getNumExternalPinsOfCell(cell)
			unclusteredCellsMap!!.computeIfAbsent(numInputPins) { ArrayList() }.add(cell)
			if (numInputPins > maxCellInputs)
				maxCellInputs = numInputPins
		}

		// Now sort them based on the gain
		for (unclusteredCellsList in unclusteredCellsMap!!.values) {
			unclusteredCellsList.sortBy { getCellBaseGain(it) }
		}
	}

	private fun getNumExternalPinsOfCell(cell: Cell): Int {
		var numInputPins = 0
		for (pin in cell.inputPins) {
			if (!pinIsSourcedInternally(pin, cell))
				numInputPins++
		}
		return numInputPins
	}

	private fun pinIsSourcedInternally(pin: CellPin, cell: Cell): Boolean {
		if (!pin.isConnectedToNet)
			return false

		val sourcePin = pin.net.sourcePin ?: return false

		val sourceCell = sourcePin.cell
		return sourceCell === cell
	}

	private fun getCellBaseGain(cell: Cell): Double {
		return cell.numExposedPins.toDouble()
	}

	override fun nextSeed(): Cell {
		// Use existing carry chains first.  This avoids accidentally placing parts of a
		// single carry chain in incompatible locations.
		while (!carryChains.isEmpty()) {
			val it = carryChains.iterator()
			val next = it.next()
			it.remove()
			return next
		}

		/* Returns the cell with the largest number of used inputs that satisfies the
		 * clocking and number of inputs constraints. */
		for (externalInputs in maxCellInputs downTo 0) {
			val possibleSeeds = unclusteredCellsMap!![externalInputs] ?: continue
			val cell = possibleSeeds[0]
			assert(cell.isValid)
			return cell
		}
		throw CadException("Design is fully packed")
	}

	override fun commitCluster(cluster: Cluster<*, *>) {
		val unclusteredCellsMap = checkNotNull(unclusteredCellsMap) { "Seed selector not initialized" }
		for (cell in cluster.cells) {
			val numExternalPins = getNumExternalPinsOfCell(cell)
			val unclusteredList = unclusteredCellsMap[numExternalPins]
			unclusteredList!!.remove(cell)
			if (unclusteredList.isEmpty())
				unclusteredCellsMap.remove(numExternalPins)
			carryChains.remove(cell)
			getCarryChainCells(cell)
		}
	}

	private fun getCarryChainCells(cell: Cell) {
		carryChains.addAll(getCarryChainCells(cell.sinkCarryChains))
		carryChains.addAll(getCarryChainCells(cell.sourceCarryChains))
	}

	private fun getCarryChainCells(
		cccs: Collection<CarryChainConnection>
	): Collection<Cell> {
		return cccs
			.map { it.endCell }
			.filter({ m -> m.getCluster<Cluster<*, *>>() == null })
	}
}
