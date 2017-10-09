//package edu.byu.ece.rapidSmith.cad.packing.rsvpack.rules
//
//import edu.byu.ece.rapidSmith.cad.cluster.Cluster
//import edu.byu.ece.rapidSmith.cad.cluster.getCluster
//import edu.byu.ece.rapidSmith.cad.cluster.isValid
//import edu.byu.ece.rapidSmith.cad.cluster.locationInCluster
//import edu.byu.ece.rapidSmith.cad.packing.rsvpack.PackRule
//import edu.byu.ece.rapidSmith.cad.packing.rsvpack.PackRuleFactory
//import edu.byu.ece.rapidSmith.cad.packing.rsvpack.PackStatus
//import edu.byu.ece.rapidSmith.design.subsite.*
//import edu.byu.ece.rapidSmith.device.Bel
//import edu.byu.ece.rapidSmith.device.Site
//import edu.byu.ece.rapidSmith.device.SiteType
//import java.util.*
//import java.util.stream.Collectors
//
///**
//
// */
//class LUTRAMValidityChecksFactory(cellLibrary: CellLibrary) : PackRuleFactory {
//	private val ramCellTypes = HashSet<LibraryCell>()
//
//	init {
//		ramCellTypes.add(cellLibrary.get("SPRAM32"))
//		ramCellTypes.add(cellLibrary.get("SPRAM64"))
//		ramCellTypes.add(cellLibrary.get("DPRAM32"))
//		ramCellTypes.add(cellLibrary.get("DPRAM64"))
//	}
//
//	fun init(design: CellDesign) {
//		val ramsMap = HashMap<String, Ram>()
//		for (cell in design.cells) {
//			if (ramCellTypes.contains(cell.libCell)) {
//				val ramGroup = cell.properties.getValue("\$RAMGROUP") as String
//				var ram: Ram? = ramsMap[ramGroup]
//				if (ram == null) {
//					ram = Ram()
//					ramsMap.put(ramGroup, ram)
//				}
//				ram.cells.add(cell as Cell)
//				cell.properties.update("RAM_CLASS", PropertyType.PACKING_PROPERTY, ram)
//			}
//		}
//	}
//
//	override fun make(cluster: Cluster<*, *>): PackRule {
//		return LUTRAMValidityChecks(cluster)
//	}
//
//	inner class LUTRAMValidityChecks(private val cluster: Cluster<*, *>) : PackRule {
//		private val lutRamsBels = HashMap<String, ArrayList<Bel>>()
//		private var state: State? = null
//		private val stack = ArrayDeque<State>()
//
//		init {
//			val template = cluster.packUnit.template
//			for (bel in template.bels) {
//				if (bel.site.type == SiteType.SLICEM) {
//					if (bel.name.matches(Regex("[A-D][5-6]LUT")))
//						lutRamsBels.computeIfAbsent(bel.name) { ArrayList<Bel>(2) }.add(bel)
//				}
//			}
//			state = State()
//			state!!.status = PackStatus.VALID
//		}
//
//		override fun validate(changedCells: Collection<Cell>): PackStatus {
//			stack.push(state)
//
//			// check LUT is placed at a valid location
//			val changedRamCells = changedCells
//				.filter { c -> ramCellTypes.contains(c.libCell) }
//
//			if (changedRamCells.isEmpty()) {
//				return state!!.status
//			}
//
//			updateState(changedRamCells)
//
//			if (!ensureValidRamPositions(changedRamCells))
//				return PackStatus.INFEASIBLE
//
//			var result = ensureRamsAreComplete()
//			state!!.status = result.status
//			state!!.conditionals = result.conditionals
//			if (state!!.status !== PackStatus.VALID)
//				return state!!.status
//
//			result = ensureDLutsUsed()
//			state!!.status = result.status
//			state!!.conditionals = result.conditionals
//
//			return state!!.status
//		}
//
//		private fun updateState(changedRamCells: List<Cell>) {
//			state = State()
//			state!!.status = PackStatus.VALID
//			state!!.conditionals = null
//			state!!.incompleteRams = HashSet(stack.peek().incompleteRams)
//			state!!.usedDLuts = HashMap(stack.peek().usedDLuts)
//
//			val rams = changedRamCells
//				.map { c -> c.properties.getValue("RAM_CLASS") as Ram }
//				.toSet()
//			state!!.incompleteRams.addAll(rams)
//			val completedRams = rams.filter { it.fullyPacked() }
//			state!!.incompleteRams.removeAll(completedRams)
//
//			for (cell in changedRamCells) {
//				val bel = cell.locationInCluster!!
//				val site = bel.site
//				val pair = SiteLutNumberPair(site,
//					Integer.parseInt(bel.name.substring(1, 2)))
//				state!!.usedDLuts.computeIfAbsent(pair) { false }
//				if (bel.name[0] == 'D')
//					state!!.usedDLuts.put(pair, true)
//			}
//		}
//
//		private fun ensureValidRamPositions(changedRamCells: List<Cell>): Boolean {
//			for (cell in changedRamCells) {
//				val location = cell.locationInCluster
//				val ramPosition = cell.properties.getValue("\$RAMPOSITION") as String
//				val locationName = location.getName()
//				assert(locationName.matches("[A-D][5-6]LUT".toRegex()))
//				if (ramPosition.indexOf(locationName[0]) == -1)
//					return false
//			}
//			return true
//		}
//
//		private fun ensureRamsAreComplete(): StatusConditionalsPair {
//			val conditionals: HashMap<Cell, Set<Bel>>
//			if (!state!!.incompleteRams.isEmpty()) {
//				conditionals = HashMap()
//				for (ram in state!!.incompleteRams) {
//					for (ramCell in ram.unpackedCells()) {
//						val possibleLocations = getPossibleLocations(ramCell)
//						if (possibleLocations.isEmpty())
//							return StatusConditionalsPair(PackStatus.INFEASIBLE, null)
//						conditionals.put(ramCell, possibleLocations)
//					}
//				}
//				return StatusConditionalsPair(PackStatus.CONDITIONAL, conditionals)
//			}
//			return StatusConditionalsPair(PackStatus.VALID, null)
//		}
//
//		private fun ensureDLutsUsed(): StatusConditionalsPair {
//			val unusedDLuts = state!!.usedDLuts
//				.filter { e -> !e.value }
//				.map { it.key }
//				.map { p -> p.site.getBel("D" + p.lutNumber + "LUT") }
//				.toSet()
//
//			if (unusedDLuts.isEmpty())
//				return StatusConditionalsPair(PackStatus.VALID, null)
//
//			val connectedRamCells = connectedRams
//
//			if (connectedRamCells.isEmpty())
//				return StatusConditionalsPair(PackStatus.INFEASIBLE, null)
//
//			val conditionals = HashMap<Cell, Set<Bel>>()
//			connectedRamCells.forEach { c -> conditionals.put(c, unusedDLuts) }
//			return StatusConditionalsPair(PackStatus.CONDITIONAL, conditionals)
//		}
//
//		private val connectedRams: Set<Cell>
//			get() {
//				val connectedCells = HashSet<Cell>()
//				for (cell in cluster.cells) {
//					for (pin in cell.pins) {
//						if (pin.isConnectedToNet && !isFilteredNet(pin.net)) {
//							for (o in pin.net.pins) {
//								if (pin === o)
//									continue
//								val oCell = o.cell as Cell
//								if (isValidRam(oCell))
//									connectedCells.add(oCell)
//							}
//						}
//					}
//				}
//				return connectedCells
//			}
//
//		private fun isFilteredNet(net: CellNet): Boolean {
//			return net.isClkNet || net.isStaticNet ||
//				net.pins.size > 100
//		}
//
//		private fun isValidRam(cell: Cell): Boolean {
//			return cell.isValid && cell.libCell in ramCellTypes &&
//				(cell.properties.getValue("\$RAMPOSITION") as String).indexOf('D') != -1
//
//		}
//
//		// TODO make getRAMPosiitoin an extension property
//		private fun getPossibleLocations(ramCell: Cell): Set<Bel> {
//			val possibles = HashSet<Bel>()
//			val locations = ramCell.properties.getValue("\$RAMPOSITION") as String
//			when (ramCell.libCell.name) {
//				"SPRAM32", "DPRAM32" -> {
//					run {
//						var ch = 'A'
//						while (ch <= 'D') {
//							if (locations.indexOf(ch) != -1)
//								possibles.addAll(lutRamsBels[ch + "5LUT"]!!)
//							ch++
//						}
//					}
//					var ch = 'A'
//					while (ch <= 'D') {
//						if (locations.indexOf(ch) != -1)
//							possibles.addAll(lutRamsBels[ch + "6LUT"]!!)
//						ch++
//					}
//				}
//				"SPRAM64", "DPRAM64" -> {
//					var ch = 'A'
//					while (ch <= 'D') {
//						if (locations.indexOf(ch) != -1)
//							possibles.addAll(lutRamsBels[ch + "6LUT"]!!)
//						ch++
//					}
//				}
//			}
//			return possibles
//		}
//
//		override val conditionals: Map<Cell, Set<Bel>>?
//			get() = state!!.conditionals
//
//		override fun revert() {
//			state = stack.pop()
//		}
//	}
//
//	private class State {
//		internal var status: PackStatus? = null
//		internal var usedDLuts: MutableMap<SiteLutNumberPair, Boolean> = HashMap()
//		internal var incompleteRams: MutableSet<Ram> = HashSet()
//		internal var conditionals: Map<Cell, Set<Bel>>? = null
//	}
//
//	private class Ram {
//		internal var cells = ArrayList<Cell>()
//
//		internal fun fullyPacked(): Boolean {
//			return cells.stream().allMatch { c -> c.getCluster<Cluster<*, *>>() != null }
//		}
//
//		internal fun unpackedCells(): List<Cell> {
//			return cells.filter { it.isValid }
//		}
//	}
//
//	private class SiteLutNumberPair(internal val site: Site, internal val lutNumber: Int) {
//
//		override fun equals(other: Any?): Boolean {
//			if (this === other) return true
//			if (other == null || javaClass != other.javaClass) return false
//			val that = other as SiteLutNumberPair?
//			return lutNumber == that!!.lutNumber && site == that.site
//		}
//
//		override fun hashCode(): Int {
//			return Objects.hash(site, lutNumber)
//		}
//
//		override fun toString(): String {
//			return "SiteLutNumberPair{" +
//				"site=" + site +
//				", lutNumber=" + lutNumber +
//				'}'
//		}
//	}
//
//}
//private class StatusConditionalsPair(
//	var status: PackStatus,
//	var conditionals: Map<Cell, Set<Bel>>
//)
