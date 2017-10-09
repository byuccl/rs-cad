package edu.byu.ece.rapidSmith.cad.pack.rsvpack

import edu.byu.ece.rapidSmith.cad.cluster.*
import edu.byu.ece.rapidSmith.design.subsite.Cell
import edu.byu.ece.rapidSmith.design.subsite.CellDesign
import edu.byu.ece.rapidSmith.device.Bel
import java.util.*

/**
 * A strategy for packing a cluster.
 */
interface PackStrategy<in T: PackUnit> {
	fun init(design: CellDesign)
	fun tryPackCluster(cluster: Cluster<T, *>, seed: Cell): PackStatus
	fun commitCluster(cluster: Cluster<T, *>)
}

/**
 * Strategy specialized for clusters with multiple BELs.
 */
class MultiBelPackStrategy<in T: PackUnit>(
	private val cellSelector: CellSelector<T>,
	private val belSelector: BelSelector<T>,
	private val prepackerFactories: List<PrepackerFactory<T>>,
	private val packRuleFactories: List<PackRuleFactory>
) : PackStrategy<T> {
	private var prepackers: List<Prepacker<T>>? = null
	private var packRules: List<PackRule>? = null

	override fun init(design: CellDesign) {
		packRuleFactories.forEach { pr -> pr.init(design) }
		cellSelector.init(design)
		belSelector.init(design)
		prepackerFactories.forEach { it.init(design) }
	}

	override fun commitCluster(cluster: Cluster<T, *>) {
		packRuleFactories.forEach { it.commitCluster(cluster) }
	}

	override fun tryPackCluster(cluster: Cluster<T, *>, seed: Cell): PackStatus {
		cellSelector.initCluster(cluster, seed)
		belSelector.initCluster(cluster)
		prepackers = prepackerFactories.map { it.make() }
		packRules = packRuleFactories.map { it.make(cluster) }

		val state = RSVPackState()
		state.cell = seed
		belSelector.initCell(seed, null)

		val result = fillCluster(cluster, state)

		packRules!!.forEach { it.cleanup() }
		cellSelector.cleanupCluster()
		belSelector.cleanupCluster()
		unbindCluster(state)

		return result
	}

	// Clear cell information for all cells in the cluster while leaving cluster
	// information intact.
	private fun unbindCluster(state: RSVPackState) {
		while (!state.isSeedState) {
			state.packedCells.keys.forEach { this.unbindCell(it) }
			state.invalidatedCells.forEach { c -> c.isValid = true }
			state.rollback()
		}
		state.packedCells.keys.forEach { this.unbindCell(it) }
		state.invalidatedCells.forEach { c -> c.isValid = true }
	}

	private fun unbindCell(cell: Cell) {
		cell.isValid = true
		cell.setCluster(null)
		cell.locationInCluster = null
	}


	private fun fillCluster(cluster: Cluster<T, *>, state: RSVPackState): PackStatus {
		// Roll back until we found a valid final cluster, or determined that
		// none exists.  Keeps us ending with a conditional cluster
		do {
			var breakFromLoop = false
			// roll back loop
			do {
				tryCellsUntilSuccess(cluster, state)

				when (state.status) {
					PackStatus.INFEASIBLE -> {
						assert(state.cell == null)
						// No rolling back seed
						if (!state.isSeedState) {
							// rollback one and try cell on next BEL
							rollBackLastCommit(state)
						}
						breakFromLoop = true
					}
					PackStatus.CONDITIONAL -> {
						assert(state.nextConditionals != null)
						if (packMore(cluster)) {
							commitCellBelPair(state, state.nextConditionals!!.keys)
							nextCell(state)
						} else {
							breakFromLoop = true
						}
					}
					PackStatus.VALID -> if (packMore(cluster)) {
						commitCellBelPair(state, null)
						nextCell(state)
					} else {
						breakFromLoop = true
					}
				}
			} while (!breakFromLoop)

			if (state.status == PackStatus.CONDITIONAL) {
				state.status = PackStatus.INFEASIBLE
				revertBelChoice(cluster, state)
			} else if (state.status == PackStatus.INFEASIBLE) {
				assert(state.isSeedState)
				break
			} else {
				assert(state.status == PackStatus.VALID)
				break
			}
		} while (true)
		return state.status
	}

	private fun packMore(cluster: Cluster<*, *>): Boolean {
		return !cluster.isFull()
	}

	private fun tryCellsUntilSuccess(
		cluster: Cluster<T, *>, state: RSVPackState
	) {
		while (state.cell != null && state.status == PackStatus.INFEASIBLE) {
			assert(state.cell!!.isValid)
			assert(state.cell!!.getCluster<Cluster<*, *>>() == null)

			tryCell(cluster, state)

			if (state.status == PackStatus.INFEASIBLE) {
				revertToLastCommit(cluster, state)
				// Don't want to choose a different seed cell
				if (state.isSeedState)
					return
				nextCell(state)
			}
		}
	}

	private fun nextCell(state: RSVPackState) {
		val nextCell = cellSelector.nextCell()
		state.cell = nextCell

		if (nextCell != null) {
			val conditionals = if (state.prevConditionals != null)
				state.prevConditionals!![nextCell]
			else
				null
			belSelector.initCell(nextCell, conditionals)
		}
	}

	private fun revertBelChoice(
		cluster: Cluster<*, *>, state: RSVPackState
	) {
		revertState(cluster, state)
	}

	private fun commitCellBelPair(
		state: RSVPackState, conditionals: Set<Cell>?
	): RSVPackState {
		cellSelector.commitCells(state.packedCells.keys, conditionals)
		belSelector.commitBels(state.packedCells.values)
		state.commit()

		return state
	}


	private fun revertToLastCommit(cluster: Cluster<*, *>, state: RSVPackState): RSVPackState {
		belSelector.revertToLastCommit()

		revertState(cluster, state)
		state.cell!!.isValid = false
		state.invalidatedCells.add(state.cell!!)
		state.cell = null

		return state
	}

	private fun rollBackLastCommit(state: RSVPackState): RSVPackState {
		belSelector.rollBackLastCommit()
		cellSelector.rollBackLastCommit()
		state.invalidatedCells.forEach { c -> c.isValid = true }
		state.rollback()

		return state
	}

	private fun tryCell(cluster: Cluster<T, *>, state: RSVPackState) {
		var status: PackStatus
		val cell = state.cell!!

		var anchor: Bel?
		do {
			anchor = belSelector.nextBel() ?: break

			// Work on a clean slate to easily roll back if necessary.
			status = addCellToCluster(cluster, cell, anchor)
			if (status != PackStatus.INFEASIBLE)
				state.packedCells[cell] = anchor

			var prepackStatus = PrepackStatus.CHANGED
			while (status != PackStatus.INFEASIBLE && prepackStatus == PrepackStatus.CHANGED) {
				prepackStatus = PrepackStatus.UNCHANGED
				for (prepacker in prepackers!!) {
					val s = prepacker.packRequired(cluster, state.packedCells)
					prepackStatus = prepackStatus.meet(s)
					if (prepackStatus == PrepackStatus.INFEASIBLE) {
						status = PackStatus.INFEASIBLE
						break
					}
				}
			}

			state.status = status
			state.nextConditionals = HashMap()
			validateRules(state)

			if (state.status == PackStatus.INFEASIBLE) {
				revertBelChoice(cluster, state)
			}
		} while (state.status == PackStatus.INFEASIBLE)

		when (state.status) {
			PackStatus.CONDITIONAL -> assert(!state.nextConditionals!!.isEmpty())
			PackStatus.VALID -> state.nextConditionals = null
			PackStatus.INFEASIBLE -> state.nextConditionals = null
		}
	}

	private fun validateRules(state: RSVPackState) {
		val rulesIterator = packRules!!.iterator()
		while (rulesIterator.hasNext() && state.status != PackStatus.INFEASIBLE) {
			val rule = rulesIterator.next()
			val (status, conditionals) = rule.validate(state.packedCells.keys)

			state.status = state.status meet status
			if (status == PackStatus.CONDITIONAL) {
				if (conditionals!!.isEmpty())
					state.status = PackStatus.INFEASIBLE
				else
					mergeConditionals(state.nextConditionals!!, conditionals)
			}
			state.checkedRules.add(rule)
		}
	}

	private fun mergeConditionals(
		conditionals: HashMap<Cell, HashSet<Bel>>,
		toAdd: Map<Cell, Set<Bel>>
	) {
		for ((key, value) in toAdd) {
			conditionals.computeIfAbsent(key) { HashSet() }.addAll(value)
		}
	}

	private fun removeCellFromCluster(cluster: Cluster<*, *>, cell: Cell, state: RSVPackState) {
		cell.setCluster(null)
		if (!state.invalidatedCells.contains(cell))
			cell.isValid = true
		cell.locationInCluster = null
		cluster.removeCell(cell)
	}

	private fun revertState(cluster: Cluster<*, *>, state: RSVPackState): RSVPackState {
		state.checkedRules.forEach { it.revert() }
		state.checkedRules.clear()
		state.packedCells.keys.forEach { c -> removeCellFromCluster(cluster, c, state) }
		state.packedCells.clear()
		state.nextConditionals = null
		return state
	}

	private class RSVPackState {
		val stack: Deque<State> = ArrayDeque()
		var status = PackStatus.INFEASIBLE
		var cell: Cell? = null
		var packedCells = HashMap<Cell, Bel>()
		var invalidatedCells = ArrayList<Cell>()
		var prevConditionals: HashMap<Cell, HashSet<Bel>>? = null
		var nextConditionals: HashMap<Cell, HashSet<Bel>>? = null
		var checkedRules = ArrayList<PackRule>()

		fun commit() {
			stack.push(State(this))
			status = PackStatus.INFEASIBLE
			cell = null
			packedCells = HashMap()
			invalidatedCells = ArrayList()
			prevConditionals = nextConditionals
			nextConditionals = null
			checkedRules = ArrayList()
		}

		fun rollback() {
			val state = stack.pop()
			status = state.status
			cell = state.cell
			packedCells = state.packedCells
			invalidatedCells = state.invalidatedCells
			prevConditionals = state.prevConditionals
			nextConditionals = state.nextConditionals
			checkedRules = state.checkedRules
		}

		val isSeedState: Boolean
			get() = stack.isEmpty()

		private class State internal constructor(curState: RSVPackState) {
			var cell: Cell? = curState.cell
			var packedCells = curState.packedCells
			var status: PackStatus = curState.status
			var invalidatedCells = curState.invalidatedCells
			var prevConditionals = curState.prevConditionals
			var nextConditionals = curState.nextConditionals
			var checkedRules = curState.checkedRules
		}
	}
}

/** Strategy for packing clusters with only one BEL. Should run quicker
 * than MultiBelPackStrategy*/
class SingleBelPackStrategy<in T: PackUnit>(
	private val packRuleFactories: List<PackRuleFactory>
) : PackStrategy<T> {
	private var packRules: List<PackRule>? = null

	override fun init(design: CellDesign) {
		packRuleFactories.forEach { pr -> pr.init(design) }
	}

	override fun commitCluster(cluster: Cluster<T, *>) {
		packRuleFactories.forEach { it.commitCluster(cluster) }
	}

	override fun tryPackCluster(cluster: Cluster<T, *>, seed: Cell): PackStatus {
		packRules = packRuleFactories.map { it.make(cluster) }
		val status = tryCell(cluster, seed)
		packRules!!.forEach { it.cleanup() }
		unbindCluster(seed)

		return status
	}

	private fun unbindCluster(seed: Cell) {
		unbindCell(seed)
	}

	private fun unbindCell(cell: Cell) {
		cell.isValid = true
		cell.setCluster(null)
		cell.locationInCluster = null
	}

	private fun tryCell(cluster: Cluster<*, *>, cell: Cell): PackStatus {
		val template = cluster.type.template
		check(template.bels.size == 1) { "Only 1 BEL allowed in template" }

		// Check if the BEL is even a valid type for this cell.
		val anchor = template.bels.first()
		if (anchor.id !in cell.possibleAnchors)
			return PackStatus.INFEASIBLE

		// Try to add the cell to the cluster.
		var status = addCellToCluster(cluster, cell, anchor)
		if (status == PackStatus.INFEASIBLE)
			return PackStatus.INFEASIBLE

		// Cell was successfully added to the cluster.  Now check all of the
		// validation rules.
		val validateResult = validateRules(cell)
		status = validateResult.status

		// If a resulting check returned infeasible, revert each of the performed checks
		// (this may only be a subset of the validators).
		if (status == PackStatus.INFEASIBLE)
			validateResult.checkedRules.forEach { it.revert() }
		return status
	}

	private fun validateRules(cell: Cell): ValidateRulesReturn {
		var status = PackStatus.VALID
		val checkedRules = ArrayList<PackRule>()
		val rulesIterator = packRules!!.iterator()
		while (rulesIterator.hasNext() && status != PackStatus.INFEASIBLE) {
			val rule = rulesIterator.next()
			val (result, _) = rule.validate(listOf(cell))

			status = status meet result
			if (result == PackStatus.CONDITIONAL)
				status = PackStatus.INFEASIBLE
			checkedRules.add(rule)
		}
		return ValidateRulesReturn(status, checkedRules)
	}

	private data class ValidateRulesReturn(
		val status: PackStatus,
		val checkedRules: Collection<PackRule>
	)
}



