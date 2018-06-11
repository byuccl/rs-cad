package edu.byu.ece.rapidSmith.cad.pack.rsvpack.rules

import edu.byu.ece.rapidSmith.cad.cluster.CarryChain
import edu.byu.ece.rapidSmith.cad.cluster.Cluster
import edu.byu.ece.rapidSmith.cad.cluster.carryChain
import edu.byu.ece.rapidSmith.cad.cluster.locationInCluster
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.PackRule
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.PackRuleFactory
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.PackRuleResult
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.PackStatus
import edu.byu.ece.rapidSmith.design.subsite.Cell
import edu.byu.ece.rapidSmith.design.subsite.CellDesign
import edu.byu.ece.rapidSmith.design.subsite.LibraryCell
import edu.byu.ece.rapidSmith.device.SiteType
import java.util.*
import kotlin.streams.asSequence

/**
 * Prevents mixing SLICEL and SLICEM carry chains.
 */
class CarryChainLookAheadRuleFactory(
	private val CarryChainSPins: List<String>,
	private val RamTypes: Set<LibraryCell>,
	private val sliceMType: SiteType
) : PackRuleFactory {
	private val requiresSLICEM = HashMap<CarryChain, Boolean>()

override fun init(design: CellDesign) {
		getCarryChains(design)
			.associateTo(requiresSLICEM) { it to requiresSLICEM(it) }
	}

	private fun getCarryChains(design: CellDesign): Set<CarryChain> {
		return design.inContextLeafCells.asSequence()
			.map { it.carryChain }
			.filterNotNull()
			.toSet()
	}

	private fun requiresSLICEM(cc: CarryChain): Boolean {
		return cc.getCells().asSequence()
			.filter { it.libCell.name == "CARRY4" }
			.any { doesSourceRequireSLICEM(it) }
	}

	private fun doesSourceRequireSLICEM(cell: Cell): Boolean {
		return CarryChainSPins.map { cell.getPin(it)!! }
			.map { it.net }
			.filter { it != null && !it.isStaticNet }
			.map { it.sourcePin }
			.map { it.cell }
			.any { it.libCell in RamTypes }
	}

	override fun make(cluster: Cluster<*, *>): PackRule {
		return CarryChainLookAheadRule()
	}

	private inner class CarryChainLookAheadRule : PackRule {

		override fun validate(changedCells: Collection<Cell>): PackRuleResult {
			var usesSliceM = false
			for (cell in changedCells) {
				val cc = cell.carryChain
				if (cc != null) {
					if (requiresSLICEM[cc]!!) {
						if (cell.libCell.name == "CARRY4") {
							val bel = cell.locationInCluster!!
							if (bel.site.type != sliceMType)
								usesSliceM = true
						}
					}
				}
			}
			val result = if (usesSliceM) PackStatus.INFEASIBLE else PackStatus.VALID
			return PackRuleResult(result, null)
		}

		override fun revert() {
			// No state, unused
		}
	}
}
