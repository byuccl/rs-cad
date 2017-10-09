package edu.byu.ece.rapidSmith.cad.pack.rsvpack.configurations

import edu.byu.ece.rapidSmith.cad.cluster.Cluster
import edu.byu.ece.rapidSmith.cad.cluster.PackUnit
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.router.ClusterRouter
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.router.ClusterRouterFactory
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.router.ClusterRouterResult
import edu.byu.ece.rapidSmith.design.subsite.CellNet
import edu.byu.ece.rapidSmith.design.subsite.CellPin
import edu.byu.ece.rapidSmith.design.subsite.RouteTree
import edu.byu.ece.rapidSmith.device.Bel
import edu.byu.ece.rapidSmith.device.BelPin
import java.util.*
import kotlin.collections.HashMap

class DirectPathClusterRouterFactory<in T: PackUnit>(
	private val preferredPin: (CellPin, Bel) -> BelPin
) : ClusterRouterFactory<T> {
	private val routers = HashMap<T, ClusterRouter<T>>()

	override fun get(packUnit: T): ClusterRouter<T> {
		return routers.computeIfAbsent(packUnit) {
			DirectPathClusterRouter(packUnit, preferredPin)
		}
	}
}

private class DirectPathClusterRouter<T: PackUnit>(
	val packUnit: T,
	val preferredPin: (CellPin, Bel) -> BelPin
) : ClusterRouter<T> {
	override fun route(cluster: Cluster<T, *>): ClusterRouterResult {
		val routeTreeMap = HashMap<CellNet, ArrayList<RouteTree>>()
		val belPinMap = HashMap<CellNet, HashMap<CellPin, BelPin>>()

		for (cell in cluster.cells) {
			val bel = cluster.getCellPlacement(cell)!!
			val outputs = cell.pins
				.filter { it.isOutpin }
				.filter { it.isConnectedToNet }

			for (cellPin in outputs) {
				val belPin = preferredPin(cellPin, bel)
				val net = cellPin.net
				val rt = routeToOutput(belPin) ?: return ClusterRouterResult(false)
				belPinMap.computeIfAbsent(net) { HashMap() }.put(cellPin, belPin)
				routeTreeMap.computeIfAbsent(net){ ArrayList() }.add(rt)
			}

			val inputs = cell.pins
				.filter { it.isInpin }
				.filter { it.isConnectedToNet }

			for (cellPin in inputs) {
				val belPin = preferredPin(cellPin, bel)
				val net = cellPin.net
				val rt = routeToInput(belPin) ?: return ClusterRouterResult(false)
				belPinMap.computeIfAbsent(net) { HashMap() }.put(cellPin, belPin)
				routeTreeMap.computeIfAbsent(net) { ArrayList() }.add(rt)
			}
		}

		return ClusterRouterResult(true, routeTreeMap, belPinMap)
	}

	private fun routeToOutput(belPin: BelPin): RouteTree? {
		val sourceWire = belPin.wire
		val sourceTree = RouteTree(sourceWire)
		val q: Queue<RouteTree> = ArrayDeque()
		q.add(sourceTree)

		var sinkTree: RouteTree? = null
		while (q.isNotEmpty()) {
			val rt = q.poll()
			val wire = rt.wire

			if (wire.connectedPin != null) {
				sinkTree = rt
				break
			}

			for (c in wire.wireConnections) {
				val sink = rt.addConnection(c)
				q.add(sink)
			}
		}

		if (sinkTree == null)
			return null

		pruneSourceTrees(arrayListOf(sourceTree), setOf(sinkTree), false)
		return sourceTree
	}

	private fun routeToInput(belPin: BelPin): RouteTree? {
		val site = belPin.bel.site
		val sourceTrees = ArrayList(site.sinkPins.map { it.internalWire }.map { RouteTree(it) })

		val q: Queue<RouteTree> = ArrayDeque()
		q.addAll(sourceTrees)

		var sinkTree: RouteTree? = null
		while (q.isNotEmpty()) {
			val rt = q.poll()
			val wire = rt.wire

			if (wire.terminal == belPin) {
				sinkTree = rt
				break
			}

			for (c in wire.wireConnections) {
				val sink = rt.addConnection(c)
				q.add(sink)
			}
		}

		if (sinkTree == null)
			return null

		pruneSourceTrees(sourceTrees, setOf(sinkTree), false)
		return sourceTrees.single()
	}
}

private fun pruneSourceTrees(
	sourceTrees: ArrayList<RouteTree>,
	sinkTrees: Set<RouteTree>, removeSources: Boolean
) {
	sourceTrees.removeIf { rt -> !rt.prune(sinkTrees) && removeSources }
}
