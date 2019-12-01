package edu.byu.ece.rapidSmith.util

import edu.byu.ece.rapidSmith.design.subsite.Cell
import edu.byu.ece.rapidSmith.design.subsite.CellDesign
import edu.byu.ece.rapidSmith.design.subsite.CellNet
import edu.byu.ece.rapidSmith.design.subsite.RouteTree
import edu.byu.ece.rapidSmith.device.Tile
import edu.byu.ece.rapidSmith.device.families.FamilyInfos
import java.io.PrintWriter
import java.io.Writer
import java.lang.Math.abs

val CellDesign.logicCells: Sequence<Cell>
	get() = this.cells.asSequence().filter { !it.isGndSource && !it.isVccSource}

fun gatherStats(design: CellDesign, resultsFile: Writer) {
	val pw = PrintWriter(resultsFile, true)
	design.cells.forEach { assert(!it.isMacro) }
	pw.println("num sites: ${design.logicCells.map { it.site }.distinct().count()}")
	pw.println("num sites: ${design.logicCells.map { it.site.tile }.distinct().count()}")
	pw.println("total wire length: ${computeWireLength(design)}")
	pw.println("les per slice: ${computeLEsPerSlice(design).joinToString(separator = " ")}")
	pw.flush()
}

fun computeWireLength(design: CellDesign): Int {
	fun isResetNet(net: CellNet): Boolean {
		var rst_pins = net.pins.asSequence().count { it.name in listOf("RESET", "RST", "reset", "rst") }
		return rst_pins.toFloat() / net.pins.size > 0.8
	}

	fun manhattanDistance(t1: Tile, t2: Tile): Int =
		abs(t1.row - t2.row) + abs(t1.column - t2.column)

	var wireLength = 0
	for (net in design.nets) {
		if (net.isClkNet || net.isVCCNet || net.isGNDNet || isResetNet(net))
			continue

		for (trees in net.intersiteRouteTreeList) {
			for (c in trees) {
				if (c.getParent<RouteTree>() != null) {
					wireLength += manhattanDistance(c.getParent<RouteTree>().wire.tile, c.wire.tile)
				}
			}
		}
	}
	return wireLength
}

fun computeLEsPerSlice(design: CellDesign): IntArray {
	val SLICE_TYPES = FamilyInfos.get(design.family).sliceSites()
	val perSite = design.logicCells.groupBy { it.site }.filterKeys { it.type in SLICE_TYPES }
	val array = IntArray(4)
	for ((_, v) in perSite) {
		array[v.map { it.le }.distinct().filterNotNull().size-1] += 1
	}
	return array
}

private val belNames = "([ABCD])([56]LUT|5FF|FF)".toRegex()
val Cell.le: Char?
	get() {
		val mo = belNames.matchEntire(this.bel.name)
		return when {
			mo == null -> null
			else -> mo.groupValues[1][0]
		}
	}
