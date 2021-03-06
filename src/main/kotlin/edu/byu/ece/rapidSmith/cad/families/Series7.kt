package edu.byu.ece.rapidSmith.cad.families

import edu.byu.ece.rapidSmith.RSEnvironment
import edu.byu.ece.rapidSmith.cad.cluster.*
import edu.byu.ece.rapidSmith.cad.cluster.site.*
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.*
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.configurations.*
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.prepackers.*
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.router.ClusterRouter
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.router.ClusterRouterFactory
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.router.PinMapper
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.rules.*
import edu.byu.ece.rapidSmith.cad.place.annealer.DefaultCoolingScheduleFactory
import edu.byu.ece.rapidSmith.cad.place.annealer.EffortLevel
import edu.byu.ece.rapidSmith.cad.place.annealer.MoveValidator
import edu.byu.ece.rapidSmith.cad.place.annealer.SimulatedAnnealingPlacer
import edu.byu.ece.rapidSmith.cad.place.annealer.configurations.BondedIOBPlacerRule
import edu.byu.ece.rapidSmith.cad.place.annealer.configurations.MismatchedRAMBValidator
import edu.byu.ece.rapidSmith.design.NetType
import edu.byu.ece.rapidSmith.design.subsite.*
import edu.byu.ece.rapidSmith.device.*
import edu.byu.ece.rapidSmith.device.families.FamilyInfos
import edu.byu.ece.rapidSmith.interfaces.vivado.VivadoInterface
import java.nio.file.Paths
import java.util.*
import kotlin.collections.HashSet
import kotlin.streams.toList

class SiteCadFlow {

    var placeTime: Long? = null
        private set

    var packTime: Long? = null
        private set

    var packerLoadTime: Long? = null
        private set

    fun pack(design: CellDesign, device: Device): List<Cluster<SitePackUnit, SiteClusterSite>> {
        val packer = getSitePacker(device)

        @Suppress("UNCHECKED_CAST")
        val clusters = packer.pack(design) as List<Cluster<SitePackUnit, SiteClusterSite>>

        return clusters
    }

    fun run(design: CellDesign, device: Device) {
        val startTime = System.currentTimeMillis()
        val packer = getSitePacker(device)
        val packerLoadTime = System.currentTimeMillis()
        @Suppress("UNCHECKED_CAST")
        val clusters = packer.pack(design) as List<Cluster<SitePackUnit, SiteClusterSite>>
        val packTime = System.currentTimeMillis()
        val placer = getGroupSAPlacer()
        placer.place(device, design, clusters)
        val placeTime = System.currentTimeMillis()
        this.packerLoadTime = packerLoadTime - startTime
        this.packTime = packTime - startTime
        this.placeTime = placeTime - packTime
        println(design)
    }

    fun prepDesign(design: CellDesign, device: Device) {
        design.unrouteDesignFull()
        design.unplaceDesign()
        design.leafCells.forEach { it.removePseudoPins() }
        design.nets.forEach { it.disconnectFromPins(
                it.pins.filter { it.isPseudoPin }) }
        val gndCiPins = design.gndNet.sinkPins
                .filter { it.cell.libCell.name == "CARRY4" }
                .filter { it.name == "CI"  }
        design.gndNet.disconnectFromPins(gndCiPins)
        val vccCiPins = design.vccNet.sinkPins
                .filter { it.cell.libCell.name == "CARRY4" }
                .filter { it.name == "CI"  }
        design.vccNet.disconnectFromPins(vccCiPins)
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            val rscp = VivadoInterface.loadRSCP(args[0], true)
            val design = rscp.design
            val device = rscp.device
            val flow = SiteCadFlow()
            flow.prepDesign(design, device)
            flow.run(design, device)
            val rscpFile = Paths.get(args[0]).toFile()
            val tcp = rscpFile.absoluteFile.parentFile.toPath().resolve("${rscpFile.nameWithoutExtension}.tcp")
            println("writing to $tcp")
            VivadoInterface.writeTCP(tcp.toString(), design, device, rscp.libCells, true)
        }
    }
}

fun getSitePacker(
        device: Device
): RSVPack<SitePackUnit> {
    val family = device.family
    val partsFolder = RSEnvironment.defaultEnv().getPartFolderPath(family)
    val cellLibraryPath = partsFolder.resolve("cellLibrary.xml")
    val belCostsPath = partsFolder.resolve("belCosts.xml")
    val packUnitsPath = partsFolder.resolve(device.partName + "_packunits_site.rpu")
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
                    BondedIOBPlacerRule())),
            DefaultCoolingScheduleFactory(EffortLevel.HIGH_H)
    )
}

private class SitePackerFactory(
        val device: Device,
        val packUnits: PackUnitList<SitePackUnit>,
        val belCosts: BelCostMap,
        val cellLibrary: CellLibrary
) {
    val family = device.family
    val ramMaker = RamMaker(cellLibrary)

    private val di0LutSourcePrepacker = DI0LutSourcePrepackerFactory(cellLibrary)
    private val lutFFPairPrepacker = Artix7LutFFPrepackerFactory(cellLibrary)
    private val lutramsPrepacker = LutramPrepackerFactory(ramMaker)

    private val mixing5And6LutPackRuleFactory = Mixing5And6LutsRuleFactory()
    private val d6LutUsedRamPackRuleFactory = D6LutUsedRamPackRuleFactory(ramMaker)
    private val ramFullyPackedPackRuleFactory = RamFullyPackedPackRuleFactory(ramMaker)
    private val ramPositionsPackRuleFactory = RamPositionsPackRuleFactory(ramMaker)
    private val reserveFFForSourcePackRuleFactory = ReserveFFForSourcePackRuleFactory(cellLibrary)
    private val mixingRamsAndLutsPackRuleFactory = MixingRamsAndLutsPackRuleFactory()
    private val carryChainLookAheadRuleFactory = CarryChainLookAheadRuleFactory(
            listOf("S[0]", "S[1]", "S[2]", "S[3]"),
            ramMaker.leafRamCellTypes,
            SiteType.valueOf(family, "SLICEM")
    )

    fun make(): RSVPack<SitePackUnit> {
        val packStrategies: Map<PackUnitType, PackStrategy<SitePackUnit>> = packUnits.map {
            val type = it.type
            val siteType = it.siteType

            val strategy = when (siteType) {
                SiteType.valueOf(family, "SLICEL") ->makeSliceLStrategy(packUnits, belCosts)
                SiteType.valueOf(family, "SLICEM") -> makeSliceMStrategy(packUnits, belCosts)
                SiteType.valueOf(family, "IOB33"),
                    SiteType.valueOf(family, "IOB33M"),
                    SiteType.valueOf(family, "IOB33S"),
                    SiteType.valueOf(family, "BUFG"),
                    SiteType.valueOf(family, "DSP48E1") ->
                    makeUncheckedStrategy(SitePackUnitType(siteType), packUnits, belCosts)
                else -> makeSingleBelStrategy(it, packUnits)
            }

            type to strategy
        }.toMap()

        val clusterFactory = SiteClusterFactory(
                packUnits, device, sharedTypes(family), compatibleTypes(family))
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
        val packUnit = packUnits.first { it.type == SitePackUnitType(SiteType.valueOf(family, "SLICEL")) }
        val cellSelector = SharedNetsCellSelector(false)
        val belSelector = ShortestRouteBelSelector(packUnit, belCosts)
        val prepackers = listOf<PrepackerFactory<SitePackUnit>>(
                lutFFPairPrepacker,
                di0LutSourcePrepacker,
                ForcedRoutingPrepackerFactory(
                        packUnit, packUnits.pinsDrivingGeneralFabric,
                        packUnits.pinsDrivenByGeneralFabric, FamilyInfos.get(family).switchboxTiles())
        )

        val tbrc = TableBasedRoutabilityCheckerFactory(packUnit, SlicePinMapper())
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
        val packUnit = packUnits.first { it.type == SitePackUnitType(SiteType.valueOf(family, "SLICEM")) }
        val cellSelector = SharedNetsCellSelector(false)
        val belSelector = ShortestRouteBelSelector(packUnit, belCosts)
        val prepackers = listOf<PrepackerFactory<SitePackUnit>>(
                lutFFPairPrepacker,
                di0LutSourcePrepacker,
                lutramsPrepacker,
                SRLChainsPrepackerFactory(),
                ForcedRoutingPrepackerFactory(packUnit,
                        packUnits.pinsDrivingGeneralFabric,
                        packUnits.pinsDrivenByGeneralFabric, FamilyInfos.get(family).switchboxTiles())
        )

        val tbrc = TableBasedRoutabilityCheckerFactory(packUnit, SlicePinMapper())

        val packRules = listOf(
                mixing5And6LutPackRuleFactory,
                reserveFFForSourcePackRuleFactory,
                mixingRamsAndLutsPackRuleFactory,
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
                        packUnits.pinsDrivenByGeneralFabric, FamilyInfos.get(family).switchboxTiles())
        )

        val packRules = listOf<PackRuleFactory>()
        return MultiBelPackStrategy(cellSelector, belSelector, prepackers, packRules)
    }

    private fun makeSingleBelStrategy(
            packUnit: PackUnit, packUnits: PackUnitList<*>
    ): PackStrategy<SitePackUnit> {
        val tbrc = TableBasedRoutabilityCheckerFactory(packUnit, object: PinMapper {
            override fun invoke(
                    cluster: Cluster<*, *>, pin: CellPin, bel: Bel,
                    existing: Map<CellPin, BelPin>
            ): List<BelPin> {
                val mapping = pin.findPinMapping(bel)!!
                // TODO mapping can actually have multiple pins
                // I'm just take the first right now since the routing of the second
                // should be a given
                return if (mapping.isNotEmpty()) mapping.take(1) else emptyList()
            }
        })
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
        val ffCells = setOf(
                cellLibrary["FDCE"],
                cellLibrary["FDRE"],
                cellLibrary["FDSE"],
                cellLibrary["FDPE"],
                cellLibrary["LDCE"],
                cellLibrary["LDPE"]
        )

        val routerFactory = object : ClusterRouterFactory<SitePackUnit> {
            val pfRouter = BasicPathFinderRouterFactory(
                    packUnits, SlicePinMapper(), ::wireInvalidator, 8)
            val directRouter = DirectPathClusterRouterFactory<SitePackUnit>(SlicePinMapper())
            val routers = LinkedHashMap<PackUnit, ClusterRouter<SitePackUnit>>()

            override fun get(packUnit: SitePackUnit): ClusterRouter<SitePackUnit> {
                val family = packUnit.site.tile.device.family
                return routers.computeIfAbsent(packUnit) {
                    when(packUnit.siteType) {
                        in FamilyInfos.get(family).sliceSites() -> pfRouter.get(packUnit)
                        in FamilyInfos.get(family).ioSites() -> pfRouter.get(packUnit)
                        else -> directRouter.get(packUnit)
                    }
                }
            }
        }

        override fun prepareDesign(design: CellDesign) {
            insertLutRoutethroughs(design)
            insertFFRoutethroughs(design)
        }

        /**
         * This identifies cases where both the CO[k] and O[k] pins on a CARRY4
         * drive non-FF input signals.  This results in contention for the *OUTMUX
         * to get both out of the slice.  In this case, a "permanent latch" is put
         * on the O[k] pin's output signal so the CO[k] pin can use the *OUTMUX.
         */
        private fun insertFFRoutethroughs(design: CellDesign) {
            val carry4 = cellLibrary["CARRY4"]

            val cells = ArrayList(design.leafCells.toList())
            cells.sortBy { it.name }
            for (cell in cells) {
                when (cell.libCell) {
                    carry4 -> {
                        for (i in 0..3) {
                            val copin = cell.getPin("CO[$i]")
                            val opin = cell.getPin("O[$i]")
                            if (copin.net == null || opin.net == null)
                                continue
                            if (doesNotDriveFFOrCarry4(copin) && doesNotDriveFFOrCarry4(opin)) {
                                insertFFRoutethrough(design, opin)
                            }
                        }
                    }
                }
            }

        }

        /**
         * Return true if this pin drives a flip flop's or latch's D input.
         * Also returns true if this pin is CO3 and it drives the CI pin of a CARRY4 cell.
         */
        private fun doesNotDriveFFOrCarry4(pin: CellPin): Boolean {
            val n = pin.net!!
            for (sp in n.sinkPins)
                if (sp.cell.libCell in ffCells && sp.name == "D")
                    return false
                else if (pin.name == "CO[3]" && sp.cell.libCell == cellLibrary["CARRY4"] && sp.name == "CI")
                    return false
            return true
        }


        /**
         * Insert FF pass-through for the specified pin
         */
        private fun insertFFRoutethrough(design: CellDesign, pin: CellPin) {
            val net = pin.net
            val cellName = design.getUniqueCellName("${net.name}-${pin.name}-pass")
            val netName = design.getUniqueNetName("${net.name}-${pin.name}-pass")
            val newCell = Cell(cellName, cellLibrary["LDCE"])
            design.addCell(newCell)
            val newNet = CellNet(netName, NetType.WIRE)
            design.addNet(newNet)

            net.disconnectFromPin(pin)
            net.connectToPin(newCell.getPin("Q"))

            newNet.connectToPin(pin)
            newNet.connectToPin(newCell.getPin("D"))

            val vccNet = design.getNet("RapidSmithGlobalVCCNet")
            val gndNet = design.getNet("RapidSmithGlobalGNDNet")
            assert(vccNet != null)
            assert(gndNet != null)
            vccNet.connectToPin(newCell.getPin("G"))
            vccNet.connectToPin(newCell.getPin("GE"))
            gndNet.connectToPin(newCell.getPin("CLR"))

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

            val cells = ArrayList(design.inContextLeafCells.toList())
            cells.sortBy { it.name }
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
                                ensurePrecedingLutA(design, cell.getPin("DI[0]"), cell.getPin("S[0]"))
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

        /*
 * Insert LUT pass-through for the specified pin
 */
        private fun insertRoutethrough(design: CellDesign, pin: CellPin) {
            val net = pin.net
            val cellName = design.getUniqueCellName("${net.name}-${pin.name}-pass")
            val netName = design.getUniqueNetName("${net.name}-${pin.name}-pass")
            val newCell = Cell(cellName, cellLibrary["LUT1"])
            newCell.properties.update("INIT", PropertyType.EDIF, "0x2'h2")
            design.addCell(newCell)
            net.disconnectFromPin(pin)
            net.connectToPin(newCell.getPin("I0"))

            val newNet = CellNet(netName, NetType.WIRE)
            design.addNet(newNet)
            newNet.connectToPin(pin)
            newNet.connectToPin(newCell.getPin("O"))
        }

        /**
         * Handle ALUT route-through case since it involves AX pin as well.
         * Assumption: this is only called when the CYINIT pin is using the AX pin.
         * Cases LUT6 + null, LUT5 + null, null + LUT6, null + LUT5, tot > 5
         */
        private fun ensurePrecedingLutA(design: CellDesign, di0Pin: CellPin, s0Pin: CellPin) {
            if (di0Pin.net == null)
                return
            ensurePrecedingLut(design, di0Pin)

            if (s0Pin.net == null)
                return

            val dCell = di0Pin.net.sourcePin.cell
            val sCell = s0Pin.net.sourcePin.cell
            if (pinCount(dCell, sCell)) {
                // Don't insert duplicates
                if (dCell != cellLibrary["LUT1"]) {
                    println("NOTE: inserting ALUT passthrough for DI[0] on cell ${di0Pin.cell}")
                    insertRoutethrough(design, di0Pin)
                }
                if (sCell != cellLibrary["LUT1"]) {
                    println("NOTE: inserting ALUT passthrough for S[0] on cell ${di0Pin.cell}")
                    insertRoutethrough(design, s0Pin)
                }
            }
        }

        /*
         * Count number of unique nets feeding the two LUT's
         */
        private fun pinCount(a: Cell, b: Cell): Boolean {
            val nets = HashSet<CellNet>()
            for (cp in a.inputPins)
                nets.add(cp.net)
            for (cp in b.inputPins)
                nets.add(cp.net)
            return (nets.size > 5)
        }


        /**
         * Inserts LUT routethroughs before pins that are not driven by LUTs and before pins that
         * are driven by a LUT that drives multiple pins.
         */
        private fun ensurePrecedingLut(design: CellDesign, pin: CellPin) {
            if (pin.isConnectedToNet) {
                val net = pin.net
                if (!net.isStaticNet) {
                    val sourcePin = net.sourcePin!!
                    if (sourcePin.cell.libCell !in lutCells) {
                        insertRoutethrough(design, pin)
                    }
                    else if (sourcePin.cell.libCell in lutCells && net.sinkPins.count() > 1) {
                        insertRoutethrough(design, pin)
                    }
                }
            }
        }

        override fun finish(
                design: List<Cluster<SitePackUnit, *>>
        ) {
            for (cluster in design) {
//				upgradeRAM32s(cluster)  Many of these can be upgraded to ram64s
                addPseudoPins(cluster)
                cluster.constructNets()
                finalRoute(routerFactory, cluster)
                addFracLutPseudoPins(cluster)
            }
        }
    }
}


fun addFracLutPseudoPins(cluster: Cluster<*, *>) {
    for (cell in cluster.cells) {
        val vcc = cell.design.vccNet
        val bel = cluster.getCellPlacement(cell)
        if (bel!!.name.matches(Regex("[A-D]6LUT"))) {
            when (cell.type) {
                "LUT6", "RAMS64E", "RAMD64E" -> { /* nothing */ }
                "LUT1", "LUT2", "LUT3", "LUT4", "LUT5" -> {
                    // If the corresponding 5LUT is also used, tie A6 to VCC
                    val bel5Lut = bel.name[0] + "5" + bel.name.substring(2)
                    if (cluster.isBelOccupied(bel.site.getBel(bel5Lut))) {
                        //cluster.setPinMapping()
                        val pin = cell.attachPseudoPin("pseudoA6", PinDirection.IN)
                        val belPin = bel.getBelPin("A6")
                        // Assume that vcc can be routed to this pin.
                        cluster.setPinMapping(pin, listOf(belPin))
                        vcc.connectToPin(pin)

                        // Add a route tree for this pin to the cluster's route tree map.
                        val reverseConns = belPin.wire.reverseWireConnections
                        assert (reverseConns.size == 1)
                        val sitePinWire = reverseConns.iterator().next().sinkWire
                        val rt = RouteTreeWithCost(sitePinWire)
                        rt.connect<RouteTreeWithCost>(sitePinWire.wireConnections.iterator().next())

                        if (cluster.routeTreeMap[vcc] == null) {
                            val list = ArrayList<RouteTree>()
                            list.add(rt)
                            cluster.addRouteTree(vcc, list)
                        }
                        else {
                            cluster.routeTreeMap[vcc]!!.add(rt)
                        }
                    }
                }
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
                "LUT1", "LUT2", "LUT3", "LUT4", "LUT5" -> { /* handled elsewhere */ }
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
        val family = site.tile.device.family

        if (site.type != SiteType.valueOf(family, "SLICEM"))
            return emptySet()

        val wiresToInvalidate = LinkedHashSet<Wire>()
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

private fun finalRoute(
        routerFactory: ClusterRouterFactory<SitePackUnit>,
        cluster: Cluster<SitePackUnit, *>
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
    removeOtherSiteWires(cluster.type, routeTrees.values)
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
                    rt.getParent<RouteTree>().disconnect(rt.connection)
                for (sink in ArrayList(rt.children)) {
                    rt.disconnect(sink.connection)
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

private fun removeOtherSiteWires(
        packUnit: SitePackUnit,
        routeTrees: Collection<ArrayList<RouteTree>>
) {
    val site = packUnit.site
    for (sourceTrees in routeTrees) {
        sourceTrees.retainAll { it.wire.site == site }
    }
}

private fun sharedTypes(
    family: FamilyType
): Map<SiteType, List<SiteType>> {
    return mapOf(
            SiteType.valueOf(family, "SLICEM") to listOf(SiteType.valueOf(family, "SLICEL")),
            SiteType.valueOf(family, "RAMBFIFO36E1") to listOf(SiteType.valueOf(family, "FIFO36E1"), SiteType.valueOf(family, "RAMB36E1"))
    )
}

private fun compatibleTypes(
        family: FamilyType
): Map<SiteType, List<SiteType>> {
    return mapOf(
            SiteType.valueOf(family, "SLICEL") to listOf(SiteType.valueOf(family, "SLICEM")),
            SiteType.valueOf(family, "FIFO36E1") to listOf(SiteType.valueOf(family, "RAMBFIFO36E1")),
            SiteType.valueOf(family, "RAMB36E1") to listOf(SiteType.valueOf(family, "RAMBFIFO36E1")),
            SiteType.valueOf(family, "BUFG") to listOf(SiteType.valueOf(family, "BUFGCTRL")),
            SiteType.valueOf(family, "IOB33") to listOf(SiteType.valueOf(family, "IOB33M"), SiteType.valueOf(family, "IOB33S"))
    )
}

private class SlicePinMapper : PinMapper {
    override fun invoke(
            cluster: Cluster<*, *>, pin: CellPin, bel: Bel,
            existing: Map<CellPin, BelPin>
    ): List<BelPin>? {
        if (pin.isPseudoPin)
            return listOf(bel.getBelPin(pin.name.substring(6)))

        return when (pin.cell.libCell.name) {
            "LUT1" -> mapLutPin(cluster, pin, bel,existing)
            "LUT2" -> mapLutPin(cluster, pin, bel,existing)
            "LUT3" -> mapLutPin(cluster, pin, bel,existing)
            "LUT4" -> mapLutPin(cluster, pin, bel,existing)
            "LUT5" -> mapLutPin(cluster, pin, bel,existing)
            "LUT6" -> mapLutPin(cluster, pin, bel,existing)
            "RAMS32", "RAMS64E" -> mapRamsPin(pin, bel)
            "CARRY4" -> mapCarryPin(pin, bel)
            else -> {
                val possibleBelPins = pin.findPinMapping(bel)!!
                possibleBelPins
            }
        }
    }
}

private fun mapLutPin(
        cluster: Cluster<*, *>, pin: CellPin, bel: Bel,
        existing: Map<CellPin, BelPin>
): List<BelPin>? {
    val site = bel.site
    val leName = bel.name[0]
    val lut6 = site.getBel(leName + "6LUT")
    val lut5 = site.getBel(leName + "5LUT")

    // Need special handling if both the LUT5 and LUT6 are occupied
    if (cluster.isBelOccupied(lut6) && cluster.isBelOccupied(lut5)) {
        if (pin.isPseudoPin)
            return listOf(bel.getBelPin("A6")!!)

        val cell = pin.cell
        val altBel = if (bel == lut6) lut5 else lut6

        // get the possible pins and their mapping to the other BELs pins
        val possibleBelPins = pin.getPossibleBelPins(bel)
                .associateBy { altBel.getBelPin(it.name) }
                .filterKeys { it != null }
                .filterValues { it != null } as MutableMap<BelPin, BelPin>

        // determine what nets map to what BEL pins.  A net may map to multiple pins.
        val nets = existing.entries
                .filter { it.value in possibleBelPins }
                .groupingBy { it.key.net }
                .aggregate { k, a: MutableList<BelPin>?, e, f ->
                    val list = if (f) ArrayList() else a!!
                    list.add(possibleBelPins[e.value]!!)
                    list
                }

        // determine which bel pins are already used by other pins on the cell
        // if a bel pin is already occupied by a pin on the cell of interest, that
        // bel pin is disqualified, even if it share the same net
        val usedPins = cell.inputPins
                .filter { it.isConnectedToNet }
                .map { existing[it] }
                .filterNotNull()

        // determine if the net already drives any pins on the bel
        val previous = nets[pin.net]
        if (previous != null) {
            for (bp in previous) {
                // search for an unused bel pin which is shared by the same net and return it
                if (bp !in usedPins) {
                    return listOf(bp)
                }
            }
        }

        // no available bel pins sharing the net.  Identify a new pin mapping
        val altUsedPins = nets.values.flatten()
        val belPins = possibleBelPins.values
                .filter { it !in usedPins }
                .filter { it !in  altUsedPins}
                .toMutableList()
        // make sure the LUT6 is in back to avoid using the A6 pin when both 5 and 6 LUT bels are used
        belPins.sortBy { it.name[1] }

        // No valid pins, return null to indicate an issue
        if (belPins.isEmpty())
            return null

        return listOf(belPins.get(0))
    } else {
        return listOf(bel.getBelPin("A${pin.name.last() - '0' + 1}")!!)
    }
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

private fun CellPin.findPinMapping(b: Bel): List<BelPin>? {
    val c = this.cell
    if (c.getType().startsWith("RAMB") || c.getType().startsWith("FIFO")) {
        // The limitation of following lines of code is that this cell is
        // already placed and so we know the bel.  In reality, you will
        // usually be asking the question regarding a potential cell placement
        // onto a  bel.
        val pm = PinMapping.findPinMappingForCell(c, b.fullName)
        if (pm == null) {
            throw IllegalArgumentException("No pin mapping found for ${c.type} -> ${b.name}")
        }
        return pm.pins[this.name]?.filter { it != "nc" }?.map { b.getBelPin(it)!! }
    } else {
        return this.getPossibleBelPins(b)
    }
}

