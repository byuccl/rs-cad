package edu.byu.ece.rapidSmith.cad.pack.rsvpack

import edu.byu.ece.rapidSmith.cad.cluster.*
import edu.byu.ece.rapidSmith.cad.pack.Packer
import edu.byu.ece.rapidSmith.design.subsite.*
import edu.byu.ece.rapidSmith.device.Bel
import edu.byu.ece.rapidSmith.util.Offset
import java.util.*

/**
 * A packer for Xilinx FPGAs.
 *
 * @property cellLibrary library of cells used by this packer
 * @property seedSelector strategy for choosing seeds for cluster.  See [SeedSelector].
 * @property packStrategies strategies for packing the different clusters.  See [PackStrategy].
 * @property utils utils for performing preparation and wrap up before and after packing.  See [PackingUtils].
 * @property clusterCostCalculator tool to determine the cost of a cluster.  See [ClusterCostCalculator].
 * @param T type of [PackUnit]s used in this packer.
 */
class RSVPack<T: PackUnit>(
	val cellLibrary: CellLibrary,
	private val clusterFactory: ClusterFactory<T, *>,
	private val seedSelector: SeedSelector<T>,
	private val packStrategies: Map<PackUnitType, PackStrategy<T>>,
	private val utils: PackingUtils<T>,
	private val clusterCostCalculator: ClusterCostCalculator<T>
) : Packer<T> {
	override fun <D: ClusterDesign<T, *>> pack(design: CellDesign): D {
		// creates a new packer instance and packs the design.
		val packer = _RSVPack(clusterFactory, clusterCostCalculator,
			seedSelector, packStrategies, utils, design)
		@Suppress("UNCHECKED_CAST")
		return packer.pack() as D
	}
}

/**
 * Adds [cell] to the [cluster] at the specified location [anchor].
 */
fun <T: PackUnit> addCellToCluster(
	cluster: Cluster<T, *>, cell: Cell, anchor: Bel
): PackStatus {
	// check that the cell is compatible with the location and that the location
	// is empty in the cell
	val canSafelyAdd = cellCanBePlacedAt(cluster, cell, anchor)

	return if (canSafelyAdd) {
		// Add the cell to the cluster and update its information
		cluster.addCell(anchor, cell)

		// Update the cluster and location for the cell
		// Using the packing info property manually is more efficient
		val packingInfo = cell.packingInfo
		setCluster(cluster, packingInfo)
		packingInfo.locInCluster = anchor

		// mark the cell as packed
		packingInfo.isValid = false
		PackStatus.VALID
	} else {
		PackStatus.INFEASIBLE
	}
}

private class _RSVPack<T: PackUnit>(
	private val clusterFactory: ClusterFactory<T, *>,
	private val clusterCostCalculator: ClusterCostCalculator<T>,
	private val seedSelector: SeedSelector<T>,
	private val packStrategies: Map<PackUnitType, PackStrategy<T>>,
	private val utils: PackingUtils<T>,
	design: CellDesign
) {
	private val design = createClusterDesign<T>(design)
	private val unclusteredCells = HashSet<Cell>(
		(this.design.cellDesign.cells.size * 1.5).toInt())

	fun pack(): ClusterDesign<T, *> {
		init()
		packNetlist()
		cleanupClusters(design)
		return design
	}

	private fun init() {
		// perform any needed modifications to the design prior to packing
		utils.prepareDesign(design.cellDesign)

		// set the unclustered cells to all cells in the design
		unclusteredCells += this.design.cellDesign.cells
		unclusteredCells -= design.cellDesign.vccNet.sourcePin.cell
		unclusteredCells -= design.cellDesign.gndNet.sourcePin.cell

		// initialize the packing info for all cells in the design
		initCellPackingInformation()

		CarryChainFinder().findCarryChains(
			clusterFactory.supportedPackUnits, design.cellDesign)

		// Initialize all of the configurations
		packStrategies.values.forEach { it.init(design.cellDesign) }
		seedSelector.init(clusterFactory.supportedPackUnits, design.cellDesign)
		clusterFactory.init()
		clusterCostCalculator.init(clusterFactory)
	}

	private fun initCellPackingInformation() {
		unclusteredCells.forEach { it.initPackingInfo() }
		design.cellDesign.vccNet.sourcePin.cell.initPackingInfo()
		design.cellDesign.gndNet.sourcePin.cell.initPackingInfo()
	}

	private fun packNetlist() {
		var remainingCells = unclusteredCells.size
		println("Cells remaining to pack " + remainingCells)

		// do until all cells have been packed
		while (!unclusteredCells.isEmpty()) {
			if (unclusteredCells.size % 1000 > remainingCells % 1000)
				println("Cells remaining to pack " + unclusteredCells.size)
			remainingCells = unclusteredCells.size

			// choose a seed cell for a new cluster
			val seedCell = seedSelector.nextSeed()

			var best: Cluster<T, *>? = null
			for (type in clusterFactory.supportedPackUnits) {
				// make sure we have more of this type available
				if (!clusterOfTypeAvailable(type))
					continue

				// make a new cluster and pack it with the appropriate strategy
				// based on the cluster type
				val cluster = clusterFactory.createNewCluster(seedCell.name, type)
				val strategy = packStrategies[type.type] ?:
					throw CadException("No strategy for pack unit $type")
				val result = strategy.tryPackCluster(cluster, seedCell)

				if (result == PackStatus.VALID) {
					val cost = clusterCostCalculator.calculateCost(cluster)
					cluster.cost = cost
					if (best == null || cost < best.cost)
						best = cluster
				}
			}

			// if best was never set, all possible types failed
			if (best == null)
				throw CadException("No valid pack unit for clusterChain " + seedCell.name)

			// save the best cluster
			commitCluster(best)
		}
	}

	private fun clusterOfTypeAvailable(clusterType: T): Boolean {
		return clusterFactory.getNumRemaining(clusterType) > 0
	}

	private fun commitCluster(cluster: Cluster<T, *>) {
		// Mark all cells in the cluster as invalid and set their cluster.
		// Commit the cluster to the seed selector and cluster generator.
		// Create the pin mapping and intrasite routing information for the cluster.
		addClusterToDesign(cluster)
		for (cell in cluster.cells) {
			cell.setCluster(cluster)
			cell.isValid = false
			cell.locationInCluster = cluster.getCellPlacement(cell)
			unclusteredCells.remove(cell)
		}

		// build the cluster chains
		buildClusterChains(cluster)

		// indicate to the configurations that this cluster has been accepted
		packStrategies.values.forEach { it.commitCluster(cluster) }
		seedSelector.commitCluster(cluster)
		clusterFactory.commitCluster(cluster)
	}

	// class performing the funny type casting to appease the compiler
	@Suppress("UNCHECKED_CAST")
	private fun addClusterToDesign(cluster: Cluster<T, *>) {
		val typedCluster = cluster as Cluster<T, ClusterSite>
		val typedDesign = design as ClusterDesign<T, ClusterSite>
		typedDesign.addCluster(typedCluster)
	}

	private fun buildClusterChains(cluster: Cluster<T, *>) {
		// get all cells involved in a carry chain in the cluster
		val ccCells = cluster.cells.filter { it.carryChain != null }
		// if no carry chain is used, there is nothing to do here
		if (ccCells.isEmpty())
			return

		// Increment the number of packed carry chains
		// TODO move this into the carry chain checker
		ccCells.map { it.carryChain!! }
			.forEach { it.incrementNumPackedCells() }


		// Add the chain to a new group
		val clusterChain = ClusterChain(cluster)
		cluster.setChain(clusterChain)

		val endChains = ccCells.flatMap { it.sinkCarryChains }
			.mapNotNull { it.endCell.getCluster<Cluster<T, *>>() }
			.map { it.getChain<Cluster<T, *>>()!! }
			.filter { it !== clusterChain }
			.distinct()
		endChains.forEach { clusterChain.absorbGroup(it, Offset(1, 0)) }

		val beginChains = ccCells.flatMap { it.sourceCarryChains }
			.mapNotNull { it.endCell.getCluster<Cluster<T, *>>() }
			.map {
				val chain = it.getChain<Cluster<T, *>>()!!
				chain to chain.getOffsetOf(it) }
			.filter { it.first !== clusterChain }
			.distinctBy { it.first }
		beginChains.forEach { it.first.absorbGroup(
			clusterChain, it.second + Offset(1, 0)) }
	}

	private fun cleanupClusters(clusterDesign: ClusterDesign<T, *>) {
		utils.finish(clusterDesign)
	}
}

private fun cellCanBePlacedAt(cluster: Cluster<*, *>, cell: Cell, anchor: Bel): Boolean {
	return !cell.getRequiredBels(anchor)
		.any { cluster.isBelOccupied(it) }
}

// Creates a new ClusterDesign with a clean copy of the netlist
private fun <T: PackUnit> createClusterDesign(netlist: CellDesign): ClusterDesign<T, *> {
	val designCopy = CellDesign(netlist.name, netlist.partName)
	netlist.cells.forEach { c -> designCopy.addCell(c.deepCopy()) }
	netlist.nets.filter { !it.isInternal }.forEach { net ->
		val newNet = net.deepCopy()
		designCopy.addNet(newNet)
		net.pins.forEach { pin ->
			check(!pin.isPseudoPin) { "All pseudo pins must be removed" }
			val newCell = designCopy.getCell(pin.cell.name)
			val newPin = newCell.getPin(pin.name)
			newNet.connectToPin(newPin)
		}
	}

	@Suppress("UNCHECKED_CAST")
	return ClusterDesign<T, ClusterSite>(designCopy)
}

/**
 *  The current status of RSVPack.
 */
enum class PackStatus {
	VALID, INFEASIBLE, CONDITIONAL;

	infix fun meet(el2: PackStatus): PackStatus {
		return when (this) {
			VALID -> el2
			CONDITIONAL -> if (el2 == INFEASIBLE) INFEASIBLE else CONDITIONAL
			INFEASIBLE -> INFEASIBLE
		}
	}
}

class CadException(msg: String) : Exception(msg)

