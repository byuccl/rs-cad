package edu.byu.ece.rapidSmith.cad.cluster.site

import edu.byu.ece.rapidSmith.cad.cluster.PackUnit
import edu.byu.ece.rapidSmith.cad.place.annealer.*
import edu.byu.ece.rapidSmith.device.Site
import edu.byu.ece.rapidSmith.device.SiteType
import edu.byu.ece.rapidSmith.device.Wire
import edu.byu.ece.rapidSmith.device.families.Artix7
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.List
import kotlin.collections.filter
import kotlin.collections.map
import kotlin.collections.plusAssign

/**
 *
 */
class SiteGroupPlacementRegionFactory : GroupPlacementRegionFactory<SiteClusterSite> {
	// caches for regions of different resource types
	private val singleClusterCache = HashMap<PackUnit, List<List<SiteClusterSite>>>()
	private val sliceLGroupsCache = HashMap<Int, List<List<SiteClusterSite>>>()
	private val sliceMGroupsCache = HashMap<Int, List<List<SiteClusterSite>>>()
	private var ioGroupsCache: List<List<SiteClusterSite>>? = null

	override fun make(
		group: PlacementGroup<SiteClusterSite>,
		device: PlacerDevice<SiteClusterSite>
	): SiteGroupPlacementRegion {
		var locations = if (group is MultipleClusterPlacementGroup<*>) {
			val type = group.type as SitePackUnit

			// build the locations based on the group type
			when(type.siteType) {
				// TODO support IOB33S and IOB33M?
				Artix7.SiteTypes.IOB33 -> {
					// TODO support IOB/[IO]LOGIC groups
					// only one region of this type
					// region consists of IOB/BUFG pairs
					ioGroupsCache ?: let {
						val tmp = device.grid.sites
							.filter { it.isCompatibleWith(type) }
							.mapNotNull { getIOPair(device.grid as SiteClusterGrid, it) }
						ioGroupsCache = tmp
						tmp
					}
				}
				Artix7.SiteTypes.SLICEL -> {
					// TODO ensure SLICEL chains can be placed on SLICEMs

					// builds and caches regions of carry chains of lengths determined by the
					// length of the groups carry chain. carry chains can but either SLICEL or
					// SLICEMs
					sliceLGroupsCache.computeIfAbsent(group.size) {
						device.grid.sites
							.filter { it.isCompatibleWith(type) }
							.mapNotNull { getCCChain(device.grid as SiteClusterGrid,
								Artix7.SiteTypes.SLICEL, it, group.size) }
					}
				}
				Artix7.SiteTypes.SLICEM -> {
					// builds and caches regions of carry chains of lengths determined by the
					// length of the groups carry chain. all elements in the region must be
					// SLICEMs
					sliceMGroupsCache.computeIfAbsent(group.size) {
						device.grid.sites
							.filter { it.isCompatibleWith(type) }
							.mapNotNull { getCCChain(device.grid as SiteClusterGrid,
								Artix7.SiteTypes.SLICEM, it, group.size) }
					}
				}
				else -> error("unsupported group type")
			}
		} else {
			// access the region for this group from the cache
			// the default region is determined by the type of the group
			singleClusterCache.computeIfAbsent(group.type) { type ->
				// locations are determined by the site compatibility with the type
				device.grid.sites
					.filter { it.isCompatibleWith(type) }
					.map { listOf(it) } // lists of single elements
			}
		}

		// apply area constraints to the placement region
		group.clusters.forEach { cluster ->
			cluster.areaConstraints?.let { cs ->
				locations = locations.filter { site ->
					cs.all { site[group.getClusterIndex(cluster)] in it }
				}
			}
		}

		return SiteGroupPlacementRegion(locations)
	}

	private fun getIOPair(
		grid: SiteClusterGrid,
		anchor: SiteClusterSite
	): List<SiteClusterSite>? {
		val MAX_SEARCH_DISTANCE = 24
		val sites = ArrayList<SiteClusterSite>()
		sites += anchor

		// perform a graph traversal from the IOB I pin to look for an accompanying BUFG
		val source = anchor.site.getPin("I").externalWire
		val stack = ArrayDeque<WireDistancePair>()
		stack.push(WireDistancePair(source, 1))
		while (stack.isNotEmpty()) {
			val (wire, distance) = stack.pop()
			val pin = wire.connectedPin
			if (pin != null && pin.site.isCompatibleWith(Artix7.SiteTypes.BUFG)) {
				// found a BUFG, return the pair
				sites += grid.getClusterSite(pin.site)
				return sites
			}

			if (distance < MAX_SEARCH_DISTANCE) {
				val sinks = wire.wireConnections.map { it.sinkWire }
					.filter { it.tile.type !in Artix7.SWITCHBOX_TILES }
				for (sink in sinks) {
					stack.push(WireDistancePair(sink, distance + 1))
				}
			}
		}

		// no associated BUFG, indicate failure
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

		// perform a graph traversal from the COUT pin to locate the next carry chain object
		// repeat for the number of carry chain objects to find
		val MAX_SEARCH_DISTANCE = 8
		var site = anchor.site
		outer@for (i in 1 until length) {
			val source = site.getPin("COUT").externalWire
			val stack = ArrayDeque<WireDistancePair>()
			stack.push(WireDistancePair(source, 1))

			// perform the DFS
			while (stack.isNotEmpty()) {
				val (wire, distance) = stack.pop()
				val pin = wire.connectedPin
				if (pin != null && pin.site.isCompatibleWith(siteType)) {
					// found the CC object.  add it and continue to the next
					sites += grid.getClusterSite(pin.site)
					site = pin.site
					continue@outer
				}

				if (distance < MAX_SEARCH_DISTANCE) {
					val sinks = wire.wireConnections.map { it.sinkWire }
						.filter { it.tile.type !in Artix7.SWITCHBOX_TILES }
					for (sink in sinks) {
						stack.push(WireDistancePair(sink, distance + 1))
					}
				}
			}
			// could not find a chain of sufficient length
			// return null to indicate failure
			return null
		}

		return sites
	}
}

private data class WireDistancePair(
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
class SiteGroupPlacementRegion(
	private val validAreas: Map<SiteClusterSite, List<SiteClusterSite>>
) : GroupPlacementRegion<SiteClusterSite> {

	constructor(validAreas: List<List<SiteClusterSite>>)
		: this(validAreas.associateBy { it.first() })

	/**
	 * A two dimensional representation of the coordinate system. This is
	 * handy for getting coordinates based on the x,y locations.
	 */
	override val validSites: List<SiteClusterSite> =
		validAreas.map { it.key }

	override val area: Int
		get() = Int.MAX_VALUE

	override fun getLocations(newAnchor: SiteClusterSite): List<SiteClusterSite>? {
		return validAreas[newAnchor]
	}

	override fun constrain(constraint: AreaConstraint<SiteClusterSite>)
		: GroupPlacementRegion<SiteClusterSite> {

		return SiteGroupPlacementRegion(validAreas.filterValues { it.all { it in constraint} })
	}

	override fun toString(): String {
		// TODO I have constraints - indicate them here somehow
		return "PlacementGrid: (all)"
	}
}
