package edu.byu.ece.rapidSmith.cad.cluster.site

import edu.byu.ece.rapidSmith.cad.cluster.PackUnit
import edu.byu.ece.rapidSmith.cad.place.annealer.*
import edu.byu.ece.rapidSmith.device.Site
import edu.byu.ece.rapidSmith.device.SiteType
import edu.byu.ece.rapidSmith.device.Wire
import edu.byu.ece.rapidSmith.device.families.Artix7
import edu.byu.ece.rapidSmith.util.ArrayGrid
import edu.byu.ece.rapidSmith.util.Grid
import edu.byu.ece.rapidSmith.util.Index
import edu.byu.ece.rapidSmith.util.Rectangle
import java.lang.IndexOutOfBoundsException
import java.lang.UnsupportedOperationException
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.List
import kotlin.collections.filter
import kotlin.collections.map
import kotlin.collections.plusAssign
import kotlin.math.*

/**
 *
 */
class SiteGroupPlacementRegionFactory : GroupPlacementRegionFactory<SiteClusterSite>() {
	private val singleClusterCache = LinkedHashMap<PackUnit, SiteGroupPlacementRegion>()
	private val sliceLGroupsCache = LinkedHashMap<Int, SiteGroupPlacementRegion>()
	private val sliceMGroupsCache = LinkedHashMap<Int, SiteGroupPlacementRegion>()
	private var ioGroupsCache: SiteGroupPlacementRegion? = null

	override fun make(
		group: PlacementGroup<SiteClusterSite>,
		device: PlacerDevice<SiteClusterSite>
	): SiteGroupPlacementRegion {
		return if (group is MultipleClusterPlacementGroup<*>) {
			val type = group.type as SitePackUnit
			when(type.siteType) { // TODO support IOB33S and IOB33M?
				Artix7.SiteTypes.IOB33 -> {
					var region = ioGroupsCache
					if (region == null) {
						val locs = device.grid.sites
							.filter { it.isCompatibleWith(type) }
							.mapNotNull { getIOPair(device.grid as SiteClusterGrid, it) }
							.toList()
						region = SiteGroupPlacementRegion(locs)
						ioGroupsCache = region
					}
					region
				}
				Artix7.SiteTypes.SLICEL -> {
					sliceLGroupsCache.computeIfAbsent(group.size) { _ ->
						val locs = device.grid.sites
							.filter { it.isCompatibleWith(type) }
							.mapNotNull { getCCChain(device.grid as SiteClusterGrid,
								Artix7.SiteTypes.SLICEL, it, group.size) }
							.toList()
						SiteGroupPlacementRegion(locs)
					}
				}
				Artix7.SiteTypes.SLICEM -> {
					sliceMGroupsCache.computeIfAbsent(group.size) { _ ->
						val locs = device.grid.sites
							.filter { it.isCompatibleWith(type) }
							.mapNotNull { getCCChain(device.grid as SiteClusterGrid,
								Artix7.SiteTypes.SLICEM, it, group.size) }
							.toList()
						SiteGroupPlacementRegion(locs)
					}
				}
				else -> error("unsupported group type")
			}
		} else {
			singleClusterCache.computeIfAbsent(group.type) { type ->
				val locations = device.grid.sites
					.filter { it.isCompatibleWith(type) }
					.map { listOf(it) }
					.toList()
				SiteGroupPlacementRegion(locations)
			}
		}
	}

	private fun getIOPair(
		grid: SiteClusterGrid,
		anchor: SiteClusterSite
	): List<SiteClusterSite>? {
		val sites = ArrayList<SiteClusterSite>()
		sites += anchor

		val source = anchor.site.getPin("I").externalWire
		val stack = ArrayDeque<WireDistancePair>()
		stack.push(WireDistancePair(source, 1))
		while (stack.isNotEmpty()) {
			val (wire, distance) = stack.pop()
			val pin = wire.connectedPin
			if (pin != null && pin.site.isCompatibleWith(Artix7.SiteTypes.BUFG)) {
				sites += grid.getClusterSite(pin.site)
				return sites
			}

			if (distance < 24) {
				val sinks = wire.wireConnections.map { it.sinkWire }
					.filter { it.tile.type !in Artix7.SWITCHBOX_TILES }
				for (sink in sinks) {
					stack.push(WireDistancePair(sink, distance + 1))
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
			val stack = ArrayDeque<WireDistancePair>()
			stack.push(WireDistancePair(source, 1))
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
						.filter { it.tile.type !in Artix7.SWITCHBOX_TILES }
					for (sink in sinks) {
						stack.push(WireDistancePair(sink, distance + 1))
					}
				}
			}
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
	validAreas: List<List<SiteClusterSite>>
) : GroupPlacementRegion<SiteClusterSite>() {

	val validAreas: Map<SiteClusterSite, List<SiteClusterSite>> =
		validAreas.associateBy { it.first() }

	override val validSites: List<SiteClusterSite> = validAreas.map { it[0] }
		.sortedWith(Comparator.comparingInt<SiteClusterSite> { it.location.row }
			.thenComparingInt { it.location.column })

	private val siteIndexGrid: Grid<Int> = makeSiteIndexGrid(validSites)

	override fun getValidSitesAround(center: Index, range: Int): List<SiteClusterSite> {
		val top = max(siteIndexGrid.lower.row, center.row - range)
		val left = max(siteIndexGrid.lower.column, center.column - range)
		val bottom = min(siteIndexGrid.upper.row, center.row + range)
		val right = min(siteIndexGrid.upper.column, center.column + range)

		if (Rectangle(top, left, bottom, right) == siteIndexGrid.rectangle)
			return validSites

		val bounded = siteIndexGrid.subgrid(Rectangle(top, left, bottom, right))
		return MergedSublists(bounded, validSites)
	}

	override val area: Int
		get() = Int.MAX_VALUE

	override fun getLocations(newAnchor: SiteClusterSite): List<SiteClusterSite>? {
		return validAreas[newAnchor]
	}

	override fun toString(): String {
		return "PlacementGrid: (all)"
	}
}

private fun makeSiteIndexGrid(validSites: List<SiteClusterSite>): Grid<Int> {
	var rows = 0;
	var cols = 0

	for (i in validSites) {
		val (r, c) = i.location
		if (r > rows) rows = r
		if (c > cols) cols = c
	}

	val grid = ArrayGrid(rows+1, cols+1) { -1 }
	for ((i, s) in validSites.withIndex()) {
		grid[s.location] = i
	}

	var last = -1
	for (r in grid.rectangle.rows) {
		for (c in grid.rectangle.columns) {
			if (grid[r, c] != -1) {
				last = grid[r, c] or 0x40000000
			} else {
				grid[r, c] = last
			}
		}
	}

	return grid
}

private val EMPTY_BIT = 0x40000000
private val INDEX_MASK = 0x3FFFFFFF

private class MergedSublists<E>(val subgrid: Grid<Int>, val list: List<E>) : List<E> {
	override val size: Int
		get() {
			var length = 0
			for (r in subgrid.rectangle.rows) {
				val (first, last) = getElementsInRow(r) ?: continue
				length += last - first + 1
			}
			return length
		}

	override fun get(index: Int): E {
		if (subgrid.isEmpty())
			throw IndexOutOfBoundsException("$index")

		var trueIndex = index
		for (r in subgrid.rectangle.rows) {
			val (first, last) = getElementsInRow(r) ?: continue
			val length = last - first + 1
			if (trueIndex < length) {
				return list[first + trueIndex]
			} else {
				trueIndex -= length
			}
		}
		throw IndexOutOfBoundsException("$index")
	}

	fun getElementsInRow(r: Int): Pair<Int, Int>? {
		val left = subgrid[r, 0]
		val right = subgrid[r, subgrid.rectangle.columns.last]
		val first = if (left and EMPTY_BIT == 0) left else ((left + 1) and INDEX_MASK)
		val last = right and INDEX_MASK
		if (left == right && left and EMPTY_BIT != 0)
			return null
		return Pair(first, last)
	}

	override fun isEmpty(): Boolean {
		for (r in subgrid.rectangle.rows) {
			val left = subgrid[r, 0]
			val right = subgrid[r, subgrid.rectangle.columns.last]
			if (left and EMPTY_BIT == 0) return false
			if (right and EMPTY_BIT == 0) return false
			if (left != right) return false
		}
		return true
	}

	override fun containsAll(elements: Collection<E>): Boolean {
		throw UnsupportedOperationException()
	}

	override fun indexOf(element: E): Int {
		throw UnsupportedOperationException()
	}

	override fun lastIndexOf(element: E): Int {
		throw UnsupportedOperationException()
	}

	override fun contains(element: E): Boolean {
		throw UnsupportedOperationException()
	}

	override fun iterator(): Iterator<E> {
		throw UnsupportedOperationException()
	}

	override fun listIterator(): ListIterator<E> {
		throw UnsupportedOperationException()
	}

	override fun listIterator(index: Int): ListIterator<E> {
		throw UnsupportedOperationException()
	}

	override fun subList(fromIndex: Int, toIndex: Int): List<E> {
		throw UnsupportedOperationException()
	}
}

