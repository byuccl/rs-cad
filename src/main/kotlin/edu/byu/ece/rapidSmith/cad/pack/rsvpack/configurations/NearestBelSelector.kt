package edu.byu.ece.rapidSmith.cad.pack.rsvpack.configurations

import edu.byu.ece.rapidSmith.cad.cluster.*
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.BelSelector
import edu.byu.ece.rapidSmith.design.subsite.*
import edu.byu.ece.rapidSmith.device.*
import edu.byu.ece.rapidSmith.util.*
import org.jdom2.input.SAXBuilder
import java.nio.file.Path
import java.util.*

typealias BelCostMap = Map<BelId, Double>

/**
 *
 */
class ShortestRouteBelSelector
constructor(
	packUnit: PackUnit,
	private val baseBelCostMap: BelCostMap,
	private val HIGH_FANOUT_LIMIT: Int = 500,
	private val LEAVE_SITE_PENALTY: Double = 0.5
) : BelSelector<PackUnit> {
	private val sinksOfSources: Map<BelPin, List<ClusterConnection>>
	private val sourcesOfSinks: Map<BelPin, List<ClusterConnection>>
	private val reserveBelCostMap = StackedHashMap<Bel, Double>()
	private val pqStack = ArrayDeque<PriorityQueue<BelCandidate>>()
	private val filteredNets = HashSet<CellNet>()

	private var cluster: Cluster<*, *>? = null
	private var pq: PriorityQueue<BelCandidate>? = null

	init {
		val ccb = ClusterConnectionsBuilder().build(packUnit)
		sinksOfSources = ccb.sinksOfSources
		sourcesOfSinks = ccb.sourcesOfSinks
	}

	override fun init(design: CellDesign) {
		fun shouldFilterNet(net: CellNet): Boolean {
			if (net.isStaticNet || net.pins.size > HIGH_FANOUT_LIMIT)
				return true
			for (pin in net.pins) {
				when (pin.type) {
					CellPinType.CLOCK, CellPinType.ENABLE,
					CellPinType.PRESET, CellPinType.RESET -> return true
					else -> { /* nothing */ }
				}
			}
			return false
		}

		design.nets.filterTo(filteredNets) { shouldFilterNet(it) }
	}

	private fun CellNet.isFilteredNet(): Boolean {
		return this in filteredNets
	}

	override fun initCluster(cluster: Cluster<*, *>) {
		this.cluster = cluster
		val template = cluster.type.template
		template.bels.forEach { bel -> reserveBelCostMap[bel] = 0.0 }
	}

	override fun initCell(cell: Cell, forcedAnchors: Collection<Bel>?) {
		val cluster = checkNotNull(cluster) { "Cluster not initialized" }
		val template = cluster.type.template

		pq = PriorityQueue()
		val candidateRoots = getPossibleCellRoots(template, cell, forcedAnchors)
		for (candidateRoot in candidateRoots) {
			val cost = calcCost(cell, cluster, candidateRoot)
			if (cost != null)
				pq!!.add(BelCandidate(candidateRoot, cost))
		}
	}

	private fun getPossibleCellRoots(
		template: PackUnitTemplate, cell: Cell, bels: Collection<Bel>?
	): List<Bel> {
		val candidates = ArrayList(cell.getPossibleAnchors(template))
		if (bels != null)
			candidates.retainAll(bels)

		return ArrayList<Bel>(candidates)
	}

	private fun calcCost(cell: Cell, cluster: Cluster<*, *>, anchor: Bel): Double? {
		var cost = 0.0
		val bels = cell.getRequiredBels(anchor)
		for (bel in bels) {
			if (cluster.isBelOccupied(bel))
				return null

			val baseCost = baseBelCostMap[bel.id] ?: 1.0
			val routingCost = getRoutingCostOfBel(cluster, cell, bel) ?: return null
			cost += baseCost + routingCost
		}
		return cost
	}

	private fun getRoutingCostOfBel(cluster: Cluster<*, *>, cell: Cell, bel: Bel): Double? {
		var cost = 0.0
		// Iterate over every connection on this cell
		val pinsOnCell = cell.pins
			.filter { it.isConnectedToNet }
			.filter { !it.net.isFilteredNet() }
		for (pin in pinsOnCell) {
			// test if there is a direct connection
			val net = pin.net

			val connectedPins = net.pins.filter { it !== pin }
			for (connPin in connectedPins) {
				val connCell = connPin.cell
				assert(if (cluster.hasCell(connCell)) connCell.getCluster<Cluster<*, *>>() === cluster else true)
				if (connCell.getCluster<Cluster<*, *>>() === cluster) {
					// choose the best connection
					val conns = getConnections(pin, bel, connPin)
					val connCost = conns.map { calcConnectionCost(it) }.min() ?: return null
					cost += connCost
				}
			}
		}
		return cost
	}

	private fun getConnections(
		candidatePin: CellPin, loc: Bel, placedPin: CellPin
	): Collection<ClusterConnection> {
		val placedCell = placedPin.cell
		val placedBel = placedCell.locationInCluster!!
		return if (placedPin.isOutpin) {
			val sourcePin = getBelPinOfCellPin(placedPin, placedBel)
			val sinkPins = getBelPinsOfCellPin(candidatePin, loc)

			sinksOfSources[sourcePin]!!
				.filter { cc -> sinkPins.contains(cc.pin) }
		} else {
			val toPins = getBelPinsOfCellPin(placedPin, placedBel)
			val fromPins = getBelPinsOfCellPin(candidatePin, loc)

			toPins.flatMap { sourcesOfSinks[it]!! }
				.filter { cc -> cc.pin in fromPins }
		}
	}

	private fun getBelPinsOfCellPin(pin: CellPin, bel: Bel): Collection<BelPin> {
		return pin.getPossibleBelPinNames(bel.id)
			.map { bel.getBelPin(it) }
	}

	private fun getBelPinOfCellPin(pin: CellPin, bel: Bel): BelPin {
		val pinNames = pin.getPossibleBelPinNames(bel.id)
		assert(pinNames.size == 1)
		val pinName = pinNames[0]
		return bel.getBelPin(pinName)
	}

	private fun calcConnectionCost(cc: ClusterConnection): Double {
		var cost = -1.0
		if (!cc.isWithinSite)
			cost *= LEAVE_SITE_PENALTY
		return cost
	}

	override fun nextBel(): Bel? {
		if (pq!!.isEmpty())
			return null
		return pq!!.poll().bel
	}

	override fun commitBels(bels: Collection<Bel>) {
		checkpoint()
		pq = null
	}

	private fun checkpoint() {
		val pqCopy = PriorityQueue<BelCandidate>()
		pq!!.forEach { bc -> pqCopy.add(BelCandidate(bc)) }
		pqStack.push(pqCopy)

		reserveBelCostMap.checkPoint()
	}

	override fun cleanupCluster() {
		pq = null
		pqStack.clear()
		reserveBelCostMap.reset()
		cluster = null
	}

	override fun revertToLastCommit() {
		pq = null
	}

	override fun rollBackLastCommit() {
		pq = pqStack.pop()
		reserveBelCostMap.rollBack()
	}

	private class BelCandidate : Comparable<BelCandidate> {
		var bel: Bel
		var cost: Double = 0.toDouble()

		constructor(bel: Bel, cost: Double) {
			this.bel = bel
			this.cost = cost
		}

		constructor(o: BelCandidate) {
			this.bel = o.bel
			this.cost = o.cost
		}

		override fun compareTo(other: BelCandidate): Int {
			return cost.compareTo(other.cost)
		}
	}
}

/**
 * Information about the connecting pin.

 * Created by Haroldsen on 4/12/2015.
 */
private class ClusterConnection(
	val pin: BelPin,
	val isWithinSite: Boolean,
	val distance: Int
) : Comparable<ClusterConnection> {
	override fun compareTo(other: ClusterConnection): Int {
		return Comparator.comparing { cc: ClusterConnection -> cc.isWithinSite }
			.thenComparing { cc -> cc.distance }
			.compare(this, other)
	}
}

/**
 *
 */
private class ClusterConnectionsBuilder {
	val sourcesOfSinks: MutableMap<BelPin, List<ClusterConnection>> = HashMap()
	val sinksOfSources: MutableMap<BelPin, List<ClusterConnection>> = HashMap()

	fun build(
		packUnit: PackUnit
	): ClusterConnectionsBuilder {
		val template = packUnit.template
		for (bel in template.bels) {
			for (sourcePin in bel.sources) {
				val ccs = traverse(sourcePin, true)
				sinksOfSources.put(sourcePin, ccs)
			}
			for (sinkPin in bel.sinks) {
				val ccs = traverse(sinkPin, false)
				sourcesOfSinks.put(sinkPin, ccs)
			}
		}
		return this
	}

	private fun traverse(sourcePin: BelPin, forward: Boolean): List<ClusterConnection> {
		val connections = ArrayList<ClusterConnection>()
		val sourceWire = sourcePin.wire
		val wrapper = CCTWire(sourceWire)

		val processed = HashSet<Wire>()
		val pq = PriorityQueue<CCTWire>()
		pq += wrapper

		while (pq.isNotEmpty()) {
			val cct = pq.poll()
			if (!processed.add(cct.wire))
				continue

			if (!cct.reversed) {
				cct.wire.getWireConnections(forward).forEach { handleWireConnection(cct, it, pq, false) }
				cct.wire.getSitePinConnection(forward)?.let { handleSitePin(cct, it, pq, false) }
				if (cct.wire != sourceWire)
					cct.wire.getBelPinConnection(forward)?.let { handleBelPin(cct, it, connections) }
			}

			if (!forward) {
				cct.wire.getWireConnections(true).forEach { handleWireConnection(cct, it, pq, true) }
				cct.wire.getSitePinConnection(true)?.let { handleSitePin(cct, it, pq, true) }
				if (cct.wire != sourceWire)
					cct.wire.getBelPinConnection(true)?.let { handleBelPin(cct, it, connections) }
			}
		}

		return connections
	}

	private fun handleWireConnection(
		source: CCTWire, c: Connection,
		pq: PriorityQueue<CCTWire>, reverse: Boolean
	) {
		val sinkWire = c.sinkWire
		val distance = if (source.reversed || reverse) {
			source.distance
		} else {
			source.distance + if (c.isPip) 1 else 0
		}
		val wrapper = CCTWire(sinkWire, source.sourceSite, distance, source.reversed || reverse)
		pq += wrapper
	}

	private fun handleSitePin(
		source: CCTWire, c: SitePinConnection,
		pq: PriorityQueue<CCTWire>, reverse: Boolean
	) {
		if (!source.leftSite) {
			pq += CCTWire(c.sinkWire, c.sitePin.site, source.distance, source.reversed || reverse)
		} else if (c.sitePin.site === source.sourceSite) {
			assert(source.wire is TileWire)
			pq += CCTWire(c.sinkWire, source.sourceSite, source.distance, source.reversed || reverse)
		}
	}

	private fun handleBelPin(
		source: CCTWire, c: BelPinConnection,
		conns: ArrayList<ClusterConnection>
	) {
		conns += ClusterConnection(c.pin, !source.leftSite, source.distance)
	}

	private class CCTWire(
		val wire: Wire,
		val sourceSite: Site? = null,
		val distance: Int = 0,
		val reversed: Boolean = false
	) : Comparable<CCTWire> {

		override fun equals(other: Any?): Boolean {
			if (this === other) return true
			if (other !is CCTWire) return false

			return wire == other.wire
		}

		override fun hashCode(): Int {
			return Objects.hash(wire)
		}

		val leftSite: Boolean
			get() = sourceSite != null

		override fun compareTo(other: CCTWire): Int {
			return comparator.compare(this, other)
		}

		override fun toString(): String {
			return "CCTWire(wire=$wire)"
		}


		companion object {
			private val comparator = Comparator
				.comparing { cct: CCTWire -> cct.reversed }
				.thenComparing { cct -> cct.leftSite }
				.thenComparing { cct -> cct.distance }
		}
	}
}

fun loadBelCostsFromFile(belCostsFiles: Path): BelCostMap {
	val doublePool = HashPool<Double>()
	val builder = SAXBuilder()
	val doc = builder.build(belCostsFiles.toFile())

	val rootEl = doc.rootElement
	val family = FamilyType.valueOf(rootEl.getChildText("family"))
	return rootEl.getChildren("bel").map {  belEl ->
		val idEl = belEl.getChild("id")
		val type = SiteType.valueOf(family, idEl.getChildText("site_type"))
		val name = idEl.getChildText("name")
		val cost = belEl.getChildText("cost").toDouble()
		BelId(type, name) to doublePool.add(cost)
	}.toMap()
}
