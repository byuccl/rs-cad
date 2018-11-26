package edu.byu.ece.rapidSmith.cad.pack.rsvpack.rules

import edu.byu.ece.rapidSmith.cad.cluster.Cluster
import edu.byu.ece.rapidSmith.cad.families.artix7.Ram
import edu.byu.ece.rapidSmith.cad.families.artix7.RamMaker
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.PackRule
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.PackRuleFactory
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.PackRuleResult
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.PackStatus
import edu.byu.ece.rapidSmith.design.subsite.Cell
import edu.byu.ece.rapidSmith.design.subsite.CellDesign
import edu.byu.ece.rapidSmith.device.Bel
import edu.byu.ece.rapidSmith.device.families.Artix7
import java.util.*

/**

 */
class RamFullyPackedPackRuleFactory(private val ramMaker: RamMaker) : PackRuleFactory {
	private var rams: Map<Cell, Ram>? = null

	override fun init(design: CellDesign) {
		rams = ramMaker.make(design)
	}

	override fun make(cluster: Cluster<*, *>): PackRule {
		return Rule(cluster, checkNotNull(rams))
	}

	inner class Rule(cluster: Cluster<*, *>, val rams: Map<Cell, Ram>) : PackRule {
		private val lutRamsBels = LinkedHashMap<String, ArrayList<Bel>>()
		private var state: State
		private val stack = ArrayDeque<State>()

		init {
			val template = cluster.type.template
			template.bels.asSequence()
				.filter { it.site.type == Artix7.SiteTypes.SLICEM }
				.filter { it.name.matches(Regex("[A-D][5-6]LUT")) }
				.forEach { lutRamsBels.computeIfAbsent(it.name) { ArrayList(1) }.add(it) }
			state = State()
			state.status = PackStatus.VALID
		}

		override fun validate(changedCells: Collection<Cell>): PackRuleResult {
			stack.push(state)

			// check LUT is placed at a valid location
			val changedRamCells = changedCells.filter { c -> c in rams }

			if (changedRamCells.isEmpty()) {
				// nothing in the any ram has changed.  Return the previous result
				val status = state.status!!
				val conditionals = state.conditionals
				return PackRuleResult(status, conditionals)
			}

			updateState(changedRamCells)

			val result = ensureRamsAreComplete()
			state.status = result.status
			state.conditionals = result.conditionals

			val status = state.status!!
			val conditionals = state.conditionals
			return PackRuleResult(status, conditionals)
		}

		private fun updateState(changedRamCells: List<Cell>) {
			state = State()
			state.status = PackStatus.VALID
			state.conditionals = null
			state.incompleteRams = LinkedHashSet(stack.peek().incompleteRams)

			val rams = changedRamCells
				.map { rams[it]!! } // check this null assertion
				.toSet()
			state.incompleteRams.addAll(rams)
			val completedRams = rams.filter { it.fullyPacked() }
			state.incompleteRams.removeAll(completedRams)
		}

		private fun ensureRamsAreComplete(): StatusConditionalsPair {
			val conditionals: HashMap<Cell, Set<Bel>>
			if (!state.incompleteRams.isEmpty()) {
				conditionals = LinkedHashMap()
				for (ram in state.incompleteRams) {
					for (ramCell in ram.unpackedCells()) {
						val possibleLocations = getPossibleLocations(ramCell)
						if (possibleLocations.isEmpty())
							return StatusConditionalsPair(PackStatus.INFEASIBLE, null)
						conditionals.put(ramCell, possibleLocations)
					}
				}
				return StatusConditionalsPair(PackStatus.CONDITIONAL, conditionals)
			}
			return StatusConditionalsPair(PackStatus.VALID, null)
		}

		private fun getPossibleLocations(ramCell: Cell): Set<Bel> {
			val possibles = LinkedHashSet<Bel>()
			val locations = ramCell.ramPosition
			when (ramCell.libCell.name) {
				"RAMS32", "RAMD32" -> {
					run {
						var ch = 'A'
						while (ch <= 'D') {
							if (locations.indexOf(ch) != -1)
								possibles.addAll(lutRamsBels[ch + "5LUT"]!!)
							ch++
						}
					}
					var ch = 'A'
					while (ch <= 'D') {
						if (locations.indexOf(ch) != -1)
							possibles.addAll(lutRamsBels[ch + "6LUT"]!!)
						ch++
					}
				}
				"RAMS64E", "RAMD64E" -> {
					var ch = 'A'
					while (ch <= 'D') {
						if (locations.indexOf(ch) != -1)
							possibles.addAll(lutRamsBels[ch + "6LUT"]!!)
						ch++
					}
				}
			}
			return possibles
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
		internal var incompleteRams: MutableSet<Ram> = LinkedHashSet()
		internal var conditionals: Map<Cell, Set<Bel>>? = null
	}

	private class StatusConditionalsPair(
		var status: PackStatus,
		var conditionals: Map<Cell, Set<Bel>>?
	)
}

