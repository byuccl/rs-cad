package edu.byu.ece.rapidSmith.cad.cluster.site

import edu.byu.ece.rapidSmith.util.getWireConnections
import edu.byu.ece.rapidSmith.design.subsite.RouteTree
import edu.byu.ece.rapidSmith.device.*
import edu.byu.ece.rapidSmith.util.getConnectedPin
import java.util.*

/**
 *
 */
class ClusterRoutingBuilder(val SWITCH_MATRIX_TILES: Set<TileType>) {
	var forward: Map<Tile, WireHashMap>? = null
	var reverse: Map<Tile, WireHashMap>? = null
	val usedWires = HashSet<Int>()

	private val wireConnectionsMap = HashMap<Wire, HashSet<WireConnection>>()
	private val revWireConnectionsMap = HashMap<Wire, HashSet<WireConnection>>()
	private val outputs = HashSet<Wire>() // to the general routing
	private val inputs = HashSet<Wire>()  // from the general routing

	/**
	 * Discover all useful routing starting from the given instance.
	 */
	fun traverse(template: Site, actual: Site, tileMap: Map<Tile, Tile>): ClusterRoutingBuilder {
		val sinkTraverser = CRTraverser(true, template, tileMap)
		val sourceTraverser = CRTraverser(false, template, tileMap)

		// template may be of different type than the pack unit type
		// use the actual to get the pins of interest, template should have all
		// matching pins
		actual.sinkPins
			.map { template.getSitePin(it.name) }
			.forEach { sourceTraverser.run(it) }
		actual.sourcePins
			.map { template.getSitePin(it.name) }
			.forEach { sinkTraverser.run(it) }

		translateConnections(sinkTraverser, tileMap, wireConnectionsMap)
		appendOutputs(sinkTraverser, tileMap, outputs)
		translateConnections(sourceTraverser, tileMap, revWireConnectionsMap)
		appendOutputs(sourceTraverser, tileMap, inputs)
		return this
	}

	fun finish() {
		connectOutputsToInputs(wireConnectionsMap, outputs, inputs)
		connectOutputsToInputs(revWireConnectionsMap, inputs, outputs)

		forward = buildWireHashMap(wireConnectionsMap, revWireConnectionsMap)
		reverse = buildWireHashMap(revWireConnectionsMap, wireConnectionsMap)
	}

	// populates connsMap with connections found in traverser
	private fun translateConnections(
		traverser: CRTraverser,
		tileMap: Map<Tile, Tile>,
		connsMap: HashMap<Wire, HashSet<WireConnection>>
	) {
		traverser.usedConnections
			.map { translateConnection(it, tileMap) }
			.forEach { connsMap.computeIfAbsent(it.first) { HashSet() }.add(it.second) }
	}

	// update the wires driving/driven by the switch box -- these wires will be
	// used to emulate the outside general routing
	private fun appendOutputs(
		traverser: CRTraverser,
		tileMap: Map<Tile, Tile>,
		packUnitOutputs: MutableSet<Wire>
	) {
		traverser.exitWires.mapTo(packUnitOutputs) { translateWire(it, tileMap) }
	}

	private fun connectOutputsToInputs(
		connsMap: HashMap<Wire, HashSet<WireConnection>>,
		wiresDriving: Set<Wire>, wiresDriven: Set<Wire>
	) {
		for (driving in wiresDriving) {
			val conns = connsMap.computeIfAbsent(driving) { HashSet() }
			wiresDriven.filter { it != driving }
				.mapTo(conns) { getTileWireConnection(driving, it, true) }
		}
	}

	private fun buildWireHashMap(
		conns: Map<Wire, Set<WireConnection>>,
		revs: Map<Wire, Set<WireConnection>>
	): Map<Tile, WireHashMap> {
		val aggregate = HashMap<Wire, HashSet<WireConnection>>()
		conns.forEach { w, c -> aggregate.computeIfAbsent(w) { HashSet() }.addAll(c) }
		revs.forEach { w, s ->
			s.forEach { c ->
				val wc = WireConnection(w.wireEnum, -c.rowOffset, -c.columnOffset, c.isPIP)
				val source = TileWire(c.getTile(w.tile), c.wire)
				aggregate.computeIfAbsent(source) { HashSet() }.add(wc)
			}
		}

		val whm = HashMap<Tile, WireHashMap>()

		for ((sourceWire, set) in aggregate) {
			val c = arrayOfNulls<WireConnection>(set.size)
			val it = set.iterator()
			for (i in set.indices) {
				c[i] = it.next()
			}
			assert(!it.hasNext())
			whm.computeIfAbsent(sourceWire.tile) { WireHashMap() }
				.put(sourceWire.wireEnum, c)
		}
		return whm
	}

	// Find all of the important used wires and wires that leave the site
	private inner class CRTraverser (
		private val forward: Boolean,
		private val instance: Site,
		private val tileMap: Map<Tile, Tile>
	) {
		val usedConnections = ArrayList<Connection>()
		val exitWires = HashSet<Wire>()
		private val stack = ArrayDeque<RouteTree>()
		// null -> not visited, false -> not marked as used, true -> marked as used
		private val visitedWires = HashMap<Wire, Boolean>()

		// TODO move the goesThroughSwitchBox info into the route tree
		// TODO mark route trees as used or unused
		private val goesThroughSwitchBox = HashSet<RouteTree>()

		fun run(sourcePin: SitePin) {
			val sourceWire = sourcePin.externalWire
			val sourceTree = RouteTree(sourceWire)
			stack.push(sourceTree)

			while (!stack.isEmpty()) {
				val rt = stack.pop()
				// exit if we've already visited this wires
				val visited = visitedWires[rt.wire]
				if (visited != null) {
					// if this wire was previously added to the set of used wires,
					// ie it drives an important sink, mark the tree as used
					if (visited)
						addTreeToUsedWires(rt)
					continue
				}
				visitedWires[rt.wire] = false
				usedWires += rt.wire.wireEnum

				rt.wire.getWireConnections(forward).forEach { handleWireConnection(rt, it) }
				rt.wire.getConnectedPin(forward)?.let { handlePin(rt, it) }
			}
		}

		private fun handleWireConnection(source: RouteTree, c: Connection) {
			val sinkWire = c.sinkWire
			if (sinkWire.tile in tileMap) {
				if (sinkWire !in source) {
					val sinkTree = source.addConnection(c)
					if (source.goesThroughSwitchBox || sinkWire.isSwitchMatrixWire())
						goesThroughSwitchBox.add(sinkTree)
					stack.push(sinkTree)
				}
			} else {
				if (source.wire.isSwitchMatrixWire())
					exitWires.add(source.wire)

				addTreeToUsedWires(source)
			}
		}

		private fun handlePin(source: RouteTree, sitePin: SitePin) {
			// the wires in this tree are needed if the pin is either on the
			// packUnit site (needed for getting from the switch box to the site)
			// or if the connection never went through a switch box (ie a direct
			// connection)

			if (sitePin.site === instance || !source.goesThroughSwitchBox)
				addTreeToUsedWires(source)
		}

		private fun addTreeToUsedWires(sink: RouteTree) {
			var rt: RouteTree? = sink
			do {
				if (rt!!.connection != null)
					usedConnections.add(rt.connection!!)
				visitedWires[rt.wire] = true
				rt = rt.sourceTree
			} while (rt != null && !visitedWires[rt.wire]!!)
		}

		private val RouteTree.goesThroughSwitchBox: Boolean
			get() = this in this@CRTraverser.goesThroughSwitchBox
	}

	private fun Wire.isSwitchMatrixWire(): Boolean {
		return SWITCH_MATRIX_TILES.contains(tile.type)
	}
}

private fun getTileWireConnection(
	sourceWire: Wire,
	sinkWire: Wire,
	isPip: Boolean
): WireConnection {
	val sourceTile = sourceWire.tile
	val sinkTile = sinkWire.tile

	val yOff = sourceTile.row - sinkTile.row
	val xOff = sourceTile.column - sinkTile.column
	return WireConnection(sinkWire.wireEnum, yOff, xOff, isPip)
}

private fun translateWire(origWire: Wire, tileMap: Map<Tile, Tile>): Wire {
	val translatedTile = tileMap[origWire.tile]!!

	if (origWire is TileWire) {
		return TileWire(translatedTile, origWire.getWireEnum())
	} else {
		assert(origWire is SiteWire)
		val translatedSite = translatedTile.getSite(0)
		return SiteWire(translatedSite, origWire.wireEnum)
	}
}

private fun translateConnection(
	c: Connection, tileMap: Map<Tile, Tile>
): Pair<Wire, WireConnection> {
	val translatedSource = translateWire(c.sourceWire, tileMap)
	val translatedSink = translateWire(c.sinkWire, tileMap)
	val connection = getTileWireConnection(
		translatedSource, translatedSink, c.isPip)
	return translatedSource to connection
}

private operator fun RouteTree.contains(wire: Wire): Boolean {
	var rt: RouteTree? = this
	while (rt != null) {
		if (rt.wire == wire)
			return true
		rt = rt.sourceTree
	}
	return false
}
