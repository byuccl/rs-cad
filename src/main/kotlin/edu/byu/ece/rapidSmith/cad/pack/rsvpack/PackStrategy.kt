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

		// if first time in here. RSVPackState class. Cell is the cell we're trying to pack.
		// this is the seed cell.

		// if failed to find a place to pack the cell, method will choose a bel, try it out,
		// and if it couldnt find something, it says this is a bad BEL choice.
		// we never choose a different seed

		// if valid, we've packed this cell and potentially several others in a state that worked.
		// we say this can be done, but can't do anymore.

		// fallback loop. comes in and says: You went all the way forward with the seed cell,
		// you came out of it now.
		do {
			var breakFromLoop = false
			// roll back loop
			do {
				// try to pack current cell we're working with
				// will continue until it finds something
				tryCellsUntilSuccess(cluster, state)

				when (state.status) {
					// last cell i tried, didn't work.
					// done here with this cell.
					PackStatus.INFEASIBLE -> {
						assert(state.cell == null)
						// No rolling back seed
						if (!state.isSeedState) {
							// rollback one and try cell on next BEL
							rollBackLastCommit(state)
						}
						breakFromLoop = true
					}

					// continue on, create a checkpoint of the cell bel pair.
					// but conditional. hold on. IF we come back here, ...
					PackStatus.CONDITIONAL -> {
						assert(state.nextConditionals != null)
						if (packMore(cluster)) {
							commitCellBelPair(state, state.nextConditionals!!.keys)
							nextCell(state)
						} else {
							breakFromLoop = true
						}
					}
					// great, go ahead. grab another cell. try and pack that cell.
					PackStatus.VALID -> if (packMore(cluster)) {
						commitCellBelPair(state, null)
						nextCell(state)
					} else {
						breakFromLoop = true
					}
				}
			} while (!breakFromLoop)

			// conditional means: what we tried couldn't be done.
			// I placed it...looking forward, I couldn't say that it's not possible
			// a chance it could be accomplished if I add thjings in different locations
			// go forward, see if I can find something.
			// by this point I've followed the conditional all the way through, discovered
			// we couldn't actually do it. revert choice.
			// try different BEL for seed.
			if (state.status == PackStatus.CONDITIONAL) {
				state.status = PackStatus.INFEASIBLE
				revertBelChoice(cluster, state)
			} else if (state.status == PackStatus.INFEASIBLE) {
				assert(state.isSeedState)
				break
			} else { // if valid. This can be done. break out of loop.
				assert(state.status == PackStatus.VALID)
				break
			}
		} while (true)
		return state.status
	}

	private fun packMore(cluster: Cluster<*, *>): Boolean {
		return !cluster.isFull()
	}

	// try one or more cells.
	// I have a cell I'm working with. try to pack it.
	// If it packs, great. I have success, go back to higher level method
	// which will checkpoint everything and continue on.
	// if it failed, undo the packing i did with that cell, grab another cell to try
	// it will pack one cell at a time until it get ones that succeeded....
	// then go back up.
	// exception: if trying to pack seed, don't pack another cell.
	// if it fails, don't try another.
	private fun tryCellsUntilSuccess(
		cluster: Cluster<T, *>, state: RSVPackState
	) {
		while (state.cell != null && state.status == PackStatus.INFEASIBLE) {
			assert(state.cell!!.isValid)
			assert(state.cell!!.getCluster<Cluster<*, *>>() == null)

			// pack a single cell, defined by the state.
			// try and place it somewhere. at some bel.
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

	// try to pack a single cell onto every possible bel
	private fun tryCell(cluster: Cluster<T, *>, state: RSVPackState) {
		var status: PackStatus
		val cell = state.cell!!

		// if can't find an anchor, break out (ran out of BELs)
		var anchor: Bel?
		do {
			// try another BEL and repeat
			anchor = belSelector.nextBel() ?: break

			// try to add anchor into cluster

			// Work on a clean slate to easily roll back if necessary.
			status = addCellToCluster(cluster, cell, anchor)
			if (status != PackStatus.INFEASIBLE)
				state.packedCells[cell] = anchor

			// prepackers: Checks that say if I've done this, I have to do this as well..
			// in all these cases, packer should find a valid state...
			// prepackers help it to resolve quicker.
			var prepackStatus = PrepackStatus.CHANGED
			while (status != PackStatus.INFEASIBLE && prepackStatus == PrepackStatus.CHANGED) {
				prepackStatus = PrepackStatus.UNCHANGED
				for (prepacker in prepackers!!) {
					val s = prepacker.packRequired(cluster, state.packedCells)
					prepackStatus = prepackStatus.meet(s)

					// fail if prepacker can't do what it needs to.
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

	// try each rule.
	// if any say infeasible, infeasible and die
	// if conditional, we're in a conditional state,
	// if all valid, we're in a valid state
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
		if (anchor.id !in cell.possibleLocations)
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



