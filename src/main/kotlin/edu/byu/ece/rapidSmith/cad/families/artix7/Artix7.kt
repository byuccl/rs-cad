package edu.byu.ece.rapidSmith.cad.families.artix7

import edu.byu.ece.rapidSmith.RSEnvironment
import edu.byu.ece.rapidSmith.cad.cluster.*
import edu.byu.ece.rapidSmith.cad.cluster.site.*
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.*
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.configurations.*
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.prepackers.*
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.router.ClusterRouter
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.router.ClusterRouterFactory
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.rules.*
import edu.byu.ece.rapidSmith.cad.place.annealer.MoveValidator
import edu.byu.ece.rapidSmith.cad.place.annealer.SimulatedAnnealingPlacer
import edu.byu.ece.rapidSmith.cad.place.annealer.configurations.BondedIOBPlacerRule
import edu.byu.ece.rapidSmith.cad.place.annealer.configurations.MismatchedRAMBValidator
import edu.byu.ece.rapidSmith.design.NetType
import edu.byu.ece.rapidSmith.design.subsite.*
import edu.byu.ece.rapidSmith.device.*
import edu.byu.ece.rapidSmith.device.families.Artix7
import edu.byu.ece.rapidSmith.device.families.Artix7.SiteTypes.*
import edu.byu.ece.rapidSmith.interfaces.vivado.VivadoInterface
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import kotlin.streams.toList

private val family = Artix7.FAMILY_TYPE
private val partsFolder = RSEnvironment.defaultEnv().getPartFolderPath(family)

class SiteCadFlow {
//	var packer: Packer<SitePackUnit> = getSitePacker()
//	var placer: Placer<SiteClusterSite>? = null
//	var placer: RouteR? = null

	fun run(design: CellDesign, device: Device) {
		val packer = getSitePacker(device)
		@Suppress("UNCHECKED_CAST")
		val clusters = packer.pack(design) as List<Cluster<SitePackUnit, SiteClusterSite>>
		val placer = getGroupSAPlacer()
		placer.place(design, clusters)
		println(design)
	}

	companion object {
		@JvmStatic
		fun main(args: Array<String>) {
			val rscp = VivadoInterface.loadRSCP(args[0])
			val design = rscp.design
			val device = rscp.device
			design.unrouteDesignFull()
			design.unplaceDesign()
			design.leafCells.forEach { it.removePseudoPins() }
			design.nets.forEach { it.disconnectFromPins(
				it.pins.filter { it.isPseudoPin }) }
			val ciPins = design.gndNet.sinkPins
				.filter { it.cell.libCell.name == "CARRY4" }
				.filter { it.name == "CI"  }
			design.gndNet.disconnectFromPins(ciPins)
			SiteCadFlow().run(design, device)
			val rscpFile = Paths.get(args[0]).toFile()
			val tcp = rscpFile.absolutePath + rscpFile.nameWithoutExtension + ".tcp"
			println("writing to $tcp")
			VivadoInterface.writeTCP(tcp, design, device, rscp.libCells)
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

fun getGroupSAPlacer(): SimulatedAnnealingPlacer<SiteClusterSite> {
	return SimulatedAnnealingPlacer(
		SiteClusterGridFactory(),
		SiteGroupPlacementRegionFactory(),
		MoveValidator(listOf(
			MismatchedRAMBValidator(),
			BondedIOBPlacerRule()))
	)
}

private class SitePackerFactory(
	val device: Device,
	val packUnits: PackUnitList<SitePackUnit>,
	val belCosts: BelCostMap,
	val cellLibrary: CellLibrary
) {
	val ramMaker = RamMaker(cellLibrary)

	private val di0LutSourcePrepacker = DI0LutSourcePrepackerFactory(cellLibrary)
	private val lutFFPairPrepacker = Artix7LutFFPrepackerFactory(cellLibrary)
	private val lutramsPrepacker = LutramPrepackerFactory(ramMaker)

	private val mixing5And6LutPackRuleFactory = Mixing5And6LutsRuleFactory()
	private val d6LutUsedRamPackRuleFactory = D6LutUsedRamPackRuleFactory(ramMaker)
	private val ramFullyPackedPackRuleFactory = RamFullyPackedPackRuleFactory(ramMaker)
	private val ramPositionsPackRuleFactory = RamPositionsPackRuleFactory(ramMaker)
	private val reserveFFForSourcePackRuleFactory = ReserveFFForSourcePackRuleFactory(cellLibrary)
	private val carryChainLookAheadRuleFactory = CarryChainLookAheadRuleFactory(
		listOf("S[0]", "S[1]", "S[2]", "S[3]"),
		ramMaker.leafRamCellTypes,
		Artix7.SiteTypes.SLICEM
	)

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
		)

		val tbrc = TableBasedRoutabilityCheckerFactory(packUnit, ::slicePinMapper)
		val packRules = listOf(
			mixing5And6LutPackRuleFactory,
			reserveFFForSourcePackRuleFactory,
			carryChainLookAheadRuleFactory,
			RoutabilityCheckerPackRuleFactory(tbrc, packUnits)
		)
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
			lutramsPrepacker,
			SRLChainsPrepackerFactory(),
			ForcedRoutingPrepackerFactory(packUnit,
				packUnits.pinsDrivingGeneralFabric,
				packUnits.pinsDrivenByGeneralFabric, Artix7.SWITCHBOX_TILES)
		)

		val tbrc = TableBasedRoutabilityCheckerFactory(packUnit, ::slicePinMapper)

		val packRules = listOf(
			mixing5And6LutPackRuleFactory,
			reserveFFForSourcePackRuleFactory,
			ramFullyPackedPackRuleFactory,
			ramPositionsPackRuleFactory,
			d6LutUsedRamPackRuleFactory,
			RoutabilityCheckerPackRuleFactory(tbrc, packUnits)
		)
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
			listOf(possibleBelPins[0])
		}
		val packRules = listOf<PackRuleFactory>(
			RoutabilityCheckerPackRuleFactory(tbrc, packUnits)
		)
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
			cellLibrary["LUT6"],
			cellLibrary["RAMS32"],
			cellLibrary["RAMD32"],
			cellLibrary["RAMS64E"],
			cellLibrary["RAMD64E"],
			cellLibrary["SRL16E"],
			cellLibrary["SRLC16E"],
			cellLibrary["SRLC32E"]
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

			val cells = ArrayList(design.leafCells.toList())
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
			clusters: List<Cluster<SitePackUnit, *>>
		) {
			for (cluster in clusters) {
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
				"LUT6", "RAMS64E", "RAMD64E" -> { /* nothing */ }
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
				"RAMS32", "RAMD32" -> {
					val wa6pin = cell.attachPseudoPin("pseudoWA6", PinDirection.IN)
					val a6pin = cell.attachPseudoPin("pseudoA6", PinDirection.IN)
					vcc.connectToPin(wa6pin)
					vcc.connectToPin(a6pin)
				}
				else -> {
					error("unexpected type: ${cell.type}: ${cell.inputPins.joinToString { it.name }}")
				}
			}
		} else if (bel.name.matches(Regex("[A-D]5LUT"))) {
			when (cell.type) {
				"LUT1", "LUT2", "LUT3", "LUT4", "LUT5", "RAMS32", "RAMD32" -> { /* nada */ }
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
}

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

		releaseDIWires(wiresToInvalidate, sink.belPins, sink.cellPin!!, source.cellPin)
		return wiresToInvalidate
	} else {
		return emptySet()
	}
}

private val LUT_NAME_PATTERN = Regex("([A-D])([56])LUT")

private fun releaseDIWires(
	toInvalidate: MutableSet<Wire>, sinkBelPins: List<BelPin>,
	sinkCellPin: CellPin, sourcePin: CellPin?
) {
	for (sinkBelPin in sinkBelPins) {
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

	for ((net, rts) in routeTrees) {
		rts.forEach { v -> cluster.addNetRouteTree(net, v) }
	}
	belPinMap.forEach(cluster::setPinMapping)
}

private fun removeTileWires(routeTrees: Collection<ArrayList<RouteTree>>) {
	for (sourceTrees in routeTrees) {
		val newSourceTrees = ArrayList<RouteTree>()
		for (sourceTree in sourceTrees) {
			val treesToRemove = sourceTree.filter { it.wire is TileWire }
			if (sourceTree !in treesToRemove)
				newSourceTrees.add(sourceTree)
			for (rt in treesToRemove) {
				if (rt.isSourced)
					rt.sourceTree.removeConnection(rt.connection)
				for (sink in ArrayList(rt.sinkTrees)) {
					rt.removeConnection(sink.connection)
					if (sink !in treesToRemove) {
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

private fun slicePinMapper(pin: CellPin, bel: Bel): List<BelPin> {
	if (pin.isPseudoPin)
		return listOf(bel.getBelPin(pin.name.substring(6)))

	return when (pin.cell.libCell.name) {
		"LUT1" -> mapLutPin(pin, bel)
		"LUT2" -> mapLutPin(pin, bel)
		"LUT3" -> mapLutPin(pin, bel)
		"LUT4" -> mapLutPin(pin, bel)
		"LUT5" -> mapLutPin(pin, bel)
		"LUT6" -> mapLutPin(pin, bel)
		"RAMS32", "RAMS64E" -> mapRamsPin(pin, bel)
		"CARRY4" -> mapCarryPin(pin, bel)
		else -> {
			val possibleBelPins = pin.getPossibleBelPins(bel)!!
			check(possibleBelPins.size == 1)
			listOf(possibleBelPins[0])
		}
	}
}

private fun mapLutPin(pin: CellPin, bel: Bel): List<BelPin> {
	return listOf(bel.getBelPin("A${pin.name.last() - '0' + 1}")!!)
}

private fun mapRamsPin(pin: CellPin, bel: Bel): List<BelPin> {
	return pin.getPossibleBelPins(bel)
}

private fun mapCarryPin(pin: CellPin, bel: Bel): List<BelPin> {
	return when (pin.name) {
		"CI" -> listOf(bel.getBelPin("CIN"))
		else -> {
			val possibleBelPins = pin.getPossibleBelPins(bel)
			check(possibleBelPins.size == 1)
			listOf(possibleBelPins[0])
		}
	}
}

