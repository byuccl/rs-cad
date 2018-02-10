package edu.byu.ece.rapidSmith.cad.cluster.site

import edu.byu.ece.rapidSmith.cad.place.annealer.*
import edu.byu.ece.rapidSmith.device.Device
import edu.byu.ece.rapidSmith.device.Site
import edu.byu.ece.rapidSmith.device.Tile
import edu.byu.ece.rapidSmith.device.families.FamilyInfos
import edu.byu.ece.rapidSmith.util.ArrayGrid
import edu.byu.ece.rapidSmith.util.Grid
import edu.byu.ece.rapidSmith.util.Rectangle
import java.util.*

class SiteClusterGridFactory : ClusterSiteGridFactory<SiteClusterSite> {
	override fun makeClusterSiteGrid(device: Device): ClusterSiteGrid<SiteClusterSite> =
		SiteClusterGrid(device)
}

class SiteClusterGrid(device: Device) : ClusterSiteGrid<SiteClusterSite>() {
	private val grid : Grid<SiteClusterSite?>
	private val coordinates : Map<SiteIndex, Coordinates>

	init {
		val fi = FamilyInfos.get(device.family)
		val sbTypes = fi.switchboxTiles()

		val sites = device.sites.values
			.filter { it.tile.type !in sbTypes } // TODO replace with SiteType.Tieoff check
			.associate { SiteIndex(it) to it }


		val xlocs = sites.keys
			.map { SiteColumnIndex(it.col, it.index) }
			.toSortedSet()
			.withIndex()
			.map { it.value!! to it.index }
			.toMap()
		val ylocs = sites.keys
			.map { it.row }
			.toSortedSet()
			.withIndex()
			.map { it.value!! to it.index }
			.toMap()

		coordinates = sites.keys.associate { it to
			Coordinates(ylocs[it.row]!!, xlocs[it.columnIndex]!!)
		}
		grid = ArrayGrid<SiteClusterSite?>(ylocs.size, xlocs.size) { null }
		sites.forEach {
			val coord = coordinates[it.key]!!
			grid[coord] = SiteClusterSite(it.value, coord)
		}
	}

	override val rectangle: Rectangle
		get() = grid.rectangle

	override val absolute: Rectangle
		get() = grid.absolute

	override fun get(row: Int, column: Int): SiteClusterSite = requireNotNull(grid[row, column])

	override fun getSiteAt(location: Coordinates): SiteClusterSite? {
		return if (location in grid.rectangle) grid[location] else null
	}

	override fun getRelatedClusterSites(site: Site): List<SiteClusterSite> {
		val wrapped = grid[coordinates[SiteIndex(site)]!!] ?: return emptyList()
		return listOf(wrapped)
	}

	override fun getRelatedClusterSites(tile: Tile): List<SiteClusterSite> {
		return tile.sites.flatMap {
			val wrapped = grid[coordinates[SiteIndex(it)]!!]
			if (wrapped == null) emptyList() else listOf(wrapped)
		}
	}

	override val sites: List<SiteClusterSite>
		get() = grid.filterNotNull()

	private data class SiteIndex(
		val row: Int, val col: Int, val index: Int
	) {
		constructor(site: Site) : this(site.tile.row, site.tile.column, site.index)

		val columnIndex: SiteColumnIndex
			get() = SiteColumnIndex(col, index)
	}

	private data class SiteColumnIndex(
		val col: Int, val index: Int
	) : Comparable<SiteColumnIndex> {
		override fun compareTo(other: SiteColumnIndex): Int {
			return Comparator.comparingInt<SiteColumnIndex> { it.col }
				.thenComparingInt { it -> it.index }
				.compare(this, other)
		}
	}
}

