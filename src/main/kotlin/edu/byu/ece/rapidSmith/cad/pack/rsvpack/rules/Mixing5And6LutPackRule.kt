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
class Mixing5And6LutsRuleFactory : PackRuleFactory {
	override fun make(cluster: Cluster<*, *>): PackRule {
		return Mixing5And5LutsRule(cluster, LUT6TYPES, LUT5TYPES)
	}

	// TODO make this a parameter
	companion object {
		private val LUT6TYPES: Set<String> = setOf(
			"LUT6", "SRLC32E"
		)
		private val LUT5TYPES: Set<String> = setOf(
			"LUT1", "LUT2", "LUT3", "LUT4", "LUT5",
			"SRL16E"
		)
	}
}

class Mixing5And5LutsRule(
	private val cluster: Cluster<*, *>,
	private val LUT6TYPES: Set<String>,
	private val LUT5TYPES: Set<String>
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
			if (cellAtLut6.libCell.name in LUT6TYPES) {
				val cellAtLut5 = cluster.getCellAtBel(lut5)!!
				return areEquationsCompatible(cellAtLut6, cellAtLut5)
			} else {
				assert(cellAtLut6.libCell.name in LUT5TYPES) { "LUT type is: ${cellAtLut6.libCell.name}" }
			}
		}

		return true
	}

	private fun areEquationsCompatible(cellAtLut6: Cell, cellAtLut5: Cell): Boolean {
		val initString6 = cellAtLut6.lutContents
		val initString5 = cellAtLut5.lutContents
		return initString6 and 0x0FFFFFFFFL == initString5
	}

	override fun revert() {}
}

private val INITSTRING_RE = """(\d+)'h([0-9a-fA-F]+)""".toRegex()

private val Cell.lutContents: Long
	get() {
		var cfg = properties.getValue("\$lutContents\$") as InitString?
		if (cfg == null) {
			val initString = properties.getStringValue("INIT")
			val parsed = INITSTRING_RE.matchEntire(initString)!!
			val numInputs = when(parsed.groupValues[1]) {
				"1" -> 0
				"2" -> 1
				"4" -> 2
				"8" -> 3
				"16" -> 4
				"32" -> 5
				"64" -> 6
				else -> error("illegal init string length")
			}
			cfg = InitString.parse("0x${parsed.groupValues[2]}", numInputs)!!
			properties.update("\$lutContents\$", PropertyType.USER, cfg)
		}
		return cfg.cfgValue
	}
