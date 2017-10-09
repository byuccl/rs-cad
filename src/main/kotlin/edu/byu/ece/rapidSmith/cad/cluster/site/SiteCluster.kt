package edu.byu.ece.rapidSmith.cad.cluster.site

import edu.byu.ece.rapidSmith.cad.cluster.Cluster
import edu.byu.ece.rapidSmith.cad.cluster.ClusterFactory
import edu.byu.ece.rapidSmith.cad.cluster.ClusterSite
import edu.byu.ece.rapidSmith.cad.cluster.PackUnitList
import edu.byu.ece.rapidSmith.cad.place.annealer.ClusterSiteGrid
import edu.byu.ece.rapidSmith.cad.place.annealer.Coordinates
import edu.byu.ece.rapidSmith.design.subsite.Cell
import edu.byu.ece.rapidSmith.device.*
import edu.byu.ece.rapidSmith.util.Exceptions.DesignAssemblyException

/**
 *
 */
class SiteCluster(
	name: String, packUnit: SitePackUnit
) : Cluster<SitePackUnit, SiteClusterSite>
(name, packUnit, packUnit.template.anchor), Comparable<SiteCluster> {
	override val isPlaceable: Boolean
		get() = true

	private val template: SitePackUnitTemplate
		get() = type.template

	override fun relocateTo(site: SiteClusterSite) {
		relocate(site.site.getBel(anchor.id))
	}

	override fun getRelocatedBel(bel: Bel, newAnchor: Bel): Bel {
		return template.relocateBel(bel, anchor, newAnchor)
	}

	override fun getRelocatedBelPin(belPin: BelPin, newAnchor: Bel): BelPin {
		return template.relocateBelPin(belPin, anchor, newAnchor)
	}

	override fun getRelocatedWire(wire: Wire, newAnchor: Bel): Wire {
		return template.relocateWire(wire, anchor, newAnchor)
	}

	override fun getRelocatedConnection(sourceWire: Wire, connection: Connection, newAnchor: Bel): Connection {
		return template.relocateConnection(sourceWire, connection, anchor, newAnchor)
	}

	override fun addCell(bel: Bel, cell: Cell): Cell {
		if (!template.bels.contains(bel))
			throw DesignAssemblyException("Bel is in a different site from " + "this clusters template.")

		return super.addCell(bel, cell)
	}

	override fun compareTo(other: SiteCluster): Int {
		return cost.compareTo(other.cost)
	}


	/*
	private void validateActualLocation(Bel actualBel) {
		assert actualBel.getSite().getIndex() == getAnchorBel().getSite().getIndex();
		assert actualBel.getId().equals(getAnchorBel().getId());
	}
*/
}

class SiteClusterSite(
	val site: Site,
	override val grid: ClusterSiteGrid<*>,
	location: Coordinates,
	override val tileLocation: Coordinates
) : ClusterSite(location) {
	constructor(
		site: Site, grid: ClusterSiteGrid<SiteClusterSite>, location: Coordinates
	) : this(site, grid, location, getTileIndex(site))

	override fun toString(): String {
		return "SiteClusterSite{$site}"
	}

	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (other !is SiteClusterSite) return false
		if (site != other.site) return false

		return true
	}

	override fun hashCode(): Int = site.hashCode()

	companion object {
		private fun getTileIndex(site: Site): Coordinates {
			return with(site.tile) { Coordinates(tileYCoordinate, tileXCoordinate) }
		}
	}
}

class SiteClusterFactory(
	packUnits: PackUnitList<SitePackUnit>,
	device: Device,
	private val sharedTypes: Map<SiteType, List<SiteType>>,
	compatibleTypes: Map<SiteType, List<SiteType>>
) : ClusterFactory<SitePackUnit, SiteClusterSite> {
	private val numUsedSites: MutableMap<SiteType, Int> = HashMap()
	private val numAvailableTypes: Map<SiteType, Int>

	override val supportedPackUnits = ArrayList(packUnits)

	init {
		numAvailableTypes = packUnits.map {
			val siteType = it.siteType
			it.siteType to getPossibleSiteCount(device, siteType, compatibleTypes)
		}.toMap()
		packUnits.forEach { numUsedSites[it.siteType] = 0 }
	}

	private fun getPossibleSiteCount(
		device: Device, siteType: SiteType,
		compatibleTypes: Map<SiteType, List<SiteType>>
	): Int {
		val defaultSites = device.getAllSitesOfType(siteType)?.count() ?: 0
		val compatibleSites = compatibleTypes[siteType]?.sumBy {
			device.getAllSitesOfType(it)?.count() ?: 0
		} ?: 0
		return defaultSites + compatibleSites
	}

	override fun init() {
		numUsedSites.entries.forEach { it.setValue(0) }
	}

	override fun getNumRemaining(packUnit: SitePackUnit): Int {
		return numAvailableTypes[packUnit.siteType]!! - numUsedSites[packUnit.siteType]!!
	}

	override fun createNewCluster(clusterName: String, packUnit: SitePackUnit)
		: Cluster<SitePackUnit, SiteClusterSite> {
		return SiteCluster(clusterName, packUnit)
	}

	override fun commitCluster(cluster: Cluster<SitePackUnit, *>) {
		val clusterType = cluster.type
		val siteType = clusterType.type.type
		numUsedSites.compute(siteType) { _, v -> v!! + 1 }

		sharedTypes[siteType]?.forEach {
			numUsedSites.compute(it) { _, v -> v!! + 1 }
		}
	}
}

