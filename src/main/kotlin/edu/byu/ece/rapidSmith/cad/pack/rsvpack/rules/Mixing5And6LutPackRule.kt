//package edu.byu.ece.rapidSmith.cad.packing.rsvpack.rules
//
//import edu.byu.ece.rapidSmith.cad.cluster.Cluster
//import edu.byu.ece.rapidSmith.cad.cluster.locationInCluster
//import edu.byu.ece.rapidSmith.cad.packing.rsvpack.PackRule
//import edu.byu.ece.rapidSmith.cad.packing.rsvpack.PackRuleFactory
//import edu.byu.ece.rapidSmith.cad.packing.rsvpack.PackStatus
//import edu.byu.ece.rapidSmith.design.subsite.Cell
//import edu.byu.ece.rapidSmith.device.Bel
//import edu.byu.ece.rapidSmith.util.luts.LutConfig
//import java.util.HashSet
//
///**
// *
// */
//class Mixing5And6LutsRuleFactory : PackRuleFactory {
//	override fun make(cluster: Cluster<*, *>): PackRule {
//		return Mixing5And5LutsRule(cluster, LUT6TYPES)
//	}
//
//	// TODO make this a parameter
//	companion object {
//		private val LUT6TYPES: Set<String>
//
//		init {
//			LUT6TYPES = HashSet<String>()
//			LUT6TYPES.add("LUT6")
//			LUT6TYPES.add("SRL32")
//			LUT6TYPES.add("SLICEM6LUT")
//			LUT6TYPES.add("SLICEL6LUT")
//			LUT6TYPES.add("SPRAM64")
//			LUT6TYPES.add("DPRAM64")
//		}
//	}
//}
//
//class Mixing5And5LutsRule(
//	private val cluster: Cluster<*, *>,
//	private val LUT6TYPES: Set<String>
//) : PackRule {
//	override fun validate(changedCells: Collection<Cell>): PackStatus {
//		val status = PackStatus.VALID
//
//		for (cell in cluster.cells) {
//			val placement = cell.locationInCluster!!
//			if (placement.name.contains("LUT")) {
//				if (!isCompatible(placement))
//					return PackStatus.INFEASIBLE
//			}
//		}
//
//		return status
//	}
//
//	private fun isCompatible(bel: Bel): Boolean {
//		val site = bel.site
//		val leName = bel.name[0]
//		val lut6 = site.getBel(leName + "6LUT")
//		val lut5 = site.getBel(leName + "5LUT")
//
//		if (cluster.isBelOccupied(lut6) && cluster.isBelOccupied(lut5)) {
//			val cellAtLut6 = cluster.getCellAtBel(lut6)!!
//			if (cellAtLut6.libCell.name in LUT6TYPES) {
//				val cellAtLut5 = cluster.getCellAtBel(lut5)!!
//				return areEquationsCompatible(cellAtLut6, cellAtLut5)
//			}
//		}
//
//		return true
//	}
//
//	private fun areEquationsCompatible(cellAtLut6: Cell, cellAtLut5: Cell): Boolean {
//		val initString6 = cellAtLut6.lutContents
//		val initString5 = cellAtLut5.lutContents
//		return initString6 and 0x0FFFFFFFFL == initString5
//	}
//
//	override val conditionals: Map<Cell, Set<Bel>>
//		get() = emptyMap()
//
//	override fun revert() {}
//}
//
//private val Cell.lutContents: Long
//	get() {
//		val cfg = properties.getValue(libCell.name) as LutConfig
//		return cfg.contents.initString.cfgValue
//	}
