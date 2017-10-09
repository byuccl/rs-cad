package edu.byu.ece.rapidSmith.cad.families.artix7

import edu.byu.ece.rapidSmith.RSEnvironment
import edu.byu.ece.rapidSmith.cad.cluster.*
import edu.byu.ece.rapidSmith.cad.cluster.site.*
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.*
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.configurations.*
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.prepackers.Artix7LutFFPrepackerFactory
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.prepackers.DI0LutSourcePrepackerFactory
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.prepackers.ForcedRoutingPrepackerFactory
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.router.ClusterRouter
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.router.ClusterRouterFactory
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.rules.RoutabilityCheckerPackRuleFactory
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.rules.TableBasedRoutabilityCheckerFactory
import edu.byu.ece.rapidSmith.cad.place.annealer.MoveValidator
import edu.byu.ece.rapidSmith.cad.place.annealer.SimulatedAnnealingPlacer
import edu.byu.ece.rapidSmith.cad.place.annealer.configurations.MismatchedRAMBValidator
import edu.byu.ece.rapidSmith.design.NetType
import edu.byu.ece.rapidSmith.design.subsite.*
import edu.byu.ece.rapidSmith.device.*
import edu.byu.ece.rapidSmith.device.families.Artix7
import edu.byu.ece.rapidSmith.device.families.Artix7.SiteTypes.*
import edu.byu.ece.rapidSmith.interfaces.vivado.VivadoInterface
import java.nio.file.Path
import java.util.*

private val family = Artix7.FAMILY_TYPE
private val partsFolder = RSEnvironment.defaultEnv().getPartFolderPath(family)

class SiteCadFlow {
//	var packer: Packer<SitePackUnit> = getSitePacker()
//	var placer: Placer<SiteClusterSite>? = null
//	var placer: RouteR? = null

	fun run(design: CellDesign, device: Device): ClusterDesign<SitePackUnit, SiteClusterSite> {
		val packer = getSitePacker(device)
		@Suppress("UNCHECKED_CAST")
		val packedDesign = packer.pack<ClusterDesign<SitePackUnit, SiteClusterSite>>(design)
		val placer = SimulatedAnnealingPlacer(
			SiteClusterGridFactory(device),
			MoveValidator(listOf(MismatchedRAMBValidator()))
		)
		placer.place(packedDesign, device)
		return packedDesign
	}

	companion object {
		@JvmStatic
		fun main(args: Array<String>) {
			val design = VivadoInterface.loadTCP(args[0]).design
			val device = design.device
			design.unplaceDesign()
			design.cells.forEach { it.removePseudoPins() }
			design.nets.forEach { it.disconnectFromPins(
				it.pins.filter { it.isPseudoPin }) }
			val ciPins = design.gndNet.sinkPins
				.filter { it.cell.libCell.name == "CARRY4" }
				.filter { it.name == "CI"  }
			design.gndNet.disconnectFromPins(ciPins)
			SiteCadFlow().run(design, device)
		}
	}
}

fun getSitePacker(
        device: Device,
        cellLibraryPath: Path = partsFolder.resolve("cellLibrary.xml"),
        belCostsPath: Path = partsFolder.resolve("belCosts.xml"),
        packUnitsPath: Path = partsFolder.resolve("packunits-site.rpu")
): RSVPack<SitePackUnit> {
	val packUnits = loadPackUnits<SitePackUnit>(packUnitsPath)
	val belCosts = loadBelCostsFromFile(belCostsPath)
	val cellLibrary = CellLibrary(cellLibraryPath)

	return SitePackerFactory(device, packUnits, belCosts, cellLibrary).make()
}

private class SitePackerFactory(
	val device: Device,
	val packUnits: PackUnitList<SitePackUnit>,
	val belCosts: BelCostMap,
	val cellLibrary: CellLibrary
) {
	private val di0LutSourcePrepacker = DI0LutSourcePrepackerFactory(cellLibrary)
	private val lutFFPairPrepacker = Artix7LutFFPrepackerFactory(cellLibrary)

	fun make(): RSVPack<SitePackUnit> {
		val packStrategies: Map<PackUnitType, PackStrategy<SitePackUnit>> = packUnits.map {
			val type = it.type
			val siteType = it.siteType

			val strategy = when (siteType) {
				SLICEL -> makeSliceLStrategy(packUnits, belCosts)
				SLICEM -> makeSliceMStrategy(packUnits, belCosts)
				IOB33, IOB33M, IOB33S, BUFG, DSP48E1 ->
					makeUncheckedStrategy(SitePackUnitType(siteType), packUnits, belCosts)
				else -> makeSingleBelStrategy(it, packUnits)
			}

			type to strategy
		}.toMap()

		val clusterFactory = SiteClusterFactory(
			packUnits, device, sharedTypes, compatibleTypes)
		return RSVPack(
			cellLibrary,
			clusterFactory,
			HighestPinCountSeedSelector(),
			packStrategies,
			Artix7PackingUtils(cellLibrary, packUnits),
			SiteClusterCostCalculator())
	}

	private fun makeSliceLStrategy(
		packUnits: PackUnitList<*>, belCosts: BelCostMap
	): PackStrategy<SitePackUnit> {
		val packUnit = packUnits.first { it.type == SitePackUnitType(SLICEL) }
		val cellSelector = SharedNetsCellSelector(false)
		val belSelector = ShortestRouteBelSelector(packUnit, belCosts)
		val prepackers = listOf<PrepackerFactory<SitePackUnit>>(
			lutFFPairPrepacker,
			di0LutSourcePrepacker,
			ForcedRoutingPrepackerFactory(
				packUnit, packUnits.pinsDrivingGeneralFabric,
				packUnits.pinsDrivenByGeneralFabric, Artix7.SWITCHBOX_TILES)
		) // TODO populate this list

		val tbrc = TableBasedRoutabilityCheckerFactory(packUnit, ::slicePinMapper)
		val packRules = listOf<PackRuleFactory>(
			RoutabilityCheckerPackRuleFactory(tbrc, packUnits)
		) // TODO populate this list
		return MultiBelPackStrategy(cellSelector, belSelector, prepackers, packRules)
	}

	private fun makeSliceMStrategy(
		packUnits: PackUnitList<*>, belCosts: BelCostMap
	): PackStrategy<SitePackUnit> {
		val packUnit = packUnits.first { it.type == SitePackUnitType(SLICEM) }
		val cellSelector = SharedNetsCellSelector(false)
		val belSelector = ShortestRouteBelSelector(packUnit, belCosts)
		val prepackers = listOf<PrepackerFactory<SitePackUnit>>(
			lutFFPairPrepacker,
			di0LutSourcePrepacker,
			ForcedRoutingPrepackerFactory(packUnit,
				packUnits.pinsDrivingGeneralFabric,
				packUnits.pinsDrivenByGeneralFabric, Artix7.SWITCHBOX_TILES)
		) // TODO populate this list

		val tbrc = TableBasedRoutabilityCheckerFactory(packUnit, ::slicePinMapper)

		val packRules = listOf<PackRuleFactory>(
			RoutabilityCheckerPackRuleFactory(tbrc, packUnits)
		) // TODO populate this list
		return MultiBelPackStrategy(cellSelector, belSelector, prepackers, packRules)
	}

	private fun makeUncheckedStrategy(
		type: PackUnitType, packUnits: PackUnitList<*>, belCosts: BelCostMap
	): MultiBelPackStrategy<SitePackUnit> {
		val packUnit = packUnits.first { it.type == type }
		val cellSelector = SharedNetsCellSelector(false)
		val belSelector = ShortestRouteBelSelector(packUnit, belCosts)
		val prepackers = listOf<PrepackerFactory<SitePackUnit>>(
			ForcedRoutingPrepackerFactory(packUnit, packUnits.pinsDrivingGeneralFabric,
				packUnits.pinsDrivenByGeneralFabric, Artix7.SWITCHBOX_TILES)
		)

		val packRules = listOf<PackRuleFactory>()
		return MultiBelPackStrategy(cellSelector, belSelector, prepackers, packRules)
	}

	private fun makeSingleBelStrategy(
		packUnit: PackUnit, packUnits: PackUnitList<*>
	): PackStrategy<SitePackUnit> {
		val tbrc = TableBasedRoutabilityCheckerFactory(packUnit) { p, b ->
			val possibleBelPins = p.getPossibleBelPins(b)
			check(possibleBelPins.size == 1)
			possibleBelPins[0]
		}
		val packRules = listOf<PackRuleFactory>(
			RoutabilityCheckerPackRuleFactory(tbrc, packUnits)
		) // TODO populate this list
		return SingleBelPackStrategy(packRules)
	}

	private class Artix7PackingUtils(
		val cellLibrary: CellLibrary,
		val packUnits: PackUnitList<SitePackUnit>
	) : PackingUtils<SitePackUnit>() {
		val lutCells = setOf(
			cellLibrary["LUT1"],
			cellLibrary["LUT2"],
			cellLibrary["LUT3"],
			cellLibrary["LUT4"],
			cellLibrary["LUT5"],
			cellLibrary["LUT6"]
		)

		val routerFactory = object : ClusterRouterFactory<SitePackUnit> {
			val pfRouter = BasicPathFinderRouterFactory(
				packUnits, ::slicePinMapper, ::wireInvalidator, 8)
			val directRouter = DirectPathClusterRouterFactory<SitePackUnit>(::slicePinMapper)
			val routers = HashMap<PackUnit, ClusterRouter<SitePackUnit>>()

			override fun get(packUnit: SitePackUnit): ClusterRouter<SitePackUnit> {
				return routers.computeIfAbsent(packUnit) {
					when(packUnit.siteType) {
						in Artix7.SLICE_SITES -> pfRouter.get(packUnit)
						in Artix7.IO_SITES -> pfRouter.get(packUnit)
						else -> directRouter.get(packUnit)
					}
				}
			}
		}

		override fun prepareDesign(design: CellDesign) {
			insertLutRoutethroughs(design)
		}

		/**
		 * BEL routethroughs cause issues with the routing feasibility checker.  This
		 * identifies portions of the design that rely on the routethroughs and adds a
		 * LUT in place of the routethrough allowing the packer to ignore LUT
		 * routethroughs in the circuit.
		 */
		private fun insertLutRoutethroughs(design: CellDesign) {
			val carry4 = cellLibrary["CARRY4"]
			val muxf7 = cellLibrary["MUXF7"]

			val cells = ArrayList(design.cells)
			for (cell in cells) {
				when (cell.libCell) {
					carry4 -> {
						for (i in 0..3) {
							val pin = cell.getPin("S[$i]")
							ensurePrecedingLut(design, pin)
						}
						let {
							val cyinit = cell.getPin("CYINIT")!!
							if (cyinit.isConnectedToNet && !cyinit.net.isStaticNet) {
								val di0 = cell.getPin("DI[0]")
								ensurePrecedingLut(design, di0)
							}
						}
					}
					muxf7 -> {
						for (i in 0..1) {
							val pin = cell.getPin("I$i")
							ensurePrecedingLut(design, pin)
						}
					}
				}
			}
		}

		/**
		 * Checks if the pin is driven by a LUT.  If not, inserts a pass-through LUT
		 * before the pin.
		 */
		private fun ensurePrecedingLut(design: CellDesign, pin: CellPin) {
			if (pin.isConnectedToNet) {
				val net = pin.net
				if (!net.isStaticNet) {
					val sourcePin = net.sourcePin!!
					if (sourcePin.cell.libCell !in lutCells) {
						val cellName = "${net.name}-${pin.name}-pass"
						val newCell = Cell(cellName, cellLibrary["LUT1"])
						newCell.properties.update("INIT", PropertyType.EDIF, "0x2'h2")
						design.addCell(newCell)
						net.disconnectFromPin(pin)
						net.connectToPin(newCell.getPin("I0"))

						val newNet = CellNet(cellName, NetType.WIRE)
						design.addNet(newNet)
						newNet.connectToPin(pin)
						newNet.connectToPin(newCell.getPin("O"))
					}
				}
			}
		}

		override fun finish(
			design: ClusterDesign<SitePackUnit, *>
		) {
			for (cluster in design.clusters) {
//				upgradeRAM32s(cluster)  Many of these can be upgraded to ram64s
				addPseudoPins(cluster)
				cluster.constructNets()
				finalRoute(routerFactory, cluster)
			}
		}
	}
}

private fun addPseudoPins(cluster: Cluster<*, *>) {
	for (cell in cluster.cells) {
		val vcc = cell.design.vccNet
		val bel = cluster.getCellPlacement(cell)
		if (bel!!.name.matches(Regex("[A-D]6LUT"))) {
			when (cell.type) {
				"LUT1", "LUT2", "LUT3", "LUT4", "LUT5" -> {
					val pin = cell.attachPseudoPin("pseudoA6", PinDirection.IN)
					vcc.connectToPin(pin)
				}
				"SRLC32E" -> {
					val pin = cell.attachPseudoPin("pseudoA1", PinDirection.IN)
					vcc.connectToPin(pin)
				}
				"SRLC16E", "SRL16E" -> {
					val a1pin = cell.attachPseudoPin("pseudoA1", PinDirection.IN)
					vcc.connectToPin(a1pin)
					val a6pin = cell.attachPseudoPin("pseudoA6", PinDirection.IN)
					vcc.connectToPin(a6pin)
				}
				else -> {
					error("unexpected type: ${cell.type}: ${cell.inputPins.joinToString { it.name }}")
				}
			}
		} else if (bel.name.matches(Regex("[A-D]6LUT"))) {
			when (cell.type) {
				"LUT1", "LUT2", "LUT3", "LUT4", "LUT5" -> { /* nada */ }
				"SRLC16E", "SRL16E" -> {
					val pin = cell.attachPseudoPin("pseudoA1", PinDirection.IN)
					vcc.connectToPin(pin)
				}
				else -> {
					error("unexpected type: ${cell.type}: ${cell.inputPins.joinToString { it.name }}")
				}
			}
		}
	}

	// TODO A1 pin on LUTs
}

// This is old code that was used to convert 32 bit rams to 64 bit rams
// It hasn't been upgraded to the new representation but is left here as
// reference for someone to implement it.  It provides a modest gain is
// designs with lots of LUTRAMs.
//
//private fun upgradeRAM32s(cluster: Cluster<*, *>) {
//	val cells = ArrayList(cluster.cells)
//	for (cell in cells) {
//		val libCell = cell.libCell
//		if (libCell.name.endsWith("RAM32")) {
//			if (cellCanBeUpgraded(cell, cluster)) {
//				val newLibCellName = libCell.name.substring(0, 5) + "64"
//				val newLibCell = cellLibrary.get(newLibCellName)
//				val design = cell.design
//				val newCell = cell.deepCopy(mapOf("type" to newLibCell))
//				val pinMap = getDirectPinMap(cell)
//				replaceCell(design, cluster, cell, newCell, pinMap)
//			}
//		}
//	}
//}
//
//private fun getDirectPinMap(cell: Cell): Map<String, String> {
//	val pinMap = HashMap<String, String>()
//	for (pin in cell.pins) {
//		pinMap.put(pin.name, pin.name)
//	}
//	return pinMap
//}
//
//private fun cellCanBeUpgraded(cell: Cell, cluster: Cluster<*, *>): Boolean {
//	val bel = cell.locationInCluster!!
//	if (bel.name.endsWith("6LUT")) {
//		val le = bel.name.get(0)
//		val lut5 = bel.site.getBel(le + "5LUT")
//		return !cluster.isBelOccupied(lut5)
//	}
//	return false
//}
//
//private fun replaceCell(
//	design: CellDesign, cluster: Cluster<*, *>,
//	oldCell: Cell, newCell: Cell, pinMap: Map<String, String>
//) {
//	assert(cluster.getPinMap().isEmpty())
//
//	val netMap = HashMap<CellPin, CellNet>()
//	val bel = oldCell.locationInCluster!!
//
//	val remap = intArrayOf(0, 0, 0, 0, 0, 0)
//	var pinsHaveChanged = false
//	for (oldPin in oldCell.pins) {
//		if (oldPin.isConnectedToNet) {
//			val net = oldPin.net
//			val newPinName = pinMap[oldPin.name]!!
//			val newPin = newCell.getPin(newPinName)!!
//			netMap.put(newPin, net)
//
//			val oldPinName = oldPin.name
//			if (oldPinName.matches("A[1-6]".toRegex())) {
//				val oldIndex = oldPinName.get(1) - '0'
//				val newIndex = newPinName[1] - '0'
//				remap[oldIndex - 1] = newIndex
//				pinsHaveChanged = pinsHaveChanged or (oldIndex != newIndex)
//			}
//		}
//	}
//
//	if (pinsHaveChanged) {
//		val type = newCell.libCell.name
//		val cfg = newCell.getPropertyValue(type) as LutConfig
//		cfg.remapPins(remap)
//	}
//
//	cluster.removeCell(oldCell)
//	oldCell.setCluster(null)
//	oldCell.locationInCluster = null
//	design.removeCell(oldCell)
//
//	design.addCell(newCell)
//	cluster.addCell(bel, newCell)
//	newCell.setCluster(cluster)
//	newCell.locationInCluster = bel
//	netMap.forEach { k, v -> v.connectToPin(k) }
//}

private fun wireInvalidator(
	packUnit: PackUnit,
	source: Source,
	sink: Terminal
): Set<Wire> {
	if (sink.isPinMapping()) {
		val cell = sink.cellPin!!.cell
		val site = cell.locationInCluster!!.site

		if (site.type != Artix7.SiteTypes.SLICEM)
			return emptySet()

		val wiresToInvalidate = HashSet<Wire>()
		wiresToInvalidate.add(site.getWire("intrasite:SLICEM/CDI1MUX.DI"))
		wiresToInvalidate.add(site.getWire("intrasite:SLICEM/BDI1MUX.DI"))
		wiresToInvalidate.add(site.getWire("intrasite:SLICEM/ADI1MUX.BDI1"))
		wiresToInvalidate.add(site.getWire("intrasite:SLICEM/CDI1MUX.DMC31"))
		wiresToInvalidate.add(site.getWire("intrasite:SLICEM/BDI1MUX.CMC31"))
		wiresToInvalidate.add(site.getWire("intrasite:SLICEM/ADI1MUX.BMC31"))

		releaseDIWires(wiresToInvalidate, sink.belPin!!, sink.cellPin!!, source.cellPin)
		return wiresToInvalidate
	} else {
		return emptySet()
	}
}

private val LUT_NAME_PATTERN = Regex("([A-D])([56])LUT")

private fun releaseDIWires(
	toInvalidate: MutableSet<Wire>, sinkBelPin: BelPin,
	sinkCellPin: CellPin, sourcePin: CellPin?
) {
	val sinkBel = sinkBelPin.bel
	val site = sinkBel.site
	if (sourcePin != null) {
		if (sinkBel.name.matches(LUT_NAME_PATTERN) && sinkBelPin.name == "DI1") {
			if (sourcePin.name == "MC31") {
				when (sinkBel.name) {
					"A6LUT", "A5LUT" -> toInvalidate.remove(
						site.getWire("intrasite:SLICEM/ADI1MUX.BMC31"))
					"B6LUT", "B5LUT" -> toInvalidate.remove(
						site.getWire("intrasite:SLICEM/BDI1MUX.CMC31"))
					"C6LUT", "C5LUT" -> toInvalidate.remove(
						site.getWire("intrasite:SLICEM/CDI1MUX.DMC31"))
				}
			}

			// TODO this code used to be wrapped checking if the cell was a ram,
			// but i don't see hav anything can get here without being a RAM (due to
			// the DI pin check)
//			val cellType = sinkCellPin.cell.type
//			// TODO list each of the RAM types here
//			if (cellType in setOf("SRLC32E", "SRLC16E")) {
			when (sinkBel.name) {
				"A6LUT", "A5LUT" -> toInvalidate.remove(
					site.getWire("intrasite:SLICEM/ADI1MUX.BDI1"))
				"B6LUT", "B5LUT" -> toInvalidate.remove(
					site.getWire("intrasite:SLICEM/BDI1MUX.DI"))
				"C6LUT", "C5LUT" -> toInvalidate.remove(
					site.getWire("intrasite:SLICEM/CDI1MUX.DI"))
			}
//			}
		}
	}
}

// Removes any static routes that don't actually drive anything
private fun trimUnsunkStaticNetRoutes(routes: Map<CellNet, ArrayList<RouteTree>>) {
	routes.forEach { net, sourceTrees ->
		if (net.isStaticNet) {
			sourceTrees.removeIf { sourceTree -> !driveSink(sourceTree) }
		}
	}
}

private fun driveSink(sourceTree: RouteTree): Boolean {
	return sourceTree.any { it.wire.terminal != null }
}

private fun <T: PackUnit> finalRoute(
	routerFactory: ClusterRouterFactory<T>, cluster: Cluster<T, *>
) {
	// Reached the end of clustering, verify it and choose
	// whether to commit it or roll it back
	val router = routerFactory.get(cluster.type)
	val result = router.route(cluster)
	if (!result.success)
		throw CadException("Final route failed: ${cluster.name}")

	val routeTreeMap = result.routeTreeMap
	val belPinMap = result.belPinMap.values
		.flatMap { e -> e.map { it.toPair() } }
		.toMap()

	val routeTrees = routeTreeMap.mapValues { ArrayList(it.value) }
	removeTileWires(routeTrees.values)
	trimUnsunkStaticNetRoutes(routeTrees)

	for ((net, rts) in routeTreeMap) {
		rts.forEach { v -> cluster.addNetRouteTree(net, v) }
	}
	belPinMap.forEach(cluster::setPinMapping)
}

private fun removeTileWires(routeTrees: Collection<ArrayList<RouteTree>>) {
	for (sourceTrees in routeTrees) {
		val newSourceTrees = ArrayList<RouteTree>()
		for (sourceTree in sourceTrees) {
			val treesToRemove = ArrayList<RouteTree>()
			for (rt in sourceTree) {
				if (rt.wire is TileWire)
					treesToRemove.add(rt)
			}
			if (!treesToRemove.contains(sourceTree))
				newSourceTrees.add(sourceTree)
			for (rt in treesToRemove) {
				if (rt.isSourced)
					rt.sourceTree.removeConnection(rt.connection)
				for (sink in ArrayList(rt.sinkTrees)) {
					rt.removeConnection(sink.connection)
					if (!treesToRemove.contains(sink)) {
						newSourceTrees.add(sink)
					}
				}
			}
		}
		sourceTrees.clear()
		sourceTrees.addAll(newSourceTrees)
	}
}

private val sharedTypes = mapOf(
	SLICEM to listOf(SLICEL),
	RAMBFIFO36E1 to listOf(FIFO36E1, RAMB36E1)
)

private val compatibleTypes = mapOf(
	SLICEL to listOf(SLICEM),
	FIFO36E1 to listOf(RAMBFIFO36E1),
	RAMB36E1 to listOf(RAMBFIFO36E1),
	BUFG to listOf(BUFGCTRL),
	IOB33 to listOf(IOB33M, IOB33S)
)

private fun slicePinMapper(pin: CellPin, bel: Bel): BelPin {
	if (pin.isPseudoPin)
		return bel.getBelPin(pin.name.substring(6))

	return when (pin.cell.libCell.name) {
		"LUT1" -> mapLutPin(pin, bel)
		"LUT2" -> mapLutPin(pin, bel)
		"LUT3" -> mapLutPin(pin, bel)
		"LUT4" -> mapLutPin(pin, bel)
		"LUT5" -> mapLutPin(pin, bel)
		"LUT6" -> mapLutPin(pin, bel)
		"CARRY4" -> mapCarryPin(pin, bel)
		else -> {
			val possibleBelPins = pin.getPossibleBelPins(bel)!!
			check(possibleBelPins.size == 1)
			possibleBelPins[0]
		}
	}
}

private fun mapLutPin(pin: CellPin, bel: Bel): BelPin {
	return bel.getBelPin("A${pin.name.last() - '0' + 1}")!!
}

private fun mapCarryPin(pin: CellPin, bel: Bel): BelPin {
	return when (pin.name) {
		"CI" -> bel.getBelPin("CIN")
		else -> {
			val possibleBelPins = pin.getPossibleBelPins(bel)
			check(possibleBelPins.size == 1)
			possibleBelPins[0]
		}
	}
}

