package edu.byu.ece.rapidSmith.cad.cluster.site

import edu.byu.ece.rapidSmith.device.*
import edu.byu.ece.rapidSmith.util.getWireConnections
import java.util.*


fun buildDrivesGeneralFabric(
	device: Device, switchboxTypes: Collection<TileType>
): Map<BelId, Set<String>> {
	return buildDrivesGeneralFabric(device, switchboxTypes, true)
}

fun buildDrivenByGeneralFabric(
	device: Device, switchboxTypes: Collection<TileType>
): Map<BelId, Set<String>> {
	return buildDrivesGeneralFabric(device, switchboxTypes, false)
}

private fun buildDrivesGeneralFabric(
	device: Device, switchboxTypes: Collection<TileType>, forward: Boolean
): Map<BelId, Set<String>> {
	val sitePinDrivers = LinkedHashMap<SiteType, Set<String>>()
	for (site in device.sites.values) {
		buildSitePinDriversForSite(site, switchboxTypes, forward, sitePinDrivers)
	}
	val belPinToSitePinMap = LinkedHashMap<BelPinTemplate, List<SitePinTemplate>>()
	sitePinDrivers.keys.forEach {
		belPinToSitePinMap.putAll(buildBelPinsToSitePinsMap(it, device, forward))
	}
	return buildBelPinDrivers(sitePinDrivers, belPinToSitePinMap)
}

private fun buildBelPinsToSitePinsMap(
	siteType: SiteType, device: Device, forward: Boolean
): Map<BelPinTemplate, List<SitePinTemplate>> {
	val siteTemplate = device.getSiteTemplate(siteType)
	return siteTemplate.belTemplates.values.flatMap { bel ->
		val sources = if (forward) bel.sources.values else bel.sinks.values
		sources.map { it to findSitePins(siteTemplate, it, forward) }
	}.toMap()
}

private typealias WireEnum = Int

fun findSitePins(
	siteTemplate: SiteTemplate,
	pinTemplate: BelPinTemplate,
	forward: Boolean
): List<SitePinTemplate> {
	fun isRouteThrough(source: Int, sink: Int) =
		if (forward)
			siteTemplate.isRoutethrough(source, sink)
		else
			siteTemplate.isRoutethrough(sink, source)

	val q: Queue<WireEnum> = ArrayDeque()
	val queued = LinkedHashSet<Int>()

	q += pinTemplate.wire
	queued += pinTemplate.wire

	val pins = if (forward) siteTemplate.sources else siteTemplate.sinks
	val pinsMap = pins.map { it.value.internalWire to it.value }.toMap()
	val whm = if (forward) siteTemplate.routing else siteTemplate.reversedRouting

	val sitePins = ArrayList<SitePinTemplate>()
	while (q.isNotEmpty()) {
		val wire = q.poll()

		val sitePin = pinsMap[wire]
		if (sitePin != null)
			sitePins += sitePin

		whm.get(wire)?.filter { it.wire !in queued }
			?.filterNot { isRouteThrough(wire, it.wire) }
			?.forEach {
				q += it.wire
				queued += it.wire
			}
	}

	return sitePins
}

private fun buildBelPinDrivers(
	sitePinDrivers: HashMap<SiteType, Set<String>>,
	belPinsToSitePinsMap: Map<BelPinTemplate, List<SitePinTemplate>>
): Map<BelId, Set<String>> {
	return belPinsToSitePinsMap.filter { (bp, sps) ->
		val id = bp.id
		val drivingSitePins = sitePinDrivers[id.siteType]!!
		sps.any { it.name in drivingSitePins }
	}.map { (bp, _) -> bp }
		.groupBy { it.id }
		.mapValues { (_, it) -> it.map { it.name }.toSet() }
}

private fun buildSitePinDriversForSite(
	site: Site, switchboxTypes: Collection<TileType>, forward: Boolean,
	sitePinDrivers: HashMap<SiteType, Set<String>>
): Map<SiteType, Set<String>> {
	site.possibleTypes.map { type ->
		sitePinDrivers.computeIfAbsent(type) {
			val pins = if (forward) site.getSourcePins(type) else site.getSinkPins(type)
			pins.filter { sitePinDrivesGeneralFabric(it, switchboxTypes, forward) }
				.map { it.name }
				.toSet()
		}
	}
	return sitePinDrivers
}

private fun sitePinDrivesGeneralFabric(
	sitePin: SitePin, switchboxTypes: Collection<TileType>, forward: Boolean
): Boolean {
	val q: Queue<Wire> = ArrayDeque()
	q += sitePin.externalWire
	val queued = LinkedHashSet<Wire>()
	queued += sitePin.externalWire

	while (q.isNotEmpty()) {
		val wire = q.poll()
		if (wire.tile.type in switchboxTypes)
			return true

		for (c in wire.getWireConnections(forward)) {
			val sink = c.sinkWire
			if (sink !in queued) {
				q += sink
				queued += sink
			}
		}
	}
	return false
}

