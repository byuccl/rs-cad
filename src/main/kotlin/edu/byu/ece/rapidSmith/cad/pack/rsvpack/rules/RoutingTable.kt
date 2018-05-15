package edu.byu.ece.rapidSmith.cad.pack.rsvpack.rules

import edu.byu.ece.rapidSmith.cad.cluster.PackUnitTemplate
import edu.byu.ece.rapidSmith.device.*
import edu.byu.ece.rapidSmith.util.SitePinConnection
import edu.byu.ece.rapidSmith.util.getSitePinConnection
import java.util.*

fun buildPinGroups(
	packUnit: PackUnitTemplate
): Map<BelPin, PinGroup> {
	return PinGroupBuilder(packUnit).build()
}

/**
 *
 */
class PinGroup(
	val sourceWires: List<Wire>,
	val sinkWires: List<Wire>,
	val sourcePins: List<BelPin>,
	val sinkPins: List<BelPin>,
	val carryChainSinks: List<Wire>,
	val carryChainSources: List<Wire>,
	val routingTable: RoutingTable
) {
	val sourceBels: List<Bel>
		get() = sourcePins.map { it.bel }

	val sinkBels: List<Bel>
		get() = sinkPins.map { it.bel }

	class Builder {
		val sourceWires = HashSet<Wire>()
		val sinkWires = HashSet<Wire>()
		val sourcePins = HashSet<BelPin>()
		val sinkPins = HashSet<BelPin>()
		val carryChainSinks = HashSet<Wire>()
		val carryChainSources = HashSet<Wire>()
		var routingTable: RoutingTable? = null

		fun build(): PinGroup {
			return PinGroup(
				sourceWires.toList(),
				sinkWires.toList(),
				sourcePins.toList(),
				sinkPins.toList(),
				carryChainSinks.toList(),
				carryChainSources.toList(),
				checkNotNull(routingTable) { "Routing table not set" }
			)
		}
	}
}

private class PinGroupBuilder(private val packUnit: PackUnitTemplate) {
	private val pinGroups = HashMap<BelPin, PinGroup.Builder>()

	fun build(): Map<BelPin, PinGroup> {
		val outputs = packUnit.outputs.toSet()
		val inputs = packUnit.inputs.toSet()

		for (bel in packUnit.bels) {
			for (pin in bel.belPins) {
				if (pin !in pinGroups) {
					val pg = PinGroup.Builder()
					val sourceWire = pin.wire
					traverseToSinks(sourceWire, pg, outputs, inputs)
					traverseToDirectConnections(pg)
				}
			}
		}

		val distincts = pinGroups.values.distinct()
		distincts.forEach { it.routingTable = RoutingTableBuilder(packUnit).build(it) }
		val builtMap = distincts.map { it to it.build() }.toMap()

		return pinGroups.mapValues { (_, b) -> builtMap[b]!! }
	}

	private fun traverseToSinks(
		sourceWire: Wire, pg: PinGroup.Builder, outputs: Set<Wire>, inputs: Set<Wire>
	) {
		val q = LinkedList<Wire>()
		val processed = HashSet<Wire>()
		q.offer(sourceWire)

		while (!q.isEmpty()) {
			val wire = q.poll()

			if (!processed.add(wire))
				continue

			wire.wireConnections
				.filter { !it.isRouteThrough }
				.forEach {
					handleWireConnection(it, outputs, q, pg.sinkWires)
				}
			wire.reverseWireConnections
				.filter { !it.isRouteThrough }
				.forEach {
					handleWireConnection(it, inputs, q, pg.sourceWires)
				}
			wire.getSitePinConnection(true)?.let {
				handleSiteConnection(it, outputs, q, pg.sinkWires)
			}
			wire.getSitePinConnection(false)?.let {
				handleSiteConnection(it, inputs, q, pg.sourceWires)
			}
			wire.terminal?.let { handleTerminal(it, pg, pg.sinkPins) }
			wire.source?.let { handleTerminal(it, pg, pg.sourcePins) }
		}
	}

	private fun handleWireConnection(
		connection: Connection, edgeWires: Set<Wire>, q: Queue<Wire>,
		sinks: MutableCollection<Wire>
	) {
		val sinkWire = connection.sinkWire
		if (sinkWire in edgeWires) {
			sinks += sinkWire
		} else {
			q.offer(sinkWire)
		}
	}

	private fun handleSiteConnection(
		connection: SitePinConnection, edgeWires: Set<Wire>, q: Queue<Wire>,
		sinks: MutableCollection<Wire>
	) {
		val sinkWire = connection.sinkWire
		if (sinkWire in edgeWires) {
			sinks += sinkWire
		} else {
			q.offer(sinkWire)
		}
	}

	private fun handleTerminal(
		pin: BelPin, pg: PinGroup.Builder, sinks: MutableCollection<BelPin>
	) {
//		// Kind of a special case.  Some inputs to DSPs are driven by
//		// VCC and GND coming from a TIEOFF in the tile (not the switch
//		// box one).  I'll check if the input comes from the TIEOFF and remove
//		// them.
//		if (pin.bel.site.type == SiteType.TIEOFF)
//			return pg

		sinks += pin

		val old = pinGroups[pin]
		if (old == null)
			pinGroups[pin] = pg
	}

	private fun traverseToDirectConnections(pg: PinGroup.Builder) {
		packUnit.directSinksOfCluster
			.filter { it.clusterPin in pg.sourcePins }
			.mapTo(pg.carryChainSinks) { it.clusterExit }
		packUnit.directSourcesOfCluster
			.filter { it.clusterPin in pg.sinkPins }
			.mapTo(pg.carryChainSources) { it.clusterExit }
	}
}

/**
 *
 */
class RoutingTable(val rows: List<Row>) {
	class Row(
		val sourcePins: Map<BelPin, SourcePinEntry>,
		val sinkPins: Map<BelPin, SinkPinEntry>
	) {
		class Builder {
			val sourcePins = HashMap<BelPin, SourcePinEntry.Builder>()
			val sinkPins = HashMap<BelPin, SinkPinEntry.Builder>()
			fun build(): Row {
				val sources = sourcePins.mapValues { (_, v) -> v.build() }
				val sinks = sinkPins.mapValues { (_, v) -> v.build() }
				return Row(HashMap(sources), HashMap(sinks))
			}
		}
	}

	class SourcePinEntry(
		val drivesGeneralFabric: Boolean,
		val drivenSinks: List<BelPin>,
		val drivenClusterPins: List<Wire>
	) {
		class Builder {
			var drivesGeneralFabric: Boolean = false
			val drivenSinks = ArrayList<BelPin>()
			val drivenClusterPins = ArrayList<Wire>()

			fun build(): SourcePinEntry {
				drivenSinks.trimToSize()
				drivenClusterPins.trimToSize()
				return SourcePinEntry(drivesGeneralFabric,
					drivenSinks, drivenClusterPins)
			}
		}
	}

	class SinkPinEntry(
		val drivenByGeneralFabric: Boolean,
		val sourceClusterPin: Wire?,
		val sourcePin: BelPin?
	) {
		class Builder {
			var drivenByGeneralFabric: Boolean = false
			var sourceClusterPin: Wire? = null
			var sourcePin: BelPin? = null

			fun build(): SinkPinEntry {
				return SinkPinEntry(drivenByGeneralFabric, sourceClusterPin, sourcePin)
			}
		}
	}
}

private class RoutingTableBuilder(val packUnit: PackUnitTemplate) {
	fun build(pg: PinGroup.Builder): RoutingTable {
		val muxes = findMuxes(pg)

		val tableRows = ArrayList<RoutingTable.Row>()
		getMuxConfigurations(muxes).mapTo(tableRows) { buildTableRow(pg, it) }
		tableRows.trimToSize()

		return RoutingTable(tableRows)
	}

	private fun buildTableRow(pg: PinGroup.Builder, muxes: Map<Wire, Wire>): RoutingTable.Row {
		val tableRow = RoutingTable.Row.Builder()

		traverseFromSourcePins(pg, muxes, tableRow)
		traverseFromClusterSources(pg, muxes, tableRow)
		traverseFromCarryChainSources(pg, muxes, tableRow)

		return tableRow.build()
	}

	private fun traverseFromSourcePins(
		pg: PinGroup.Builder, muxes: Map<Wire, Wire>,
		tableRow: RoutingTable.Row.Builder
	) {
		for (sourcePin in pg.sourcePins) {
			val entry = RoutingTable.SourcePinEntry.Builder()
			tableRow.sourcePins[sourcePin] = entry

			val sourceWire = sourcePin.wire
			val q: Queue<Wire> = ArrayDeque()
			val processed = HashSet<Wire>()
			q += sourceWire

			while (!q.isEmpty()) {
				val wire = q.poll()
				if (!processed.add(wire))
					continue

				wire.wireConnections
					.filter { !it.isRouteThrough }
					.forEach { c ->
						val sinkWire = c.sinkWire
						if (sinkWire in pg.sinkWires) {
							entry.drivenClusterPins += sinkWire
							entry.drivesGeneralFabric = true
						} else if (sinkWire in pg.carryChainSinks) {
							entry.drivenClusterPins += sinkWire
						} else if (sinkWire !in muxes || muxes[sinkWire] == wire) {
							q += sinkWire
						}
					}
				wire.getSitePinConnection(true)?.let { q += it.sinkWire }
				wire.terminal?.let { sink ->
					entry.drivenSinks += sink
					val sinkEntry = tableRow.sinkPins.computeIfAbsent(sink) {
						RoutingTable.SinkPinEntry.Builder()
					}

					// Correctly handle inout pins.  Out only pins take precedence over input pins
					if (sinkEntry.sourcePin == null) {
						sinkEntry.sourcePin = sourcePin
					} else {
						if (sinkEntry.sourcePin!!.direction == PinDirection.OUT) {
							assert(sourcePin.direction != PinDirection.OUT)
						} else {
							sinkEntry.sourcePin = sourcePin
						}
					}
				}
			}
		}
	}

	private fun traverseFromClusterSources(
		pg: PinGroup.Builder, muxes: Map<Wire, Wire>,
		tableRow: RoutingTable.Row.Builder
	) {
		for (sourceWire in pg.sourceWires) {
			val q: Queue<Wire> = ArrayDeque()
			val processed = HashSet<Wire>()
			q += sourceWire

			while (!q.isEmpty()) {
				val wire = q.poll()
				if (!processed.add(wire))
					continue

				wire.wireConnections
					.filter { !it.isRouteThrough }
					.forEach {
						val sinkWire = it.sinkWire
						if (sinkWire in pg.sinkWires || sinkWire in pg.carryChainSinks) {
//							println("Ignoring route through cluster: $sourceWire -> $sinkWire")
						} else if (sinkWire !in muxes || muxes[sinkWire] == wire) {
							q += sinkWire
						}
					}

				wire.getSitePinConnection(true)?.let { q += it.sinkWire }

				wire.terminal?.let { sink ->
					val sinkEntry = tableRow.sinkPins.computeIfAbsent(sink) {
						RoutingTable.SinkPinEntry.Builder()
					}
					sinkEntry.sourceClusterPin = sourceWire
					sinkEntry.drivenByGeneralFabric = true
				}
			}
		}
	}

	private fun traverseFromCarryChainSources(
		pg: PinGroup.Builder, muxes: Map<Wire, Wire>,
		tableRow: RoutingTable.Row.Builder
	) {
		for (sourceWire in pg.carryChainSources) {
			val q: Queue<Wire> = ArrayDeque()
			val processed = HashSet<Wire>()
			q += sourceWire

			while (!q.isEmpty()) {
				val wire = q.poll()
				if (!processed.add(wire))
					continue
				wire.wireConnections
					.filter { !it.isRouteThrough }
					.forEach { c ->
						val sinkWire = c.sinkWire
						if (sinkWire in pg.sinkWires || sinkWire in pg.carryChainSinks) {
							throw AssertionError("Did not expect any straight entry to exit routes")
						} else if (sinkWire !in muxes || muxes[sinkWire] == wire) {
							q += sinkWire
						}
					}
				wire.getSitePinConnection(true)?.let { q += it.sinkWire }

				wire.terminal?.let { sink ->
					val sinkEntry = tableRow.sinkPins.computeIfAbsent(sink) {
						RoutingTable.SinkPinEntry.Builder()
					}
					sinkEntry.sourceClusterPin = sourceWire
				}
			}
		}
	}

	// TODO can this be performed while building the pin groups
	private fun findMuxes(pg: PinGroup.Builder): Map<Wire, Set<Wire>> {
		val muxes = HashMap<Wire, HashSet<Wire>>()

		val queue: Queue<Wire> = ArrayDeque()
		queue.addAll(pg.sinkPins.map(BelPin::getWire))
		queue.addAll(pg.sinkWires)
		// TODO still need carry chain sinks, how do I represent them?
		//		for (Wire sinkWire : pg.getCarryChainSinks()) {
		//
		//		}

		val processed = HashSet<Wire>()
		while (!queue.isEmpty()) {
			val wire = queue.poll()
			if (!processed.add(wire))
				continue

			wire.reverseWireConnections
				.filter { !it.isRouteThrough }
				.forEach { c ->
					val source = c.sinkWire
					if (c.isPip) {
						muxes.computeIfAbsent(wire) { HashSet() }.add(source)
					}
					if (source !in packUnit.inputs)
						queue += source
				}

			wire.getSitePinConnection(false)?.let {
				val source = it.sinkWire
				if (source !in packUnit.inputs) {
					queue += source
				}
			}
		}

		return muxes.filterValues { it.size > 1 }
	}
}

private fun getMuxConfigurations(
	muxes: Map<Wire, Set<Wire>>
): Iterable<Map<Wire, Wire>> {
	return Iterable { MuxConfigurationIterator(muxes) }
}

private class MuxConfigurationIterator(
	muxes: Map<Wire, Set<Wire>>
) : Iterator<Map<Wire, Wire>> {
	private val status = ArrayList<Int>()
	private val sources = ArrayList<List<Wire>>()
	private val sinks = ArrayList<Wire>()
	private var exhausted = false

	init {
		for ((key, value) in muxes) {
			status += 0
			sources += ArrayList(value)
			sinks += key
		}
	}

	override fun hasNext(): Boolean {
		return !exhausted
	}

	override fun next(): Map<Wire, Wire> {
		if (!hasNext())
			throw NoSuchElementException()

		val next = buildNext()
		updateStatus()

		return next
	}

	private fun updateStatus() {
		for (i in status.indices.reversed()) {
			val nextStatus = status[i] + 1
			if (nextStatus >= sources[i].size) {
				status[i] = 0
			} else {
				status[i] = nextStatus
				return
			}
		}
		exhausted = true
	}

	private fun buildNext(): Map<Wire, Wire> {
		val next = HashMap<Wire, Wire>()
		for (i in status.indices) {
			next.put(sinks[i], sources[i][status[i]])
		}
		return next
	}
}
