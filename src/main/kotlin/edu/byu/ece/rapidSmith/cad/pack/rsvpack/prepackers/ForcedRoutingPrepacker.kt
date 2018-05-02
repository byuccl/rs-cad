package edu.byu.ece.rapidSmith.cad.pack.rsvpack.prepackers

import edu.byu.ece.rapidSmith.cad.cluster.*
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.*
import edu.byu.ece.rapidSmith.design.subsite.Cell
import edu.byu.ece.rapidSmith.design.subsite.CellPin
import edu.byu.ece.rapidSmith.design.subsite.LibraryPin
import edu.byu.ece.rapidSmith.device.*
import edu.byu.ece.rapidSmith.util.getBelPinConnection
import edu.byu.ece.rapidSmith.util.getSitePinConnection
import edu.byu.ece.rapidSmith.util.getWireConnections
import java.util.*

class ForcedRoutingPrepackerFactory(
	packUnit: PackUnit,
	private val pinsDrivingGeneralFabric: Map<BelId, Set<String>>,
	private val pinsDrivenByGeneralFabric: Map<BelId, Set<String>>,
	switchboxTypes: Collection<TileType>
) : PrepackerFactory<PackUnit>() {
	private val sourcesOfSinks: Map<BelPin, List<BelPin>>
	private val sinksOfSources: Map<BelPin, List<BelPin>>

	init {
		val ccb = ClusterConnectionsBuilder(switchboxTypes)
		ccb.build(packUnit)
		sourcesOfSinks = ccb.sourcesOfSinks
		sinksOfSources = ccb.sinksOfSources
	}

	override fun make(): Prepacker<PackUnit> =
		ForcedRoutingPrepacker(
			pinsDrivingGeneralFabric,
			pinsDrivenByGeneralFabric,
			sourcesOfSinks, sinksOfSources)
}

class ForcedRoutingPrepacker(
	private val pinsDrivingGeneralFabric: Map<BelId, Set<String>>,
	private val pinsDrivenByGeneralFabric: Map<BelId, Set<String>>,
	private val sourcesOfSinks: Map<BelPin, List<BelPin>>,
	private val sinksOfSources: Map<BelPin, List<BelPin>>
) : Prepacker<PackUnit>() {
	private val drivenLibraryPins = HashMap<LibraryPin, Boolean>()
	private val drivingLibraryPins = HashMap<LibraryPin, Boolean>()

	override fun packRequired(
		cluster: Cluster<*, *>, changedCells: MutableMap<Cell, Bel>
	): PrepackStatus {
		var changesMade = true
		var prepackStatus = PrepackStatus.UNCHANGED
		while (changesMade) {
			changesMade = false
			var status = expandRequiredSinks(cluster, changedCells)
			prepackStatus = prepackStatus.meet(status)
			if (prepackStatus == PrepackStatus.INFEASIBLE)
				return PrepackStatus.INFEASIBLE

			status = expandRequiredSources(cluster, changedCells)
			prepackStatus = prepackStatus.meet(status)
			if (prepackStatus == PrepackStatus.INFEASIBLE)
				return PrepackStatus.INFEASIBLE
		}
		return prepackStatus
	}

	private fun expandRequiredSinks(
		cluster: Cluster<*, *>, packedCells: MutableMap<Cell, Bel>
	): PrepackStatus {
		var changed = false
		for (sourceCell in ArrayList(cluster.cells)) {
			for (sourceCellPin in sourceCell.outputPins) {
				if (!sourceCellPin.isConnectedToNet)
					continue

				for (sinkCellPin in sourceCellPin.net.pins) {
					//TODO: Possibly handle partition pins more intelligently.
					if (sinkCellPin === sourceCellPin || sinkCellPin.isPartitionPin)
						continue
					val sinkCell = sinkCellPin.cell as Cell
					val cellCluster = sinkCell.getCluster<Cluster<*, *>>()
					if (cellCluster != null)
						continue

					// means sinkBels can be reached from general fabric
					val sinkBels = getPossibleSinkBels(sourceCellPin, sinkCellPin, cluster) ?:
						continue

					// 0 bels could indicate a direct connection outside the cluster.
					if (sinkBels.isEmpty()) {
						return PrepackStatus.INFEASIBLE
					} else if (sinkBels.size == 1) {
						val sinkBel = sinkBels.iterator().next()
						var packStatus = PackStatus.INFEASIBLE
						if (sinkCell.isValid) {
							packStatus = addCellToCluster(cluster, sinkCell, sinkBel)
							changed = true
							if (packStatus != PackStatus.INFEASIBLE)
								packedCells[sinkCell] = sinkBel
						}
						if (packStatus != PackStatus.VALID)
							return PrepackStatus.INFEASIBLE
					}
				}
			}
		}

		return if (changed) PrepackStatus.CHANGED else PrepackStatus.UNCHANGED
	}

	// Null indicate the connections are possible through general fabric
	// Empty set indicates an invalid configuration
	// One element indicates a forced packing
	// More than one element indicates multiple possible packings
	private fun getPossibleSinkBels(
		sourceCellPin: CellPin, sinkCellPin: CellPin, candidate: Cluster<*, *>
	): Set<Bel>? {
		val template = candidate.type.template
		val sourceCell = sourceCellPin.cell as Cell
		val sourceBel = sourceCell.locationInCluster!!
		assert(sourceCellPin.getPossibleBelPinNames(sourceBel.id).size == 1)
		val belPinName = sourceCellPin.getPossibleBelPinNames(sourceBel.id).first()
		val sourceBelPin = sourceBel.getBelPin(belPinName)!!

		// Check for direct connections
		for (dc in template.directSinksOfCluster) {
			if (dc.clusterPin == sourceBelPin) {
				val possNames = sinkCellPin.getPossibleBelPinNames(dc.endPin.id)
				if (possNames.contains(dc.endPin.name))
					return null
			}
		}

		// Check if this connection is possible between sites
		val sourceDrivesFabric = sourceBelPin.drivesGeneralFabric
		if (sourceDrivesFabric && sinkCellPin.isDrivenByFabric)
			return null

		return sourceBelPin.sinks
			.filter { !candidate.isBelOccupied(it.bel) }
			.filter {
				val possibleSinkCellPins = sinkCellPin.getPossibleBelPinNames(it.bel.id)
				possibleSinkCellPins != null && possibleSinkCellPins.contains(it.name)
			}.map { it.bel }.toSet()
	}

	private val CellPin.isDrivenByFabric: Boolean
		get() {
			var driven = drivenLibraryPins[libraryPin]
			if (driven == null) {
				driven = computeDrivenByFabric()
				drivenLibraryPins[libraryPin] = driven
			}
			return driven
		}

	private fun CellPin.computeDrivenByFabric(): Boolean {
		return cell.libCell.possibleAnchors
			.flatMap { a ->
				val driven = pinsDrivenByGeneralFabric[a] ?: emptySet()
				getPossibleBelPinNames(a).map { driven to it!! } }
			.any { it.second in it.first }
	}

	private fun expandRequiredSources(
		candidate: Cluster<*, *>, packedCells: MutableMap<Cell, Bel>
	): PrepackStatus {
		var changed = false

		for (sinkCell in ArrayList(candidate.cells)) {
			for (sinkCellPin in sinkCell.inputPins) {
				if (!sinkCellPin.isConnectedToNet || sinkCellPin.net.isStaticNet)
					continue

				val sourceCellPin = sinkCellPin.net.sourcePin ?: continue

				// TODO: Handle partition pins more intelligently?
				if (sourceCellPin.isPartitionPin)
					continue

				val sourceCell = sourceCellPin.cell as Cell
				if (sourceCell.getCluster<Cluster<*, *>>() != null)
					continue

				// means pins can both reached from general fabric
				val sourceBels = getPossibleSourceBels(sinkCellPin, sourceCellPin, candidate) ?:
					continue

				// Zero bels could be a direct connection.  I'd like to figure out if a pin
				// can be a direct connection or not but I currently don't have this info yet.
				if (sourceBels.isEmpty()) {
					return PrepackStatus.INFEASIBLE
				} else if (sourceBels.size == 1) {
					val sourceBel = sourceBels.iterator().next()
					var packStatus = PackStatus.INFEASIBLE
					if (sourceCell.isValid) {
						packStatus = addCellToCluster(candidate, sourceCell, sourceBel)
						if (packStatus != PackStatus.INFEASIBLE)
							packedCells[sourceCell] = sourceBel
						changed = true
					}
					if (packStatus != PackStatus.VALID)
						return PrepackStatus.INFEASIBLE
				}
			}
		}

		return if (changed) PrepackStatus.CHANGED else PrepackStatus.UNCHANGED
	}

// Null indicate the connections are possible through general fabric
// Empty set indicates an invalid configuration
// One element indicates a forced packing
// More than one element indicates multiple possible packings
	private fun getPossibleSourceBels(
		sinkCellPin: CellPin, sourceCellPin: CellPin, candidate: Cluster<*, *>
	): Set<Bel>? {
		val template = candidate.type.template
		val sourceCell = sourceCellPin.cell
		val sinkCell = sinkCellPin.cell as Cell
		val sinkBel = sinkCell.locationInCluster!!
		val belPinNames = sinkCellPin.getPossibleBelPinNames(sinkBel.id)

		val sinkBelPins = belPinNames.map { sinkBel.getBelPin(it) }.toSet()

		// Check for direct connections
		for (dc in template.directSourcesOfCluster) {
			for (sinkBelPin in sinkBelPins) {
				if (dc.clusterPin == sinkBelPin) {
					val possNames = sourceCellPin.getPossibleBelPinNames(dc.endPin.id)
					if (possNames.contains(dc.endPin.name))
						return null
				}
			}
		}

		// test if the connection is possible between sites
		val sourceDrivesFabric = sourceCellPin.cellPinDrivesFabric()
		var sinkDrivenByFabric = false
		for (sinkBelPin in sinkBelPins)
			sinkDrivenByFabric = sinkDrivenByFabric or sinkBelPin.drivenByGeneralFabric
		if (sourceDrivesFabric && sinkDrivenByFabric)
			return null

		val compatibleSourceBels = sourceCell.libCell.possibleAnchors
		return sinkBelPins.flatMap {it.sources }
			.map { it.bel }
			.distinct()
			.filter { !candidate.isBelOccupied(it) }
			.filter { it.id in compatibleSourceBels }
			.toSet()
	}

	private fun CellPin.cellPinDrivesFabric(): Boolean {
		var drives = drivingLibraryPins[libraryPin]
		if (drives == null) {
			drives = computeDrivesFabric()
			drivingLibraryPins[libraryPin] = drives
		}
		return drives
	}

	private fun CellPin.computeDrivesFabric(): Boolean {
		for (belId in cell.libCell.possibleAnchors) {
			val possibleSourcePins = getPossibleBelPinNames(belId)
			assert(possibleSourcePins.size == 1)
			val sourcePin = possibleSourcePins.iterator().next()
			val drivers = pinsDrivingGeneralFabric[belId] ?: emptySet()
			val pinDrivesFabric = sourcePin in drivers

			if (pinDrivesFabric)
				return true
		}
		return false
	}

	private val BelPin.sources: List<BelPin>
		get() = sourcesOfSinks[this]!!

	private val BelPin.sinks: List<BelPin>
		get() = sinksOfSources[this]!!

	private val BelPin.drivesGeneralFabric: Boolean
		get() {
			val drivers = pinsDrivingGeneralFabric[bel.id] ?: return false
			return name in drivers
		}

	private val BelPin.drivenByGeneralFabric: Boolean
		get() {
			val driven = pinsDrivenByGeneralFabric[bel.id] ?: return false
			return name in driven
		}

}

/**
 *
 */
private class ClusterConnectionsBuilder(
	val SWITCHBOX_TYPES: Collection<TileType>
) {
	val sourcesOfSinks = HashMap<BelPin, List<BelPin>>()
	val sinksOfSources = HashMap<BelPin, List<BelPin>>()

	fun build(
		packUnit: PackUnit
	): ClusterConnectionsBuilder {
		val template = packUnit.template
		for (bel in template.bels) {
			for (sourcePin in bel.sources) {
				val sinks = traverse(sourcePin, true)
				sinksOfSources[sourcePin] = sinks
			}
			for (sinkPin in bel.sinks) {
				val sources = traverse(sinkPin, false)
				sourcesOfSinks[sinkPin] = sources
			}
		}
		return this
	}

	private fun traverse(
		sourcePin: BelPin, forward: Boolean
	): List<BelPin> {
		val connections = ArrayList<BelPin>()
		val sourceWire = sourcePin.wire
		val visited = HashSet<Wire>()

		val q: Queue<Wire> = ArrayDeque()
		q += sourceWire

		while (q.isNotEmpty()) {
			val wire = q.poll()
			if (!visited.add(wire))
				continue

			if (wire.tile.type in SWITCHBOX_TYPES)
				continue

			wire.getWireConnections(forward).forEach { q += it.sinkWire }
			wire.getSitePinConnection(forward)?.let { q += it.sinkWire }
			if (wire != sourceWire)
				wire.getBelPinConnection(forward)?.let { connections += it.pin }
		}

		return connections
	}
}

