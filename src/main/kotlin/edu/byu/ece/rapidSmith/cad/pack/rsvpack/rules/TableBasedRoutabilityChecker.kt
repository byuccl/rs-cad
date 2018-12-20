package edu.byu.ece.rapidSmith.cad.pack.rsvpack.rules

import edu.byu.ece.rapidSmith.cad.cluster.*
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.CadException
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.router.PinMapper
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.rules.RoutingTable.SourcePinEntry
import edu.byu.ece.rapidSmith.design.NetType
import edu.byu.ece.rapidSmith.design.subsite.Cell
import edu.byu.ece.rapidSmith.design.subsite.CellNet
import edu.byu.ece.rapidSmith.design.subsite.CellPin
import edu.byu.ece.rapidSmith.design.subsite.LibraryPin
import edu.byu.ece.rapidSmith.device.*
import edu.byu.ece.rapidSmith.util.StackedHashMap
import java.util.*

/**
 * Determines routing feasibility for a cluster by comparing against entries in a
 * table containing all possible routing configurations for a cluster.
 *
 * @param cluster the cluster to be routed
 * @param pinGroups mapping of the BelPins in the cluster type to the associated PinGroup
 * @param packUnits the [PackUnitList] containing the pack units
 * @param preferredPin function mapping each cell pin to a preferred BelPin to simplify routing
 */
class TableBasedRoutabilityChecker(
	private val cluster: Cluster<*, *>,
	private val pinGroups: Map<BelPin, PinGroup>,
	private val packUnits: PackUnitList<*>,
	private val preferredPin: PinMapper
) : RoutabilityChecker {
	private val template: PackUnitTemplate = cluster.type.template

	// The determined mappings from BelPins to CellPins for quick lookup
	private val _bel2CellPinMap: StackedHashMap<BelPin, CellPin> = StackedHashMap()
	private val bel2CellPinMap: Map<BelPin, CellPin>
		get() = _bel2CellPinMap

	// The determined mappings from CellPins to BelPins for quick lookup
	private val _cell2BelPinMap: StackedHashMap<CellPin, List<BelPin>> = StackedHashMap()
	private val cell2BelPinMap: Map<CellPin, List<BelPin>>
		get() = _cell2BelPinMap

	// Map from the nets in the cluster to the possible sources.
	private val _netSources: StackedHashMap<CellNet, Source.Builder> = StackedHashMap()
	private val netSources: Map<CellNet, Source>
		get() = _netSources

	// Map from the nets in the cluster to the sinks that need to be reach.
	private val _netSinks: StackedHashMap<CellNet, Sinks.Builder> = StackedHashMap()
	private val netSinks: Map<CellNet, Sinks>
		get() = _netSinks

	// The current statuses of each pin group.
	private val pinGroupsStatuses: StackedHashMap<PinGroup, PinGroupStatus> = StackedHashMap()
	private val pinMapping: StackedHashMap<CellPin, BelPin> = StackedHashMap()

	// Convenience method for determining if pins drive/are driven by general fabric
	private val BelPin.drivesGeneralFabric: Boolean
		get() = this.template.drivesGeneralFabric

	private val BelPin.drivenByGeneralFabric: Boolean
		get() = this.template.drivenByGeneralFabric

	private val BelPinTemplate.drivesGeneralFabric: Boolean
		get() = this.name in packUnits.pinsDrivingGeneralFabric[id]!!

	private val BelPinTemplate.drivenByGeneralFabric: Boolean
		get() = this.name in packUnits.pinsDrivenByGeneralFabric[id]!!

	override fun check(changed: Collection<Cell>): RoutabilityResult {
		// initialize objects for any new nets added to the cluster
		initNewNets(changed)

		// update the info for the nets with the new source and sink objects
		// added to the cluster in this change
		if (!updateChangedNets(changed))
			return RoutabilityResult(Routability.INFEASIBLE, null)

		// identify the pin groups that have changed
		val changedGroups = getChangedPinGroups(changed)

		// check the groups.  returns whether a successful route was found
		val noInvalids = checkGroups(changedGroups)

		// if no successful route was found, exit here with an INFEASIBLE result
		if (!noInvalids)
			return RoutabilityResult(Routability.INFEASIBLE, null)

		// check the pin group statuses to determine if the table is only
		// conditionally valid.  the table is conditionally if any pin group
		// could only be routed conditionally
		val isConditional = pinGroupsStatuses.values
			.any { it.feasibility == Routability.CONDITIONAL }

		return if (isConditional) {
			// determine the cells the route is conditional on
			val conditionals = joinGroupConditionals()
			RoutabilityResult(Routability.CONDITIONAL, conditionals)
		} else {
			// an unconditional route exists for the configuration
			RoutabilityResult(Routability.VALID, null)
		}
	}

	// initializes the net info for each new net added to the cluster by this new
	// set of cells.  at this time, all pins in the newly created nets are
	// considered outside of the cluster.  the updateChangedNets method, which  is
	// called immediately after this method, will update the net info with any pins
	// that now exist in the cluster
	private fun initNewNets(changed: Collection<Cell>) {
		for (cell in changed) {
			for (pin in cell.pins) {
				if (pin.isConnectedToNet) {
					val net = pin.net
					if (net !in netSources) {
						initNetSource(net)
						initNetSinks(net)
					}
				}
			}
		}
	}

	// initializes the source pin info for a new net. the source at this stage is
	// treated as being outside the cluster
	private fun initNetSource(net: CellNet) {
		val source = Source.Builder()
		when {
			net.type == NetType.VCC -> {
				source.vcc = true
				source.drivesGeneralFabric = true
			}
			net.type == NetType.GND -> {
				source.gnd = true
				source.drivesGeneralFabric = true
			}
			else -> {
				val sourcePin = net.sourcePin
				val sourceCell = sourcePin.cell

				source.cellPin = sourcePin
				val sourceCluster = sourceCell.getCluster<Cluster<*, *>>()

				// source is placed outside the cluster
				if (sourceCluster != null && sourceCluster !== cluster) {
					initOutsideClusterSource(source, sourcePin)
				} else {
					// even if the source is placed, we are treating it as unplaced
					// right now
					initUnplacedSource(source, sourcePin)
				}
			}
		}

		_netSources[net] = source
	}

	private fun initOutsideClusterSource(source: Source.Builder, sourcePin: CellPin) {
		val sourceCell = sourcePin.cell
		val bel = sourceCell.locationInCluster!!

		// The source cell has already been placed so we know where it is and
		// where it enters this cluster.
		val belPins = sourcePin.getPossibleBelPins(bel)
		val belPin = belPins.first()
		val endSiteIndex = bel.site.index

		source.isPlaced = true
		source.drivesGeneralFabric = belPin.drivesGeneralFabric

		template.directSourcesOfCluster
			.filter { endSiteIndex == it.endSiteIndex && it.endPin == belPin.template }
			.mapTo(source.sourceWires) { it.clusterExit }
	}

	// TODO this creates a union of all possible sources.  In reality only one
	// source can be actual.  More realistically, we need to treat each source
	// individually and verify that the group works for the source.  This could
	// be tricky.  So far though, this has been a non-issue.
	private fun initUnplacedSource(
		source: Source.Builder, sourcePin: CellPin
	) {
		// Possible sources contains the BelPinTemplates of all BelPins that can
		// potentially be the source of this net.
		val possibleSources = sourcePin.getPossibleSources()

		source.drivesGeneralFabric = possibleSources.any { it.drivesGeneralFabric }

		template.directSourcesOfCluster
			.filter { it.endPin in possibleSources }
			.mapTo(source.sourceWires) { it.clusterExit }
	}

	private fun CellPin.getPossibleSources(): List<BelPinTemplate> {
		return this.getPossibleBelPinsUnplaced()
	}

	/**
	 * Get the templates of all BelPins in the different pack units that this cell
	 * could potentially be placed on.  This value is lazily computed and then cached.
	 */
	private fun CellPin.getPossibleBelPinsUnplaced(): List<BelPinTemplate> {
		return possiblePins.computeIfAbsent(libraryPin) { _ ->
			val compatibleBels = this.cell.libCell.possibleAnchors
			packUnits.flatMap { it.template.bels }
				.filter { it.id in compatibleBels }
				.flatMap { bel -> this.getPossibleBelPinNames(bel.id).map { bel to it } }
				.map { (bel, pin) -> bel.template.getPinTemplate(pin) }
		}
	}

	// Just create an object.  The sinks will be built when a source is added
	// into the cluster.
	private fun initNetSinks(net: CellNet) {
		_netSinks[net] = Sinks.Builder()
	}

	/**
	 * Move all of the pins affected by this change from unused to being within
	 * the cluster
	 */
	private fun updateChangedNets(changed: Collection<Cell>): Boolean {
		for (cell in changed) {
			for (pin in cell.pins) {
				if (pin.isConnectedToNet) {
					if (pin.isInpin) {
						if (!updateSinkPin(pin))
							return false
					}
					if (pin.isOutpin) {
						if (!updateSourcePin(pin))
							return false
					}
				}
			}
		}
		return true
	}

	/** Relocates [sinkPin] from outside the cluster to inside the cluster. */
	private fun updateSinkPin(sinkPin: CellPin): Boolean {
		val net = sinkPin.net

		// get the Sinks object.  create a new copy if the object is from an
		// earlier route
		var sinks = _netSinks[net]!!
		if (!sinks.isCurrent) {
			sinks = Sinks.Builder(sinks)
			_netSinks[net] = sinks
		}

		// remove the pin from the conditionals
		sinks.conditionalMustLeaves.remove(sinkPin)
		sinks.requiredCarryChains.remove(sinkPin)
		sinks.conditionals.remove(sinkPin)

		// update info on the sinkpin
		val sinkCell = sinkPin.cell
		val sinkBel = sinkCell.locationInCluster!!
		val belPins = preferredPin(cluster, sinkPin, sinkBel, pinMapping) ?:
			return false
		sinks.sinkPinsInCluster += sinkPin
		belPins.forEach { _bel2CellPinMap[it] = sinkPin }
		_cell2BelPinMap[sinkPin] = belPins

		if (belPins.isNotEmpty()) {
			pinMapping[sinkPin] = belPins[0]
		}
		return true
	}

	private fun CellPin.getPossibleSinks(): List<BelPinTemplate> {
		// Possible sources contains the BelPinTemplates of all BelPins that can
		// potentially be the source of this net.
		return getPossibleBelPinsUnplaced()
	}

	/**
	 * Updates the source pin info for sourcePin.  The pin is moved into the
	 * cluster and information about its source is updated.  Also, at this time
	 * all information about the sinks of the pin, if not already computed, is
	 * computed.
	 */
	private fun updateSourcePin(sourcePin: CellPin): Boolean {
		val net = sourcePin.net
		assert(!net.isStaticNet)

		var source = _netSources[net]!!
		if (!source.isCurrent) {
			source = Source.Builder(source)
			_netSources[net] = source
		}

		// clear the old source info
		source.sourceWires.clear()

		// update the placement of the source
		assert(!source.isPlaced)
		source.isPlaced = true
		val sourceCell = sourcePin.cell
		val bel = sourceCell.locationInCluster

		val belPins = sourcePin.getPossibleBelPins(bel)
		assert(belPins.size == 1)
		val belPin = belPins[0]
		source.belPin = belPin
		source.drivesGeneralFabric = belPin.drivesGeneralFabric
		_bel2CellPinMap[belPin] = sourcePin
		_cell2BelPinMap[sourcePin] = listOf(belPin)

		// update the sinks with external routes now
		val sinks = _netSinks[net]!!
		for (sinkPin in net.sinkPins) {
			val sinkCell = sinkPin.cell
			val sinkCluster = sinkCell.getCluster<Cluster<*, *>>()
			if (sinkCluster == null) {
				initUnplacedSinks(sinks, sinkPin)
			} else if (sinkCluster !== cluster) {
				initOutsideClusterSinks(sinks, sinkPin, sinkCell)
			}
		}
		return true
	}

	/**
	 * Update the info of an unplaced sink of a placed source.
	 */
	private fun initUnplacedSinks(sinks: Sinks.Builder, sinkPin: CellPin) {
		val possibleSinks = sinkPin.getPossibleSinks()
		val reachableFromGeneralFabric = possibleSinks
			.any { it.drivenByGeneralFabric }

		// identify any direct sinks paths
		var directSink = false
		val carryExits = LinkedHashSet<Wire>()
		for (dc in template.directSinksOfCluster) {
			if (dc.endPin in possibleSinks) {
				carryExits += dc.clusterExit
				directSink = true
			}
		}

		// place in the correct container
		if (reachableFromGeneralFabric && directSink) {
			sinks.optionalCarryChains[sinkPin] = carryExits
		} else if (directSink) {
			sinks.requiredCarryChains[sinkPin] = carryExits
		} else if (reachableFromGeneralFabric) {
			sinks.conditionalMustLeaves += sinkPin
		} else {
			sinks.conditionals += sinkPin
		}
	}

	/**
	 * Update the info of a sink placed in another cluster.
	 */
	private fun initOutsideClusterSinks(
		sinks: Sinks.Builder, sinkPin: CellPin, sinkCell: Cell
	) {
		// The source cell has already been placed so we know where it is and
		// where it enters this cluster.
		val sinkBel = sinkCell.locationInCluster!!
		val belPins = preferredPin(sinkCell.getCluster()!!, sinkPin, sinkBel, emptyMap()) ?:
			throw CadException("Illegal pin mapping, $sinkPin")
		val endSiteIndex = sinkBel.site.index

		if (belPins.isNotEmpty()) {
			pinMapping[sinkPin] = belPins[0]
		}

		for (belPin in belPins) {
			// find any direct connections to this path
			var directSink = false
			val carrySinks = LinkedHashSet<Wire>()
			for (dc in template.directSinksOfCluster) {
				if (endSiteIndex == dc.endSiteIndex && dc.endPin == belPin.template) {
					carrySinks.add(dc.clusterExit)
					directSink = true
				}
			}

			// place in the correct location
			val drivenGenerally = belPin.drivenByGeneralFabric
			if (drivenGenerally && directSink) {
				sinks.optionalCarryChains[sinkPin] = carrySinks
			} else if (drivenGenerally) {
				sinks.mustLeave = true
			} else {
				sinks.requiredCarryChains[sinkPin] = carrySinks
			}
		}
	}

	private fun getChangedPinGroups(changed: Collection<Cell>): Set<PinGroup> {
		val changedGroups = LinkedHashSet<PinGroup>()
		for (cell in changed) {
			val pins = cell.pins
			for (pin in pins) {
				if (pin.isConnectedToNet) {
					cell2BelPinMap[pin]?.mapTo(changedGroups) { pinGroups[it]!! }
				} else if (isLUTOpin(pin)) {
					// special code indicating that changing one output LUT may affect
					// the validity of the other output on the LUT
					val bel = cell.locationInCluster
					val belPins = pin.getPossibleBelPins(bel)
					assert(belPins.size == 1)
					val belPin = belPins[0]
					changedGroups.add(pinGroups[belPin]!!)
				}
			}
		}
		return changedGroups
	}

	private fun isLUTOpin(cellPin: CellPin): Boolean {
		val cell = cellPin.cell
		val libCell = cell.libCell
		if (!libCell.isLut)
			return false
		return cellPin.isOutpin
	}

	/**
	 * Check each group looking for a valid route for each group.
	 */
	private fun checkGroups(groupsToCheck: Set<PinGroup>): Boolean {
		for (pg in groupsToCheck) {
			val tableRows = pg.routingTable.rows

			val oldPgStatus = getPinGroupStatus(pg)!!
			val rowStatusList = ArrayList<RowStatus>()

			// Check against each entry in the routing table
			val (lastRowChecked, conditionalRowFound, validRowFound) =
				checkTableRows(tableRows, oldPgStatus, rowStatusList, pg)

			val feasibility = when {
				validRowFound -> Routability.VALID
				conditionalRowFound -> Routability.CONDITIONAL
				else ->
					// no need to update the status as it will just be reverted.
					// exit early here
					return false
			}

			// fill the remaining rows with their old values, these rows are not
			// checked, this is only needed if a valid row was found
			fillRemainingRows(lastRowChecked, tableRows, rowStatusList, oldPgStatus)

			// conditionals of the pin group are computed only when needed
			val pgStatus = PinGroupStatus(feasibility, rowStatusList)

			// update the status of the pin group
			pinGroupsStatuses[pg] = pgStatus
		}
		return true
	}

	private fun fillRemainingRows(
		lastRowChecked: Int, tableRows: List<RoutingTable.Row>,
		rowStatusList: ArrayList<RowStatus>, oldPgStatus: PinGroupStatus
	) {
		// fill out remaining rows by updating them with the previous entry
		var i = lastRowChecked
		while (i < tableRows.size) {
			rowStatusList += getRowStatus(oldPgStatus, i)
			i++
		}
	}

	/**
	 * Check the rows of the table one by one until a valid row is found or all
	 * rows are checked.
	 */
	private fun checkTableRows(
		tableRows: List<RoutingTable.Row>, oldPgStatus: PinGroupStatus,
		rowStatusList: ArrayList<RowStatus>, pg: PinGroup
	): CheckTableRowsResult {
		var conditionalRowFound = false
		var validRowFound = false

		var i = 0
		while (i < tableRows.size) {
			val row = tableRows[i]

			val oldStatus = getRowStatus(oldPgStatus, i)

			// if the old status of the row was infeasible, it will remain
			// infeasible.  just skip the row
			if (oldStatus.feasibility == Routability.INFEASIBLE) {
				rowStatusList += RowStatus(Routability.INFEASIBLE)
				i++
				continue
			}

			// check the row and if valid, quit early
			val rowStatus = checkRow(pg, row)
			rowStatusList += rowStatus
			if (rowStatus.feasibility == Routability.CONDITIONAL) {
				conditionalRowFound = true
			} else if (rowStatus.feasibility == Routability.VALID) {
				validRowFound = true
				i++
				break
			}
			i++
		}
		return CheckTableRowsResult(i, conditionalRowFound, validRowFound)
	}

	private fun getRowStatus(oldPgStatus: PinGroupStatus, i: Int): RowStatus {
		// null means this is the initial pin group status,
		// all rows initialize to valid
		if (oldPgStatus.rowStatuses == null)
			return RowStatus(Routability.VALID)

		return oldPgStatus.rowStatuses[i]
	}

	// get the current status of the pin group.  If the status hasn't been created
	// already, make a new status (all rows initialize to VALID)
	private fun getPinGroupStatus(pg: PinGroup): PinGroupStatus? {
		return pinGroupsStatuses[pg] ?:
			PinGroupStatus(Routability.VALID, null)
	}

	/**
	 * Check the status of a row of the pin group.  Searches for a valid solution
	 * to each sink and source pin from the pin group.  Terminates early if possible
	 */
	private fun checkRow(pg: PinGroup, row: RoutingTable.Row): RowStatus {
		val rowStatus = RowStatus()

		checkRowSinks(pg, row, rowStatus)
		if (rowStatus.feasibility == Routability.INFEASIBLE)
			return rowStatus

		checkRowSources(row, rowStatus)
		return rowStatus
	}

	private fun checkRowSinks(pg: PinGroup, row: RoutingTable.Row, rowStatus: RowStatus) {
		for ((belPin) in row.sinkPins) {
			assert(rowStatus.feasibility !== Routability.INFEASIBLE)

			// get the associated cell pin with this pin.  If the pin is not associated,
			// continue to the next pin
			val cellPin = bel2CellPinMap[belPin] ?: continue

			// check the feasibility for the sink pin
			val result = isRowValidForSink(pg, row, cellPin, belPin)

			// down grade the feasibility as necessary
			rowStatus.feasibility = rowStatus.feasibility.meet(result.status)

			// exit early if the sink cannot be routed to
			if (rowStatus.feasibility == Routability.INFEASIBLE)
				return

			if (result.status == Routability.CONDITIONAL) {
				// build the information about the conditionals
				val sourceCell = cellPin.net.sourcePin.cell
				val condMap = mapOf(sourceCell to setOf(result.conditionalSource!!))
				mergeConditionalsInRow(rowStatus, condMap)

				// exit early if the sink cannot be routed to
				if (rowStatus.feasibility == Routability.INFEASIBLE)
					return
			}

			if (rowStatus.feasibility != Routability.INFEASIBLE) {
				// check if the source is already being used
				val net = cellPin.net!!
				val sourceNet = rowStatus.claimedSources[result.claimedSource]
				if (sourceNet != null && sourceNet != net) {
					// the source is already being used by another net and this row
					// is therefore invalid
					rowStatus.feasibility = Routability.INFEASIBLE
					return // exit early
				} else {
					// claim this source preventing any other pins from
					// trying to use it
					rowStatus.claimedSources[result.claimedSource!!] = net
				}
			}
		}
	}

	private fun checkRowSources(row: RoutingTable.Row, rowStatus: RowStatus) {
		for ((belPin) in row.sourcePins) {
			// get the cell pin related to the current source BelPin
			// if the pin is not used, continue to the next BelPin
			val cellPin = bel2CellPinMap[belPin] ?: continue

			// perform the check
			val result = isRowValidForSource(row, cellPin, belPin)

			if (result.status == Routability.CONDITIONAL) {
				// check the row conditionals
				rowStatus.feasibility = Routability.CONDITIONAL
				mergeConditionalsInRow(rowStatus, result.conditionalSinks!!)
			} else if (result.status == Routability.INFEASIBLE) {
				rowStatus.feasibility = Routability.INFEASIBLE
			}

			// terminate early if the row is not valid, mergeConditionals can
			// cause a row to become invalid
			if (rowStatus.feasibility == Routability.INFEASIBLE)
				return
		}
	}

	/**
	 * Merge each of the conitional cells for a row to produce a single set of
	 * conditional build requirements
	 */
	private fun mergeConditionalsInRow(
		rowStatus: RowStatus, toAdd: Map<Cell, Collection<Bel>>
	) {
		for ((cell, bels) in toAdd) {
			if (cell in rowStatus.conditionals) {
				// if another cell is conditional on this one, the possible locations
				// for the cell is the intersection of the two requirements
				val prevBels = rowStatus.conditionals[cell]!!
				prevBels.retainAll(bels)

				// reducing the valid locations may indicate the row does not produce
				// a valid route
				if (prevBels.isEmpty())
					rowStatus.feasibility = Routability.INFEASIBLE
			} else {
				rowStatus.conditionals[cell] = LinkedHashSet(bels)
			}
		}
	}

	/**
	 * Determines if the sink is routable for this row.
	 */
	private fun isRowValidForSink(
		pg: PinGroup, tableRow: RoutingTable.Row, cellPin: CellPin, belPin: BelPin
	): IsRowValidForSinkReturn {
		val source = netSources[cellPin.net]!!
		val sourcePin = source.cellPin
		val entry = tableRow.sinkPins[belPin]!!

		var status = Routability.INFEASIBLE
		val claimedSource: Any?
		var conditionalSource: Bel? = null

		if (entry.sourcePin != null) {
			// the source for this pin is in the pin group
			val entryPin = entry.sourcePin
			claimedSource = entryPin

			if (source.isPlaced) {
				// valid if the source for this row matches the source for the net
				if (entryPin == source.belPin)
					status = Routability.VALID
			} else if (source.vcc) {
				// valid if the source is vcc and unoccupied
				if (entryPin in template.vccSources && !entryPin.bel.isOccupied())
					status = Routability.VALID
			} else if (source.gnd) {
				// valid if the source is gnd and unoccupied
				if (entryPin in template.gndSources && !entryPin.bel.isOccupied())
					status = Routability.VALID
			} else {
				// the source doesn't match the requirement.  can't be a valid route
				// but could be conditional if the source is not yet placed and can
				// go the the source BEL
				val sourceCell = sourcePin!!.cell
				val entrySourceBel = entry.sourcePin.bel

				// BEL must be unoccupied
				if (!cluster.isBelOccupied(entrySourceBel)) {
					val possibleBels = sourceCell.libCell.possibleAnchors

					// must be a valid location for the source
					if (entrySourceBel.id in possibleBels) {
						val possiblePins = sourcePin.getPossibleBelPins(entrySourceBel)
						if (entry.sourcePin in possiblePins) {
							status = Routability.CONDITIONAL
							conditionalSource = entrySourceBel
						}
					}
				}
			}
		} else if (entry.drivenByGeneralFabric) {
			// the source for this row comes from general routing
			claimedSource = entry.sourceClusterPin

			if (sourcePin != null && sourcePin.isInCluster()) {
				assert(source.belPin != null)

				// the source of this net is in this pin group,  this means the
				// source leaves the cluster and re-enters from another pin
				if (source.belPin in pg.sourcePins) {
					val sourcePinEntry = tableRow.sourcePins[source.belPin]!!
					if (sourcePinEntry.drivesGeneralFabric)
						status = Routability.VALID
				} else {
					// the source is in the cluster but a different pin group. in
					// this case, the source must drive general fabric to reach this
					// pin.  when checking the source, we'll confirm that the source
					// drives general fabric
					if (source.drivesGeneralFabric)
						status = Routability.VALID
				}
			} else {
				// we're coming from outside the cluster.  Let's just make sure the
				// source can reach general fabric
				if (source.drivesGeneralFabric)
					status = Routability.VALID
			}
		} else if (entry.sourceClusterPin != null) {
			// the source for this pin arrives on a direct connection.  check that
			// the source comes from a valid input for the net
			claimedSource = entry.sourceClusterPin
			if (entry.sourceClusterPin in source.sourceWires)
				status = Routability.VALID
			else if (source.vcc) {  // This is a bit hackish, but the CASCADEIN on the BRAMs seems to drive VCC
				status = Routability.VALID
			}
			else if (source.gnd) {  // This is a bit hackish - don't understand why we need them?
				status = Routability.VALID
			}
		} else {
			error("No source specified")
		}

		return IsRowValidForSinkReturn(status, claimedSource, conditionalSource)
	}

	/**
	 * Checks if the BEL is occupied in the cluster.  A BEL is occupied if the BEL is
	 * being used or, if it is a LUT, the corresponding LUT5/LUT6 pair does not prevent
	 * it from being used as a static source.
	 */
	private fun Bel.isOccupied(): Boolean {
		val belOccupied = cluster.isBelOccupied(this)
		if (belOccupied)
			return true

		val belName = name
		if (belName.endsWith("5LUT")) {
			// get the cell at the corresponding lut6 BEL
			val lut6Name = belName[0] + "6LUT"
			val lut6 = site.getBel(lut6Name)
			val cellAtLut6 = cluster.getCellAtBel(lut6) ?: return false

			// if the cell at the 6LUT uses all 6 inputs, then the 5LUT BEL is
			// occupied by the 6LUT cell.
			if (cellAtLut6.libCell.numLutInputs == 6)
				return true

			// checks that the cell placed here is not a lutram (I think)
			// a lutram would prevent this cell from being a static source
			if (!cellAtLut6.libCell.name.startsWith("LUT"))
				return true
		} else if (belName.endsWith("6LUT")) {
			// get the cell at the corresponding lut5 BEL
			val lut5Name = belName[0] + "5LUT"
			val lut5 = site.getBel(lut5Name)
			val cellAtLut5 = cluster.getCellAtBel(lut5) ?: return false

			// checks that the cell placed here is not a lutram (I think)
			// a lutram would prevent this cell from being a static source
			if (!cellAtLut5.libCell.name.startsWith("LUT"))
				return true
		}
		return false
	}

	private fun CellPin.isInCluster(): Boolean {
		return cell.getCluster<Cluster<*, *>>() === cluster
	}

	/**
	 * Checks if the row describes a legal routing configuration for the source pin,
	 * i.e. all sinks can be reached
	 */
	private fun isRowValidForSource(
		tableRow: RoutingTable.Row, cellPin: CellPin, belPin: BelPin
	): IsRowValidForSourceReturn {
		val entry = tableRow.sourcePins[belPin]!!
		val net = cellPin.net
		val sinks = netSinks[net]!!

		// stores the unplaced pins that must be packed into the cluster to be
		// routable
		val conditionals = LinkedHashSet<CellPin>()

		if (sinks.mustLeave) {
			/*
			 * pins exist outside the cluster accessible from general routing.
			 * check if a path to the general fabric exists.  if mustLeave is true,
			 * conditionalMustLeave pins are irrelevant as we need a path out the
			 * cluster anyway.
			 */
			if (!entry.drivesGeneralFabric)
				return IsRowValidForSourceReturn(Routability.INFEASIBLE, null)
		} else if (!sinks.conditionalMustLeaves.isEmpty()) {
			/*
			 * if the source does not drive general fabric, then these pins must are
			 * marked as conditional and a valid location must be found for them
			 */
			if (!entry.drivesGeneralFabric)
				conditionals += sinks.conditionalMustLeaves
		}

		conditionals.addAll(sinks.conditionals)

		// Check if the sink in the cluster is outside the pin group
		// and requires exiting and re-entering to reach.
		for (sinkInCluster in sinks.sinkPinsInCluster) {
			val sinkBelPins = cell2BelPinMap[sinkInCluster]!!
			for (sinkBelPin in sinkBelPins) {
				if (sinkBelPin !in tableRow.sinkPins) {
					// source must be able to exit cluster and sink must be able to come
					// into cluster via general fabric
					if (!(sinkBelPin.drivenByGeneralFabric && entry.drivesGeneralFabric))
						return IsRowValidForSourceReturn(Routability.INFEASIBLE, null)
				}
			}
		}

		// checks the carry chains
		for ((pin, wires) in sinks.requiredCarryChains) {
			if (Collections.disjoint(entry.drivenClusterPins, wires)) {
				val cell = pin.cell
				if (cell.getCluster<Cluster<*, *>>() == null) {
					conditionals.add(pin)
				} else {
					return IsRowValidForSourceReturn(Routability.INFEASIBLE, null)
				}
			}
		}

		// check the optional carry chain outputs if the source does not drive general fabric
		if (!entry.drivesGeneralFabric) {
			for ((pin, wires) in sinks.optionalCarryChains) {
				if (Collections.disjoint(entry.drivenClusterPins, wires)) {
					val cell = pin.cell
					if (cell.getCluster<Cluster<*, *>>() == null) {
						conditionals += pin
					} else {
						return IsRowValidForSourceReturn(Routability.INFEASIBLE, null)
					}
				}
			}
		}

		if (!conditionals.isEmpty()) {
			val (status, conditionalSinks) = checkConditionalSinks(entry, conditionals)
			return IsRowValidForSourceReturn(status, conditionalSinks)
		}
		return IsRowValidForSourceReturn(Routability.VALID, null)
	}

	/**
	 * Checks if a valid location exists for each possible sink
	 */
	private fun checkConditionalSinks(
		entry: SourcePinEntry, conditionals: Set<CellPin>
	): Pair<Routability, HashMap<Cell, List<Bel>>?> {
		// quick check to see if I should even bother checking
		if (entry.drivenSinks.size < conditionals.size)
			return Pair(Routability.INFEASIBLE, null)

		val conditionalSinks = LinkedHashMap<Cell, List<Bel>>()
		val connectedSinks = entry.drivenSinks
		for (sinkPin in conditionals) {
			val conditionalBels = getConditionalSinks(connectedSinks, sinkPin)

			if (conditionalBels.isEmpty())
				return Pair(Routability.INFEASIBLE, null)
			conditionalSinks[sinkPin.cell] = conditionalBels
		}
		return Pair(Routability.CONDITIONAL, conditionalSinks)
	}

	private fun getConditionalSinks(
		connectedSinks: List<BelPin>, sinkPin: CellPin
	): List<Bel> {
		val anchors = LinkedHashSet(sinkPin.cell.possibleLocations)
		val conditionals = LinkedHashSet<Bel>()
		for (belPin in connectedSinks) {
			val bel = belPin.bel
			if (isPossibleConditionalPinMapping(sinkPin, belPin, anchors))
				conditionals.add(bel)
		}

		return ArrayList(conditionals)
	}

	private fun isPossibleConditionalPinMapping(
		from: CellPin, to: BelPin, anchors: Set<BelId>
	): Boolean {
		return !cluster.isBelOccupied(to.bel) &&
			to.bel.id in anchors &&
			to in from.getPossibleBelPins(to.bel)
	}

	/**
	 * Joins the conditionals from each of the pin groups
	 */
	private fun joinGroupConditionals(): Map<Cell, Set<Bel>> {
		val conditionals = LinkedHashMap<Cell, HashSet<Bel>>()
		for (pgStatus in pinGroupsStatuses.values) {
			if (pgStatus.feasibility == Routability.VALID)
				continue

			if (pgStatus.conditionals == null)
				buildGroupConditionals(pgStatus)

			for ((key, value) in pgStatus.conditionals!!) {
				conditionals.computeIfAbsent(key) { HashSet() }.addAll(value)
			}
		}
		return conditionals
	}

	/**
	 * Build conditionals for the entire group.  This is a union of each of the
	 * row conditionals.
	 */
	private fun buildGroupConditionals(pgStatus: PinGroupStatus) {
		val conditionals = LinkedHashMap<Cell, HashSet<Bel>>()
		for (rowStatus in pgStatus.rowStatuses!!) {
			if (rowStatus.feasibility === Routability.INFEASIBLE)
				continue
			assert(rowStatus.feasibility === Routability.CONDITIONAL)
			assert(!rowStatus.conditionals.isEmpty())

			for ((key, value) in rowStatus.conditionals) {
				conditionals.computeIfAbsent(key) { HashSet() }.addAll(value)
			}
		}
		pgStatus.conditionals = conditionals
	}

	override fun checkpoint() {
		// indicate the values are now out of date and must be copied to be modified.
		// if not copied, changing the values can corrupt a previous checkpoint
		_netSinks.values.forEach { it.isCurrent = false }
		_netSources.values.forEach { it.isCurrent = false }

		_bel2CellPinMap.checkPoint()
		_cell2BelPinMap.checkPoint()
		_netSources.checkPoint()
		_netSinks.checkPoint()
		pinGroupsStatuses.checkPoint()
		pinMapping.checkPoint()
	}

	override fun rollback() {
		_bel2CellPinMap.rollBack()
		_cell2BelPinMap.rollBack()
		_netSources.rollBack()
		_netSinks.rollBack()
		pinGroupsStatuses.rollBack()
		pinMapping.rollBack()
	}

	companion object {
		private val possiblePins = LinkedHashMap<LibraryPin, List<BelPinTemplate>>()
	}
}

private class RowStatus(var feasibility: Routability = Routability.VALID) {
	val conditionals: MutableMap<Cell, MutableSet<Bel>> = LinkedHashMap()
	val claimedSources: MutableMap<Any, CellNet> = LinkedHashMap()
}

private data class PinGroupStatus(
	val feasibility: Routability,
	val rowStatuses: List<RowStatus>?
) {
	var conditionals: Map<Cell, Set<Bel>>? = null
}

/**
 * The possible sources for a net in the cluster.
 */
private abstract class Source {
	// Is the source cell placed?
	abstract val isPlaced: Boolean
	// Is the net vcc/gnd?
	abstract val vcc: Boolean
	abstract val gnd: Boolean
	// Can the source enter this cluster from the general routing fabric
	abstract val drivesGeneralFabric: Boolean
	// What BelPin in this cluster does this net start at
	abstract val belPin: BelPin?
	// What carry chain wires can this net come in on
	abstract val sourceWires: List<Wire>
	// What cell pin is associate with this net
	abstract val cellPin: CellPin?

	// separates the construction from the usage
	class Builder: Source {
		var isCurrent = true
		override var isPlaced: Boolean
		override var vcc: Boolean
		override var gnd: Boolean
		override var drivesGeneralFabric: Boolean
		override var belPin: BelPin?
		override val sourceWires: ArrayList<Wire>
		override var cellPin: CellPin?

		constructor(): super() {
			isPlaced = false
			vcc = false
			gnd = false
			drivesGeneralFabric = false
			belPin = null
			sourceWires = ArrayList()
			cellPin = null
		}

		constructor(other: Source) : super() {
			isPlaced = other.isPlaced
			vcc = other.vcc
			gnd = other.gnd
			drivesGeneralFabric = other.drivesGeneralFabric
			belPin = other.belPin
			sourceWires = ArrayList(other.sourceWires)
			cellPin = other.cellPin
		}
	}
}

/**
 * The different sinks in a net that must be reached.
 */
private abstract class Sinks {
	// Must this net leave to the general routing fabric (ie are there one or more
	// sinks on this net that are placed outside by accessible only via the general
	// routing fabric.
	abstract val mustLeave: Boolean
	// Sinks that are packed into this cluster.
	abstract val sinkPinsInCluster: List<CellPin>
	// Unplaced sinks that can be reached via general fabric
	abstract val conditionalMustLeaves: Set<CellPin>
	// Unplaced sinks that must be packed into this cluster
	abstract val conditionals: Set<CellPin>
	// Carry chains outside this cluster
	abstract val requiredCarryChains: Map<CellPin, Set<Wire>>
	// Carry Chains that may also be reached via general routing.  Is this guy used?
	abstract val optionalCarryChains: Map<CellPin, Set<Wire>>

	/** Builder class to separate usage from creation */
	class Builder: Sinks {
		var isCurrent = true
		override var mustLeave: Boolean
		override val sinkPinsInCluster: ArrayList<CellPin>
		override val conditionalMustLeaves: HashSet<CellPin>
		override val conditionals: HashSet<CellPin>
		override val requiredCarryChains: HashMap<CellPin, Set<Wire>>
		override val optionalCarryChains: HashMap<CellPin, Set<Wire>>

		constructor(): super() {
			mustLeave = false
			sinkPinsInCluster = ArrayList()
			conditionalMustLeaves = LinkedHashSet()
			conditionals = LinkedHashSet()
			requiredCarryChains = LinkedHashMap()
			optionalCarryChains = LinkedHashMap()
		}

		constructor(other: Sinks): super() {
			sinkPinsInCluster = ArrayList(other.sinkPinsInCluster)
			mustLeave = other.mustLeave
			conditionals = LinkedHashSet(other.conditionals)
			conditionalMustLeaves = LinkedHashSet(other.conditionalMustLeaves)
			requiredCarryChains = LinkedHashMap(other.requiredCarryChains)
			optionalCarryChains = LinkedHashMap(other.optionalCarryChains)
		}
	}
}


// return values
private data class CheckTableRowsResult(
	val lastRowChecked: Int,
	val conditionalRowFound: Boolean,
	val validRowFound: Boolean
)

private data class IsRowValidForSinkReturn(
	val status: Routability,
	val claimedSource: Any?,
	val conditionalSource: Bel?
)

private data class IsRowValidForSourceReturn(
	val status: Routability,
	val conditionalSinks: Map<Cell, List<Bel>>?
)

/**
 * Factory for creating a new checker.  The factory just requires the pack unit
 * for clusters being checked and the mapping of cell pins to preferred BelPins.
 */
class TableBasedRoutabilityCheckerFactory(
	packUnit: PackUnit,
	private val preferredPin: PinMapper
) : RoutabilityCheckerFactory {
	private val pinGroups = buildPinGroups(packUnit.template)

	override fun create(
		cluster: Cluster<*, *>, packUnits: PackUnitList<*>
	): RoutabilityChecker {
		return TableBasedRoutabilityChecker(
			cluster, pinGroups, packUnits, preferredPin)
	}
}
