package edu.byu.ece.rapidSmith.cad.pack.rsvpack.rules

import edu.byu.ece.rapidSmith.cad.cluster.Cluster
import edu.byu.ece.rapidSmith.cad.cluster.isValid
import edu.byu.ece.rapidSmith.cad.cluster.locationInCluster
import edu.byu.ece.rapidSmith.cad.families.artix7.Ram
import edu.byu.ece.rapidSmith.cad.families.artix7.RamMaker
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.PackRule
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.PackRuleFactory
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.PackRuleResult
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.PackStatus
import edu.byu.ece.rapidSmith.design.subsite.Cell
import edu.byu.ece.rapidSmith.design.subsite.CellDesign
import edu.byu.ece.rapidSmith.design.subsite.CellNet
import edu.byu.ece.rapidSmith.device.Bel
import edu.byu.ece.rapidSmith.device.Site
import java.util.*

/**

 */
class D6LutUsedRamPackRuleFactory(
	private val ramMaker: RamMaker
) : PackRuleFactory {
	private var rams: Map<Cell, Ram>? = null

	override fun init(design: CellDesign) {
		this.rams = ramMaker.make(design)
	}

	override fun make(cluster: Cluster<*, *>): PackRule {
		return Rule(cluster, checkNotNull(rams))
	}

	inner class Rule(
		private val cluster: Cluster<*, *>,
		private val rams: Map<Cell, Ram>
	) : PackRule {
		private var state: State
		private val stack = ArrayDeque<State>()

		init {
			state = State()
			state.status = PackStatus.VALID
		}

		override fun validate(changedCells: Collection<Cell>): PackRuleResult {
			stack.push(state)

			// check LUT is placed at a valid location
			val changedRamCells = changedCells.filter { c -> c in rams }

			if (changedRamCells.isEmpty()) {
				val status = state.status!!
				val conditionals = state.conditionals
				return PackRuleResult(status, conditionals)
			}

			updateState(changedRamCells)

			val result = ensureDLutsUsed()
			return PackRuleResult(result.status, result.conditionals)
		}

		private fun updateState(changedRamCells: List<Cell>) {
			state = State()
			state.status = PackStatus.VALID
			state.conditionals = null
			state.usedDLuts = LinkedHashMap(stack.peek().usedDLuts)

			for (cell in changedRamCells) {
				val bel = cell.locationInCluster!!
				val site = bel.site
				val pair = SiteLutNumberPair(site,
					Integer.parseInt(bel.name.substring(1, 2)))
				state.usedDLuts.computeIfAbsent(pair) { false }
				if (bel.name[0] == 'D')
					state.usedDLuts.put(pair, true)
			}
		}

		private fun ensureDLutsUsed(): StatusConditionalsPair {
			val unusedDLuts = state.usedDLuts
				.filter { e -> !e.value }
				.map { it.key }
				.map { p -> p.site.getBel("D" + p.lutNumber + "LUT") }
				.toSet()

			if (unusedDLuts.isEmpty())
				return StatusConditionalsPair(PackStatus.VALID, null)

			val connectedRamCells = connectedRams

			if (connectedRamCells.isEmpty())
				return StatusConditionalsPair(PackStatus.INFEASIBLE, null)

			val conditionals = LinkedHashMap<Cell, Set<Bel>>()
			connectedRamCells.forEach { c -> conditionals.put(c, unusedDLuts) }
			return StatusConditionalsPair(PackStatus.CONDITIONAL, conditionals)
		}

		private val connectedRams: Set<Cell>
			get() {
				return cluster.cells.flatMap { it.pins }
					.filter { it.isConnectedToNet && !isFilteredNet(it.net) }
					.flatMap { pin -> pin.net.pins
						.filter { pin !== it }
						.map { it.cell }
						.filter { isValidRam(it) } }
					.toSet()
			}

		private val filteredNets = LinkedHashMap<CellNet, Boolean>()
		private fun isFilteredNet(net: CellNet): Boolean {
			return filteredNets.computeIfAbsent(net) {
				net.isClkNet || net.isStaticNet ||
					net.pins.size > 100
			}
		}

		// the cell must be valid, a ram cell and placeable in the DLUT
		private fun isValidRam(cell: Cell): Boolean {
			return cell.isValid &&
				cell.libCell in ramMaker.leafRamCellTypes &&
				cell.ramPosition.indexOf('D') != -1
		}

		override fun revert() {
			state = stack.pop()
		}

		private val Cell.ramPosition : String
			get() {
				return rams[this]!!.positions[this]!!
			}

	}

	private class State {
		internal var status: PackStatus? = null
		internal var usedDLuts: MutableMap<SiteLutNumberPair, Boolean> = LinkedHashMap()
		internal var conditionals: Map<Cell, Set<Bel>>? = null
	}

	private class SiteLutNumberPair(internal val site: Site, internal val lutNumber: Int) {

		override fun equals(other: Any?): Boolean {
			if (this === other) return true
			if (other == null || javaClass != other.javaClass) return false
			val that = other as SiteLutNumberPair?
			return lutNumber == that!!.lutNumber && site == that.site
		}

		override fun hashCode(): Int {
			return Objects.hash(site, lutNumber)
		}

		override fun toString(): String {
			return "SiteLutNumberPair{" +
				"site=" + site +
				", lutNumber=" + lutNumber +
				'}'
		}
	}

	private class StatusConditionalsPair(
		var status: PackStatus,
		var conditionals: Map<Cell, Set<Bel>>?
	)
}
