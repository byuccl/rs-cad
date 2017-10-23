package edu.byu.ece.rapidSmith.cad.pack.rsvpack.rules

import edu.byu.ece.rapidSmith.cad.cluster.*
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.PackRule
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.PackRuleFactory
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.PackRuleResult
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.PackStatus
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.rules.CarryChainValidityRuleFactory.SearchDirection.SINK2SOURCE
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.rules.CarryChainValidityRuleFactory.SearchDirection.SOURCE2SINK
import edu.byu.ece.rapidSmith.design.subsite.Cell
import edu.byu.ece.rapidSmith.design.subsite.CellDesign
import java.util.*
import kotlin.collections.HashSet

/**
 *
 */
class CarryChainValidityRuleFactory : PackRuleFactory {
	private var mergedCells: MutableMap<Cell, MutableSet<Cell>> = HashMap()
	private val numPackedCellsMap = HashMap<CarryChain, Int>()

	private val CarryChain.isPartiallyPlaced: Boolean
		get() = numPackedCellsMap.getOrDefault(this, 0) != 0

	private fun CarryChain.incrementNumPackedCells() {
		numPackedCellsMap.compute(this) { _, v -> v?.plus(1) ?: 1}
	}

	private fun CarryChain.decrementNumPackedCells() {
		numPackedCellsMap.compute(this) { _, v -> v!! - 1 }
	}

	override fun init(design: CellDesign) {
		mergedCells = HashMap()
	}

	override fun commitCluster(cluster: Cluster<*, *>) {
		val ccCells = cluster.cells.filter { it.carryChain != null }
		if (ccCells.isEmpty())
			return

		ccCells.map { it.carryChain!! }.forEach { it.incrementNumPackedCells() }
	}

	override fun make(cluster: Cluster<*, *>): PackRule {
		return CarryChainValidityRule()
	}

	private inner class CarryChainValidityRule : PackRule {
		private val incrementedCarryChains = ArrayDeque<List<CarryChain>>()

		override fun validate(changedCells: Collection<Cell>): PackRuleResult {
			val ccCells = getCarryChainCells(changedCells)
			val infeasible = !usesNonconsecutiveCarryChains(ccCells)

			val ccs = ccCells.map { it.carryChain!! }
			ccs.forEach { it.incrementNumPackedCells() }
			incrementedCarryChains.push(ccs)

			return when {
				infeasible -> PackRuleResult(PackStatus.INFEASIBLE, null)
				else -> PackRuleResult(PackStatus.VALID, null)
			}
		}

		private fun usesNonconsecutiveCarryChains(ccCells: List<Cell>): Boolean {
			return ccCells
				.filter { it.carryChain!!.isPartiallyPlaced }
				.all { it.sinkCarryChains.all {
					cellCanBePlaced(it.endCell, SOURCE2SINK) } &&
					it.sourceCarryChains.all {
						cellCanBePlaced(it.endCell, SINK2SOURCE) }
				}
		}

		private fun cellCanBePlaced(endCell: Cell, direction: SearchDirection)
			: Boolean {
			// Can only pack partially placed carry chains
			val cluster = endCell.getCluster<Cluster<*, *>>()
			if (cluster != null)
				return true

			val queue = ArrayDeque<CarryChainConnection>()
			val set = HashSet<CarryChainConnection>()
			val cccs = getCCCs(endCell, direction)
			set.addAll(cccs)
			queue.addAll(cccs)
			while (!queue.isEmpty()) {
				val end = queue.poll().endCell
				if (end.getCluster<Cluster<*, *>>() != null)
					return false

				val cccSet = HashSet(getCCCs(end, direction))
				cccSet.removeAll(set)
				set.addAll(cccSet)
				queue.addAll(cccSet)
			}
			return true
		}

		private fun getCCCs(endCell: Cell, direction: SearchDirection)
			: Collection<CarryChainConnection> {
			return when (direction) {
				SINK2SOURCE -> endCell.sourceCarryChains
				SOURCE2SINK -> endCell.sinkCarryChains
			}
		}

		override fun revert() {
			incrementedCarryChains.pop().forEach { it.decrementNumPackedCells() }
		}
	}

	private fun getCarryChainCells(changedCells: Collection<Cell>): List<Cell> {
		return changedCells.filter { c -> c.carryChain != null }
	}

	private enum class SearchDirection {
		SOURCE2SINK, SINK2SOURCE
	}
}
