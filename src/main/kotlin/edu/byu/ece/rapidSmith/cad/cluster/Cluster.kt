package edu.byu.ece.rapidSmith.cad.cluster

import edu.byu.ece.rapidSmith.cad.place.annealer.Coordinates
import edu.byu.ece.rapidSmith.design.subsite.Cell
import edu.byu.ece.rapidSmith.design.subsite.CellNet
import edu.byu.ece.rapidSmith.design.subsite.CellPin
import edu.byu.ece.rapidSmith.design.subsite.RouteTree
import edu.byu.ece.rapidSmith.device.*
import java.util.*

/**
 * Class representing a cluster.  Clusters represent one or more cells that are
 * grouped into a hierarchical portion of a device.  Cells inside the cluster are
 * relatively placed and the placer is responsible for finding a location for the
 * clusters in a packed design.
 */
abstract class Cluster<out T: PackUnit, S: ClusterSite>(
	val name: String, val type: T, var anchor: Bel, val index: Int
) {
	// Field getters and setters
	var cost: Double = 0.toDouble()
	private var _chain: ClusterChain<*>? = null
	private var placementMap = LinkedHashMap<Bel, Cell>()
	private var cellLocationMap = LinkedHashMap<Cell, Bel>()
	private var pinMap = LinkedHashMap<CellPin, List<BelPin>>()
	private var internalNets: MutableMap<CellNet, ArrayList<RouteTree>>? = null
	private var externalNets: MutableMap<CellNet, ArrayList<RouteTree>>? = null

	var placement: S? = null
		protected set

	/**
	 * Adds the cell [cell] to the cluster at location [bel].
	 */
	open fun addCell(bel: Bel, cell: Cell): Cell {
		require(cell !in this)
		require(!placementMap.containsKey(bel))

		placementMap[bel] = cell
		cellLocationMap[cell] = bel

		return cell
	}

	/**
	 * Removes the cell [cell] from the cluster.
	 */
	fun removeCell(cell: Cell) {
		val bel = cellLocationMap.remove(cell)
		placementMap.remove(bel)
		cellLocationMap.remove(cell)

		cell.locationInCluster = null
	}

	/** Collection of the cells in the cluster. */
	val cells: Collection<Cell>
		get() = cellLocationMap.keys

	/** Returns `true` if [cell] is in this cluster. */
	fun hasCell(cell: Cell): Boolean {
		return cell in cellLocationMap
	}

	operator fun contains(cell: Cell): Boolean = hasCell(cell)

	/** Checks if the [bel] is occupied in the cluster. If checkOtherLUT is true, the corresponding
	 * LUT of a LUT5/LUT6 pair is checked as well; if either LUT has a cell, then both LUT BELs are
	 * considered occupied.
	 */
	fun isBelOccupied(bel: Bel, checkOtherLut: Boolean): Boolean {
		if (!checkOtherLut)
			return bel in placementMap

		if (bel in placementMap)
			return true

		val belName = bel.name
		if (belName.endsWith("5LUT")) {
			// get the cell at the corresponding lut6 BEL
			val lut6Name = belName[0] + "6LUT"
			val lut6 = bel.site.getBel(lut6Name)
			val cellAtLut6 = placementMap[lut6] ?: return false

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
			val lut5 = bel.site.getBel(lut5Name)
			val cellAtLut5 = placementMap[lut5] ?: return false

			if (!cellAtLut5.libCell.name.startsWith("LUT"))
				return true
		}
		return false
	}

	fun isBelOccupied(bel: Bel): Boolean {
		return bel in placementMap
	}

	/** Returns `true` if all Bels in this cluster are occupied. */
	fun isFull(): Boolean =
		placementMap.size == type.template.bels.size

	/**
	 * Returns the location of [cell] in this cluster or `null` if it is not in
	 * this cluster.
	 */
	fun getCellPlacement(cell: Cell): Bel? {
		return cellLocationMap[cell]
	}

	/**
	 * Returns the cell at location [bel] or null if the Bel is unoccupied.
	 */
	fun getCellAtBel(bel: Bel): Cell? {
		return placementMap[bel]
	}

	fun <C: Cluster<*, *>> getChain(): ClusterChain<C>? {
		@Suppress("UNCHECKED_CAST")
		return _chain as ClusterChain<C>?
	}

	fun <C: Cluster<*, *>> setChain(chain: ClusterChain<C>?) {
		this._chain = chain
	}

	fun addRouteTree(net : CellNet, trees : ArrayList<RouteTree>) {
		externalNets?.put(net, trees)
	}

	// Nets in cluster methods
	/**
	 * Method to construct the structures containing the nets in the cluster from
	 * the pins.  This method must be called prior to analyzing or changing the
	 * routing of this cluster.
	 */
	fun constructNets() {
		internalNets = LinkedHashMap()
		externalNets = LinkedHashMap()

		val nets = cells
			.flatMap { it.pins }
			.filter { it.isConnectedToNet }
			.map { it.net }
			.distinct()

		for (net in nets) {
			val leavesCluster = net.pins.any { it.isPartitionPin || !hasCell(it.cell) }
			if (leavesCluster)
				externalNets!![net] = ArrayList()
			else
				internalNets!![net] = ArrayList()
		}
	}

	/**
	 * Returns all nets that connect to pins within this cluster.
	 */
	val nets: Collection<CellNet>
		get() {
			val nets = ArrayList<CellNet>()
			nets.addAll(getInternalNets())
			nets.addAll(getExternalNets())
			return nets
		}

	/**
	 * Returns all net fully contained within this cluster.  Note, some nets in this
	 * collection may require going to the switchbox to be routed.
	 */
	fun getInternalNets(): Set<CellNet> {
		val intNets = checkNotNull(internalNets) { "Nets not constructed "}
		return intNets.keys
	}

	/**
	 * Returns all nets that exit this cluster.
	 */
	fun getExternalNets(): Set<CellNet> {
		val extNets = checkNotNull(externalNets)  { "Nets not constructed "}
		return extNets.keys
	}

	/**
	 * Clears all routing for this cluster.
	 */
	fun clearRouting() {
		if (internalNets != null) internalNets!!.values.forEach{ it.clear() }
		if (externalNets != null) externalNets!!.values.forEach{ it.clear() }
	}

	/**
	 * Applies [routeTree] to [net] in this cluster.
	 * @throws IllegalArgumentException if [net] is not in this cluster.
	 */
	fun addNetRouteTree(net: CellNet, routeTree: RouteTree) {
		val extNets = checkNotNull(externalNets) { "Nets not constructed "}
		val intNets = checkNotNull(internalNets) { "Nets not constructed "}

		if (net in intNets) {
			var routeTrees: MutableList<RouteTree>? = intNets[net]
			if (routeTrees == null) {
				routeTrees = ArrayList()
				intNets[net] = routeTrees
			}
			routeTrees.add(routeTree)
		} else if (net in extNets) {
			var routeTrees: MutableList<RouteTree>? = extNets[net]
			if (routeTrees == null) {
				routeTrees = ArrayList()
				extNets[net] = routeTrees
			}
			routeTrees.add(routeTree)
		} else {
			throw IllegalArgumentException("Cluster does not have net")
		}
	}

	/**
	 * Returns all route trees associated with [net] in this cluster.
	 */
	fun getRouteTrees(net: CellNet): List<RouteTree>? {
		val extNets = checkNotNull(externalNets) { "Nets not constructed "}
		val intNets = checkNotNull(internalNets) { "Nets not constructed "}

		return when (net) {
			in intNets -> intNets[net]
			in extNets -> extNets[net]
			else -> null
		}
	}

	/**
	 * Map of nets in the cluster to route trees for the nets.
	 */
	var routeTreeMap: MutableMap<CellNet, MutableList<RouteTree>>
		get() {
			checkNotNull(externalNets)
			checkNotNull(internalNets)

			val routeTreeMap = mutableMapOf<CellNet, MutableList<RouteTree>>()
			routeTreeMap.putAll(internalNets!!)
			routeTreeMap.putAll(externalNets!!)
			return routeTreeMap
		}
		set(newMap) {
			for (e in internalNets!!.entries) {
				val newRouteTree = newMap[e.key] ?: mutableListOf()
				e.setValue(ArrayList(newRouteTree))
			}

			for (e in externalNets!!.entries) {
				val newRouteTree = newMap[e.key] ?: mutableListOf()
				e.setValue(ArrayList(newRouteTree))
			}
		}

	// Pin mapping methods
	/**
	 * Sets the pin mapping for [cellPin] in this cluster.
	 */
	fun setPinMapping(cellPin: CellPin, belPin: List<BelPin>) {
		pinMap[cellPin] = belPin
	}

	/**
	 * Removes the pin mapping for [cellPin] in this cluster.
	 */
	fun removePinMapping(cellPin: CellPin): List<BelPin>? {
		return pinMap.remove(cellPin)
	}

	/**
	 * Returns the pin mapping for [cellPin] in this cluster or `null` if the pin
	 * is not mapped.
	 */
	fun getPinMapping(cellPin: CellPin): List<BelPin>? {
		return pinMap[cellPin]
	}

	/**
	 * Returns a map containing all mapped cell pins in this cluster and the
	 * bel pin they are mapped to.
	 */
	fun getPinMap(): Map<CellPin, List<BelPin>> {
		return pinMap
	}

	// placement methods
	/**
	 * Returns `true` if this cluster should be placed by a placer.
	 */
	abstract val isPlaceable: Boolean

	/**
	 * Returns `true` if this cluster is placed.
	 */
	val isPlaced: Boolean
		get() = placement != null

	/**
	 * Sets the placement of this cluster
	 */
	fun place(site: S?) {
		placement = site
	}

	/**
	 * Unplaces this cluster.
	 */
	fun unplace() {
		placement = null
	}

	/**
	 * Updates the locations of the cells in this cluster to be relative to the
	 * placement of this cell.  The cluster must be placed prior to calling this
	 * method.
	 *
	 * @throws IllegalStateException if the cluster is unplaced.
	 */
	fun commitPlacement() {
		val p = checkNotNull(placement) { "Cluster not placed" }
		relocateTo(p)
	}

	// Relocating methods
	/**
	 * This method is responsible for updating the locations of all of the cells
	 * in this cluster to be relative to the ClusterSite [site].
	 */
	protected abstract fun relocateTo(site: S)

	/**
	 * Relocates all cells, pins and routes in this cluster to be relative to the
	 * new anchor BEL.
	 */
	fun relocate(newAnchor: Bel) {
		val relocatedBelMap = LinkedHashMap<Bel, Cell>()
		for (e in placementMap.entries)
			relocateBel(newAnchor, relocatedBelMap, e)
		placementMap = relocatedBelMap

		cellLocationMap = LinkedHashMap()
		placementMap.entries.associateTo(cellLocationMap) { (k, v) -> v to k }

		val relocatePinMap = LinkedHashMap<CellPin, List<BelPin>>()
		pinMap.entries.associateTo(relocatePinMap) { (k, v) ->
			k to v.map { relocatePin(it, newAnchor) }
		}
		pinMap = relocatePinMap

		val relocateTreeMap = LinkedHashMap<CellNet, List<RouteTree>>()
		routeTreeMap.entries.associateTo(relocateTreeMap) { (k, v) ->
			k to v.map { relocateRouteTree(it, newAnchor) }
		}
		clearRouting()
		relocateTreeMap.forEach { cell, list -> list.forEach { t -> addNetRouteTree(cell, t) } }

		anchor = newAnchor
	}

	private fun relocateBel(newAnchor: Bel, relocatedMap: HashMap<Bel, Cell>, e: Map.Entry<Bel, Cell>) {
		val cell = e.value
		val relocatedBel = getRelocatedBel(e.key, newAnchor)
		relocatedBel.site.type = relocatedBel.id.siteType
		relocatedMap[relocatedBel] = cell
	}

	private fun relocatePin(belPin: BelPin, newAnchor: Bel): BelPin {
		return getRelocatedBelPin(belPin, newAnchor)
	}

	private fun relocateRouteTree(template: RouteTree, newAnchor: Bel): RouteTree {
		val map = LinkedHashMap<RouteTree, RouteTree>()

		for (rt in template) {
			if (rt.getParent<RouteTree>() == null) {
				val newWire = getRelocatedWire(rt.wire, newAnchor)
				map[rt] = RouteTree(newWire)
			} else {
				check(rt.wire !is TileWire)

				val sourceTree = map[rt.getParent()]!!
				val newConn = getRelocatedConnection(
						sourceTree.wire, rt.connection, newAnchor)
				map[rt] = sourceTree.connect<RouteTree>(newConn)
			}
		}

		return map[template]!!
	}

	/**
	 * Method responsible for getting the corresponding BEL of [bel] relative to
	 * the new anchor [newAnchor].
	 */
	protected abstract fun getRelocatedBel(bel: Bel, newAnchor: Bel): Bel

	/**
	 * Method responsible for getting the corresponding BelPin of [belPin] relative to
	 * the new anchor [newAnchor].
	 */
	protected abstract fun getRelocatedBelPin(belPin: BelPin, newAnchor: Bel): BelPin

	/**
	 * Method responsible for getting the corresponding wire of [wire] relative to
	 * the new anchor [newAnchor].
	 */
	protected abstract fun getRelocatedWire(wire: Wire, newAnchor: Bel): Wire

	/**
	 * Method responsible for getting the corresponding connection relative to
	 * the new anchor [newAnchor].
	 */
	protected abstract fun getRelocatedConnection(
			sourceWire: Wire, connection: Connection, newAnchor: Bel): Connection

	override fun toString(): String {
		return "Cluster{$name}"
	}
}

/**
 * A location in a device for a site.
 *
 * @property location the index in the type grid for this site
 */
abstract class ClusterSite{
	/** The coordinates of this site */
	abstract val location: Coordinates
	/** The tile coordinates of this site */
	abstract val tileLocation: Coordinates

	abstract fun isCompatibleWith(packUnit: PackUnit): Boolean
	override fun equals(other: Any?): Boolean {
		if (this === other)
			return true
		if (other == null || other.javaClass != javaClass)
			return false
		other as ClusterSite
		return location == other.location
	}

	override fun hashCode(): Int {
		return location.hashCode()
	}
}

/**
 * Class responsible for creating new clusters and identifying the
 * available types of cluster.
 */
interface ClusterFactory<T: PackUnit, S: ClusterSite> {
	/**
	 * Method for initializing the factory.  Called prior to making any new clusters.
	 */
	fun init()

	/**
	 * Returns all pack units supported by this cluster factory.
	 */
	val supportedPackUnits: Collection<T>

	/**
	 * Returns the number of pack units of type [packUnit] remaining in the device.
	 */
	fun getNumRemaining(packUnit: T): Int

	/**
	 * Creates and returns a new cluster of type [packUnit].
	 */
	fun createNewCluster(clusterName: String, packUnit: T): Cluster<T, S>

	/**
	 * Signifies that this cluster is complete and will be used in the design.  Allows
	 * for the ClusterFactory to update the number of used clusters of each type.
	 */
	fun commitCluster(cluster: Cluster<T, *>)
}
