package edu.byu.ece.rapidSmith.cad.cluster.site

import edu.byu.ece.rapidSmith.cad.cluster.PackUnit
import edu.byu.ece.rapidSmith.cad.place.annealer.*
import edu.byu.ece.rapidSmith.device.Site
import edu.byu.ece.rapidSmith.device.SiteType
import edu.byu.ece.rapidSmith.device.Wire
import edu.byu.ece.rapidSmith.device.families.Zynq
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.List
import kotlin.collections.filter
import kotlin.collections.map
import kotlin.collections.plusAssign

/**
 *
 */
class ZynqSiteGroupPlacementRegionFactory : GroupPlacementRegionFactory<SiteClusterSite>() {
	private val singleClusterCache = HashMap<PackUnit, List<List<SiteClusterSite>>>()
	private val sliceLGroupsCache = HashMap<Int, List<List<SiteClusterSite>>>()
	private val sliceMGroupsCache = HashMap<Int, List<List<SiteClusterSite>>>()
	private var ioGroupsCache: List<List<SiteClusterSite>>? = null

	// TODO: Make it support different families.

	override fun make(
		group: PlacementGroup<SiteClusterSite>,
		device: PlacerDevice<SiteClusterSite>
	): SiteGroupPlacementRegion {
		if (group is MultipleClusterPlacementGroup<*>) {
			val type = group.type as SitePackUnit
			val locations = when(type.siteType) { // TODO support IOB33S and IOB33M?
				Zynq.SiteTypes.IOB33 -> {
					var locs = ioGroupsCache
					if (locs == null) {
						locs = device.grid.sites
							.filter { it.isCompatibleWith(type) }
							.mapNotNull { getIOPair(device.grid as SiteClusterGrid, it) }
						ioGroupsCache = locs
					}
					locs
				}
				Zynq.SiteTypes.SLICEL -> {
					sliceLGroupsCache.computeIfAbsent(group.size) {
						device.grid.sites
							.filter { it.isCompatibleWith(type) }
							.mapNotNull { getCCChain(device.grid as SiteClusterGrid,
								Zynq.SiteTypes.SLICEL, it, group.size) }
					}
				}
				Zynq.SiteTypes.SLICEM -> {
					sliceMGroupsCache.computeIfAbsent(group.size) {
						device.grid.sites
							.filter { it.isCompatibleWith(type) }
							.mapNotNull { getCCChain(device.grid as SiteClusterGrid,
								Zynq.SiteTypes.SLICEM, it, group.size) }
					}
				}
				else -> error("unsupported group type")
			}

			return SiteGroupPlacementRegion(locations)
		} else {
			val locations = singleClusterCache.computeIfAbsent(group.type) { type ->
				device.grid.sites
					.filter { it.isCompatibleWith(type) }
					.map { listOf(it) }
			}
			return SiteGroupPlacementRegion(locations)
		}
	}

	private fun getIOPair(
		grid: SiteClusterGrid,
		anchor: SiteClusterSite
	): List<SiteClusterSite>? {
		val sites = ArrayList<SiteClusterSite>()
		sites += anchor

		val source = anchor.site.getPin("I").externalWire
		val stack = ArrayDeque<ZynqWireDistancePair>()
		stack.push(ZynqWireDistancePair(source, 1))
		while (stack.isNotEmpty()) {
			val (wire, distance) = stack.pop()
			val pin = wire.connectedPin
			if (pin != null && pin.site.isCompatibleWith(Zynq.SiteTypes.BUFG)) {
				sites += grid.getClusterSite(pin.site)
				return sites
			}

			if (distance < 24) {
				val sinks = wire.wireConnections.map { it.sinkWire }
					.filter { it.tile.type !in Zynq.SWITCHBOX_TILES }
				for (sink in sinks) {
					stack.push(ZynqWireDistancePair(sink, distance + 1))
				}
			}
		}
		return null
	}

	// TODO checking site compatibility is still an issue
	private fun getCCChain(
		grid: SiteClusterGrid,
		siteType: SiteType,
		anchor: SiteClusterSite, length: Int
	): List<SiteClusterSite>? {
		val sites = ArrayList<SiteClusterSite>()
		sites += anchor

		var site = anchor.site
		outer@for (i in 1 until length) {
			val source = site.getPin("COUT").externalWire
			val stack = ArrayDeque<ZynqWireDistancePair>()
			stack.push(ZynqWireDistancePair(source, 1))
			while (stack.isNotEmpty()) {
				val (wire, distance) = stack.pop()
				val pin = wire.connectedPin
				if (pin != null && pin.site.isCompatibleWith(siteType)) {
					sites += grid.getClusterSite(pin.site)
					site = pin.site
					continue@outer
				}

				if (distance < 8) {
					val sinks = wire.wireConnections.map { it.sinkWire }
						.filter { it.tile.type !in Zynq.SWITCHBOX_TILES }
					for (sink in sinks) {
						stack.push(ZynqWireDistancePair(sink, distance + 1))
					}
				}
			}
			return null
		}

		return sites
	}
}

private data class ZynqWireDistancePair(
	val wire: Wire,
	val distance: Int
)

private fun Site.isCompatibleWith(siteType: SiteType): Boolean {
	if (this.defaultType == siteType)
		return true
	if (this.possibleTypes != null && siteType in this.possibleTypes)
		return true
	return false
}

/**
 *
 */
class ZynqSiteGroupPlacementRegion(
	validAreas: List<List<SiteClusterSite>>
) : GroupPlacementRegion<SiteClusterSite>() {

	val validAreas: Map<SiteClusterSite, List<SiteClusterSite>> =
		validAreas.associateBy { it.first() }

	/**
	 * A two dimensional representation of the coordinate system. This is
	 * handy for getting coordinates based on the x,y locations.
	 */
	override val validSites: List<SiteClusterSite> = validAreas.map { it.first() }

	override val area: Int
		get() = Int.MAX_VALUE

	override fun getLocations(newAnchor: SiteClusterSite): List<SiteClusterSite>? {
		return validAreas[newAnchor]
	}

	override fun toString(): String {
		return "PlacementGrid: (all)"
	}
}
