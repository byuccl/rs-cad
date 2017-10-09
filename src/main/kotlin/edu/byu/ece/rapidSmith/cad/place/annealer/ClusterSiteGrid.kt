package edu.byu.ece.rapidSmith.cad.place.annealer

import edu.byu.ece.rapidSmith.cad.cluster.ClusterSite
import edu.byu.ece.rapidSmith.cad.cluster.PackUnit
import edu.byu.ece.rapidSmith.cad.cluster.site.SiteClusterSite
import edu.byu.ece.rapidSmith.device.Site
import edu.byu.ece.rapidSmith.device.Tile
import edu.byu.ece.rapidSmith.util.Grid
import edu.byu.ece.rapidSmith.util.Index
import edu.byu.ece.rapidSmith.util.Offset

typealias Coordinates = Index

abstract class ClusterSiteGridFactory<in C: PackUnit, out S: ClusterSite> {
	abstract fun make(type: C): ClusterSiteGrid<S>
}

/**
 * Represents a set of ClusterSites for a given PackUnit on a device. Each
 * PackUnit has its own coordinate system and this class provides an interface to
 * this coordinate system.
 *
 * Note that not all sites in this coordinate system are valid.
 */
abstract class ClusterSiteGrid<out S: ClusterSite>(
	val type: PackUnit
) : Grid<S?> {
	/** The set of valid sites associated with this coordinate system. */
	abstract val validSites: List<S>

	abstract fun getRelatedClusterSites(site: Site): List<S>

	abstract fun getRelatedClusterSites(tile: Tile): List<S>

	abstract fun getSiteCoordinates(site: @UnsafeVariance S): Coordinates?

	/**
	 * Determines if the given site is a valid location within the area constraint.
	 */
	abstract operator fun contains(anchorSite: @UnsafeVariance S): Boolean

	/**
	 * Determine the area of the placement constraint.
	 */
	open val area: Int
		get() = rectangle.height * rectangle.width

	override fun toString(): String = "Grid: $type ($rectangle)"

	fun getOrNull(coords: Coordinates): S? {
		return if (coords in rectangle) get(coords) else null
	}
}

/**
 * Returns the sites that group [g] will use if placed at site [anchor] or null
 * if [g] will not fit within this grid if placed at [anchor].
 */
fun <S: ClusterSite> ClusterSiteGrid<S>.getSitesForGroup(g: PlacementGroup<S>, anchor: S): Set<S>? {
	val anchorLoc = if (anchor.grid === this) anchor.location else getSiteCoordinates(anchor)
	anchorLoc ?: return null

	return g.usedOffsets.map { get(anchorLoc + it) ?: return null }.toSet()
}

/** Returns the site found at the location of [anchor] offset by [offset]. */
fun <S: ClusterSite> ClusterSiteGrid<S>.getOffsetSite(anchor: S, offset: Offset): S? {
	val anchorLoc = if (anchor.grid === this) anchor.location else getSiteCoordinates(anchor)
	anchorLoc ?: return null

	val offsetCoords = anchorLoc + offset
	return get(offsetCoords)
}

