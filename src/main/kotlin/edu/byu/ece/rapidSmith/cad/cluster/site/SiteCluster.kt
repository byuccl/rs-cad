package edu.byu.ece.rapidSmith.cad.cluster.site

import edu.byu.ece.rapidSmith.RSEnvironment
import edu.byu.ece.rapidSmith.cad.cluster.*
import edu.byu.ece.rapidSmith.cad.place.annealer.Coordinates
import edu.byu.ece.rapidSmith.design.subsite.Cell
import edu.byu.ece.rapidSmith.design.subsite.CellDesign
import edu.byu.ece.rapidSmith.device.*
import edu.byu.ece.rapidSmith.interfaces.vivado.VivadoInterface
import edu.byu.ece.rapidSmith.util.Exceptions.DesignAssemblyException
import java.nio.file.Path

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
		return template.relocateBelPin(belPin, newAnchor)
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
	override val location: Coordinates,
	override val tileLocation: Coordinates
) : ClusterSite() {
	constructor(
		site: Site, location: Coordinates
	) : this(site, location, getTileIndex(site))

	override fun toString(): String {
		return "SiteClusterSite{$site}"
	}

	override fun isCompatibleWith(packUnit: PackUnit): Boolean {
		if (packUnit !is SitePackUnit) return false

		if (packUnit.siteType == site.defaultType)
			return true

		val device = site.tile.device
		val siteTemplate = device.getSiteTemplate(packUnit.siteType)
		return if (siteTemplate.compatibleTypes != null)
			site.defaultType in siteTemplate.compatibleTypes
		else
			false
	}

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

fun CellDesign.convertToSiteClusterDesign(packUnits: PackUnitList<SitePackUnit>)
	: List<Cluster<SitePackUnit, SiteClusterSite>> {

	val puMap = packUnits.packUnits.associateBy { it.siteType }
	val clusterDesign = ClusterDesign<SitePackUnit, SiteClusterSite>()

	val clusters = HashMap<Site, SiteCluster>()
	for (cell in inContextLeafCells) {
		if (!cell.isGndSource && !cell.isVccSource) {
			val bel = requireNotNull(cell.bel) { "Unplaced cells not allowed" }
			val site = bel.site

			val cluster = clusters.computeIfAbsent(site) {
				val pu = requireNotNull(puMap[site.type]) { "No pack unit for site: ${site.type}" }
				SiteCluster(site.name, pu)
			}
			val puBel = cluster.type.template.bels.single { bel.name == it.name }
			cluster.addCell(puBel, cell)
		}
	}

	for (net in nets) {
		for ((_, sourceTree) in net.belPinRouteTrees) {
			println(sourceTree)
		}
	}

	return clusterDesign.clusters.toList()
}

fun main(args: Array<String>) {
	val vcp = VivadoInterface.loadRSCP(args[0])
	val design = vcp.design
	val device = vcp.device
	val family = device.family

	val partsFolder = RSEnvironment.defaultEnv().getPartFolderPath(family)
	val packUnitsPath: Path = partsFolder.resolve("packunits-site.rpu")

	val packUnits = loadPackUnits<SitePackUnit>(packUnitsPath)
	design.convertToSiteClusterDesign(packUnits)
}
