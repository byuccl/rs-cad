package edu.byu.ece.rapidSmith.cad.pack.rsvpack.configurations

import edu.byu.ece.rapidSmith.cad.cluster.Cluster
import edu.byu.ece.rapidSmith.cad.cluster.PackUnit
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.router.ClusterRouter
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.router.ClusterRouterFactory
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.router.ClusterRouterResult
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.router.PinMapper
import edu.byu.ece.rapidSmith.design.subsite.CellNet
import edu.byu.ece.rapidSmith.design.subsite.CellPin
import edu.byu.ece.rapidSmith.design.subsite.RouteTree
import edu.byu.ece.rapidSmith.device.BelPin
import java.util.*
import kotlin.collections.HashMap

class DirectPathClusterRouterFactory<in T: PackUnit>(
	private val preferredPin: PinMapper
) : ClusterRouterFactory<T> {
	private val routers = LinkedHashMap<T, ClusterRouter<T>>()

	override fun get(packUnit: T): ClusterRouter<T> {
		return routers.computeIfAbsent(packUnit) {
			DirectPathClusterRouter(packUnit, preferredPin)
		}
	}
}

private class DirectPathClusterRouter<T: PackUnit>(
	val packUnit: T,
	val preferredPin: PinMapper
) : ClusterRouter<T> {
	override fun route(cluster: Cluster<T, *>): ClusterRouterResult {
		val routeTreeMap = LinkedHashMap<CellNet, ArrayList<RouteTree>>()
		val belPinMap = LinkedHashMap<CellNet, HashMap<CellPin, List<BelPin>>>()

		for (cell in cluster.cells) {
			val bel = cluster.getCellPlacement(cell)!!
			val outputs = cell.pins
				.filter { it.isOutpin }
				.filter { it.isConnectedToNet }

			for (cellPin in outputs) {
				val belPins = preferredPin(cluster, cellPin, bel)
				if (belPins != null) {
					for (bp in belPins) {
						val net = cellPin.net
						val rt = routeToOutput(bp) ?: return ClusterRouterResult(false)
						belPinMap.computeIfAbsent(net) { HashMap() }.put(cellPin, belPins)
						routeTreeMap.computeIfAbsent(net) { ArrayList() }.add(rt)
					}
				}
			}

			val inputs = cell.pins
				.filter { it.isInpin }
				.filter { it.isConnectedToNet }

			for (cellPin in inputs) {
				val belPins = preferredPin(cluster, cellPin, bel)
				val net = cellPin.net
				if (belPins != null && belPins.isNotEmpty()) {
					val rt = routeToInputs(belPins) ?: return ClusterRouterResult(false)
					belPinMap.computeIfAbsent(net) { HashMap() }.put(cellPin, belPins)
					routeTreeMap.computeIfAbsent(net) { ArrayList() }.addAll(rt)
				}
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

			wire.wireConnections.mapTo(q) { rt.connect<RouteTree>(it) }
		}

		if (sinkTree == null)
			return null

		pruneSourceTrees(arrayListOf(sourceTree), setOf(sinkTree), false)
		return sourceTree
	}

	private fun routeToInputs(belPins: List<BelPin>): List<RouteTree>? {
		val site = belPins[0].bel.site
		val sourceTrees = ArrayList(site.sinkPins.map { it.internalWire }.map { RouteTree(it) })

		val q: Queue<RouteTree> = ArrayDeque()
		q.addAll(sourceTrees)

		var foundSinks = 0
		val sinkTrees = ArrayList<RouteTree>()
		while (q.isNotEmpty()) {
			val rt = q.poll()
			val wire = rt.wire

			if (wire.terminal in belPins) {
				sinkTrees.add(rt)
				foundSinks++
				if (foundSinks == belPins.size)
					break
			}

			wire.wireConnections.mapTo(q) { rt.connect<RouteTree>(it) }
		}

		if (sinkTrees.size < belPins.size)
			return null

		pruneSourceTrees(sourceTrees, sinkTrees.toSet(), true)
		return sourceTrees
	}
}

private fun pruneSourceTrees(
	sourceTrees: ArrayList<RouteTree>,
	sinkTrees: Set<RouteTree>, removeSources: Boolean
) {
	sourceTrees.removeIf { rt -> !rt.prune(sinkTrees) && removeSources }
}
