package edu.byu.ece.rapidSmith.cad.cluster.site

import edu.byu.ece.rapidSmith.cad.cluster.PackUnit
import edu.byu.ece.rapidSmith.cad.place.annealer.ClusterSiteGrid
import edu.byu.ece.rapidSmith.cad.place.annealer.GroupPlacementRegion
import edu.byu.ece.rapidSmith.cad.place.annealer.GroupPlacementRegionFactory
import edu.byu.ece.rapidSmith.cad.place.annealer.PlacerDevice
import edu.byu.ece.rapidSmith.device.Site
import edu.byu.ece.rapidSmith.device.Tile
import edu.byu.ece.rapidSmith.util.ArrayGrid
import edu.byu.ece.rapidSmith.util.Dimensions
import edu.byu.ece.rapidSmith.util.Rectangle
import java.util.HashSet

/**
 *
 */
class SiteGroupPlacementRegionFactory : GroupPlacementRegionFactory<SitePackUnit, SiteClusterSite>() {
	override fun make(
		type: SitePackUnit,
		device: PlacerDevice<SiteClusterSite>
	): SiteGroupPlacementRegion {
		val locations = device.grid.sites.filter { it.isCompatibleWith(type) }
		return SiteGroupPlacementRegion(type, locations)
	}
}

/**
 *
 */
class SiteGroupPlacementRegion(
	private val packUnit: SitePackUnit,
	validSites: List<SiteClusterSite>
) : GroupPlacementRegion<SiteClusterSite>(packUnit) {

	/**
	 * A two dimensional representation of the coordinate system. This is
	 * handy for getting coordinates based on the x,y locations.
	 */
	private val rowRelocationMap: Map<Int, Int>
	private val colRelocationMap: Map<Int, Int>
	override val validSites: List<SiteClusterSite>

	init {
		// Create a set of these sites and keep track of the maximum height and width
		val rowLocations = HashSet<Int>()
		val colLocations = HashSet<Int>()
		for (site in validSites) {
			rowLocations.add(site.location.row)
			colLocations.add(site.location.column)
		}

		val sortedRows = ArrayList(rowLocations).sorted()
		val sortedCols = ArrayList(colLocations).sorted()

		rowRelocationMap = sortedRows.withIndex().map { it.value to it.index }.toMap()
		colRelocationMap = sortedCols.withIndex().map { it.value to it.index }.toMap()

		// Populate the coordinate system with the sites
		val validClusterSites = ArrayList<SiteClusterSite>()
		for (site in validSites) {
			validClusterSites += site
		}
		validClusterSites.trimToSize()
		this.validSites = validClusterSites
	}

	override val area: Int
		get() = Int.MAX_VALUE

	override fun toString(): String {
		return "PlacementGrid[$packUnit]: (all)"
	}
}
