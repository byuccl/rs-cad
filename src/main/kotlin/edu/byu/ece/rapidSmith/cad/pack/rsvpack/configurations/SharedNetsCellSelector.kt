package edu.byu.ece.rapidSmith.cad.pack.rsvpack.configurations

import edu.byu.ece.rapidSmith.cad.cluster.Cluster
import edu.byu.ece.rapidSmith.cad.cluster.PackUnit
import edu.byu.ece.rapidSmith.cad.cluster.isValid
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.CellSelector
import edu.byu.ece.rapidSmith.design.subsite.*
import java.util.*

class SharedNetsCellSelector(
	val searchTwoDeep: Boolean,
	val HIGH_FANOUT_LIMIT: Int = 400,
	val AB: Double = 0.9,
	val MAX_ATTEMPTS: Int = 50
) : CellSelector<PackUnit> {
	private lateinit var cluster: Cluster<*, *>

	private val stateStack = ArrayDeque<State>()

	private val sharedNetsMap = HashMap<Cell, Map<Cell, List<CellNet>>>()
	private val sharedPinsMap = HashMap<Cell, Map<Cell, List<CellPin>>>()
	private val numUsedPinsMap = HashMap<Cell, Int>()
	private val numUniqueNetsMap = HashMap<Cell, Int>()
	private val filteredNets = HashSet<CellNet>()

	override fun init(design: CellDesign) {
		design.nets.forEach { if (shouldFilterNet(it)) filteredNets += it }
		design.leafCells.forEach { numUsedPinsMap[it] = computeNumUsedPinsOnCell(it) }
		design.leafCells.forEach { numUniqueNetsMap[it] = computeNumUniqueNetsOnCell(it) }
		design.leafCells.forEach { sharedNetsMap[it] = findSharedNets(it) }
		design.leafCells.forEach { sharedPinsMap[it] = findSharedPins(it) }
	}

	private fun computeNumUsedPinsOnCell(cell: Cell): Int {
		var numUsedPins = 0
		for (pin in cell.pins) {
			if (pin.isConnectedToNet) {
				val net = pin.net
				if (!net.isFilteredNet())
					numUsedPins++
			}
		}
		return numUsedPins
	}

	private fun computeNumUniqueNetsOnCell(cell: Cell): Int {
		val uniqueNets = HashSet<CellNet>()
		for (pin in cell.pins) {
			if (pin.isConnectedToNet) {
				val net = pin.net
				if (!net.isFilteredNet())
					uniqueNets.add(pin.net)
			}
		}
		return uniqueNets.size
	}

	private fun findSharedNets(cell: Cell): Map<Cell, List<CellNet>> {
		val netCellPairs = cell.netList
			.filter { !it.isFilteredNet() }
			.flatMap { net ->
				net.pins.map { pin -> Pair(net, pin.cell as Cell) }
			}
		val sharedNetsGroup = netCellPairs.groupingBy { it.second }
		val sharedNets = sharedNetsGroup
			.fold({ _, _ -> HashSet<CellNet>() }) { _, a, (e) ->
				a += e
				a
			}
		return sharedNets.mapValues { it.value.toList() }
	}

	private fun findSharedPins(cell: Cell): Map<Cell, List<CellPin>> {
		val pinCellPairs = cell.netList
			.filter { !it.isFilteredNet() }
			.flatMap { net ->
				net.pins.map { pin -> Pair(pin, pin.cell as Cell) }
			}
		val sharedNetsGroup = pinCellPairs.groupingBy { it.second }
		val sharedPins = sharedNetsGroup
			.fold({ _, _ -> HashSet<CellPin>() }) { _, a, (e) ->
				a += e
				a
			}
		return sharedPins.mapValues { it.value.toList() }
	}

	private val Cell.numUsedPins: Int
		get() {
			var value = numUsedPinsMap[this]
			if (value == null) {
				value = computeNumUsedPinsOnCell(this)
				numUsedPinsMap[this] = value
			}
			return value
		}

	private val Cell.numUniqueNets: Int
		get() {
			var value = numUniqueNetsMap[this]
			if (value == null) {
				value = computeNumUniqueNetsOnCell(this)
				numUniqueNetsMap[this] = value
			}
			return value
		}

	private fun getSharedNets(c1: Cell, c2: Cell): List<CellNet> {
		val sharedNetsWith = sharedNetsMap.computeIfAbsent(c1) { findSharedNets(it) }
		return sharedNetsWith[c2] ?: emptyList()

	}

	private fun getSharedPins(with: Cell, on: Cell): List<CellPin> {
		val sharedPinsWith = sharedPinsMap.computeIfAbsent(with) { findSharedPins(it) }
		return sharedPinsWith[on] ?: emptyList()
	}

	private fun getConnectedCells(of: Cell): Set<Cell> {
		return sharedPinsMap.computeIfAbsent(of) { findSharedPins(it) }.keys
	}

	private fun shouldFilterNet(net: CellNet): Boolean {
		if (net.isStaticNet || net.pins.size > HIGH_FANOUT_LIMIT)
			return true
		for (pin in net.pins) {
			when (pin.type) {
				CellPinType.CLOCK, CellPinType.ENABLE,
				CellPinType.PRESET, CellPinType.RESET -> return true
				else -> { /* nothing */ }
			}
		}
		return false
	}

	private fun CellNet.isFilteredNet(): Boolean {
		return this in filteredNets
	}

	override fun initCluster(cluster: Cluster<*, *>, seed: Cell) {
		this.cluster = cluster
	}

	override fun nextCell(): Cell? {
		val state = stateStack.peek()
		if (state.numAttempts>= MAX_ATTEMPTS)
			return null
		state.numAttempts += 1

		var pq = state.pq
		if (pq == null) {
			pq = getPriorityQueue(state.conditionals)
			state.pq = pq
		}

		while (pq.isNotEmpty()) {
			val cell = pq.poll()
			if (cell.isValid)
				return cell
		}

		if (!searchTwoDeep || state.usingSecondaryCandidates || state.conditionals != null)
			return null

		pq = buildSecondaryQueue()
		state.pq = pq
		state.usingSecondaryCandidates = true

		while (pq.isNotEmpty()) {
			val cell = pq.poll()
			if (cell.isValid)
				return cell
		}
		return null
	}

	override fun commitCells(cells: Collection<Cell>, conditionals: Collection<Cell>?) {
		val state = State()
		state.conditionals = conditionals
		stateStack.push(state)
	}

	override fun cleanupCluster() {
		stateStack.clear()
	}

	override fun rollBackLastCommit() {
		stateStack.pop()
	}

	private fun getPriorityQueue(
		conditionals: Collection<Cell>?
	): PriorityQueue<Cell> {
		val gains = computePrimaryCellGains()
		if (conditionals == null) {
			val pq = PriorityQueue(CellComparator(gains).reversed())
			gains.keys.forEach { pq.add(it) }
			return pq
		} else {
			val pq = PriorityQueue(CellComparator(gains).reversed())
			conditionals.forEach {
				gains[it] = 0.0
				pq.add(it)
			}
			return pq
		}
	}

	private fun computePrimaryCellGains(): MutableMap<Cell, Double> {
		val primaryCells = getPrimaryCells()
		val primaryGains = primaryCells
			.filter { it.isValid }
			.associate { it to computeCellGain(it, cluster.cells) }
		return HashMap(primaryGains.mapValues { it.value })
	}

	private fun buildSecondaryQueue(): PriorityQueue<Cell> {
		val gains = computeSecondaryCellGains()
		val pq = PriorityQueue<Cell>(CellComparator(gains).reversed())
		gains.keys.forEach { pq.add(it) }
		return pq
	}

	private fun computeSecondaryCellGains(): MutableMap<Cell, Double> {
		val primaryCells = getPrimaryCells()
		val gains = HashMap<Cell, Double>()
		for (primaryCell in primaryCells) {
			val primaryGain = computeCellGain(primaryCell, cluster.cells)
			val secondaryCells = getSecondaryCells(primaryCell)
				.filter { it.isValid }
				.filter { it !in primaryCells }

			for (secondaryCell in secondaryCells) {
				val cellGain = computeCellGain(secondaryCell, listOf(primaryCell))
				val gain = primaryGain * cellGain
				val oldGain = gains[secondaryCell] ?: 0.0
				gains[secondaryCell] = oldGain + gain
			}
		}
		return gains
	}

	private fun getPrimaryCells(): Collection<Cell> {
		return cluster.cells
			.flatMap { getConnectedCells(it) }
			.filter { it !in cluster.cells }
			.toSet()
	}

	private fun getSecondaryCells(
		primaryCell: Cell
	): Collection<Cell> {
		return getConnectedCells(primaryCell)
			.distinct()
			.filter { it !in cluster.cells }
	}

	private fun computeCellGain(cell: Cell, from: Collection<Cell>): Double {
		return netAbsorptionGain(cell, from) + pinAbsorptionGain(cell, from)
	}

	private fun netAbsorptionGain(cell: Cell, from: Collection<Cell>): Double {
		val sharedNets = from
			.flatMap { getSharedNets(it, cell) }
			.distinct()
		return AB * sharedNets.size / cell.numUniqueNets
	}

	private fun pinAbsorptionGain(cell: Cell, from: Collection<Cell>): Double {
		val sharedPins = from
			.flatMap { getSharedPins(it, cell) }
			.distinct()
		return (1 - AB) * sharedPins.size / cell.numUsedPins
	}
}

private class CellComparator(val costsMap: Map<Cell, Double>): Comparator<Cell> {
	override fun compare(o1: Cell, o2: Cell): Int {
		return costsMap[o1]!!.compareTo(costsMap[o2]!!)
	}
}

private class State {
	var pq: PriorityQueue<Cell>? = null
	var conditionals: Collection<Cell>? = null
	var usingSecondaryCandidates = false
	var numAttempts = 0
}
