package edu.byu.ece.rapidSmith.cad.pack.rsvpack.rules

import edu.byu.ece.rapidSmith.cad.cluster.Cluster
import edu.byu.ece.rapidSmith.cad.cluster.locationInCluster
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.PackRule
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.PackRuleFactory
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.PackRuleResult
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.PackStatus
import edu.byu.ece.rapidSmith.design.subsite.Cell
import edu.byu.ece.rapidSmith.design.subsite.PropertyType
import edu.byu.ece.rapidSmith.device.Bel
import edu.byu.ece.rapidSmith.util.luts.InitString

/**
 *
 */
class MixingRamsAndLutsPackRuleFactory : PackRuleFactory {
	override fun make(cluster: Cluster<*, *>): PackRule {
		return MixingRamsAndLutsPackRule(cluster, LUT_TYPES, RAM_TYPES)
	}

	// TODO make this a parameter
	companion object {
		private val LUT_TYPES: Set<String> = setOf(
			"LUT6", "LUT1", "LUT2", "LUT3", "LUT4", "LUT5"
			)
		private val RAM_TYPES: Set<String> = setOf(
			"SRL16E", "RAMS32", "RAMD32", "SRLC32E", "RAMS64E", "RAMD64E"
		)
	}
}

private class MixingRamsAndLutsPackRule(
	private val cluster: Cluster<*, *>,
	private val LUT_TYPES: Set<String>,
	private val RAM_TYPES: Set<String>
) : PackRule {
	override fun validate(changedCells: Collection<Cell>): PackRuleResult {
		val status = PackStatus.VALID

		if (anyIncompatibleCells(cluster.cells))
			return PackRuleResult(PackStatus.INFEASIBLE, null)

		return PackRuleResult(status, emptyMap())
	}

	private fun anyIncompatibleCells(cells: Iterable<Cell>): Boolean {
		return cells.map { it.locationInCluster!! }
			.any { it.name.contains("LUT") && !isCompatible(it) }
	}

	private fun isCompatible(bel: Bel): Boolean {
		val site = bel.site
		val leName = bel.name[0]
		val lut6 = site.getBel(leName + "6LUT")
		val lut5 = site.getBel(leName + "5LUT")

		if (cluster.isBelOccupied(lut6) && cluster.isBelOccupied(lut5)) {
			val cellAtLut6 = cluster.getCellAtBel(lut6)!!
			val cellAtLut5 = cluster.getCellAtBel(lut5)!!
			if (cellAtLut5.type in LUT_TYPES) {
				return cellAtLut6.type in LUT_TYPES
			} else {
				assert(cellAtLut5.type in RAM_TYPES)
				return cellAtLut6.type in RAM_TYPES
			}
		}

		return true
	}

	override fun revert() {}
}

