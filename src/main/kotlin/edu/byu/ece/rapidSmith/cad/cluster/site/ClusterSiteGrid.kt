package edu.byu.ece.rapidSmith.cad.cluster.site

import edu.byu.ece.rapidSmith.cad.place.annealer.ClusterSiteGrid
import edu.byu.ece.rapidSmith.cad.place.annealer.ClusterSiteGridFactory
import edu.byu.ece.rapidSmith.cad.place.annealer.Coordinates
import edu.byu.ece.rapidSmith.device.Device
import edu.byu.ece.rapidSmith.device.Site
import edu.byu.ece.rapidSmith.device.Tile
import edu.byu.ece.rapidSmith.util.ArrayGrid
import edu.byu.ece.rapidSmith.util.Dimensions
import edu.byu.ece.rapidSmith.util.Rectangle
import java.util.*
import kotlin.collections.ArrayList

/**
 *
 */
class SiteClusterGridFactory(
	private val device: Device
) : ClusterSiteGridFactory<SitePackUnit, SiteClusterSite>() {
	override fun make(type: SitePackUnit): SiteClusterGrid {
		val locations = device.getAllCompatibleSites(type.siteType)
		return SiteClusterGrid(type, locations)
	}
}

/**
 *
 */
class SiteClusterGrid(packUnit: SitePackUnit, validSites: List<Site>) :
	ClusterSiteGrid<SiteClusterSite>(packUnit) {
	/**
	 * A two dimensional representation of the coordinate system. This is
	 * handy for getting coordinates based on the x,y locations.
	 */
	private val rowRelocationMap: Map<Int, Int>
	private val colRelocationMap: Map<Int, Int>
	private val grid: ArrayGrid<SiteClusterSite?>
	override val validSites: List<SiteClusterSite>

	init {
		// Create a set of these sites and keep track of the maximum height and width
		val rowLocations = HashSet<Int>()
		val colLocations = HashSet<Int>()
		for (site in validSites) {
			rowLocations.add(site.instanceY)
			colLocations.add(site.instanceX)
		}

		val sortedRows = ArrayList(rowLocations).sorted()
		val sortedCols = ArrayList(colLocations).sorted()

		rowRelocationMap = sortedRows.withIndex().map { it.value to it.index }.toMap()
		colRelocationMap = sortedCols.withIndex().map { it.value to it.index }.toMap()

		// Populate the coordinate system with the sites
		val dimensions = Dimensions(rowLocations.size, colLocations.size)
		grid = ArrayGrid(dimensions) { null }
		val validClusterSites = ArrayList<SiteClusterSite>()
		for (site in validSites) {
			val siteCoordinates = getSiteCoordinates(site)!!
			val clusterSite = SiteClusterSite(site, this, siteCoordinates)
			grid[siteCoordinates] = clusterSite
			validClusterSites += clusterSite
		}
		validClusterSites.trimToSize()
		this.validSites = validClusterSites
	}

	override val rectangle: Rectangle
		get() = grid.rectangle
	override val absolute: Rectangle
		get() = grid.absolute

	override fun get(row: Int, column: Int): SiteClusterSite? = grid[row, column]

	private fun getSiteCoordinates(site: Site): Coordinates? {
		val row = rowRelocationMap[site.instanceY]
		val col = colRelocationMap[site.instanceX]
		return if (row == null || col == null) null
		else Coordinates(row, col)
	}

	override fun getSiteCoordinates(site: SiteClusterSite): Coordinates? {
		val row = rowRelocationMap[site.site.instanceY]
		val col = colRelocationMap[site.site.instanceX]
		return if (row == null || col == null) null else Coordinates(row, col)
	}

	override fun getRelatedClusterSites(site: Site): List<SiteClusterSite> {
		val coordinates = getSiteCoordinates(site) ?: return emptyList()
		val clusterSite = get(coordinates)!!
		return listOf(clusterSite)
	}

	override fun getRelatedClusterSites(tile: Tile): List<SiteClusterSite> {
		return tile.sites.flatMap { getRelatedClusterSites(it) }
	}

	override fun contains(anchorSite: SiteClusterSite): Boolean {
		return if (anchorSite.grid === this)
			anchorSite.location in grid.rectangle
		else
			getSiteCoordinates(anchorSite) in grid.rectangle
	}

}

