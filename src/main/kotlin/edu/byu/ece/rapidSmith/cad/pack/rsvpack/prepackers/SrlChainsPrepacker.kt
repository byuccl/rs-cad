package edu.byu.ece.rapidSmith.cad.pack.rsvpack.prepackers

import edu.byu.ece.rapidSmith.cad.cluster.Cluster
import edu.byu.ece.rapidSmith.cad.cluster.PackUnit
import edu.byu.ece.rapidSmith.cad.cluster.getCluster
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.*
import edu.byu.ece.rapidSmith.design.subsite.Cell
import edu.byu.ece.rapidSmith.design.subsite.CellDesign
import edu.byu.ece.rapidSmith.device.Bel
import java.util.*
import kotlin.streams.asSequence

/**
 * Makes sure SRL chains are grouped together.
 */
class SRLChainsPrepackerFactory : PrepackerFactory<PackUnit>() {
	private val mc31Sinks = LinkedHashMap<Cell, Cell>()

	override fun init(design: CellDesign) {
		val q31SourceCells = design.inContextLeafCells.asSequence()
			.sortedBy { it.name }
			.filter { it.usesPin("Q31") || it.usesPin("Q15") }

		q31SourceCells.forEach { source ->
			val mc31Pin = source.getPin("Q31") ?: source.getPin("Q15")!!
			val sinks = mc31Pin.net.sinkPins
			if (sinks.size != 1)
				throw AssertionError("Too many or too few sinks on an MC31 pin")
			val sink = sinks.first()!!.cell
			mc31Sinks[source] = sink
		}
	}

	override fun make(): Prepacker<PackUnit> {
		return SRLChainsPrepacker(mc31Sinks)
	}
}

private class SRLChainsPrepacker(val mc31Sinks: Map<Cell, Cell>) : Prepacker<PackUnit>() {
	override fun packRequired(
		cluster: Cluster<*, *>,
		changedCells: MutableMap<Cell, Bel>
	): PrepackStatus {
		val mc31sinkCells = changedCells.asSequence()
			.map { it.key }
			.filter { it in mc31Sinks }
			.toList()

		if (mc31sinkCells.isEmpty())
			return PrepackStatus.UNCHANGED

		val sources: Queue<Cell> = ArrayDeque()
		sources.addAll(mc31sinkCells)

		var status = PrepackStatus.UNCHANGED
		while (sources.isNotEmpty()) {
			val sourceCell = sources.poll()
			val sourceBel = cluster.getCellPlacement(sourceCell)!!
			if (sourceBel.name != "A6LUT") {
				val sink = mc31Sinks[sourceCell]!!
				val sinkBelName = "${sourceBel.name[0] - 1}6LUT"
				val sinkBel = sourceBel.site.getBel(sinkBelName)

				val sinkCluster = sink.getCluster<Cluster<*, *>>()
				if (sinkCluster === cluster) {
					val actualLocation = cluster.getCellPlacement(sink)!!
					if (actualLocation != sinkBel)
						return PrepackStatus.INFEASIBLE
				} else if (sinkCluster != null) {
					return PrepackStatus.INFEASIBLE
				} else {
					val addStatus = addCellToCluster(cluster, sink, sinkBel)
					if (addStatus == PackStatus.VALID)
						changedCells[sink] = sinkBel
					if (addStatus == PackStatus.INFEASIBLE)
						return PrepackStatus.INFEASIBLE
					status = PrepackStatus.CHANGED

					if (sink in mc31Sinks)
						sources.add(sink)
				}
			}
		}
		return status
	}
}

private fun Cell.usesPin(name: String): Boolean {
	val pin = getPin(name)
	return pin?.isConnectedToNet ?: false
}

