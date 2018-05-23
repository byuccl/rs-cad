package edu.byu.ece.rapidSmith.cad.cluster.site

import com.caucho.hessian.io.Hessian2Input
import com.caucho.hessian.io.Hessian2Output
import edu.byu.ece.rapidSmith.cad.cluster.PackUnitList
import edu.byu.ece.rapidSmith.device.*
import edu.byu.ece.rapidSmith.util.getWireConnections
import java.awt.Point
import java.util.*

typealias PinName = String

// TODO document this

/**
 * Generates pack units defining a site-level packing for this device.
 */
abstract class SitePackUnitGenerator {
	protected abstract val PACKABLE_SITE_TYPES: List<SiteType>
	protected abstract val NULL_TILE_TYPE: TileType
	protected abstract val TIEOFF_SITE_TYPE: SiteType
	protected abstract val SWITCH_MATRIX_TILES: Set<TileType>
	protected abstract val INTERFACE_TILES: Set<TileType>
	protected abstract val VERSION: String
	protected abstract val VCC_SOURCES: Map<BelId, PinName>
	protected abstract val GND_SOURCES: Map<BelId, PinName>

	abstract protected fun findClusterInstances(siteType: SiteType, device: Device): List<Site>

	private var numBuiltTiles = 0
	private val tileMapsMap = HashMap<SiteType, Map<Site, Map<Tile, Tile>>>()

	fun buildFromDevice(device: Device): PackUnitList<SitePackUnit> {
		val templates = makePackUnits(device)
		val drivers = buildDrivesGeneralFabric(device, SWITCH_MATRIX_TILES)
		val drivens = buildDrivenByGeneralFabric(device, SWITCH_MATRIX_TILES)
		return PackUnitList(VERSION, device.partName, templates, drivers, drivens)
	}

	private fun makePackUnits(device: Device): ArrayList<SitePackUnit> {
		val packUnits = ArrayList<SitePackUnit>()
		val instancesMap = HashMap<SiteType, List<Site>>()

		// for packable tile sets, find all instances of them on the device
		println("Finding clusterChain instances")
		for (clusterSiteType in PACKABLE_SITE_TYPES) {
			val clusterInstances = findClusterInstances(clusterSiteType, device)
			if (clusterInstances.isEmpty()) {
				println("No instances of type " + clusterSiteType)
				continue
			}
			instancesMap.put(clusterSiteType, clusterInstances)
		}

		// Build the new tiles in the cluster
		println("Building tiles")
		for ((type, siteTemplates) in instancesMap) {
			siteTemplates.forEach { setMode(type, it) }

			val builder = SitePackUnitTemplate.Builder()
			val (puDevice, tileMaps) = buildPackUnitDevice(type, device, siteTemplates)
			builder.device = puDevice
			tileMapsMap[type] = tileMaps

			builder.gndSources = findStaticSources(puDevice.tiles, GND_SOURCES)
			builder.vccSources = findStaticSources(puDevice.tiles, VCC_SOURCES)

			val baseInstance = siteTemplates[0]
			val baseTileMap = tileMaps[baseInstance]!!
			val actualInstance = baseTileMap[baseInstance.tile]!!.getSite(0)

			setSiteAndBels(builder, baseInstance, baseTileMap)
			val (inputs, outputs) = OutputsAndInputsFinder(SWITCH_MATRIX_TILES)
				.traverse(baseInstance, actualInstance, baseTileMap)
			builder.inputs = inputs
			builder.outputs = outputs.toList()

			findDirectSourcesAndSinks(builder, tileMaps, siteTemplates, actualInstance)
			val template = builder.build()
			packUnits += SitePackUnit(SitePackUnitType(type), template)
		}

		return packUnits
	}

	private fun buildPackUnitDevice(
		type: SiteType,
		oldDevice: Device,
		templateSites: List<Site>
	): Pair<Device, Map<Site, Map<Tile, Tile>>> {
		val tileMaps = HashMap<Site, Map<Tile, Tile>>()
		val tilePointMap = HashMap<Point, Tile>()

		for (template in templateSites) {
			val tileSet = HashSet<Tile>()
			tileSet.add(template.tile)
			findSwitchMatrices(template, tileSet)

			for (oldTile in tileSet) {
				val columnOffset = oldTile.column - template.tile.column
				val rowOffset = oldTile.row - template.tile.row
				val tileOffset = Point(columnOffset, rowOffset)

				val newTile = tilePointMap.computeIfAbsent(tileOffset) { Tile() }
				if (newTile.name == null) {
					newTile.name = "unnamedTile" + numBuiltTiles++
					newTile.type = oldTile.type

					newTile.sites = buildSites(oldTile, newTile, template)
					newTile.wireSites = buildWireSites(oldTile, template)
				}
			}

			// the map from instance tiles to template tiles
			val tileMap = buildTileMap(tilePointMap, tileSet, template)
			tileMaps[template] = tileMap // save this for later reference
		}

		val matrix = makeTilesMatrix(tilePointMap)
		val device = makeDevice(oldDevice, matrix)
		device.constructTileMap()
		device.constructDependentResources()
		device.wireEnumerator = makeWireEnumerator(oldDevice.wireEnumerator)
//		val usedWires = makeDeviceRouting(templateSites, tileMaps, type, tilePointMap)
//		device.wireEnumerator = makeWireEnumerator(oldDevice.wireEnumerator, usedWires)

		return Pair(device, tileMaps)
	}

	private fun makeDeviceRouting(
		templates: List<Site>, tileMaps: HashMap<Site, Map<Tile, Tile>>,
		type: SiteType, tilePointMap: HashMap<Point, Tile>
	): Set<Int> {
		val actual = getActualSite(templates, tileMaps)
		val (forwardRouting, reverseRouting, usedWires) =
			buildClusterRouting(type, templates, actual, tileMaps)
		tilePointMap.values.forEach { tile ->
			tile.wireHashMap = forwardRouting[tile] ?: WireHashMap.EMPTY_WIRE_HASHMAP
			tile.setReverseWireConnections(
				reverseRouting[tile] ?: WireHashMap.EMPTY_WIRE_HASHMAP)
		}
		return usedWires
	}

	private fun getActualSite(templates: List<Site>, tileMaps: HashMap<Site, Map<Tile, Tile>>): Site {
		val templateSite = templates.first()
		val templateTile = templateSite.tile
		return tileMaps[templateSite]!![templateTile]!!.getSite(0)
	}

	private fun isMainTile(oldTile: Tile, instance: Site) = oldTile === instance.tile

	private fun findSwitchMatrices(root: Site, tileSet: MutableSet<Tile>) {
		findSwitchMatrices(root, tileSet, true)
		findSwitchMatrices(root, tileSet, false)
	}

	private fun findSwitchMatrices(root: Site, tileSet: MutableSet<Tile>, forward: Boolean) {
		val wireQueue: Queue<Wire> = ArrayDeque()
		val queuedWires = HashSet<Wire>()
		val sourcePins = if (forward) root.sourcePins else root.sinkPins
		for (sourcePin in sourcePins) {
			val sourceWire = sourcePin.externalWire
			wireQueue.add(sourceWire)
			queuedWires.add(sourceWire)
		}

		while (!wireQueue.isEmpty()) {
			val sourceWire = wireQueue.poll()

			for (conn in sourceWire.getWireConnections(forward)) {
				val sinkWire = conn.sinkWire
				val sinkTile = sinkWire.tile
				if (root.tile === sinkTile || sinkTile.type in INTERFACE_TILES) {
					// add wire connection to stack
					if (sinkWire !in queuedWires) {
						wireQueue += sinkWire
						queuedWires += sinkWire
					}
					tileSet.add(sinkTile)
				} else if (sinkTile.type in SWITCH_MATRIX_TILES) {
					tileSet.add(sinkTile)
				}
			}
		}
	}

	// creates a map from the tiles in the instance to the tiles in the template
	private fun buildTileMap(
		tileMap: Map<Point, Tile>, tileSet: Set<Tile>, instance: Site
	): Map<Tile, Tile> {
		val instanceTile = instance.tile
		val retMap = HashMap<Tile, Tile>()
		for (tile in tileSet) {
			val columnOffset = tile.column - instanceTile.column
			val rowOffset = tile.row - instanceTile.row
			val tileOffset = Point(columnOffset, rowOffset)
			retMap.put(tile, tileMap[tileOffset]!!)
		}
		return retMap
	}

	private fun buildSites(oldTile: Tile, newTile: Tile, instance: Site): Array<Site>? {
		if (oldTile.sites == null)
			return null

		return if (isMainTile(oldTile, instance)) {
			Array(1) {
				val newSite = copySite(newTile, instance)
				newSite.index = 0
				val types = arrayOf<SiteType>(instance.type)
				newSite.possibleTypes = types
				val externalWires = HashMap(instance.externalWires)
				externalWires.keys.removeIf({ type -> type !== instance.type })
				newSite.externalWires = externalWires
				newSite
			}
		} else {
			val sites = oldTile.sites
			Array(sites.size) { copySite(newTile, sites[it]) }
		}
	}

	private fun buildWireSites(oldTile: Tile, instance: Site): Map<Int, Int>? {
		return if (isMainTile(oldTile, instance)) {
			val wireSites = HashMap(oldTile.wireSites)
			wireSites.values.removeIf({ idx -> idx != instance.index })
			wireSites.entries.forEach { e -> e.setValue(0) }
			wireSites
		} else if (oldTile.wireSites != null) {
			  HashMap(oldTile.wireSites)
		} else {
			null
		}
	}

	private fun copySite(newTile: Tile, oldSite: Site): Site {
		val newSite = Site()
		newSite.index = oldSite.index
		newSite.tile = newTile
		val type = oldSite.type!!
		val possTypes = arrayOf(type)
		newSite.possibleTypes = possTypes

		val externalWires = HashMap<SiteType, MutableMap<String, Int>>(1, 100.0f)
		externalWires.put(type, oldSite.externalWires[type]!!)
		newSite.externalWires = externalWires
		return newSite
	}

	private fun makeTilesMatrix(
		tilePointMap: Map<Point, Tile>
	): List<List<Tile?>> {
		val leftCol = tilePointMap.keys.stream().mapToInt { p -> p.x }.min().asInt
		val rightCol = tilePointMap.keys.stream().mapToInt { p -> p.x }.max().asInt
		val topRow = tilePointMap.keys.stream().mapToInt { p -> p.y }.min().asInt
		val botRow = tilePointMap.keys.stream().mapToInt { p -> p.y }.max().asInt

		return (0..botRow - topRow).map { j ->
			(0..rightCol - leftCol).map {
				tilePointMap[Point(it + leftCol, j + topRow)]
			}
		}
	}

	private fun nameTilesAndSites(tiles: Iterable<Tile>) {
		for (tile in tiles) {
			val tileName = "${tile.type}_X${tile.row}Y${tile.column}"
			tile.name = tileName
			tile.sites?.let { sites ->
				for (site in sites) {
					val index = site.index
					site.name = "${tileName}_${site.defaultType}_X${index}Y$index"
				}
			}
		}
	}

	private fun setSiteAndBels(
		packUnit: SitePackUnitTemplate.Builder,
		instance: Site,
		tilesMap: Map<Tile, Tile>
	) {
		val newTile = tilesMap[instance.tile]!!
		assert(newTile.sites.size == 1)
		val newSite = newTile.getSite(0)
		packUnit.bels = newSite.bels.toList()
		packUnit.site = newSite
	}

	private fun makeDevice(
		oldDevice: Device,
		tileMatrix: List<List<Tile?>>
	): Device {
		val newDevice = Device()
		val tileArray = makeTileArray(tileMatrix, newDevice)
		val tiles = tileArray.flatten()
		nameTilesAndSites(tiles)
		newDevice.partName = oldDevice.partName
		newDevice.family = oldDevice.family
		newDevice.setTileArray(tileArray)
		newDevice.siteTemplates = filterSiteTemplates(oldDevice.siteTemplates, tiles)
		setSiteTypes(tiles)
		newDevice.routeThroughMap = filterRouteThroughs(oldDevice.routeThroughMap, tiles)
		return newDevice
	}

	private fun makeTileArray(
		tileMatrix: List<List<Tile?>>, device: Device
	): Array<Array<Tile>> {
		val maxColumns = tileMatrix.map { it.size }.max()!!
		return Array(tileMatrix.size) { y ->
			val row = tileMatrix[y]
			Array(maxColumns) { x ->
				val tile = if (x < row.size && row[x] != null) {
					row[x]!!
				} else {
					val t = Tile()
					t.type = NULL_TILE_TYPE
					t.name = "${NULL_TILE_TYPE.name()}_X${x}Y$y"
					t.wireHashMap = WireHashMap.EMPTY_WIRE_HASHMAP
					t.setReverseWireConnections(WireHashMap.EMPTY_WIRE_HASHMAP)
					t
				}
				tile.row = y
				tile.column = x
				tile.device = device
				tile
			}
		}
	}

	private fun filterRouteThroughs(
		oldRTs: Map<Int, Map<Int, PIPRouteThrough>>,
		tiles: Iterable<Tile>
	): Map<Int, Map<Int, PIPRouteThrough>> {
		val sites = tiles.asSequence()
			.flatMap { it.sites?.asSequence() ?: emptySequence() }
			.toList()
		val siteTypes = sites.map { it.type }.toSet()

		val flattened = oldRTs.flatMap { (source, v) ->
			v.map { Triple(source, it.key, it.value) }
		}

		val pinwiresMap = sites.map { it to findSiteSourcesAndSinks(it) }.toMap()
		val newRouteThroughs = HashMap<Int, MutableMap<Int, PIPRouteThrough>>()
		sites.map {
				val pinwires = pinwiresMap[it]!!
				// keep only if this route through is used in the device
				flattened.filter {
					it.third.type in siteTypes &&
						it.first in pinwires &&
						it.second in pinwires
			}.forEach {
				newRouteThroughs.computeIfAbsent(it.first) { HashMap() }[it.second] = it.third
			}
		}
		return newRouteThroughs
	}

	private fun findSiteSourcesAndSinks(site: Site): Set<Int> {
		return (site.sourcePins + site.sinkPins)
			.map { it.externalWire }
			.map { it.wireEnum }
			.toSet()
	}

	private fun filterSiteTemplates(
		oldTemplates: Map<SiteType, SiteTemplate>,
		tiles: Iterable<Tile>
	): Map<SiteType, SiteTemplate> {
		val siteTypes = tiles.asSequence()
			.flatMap { it.sites?.asSequence() ?: emptySequence() }
			.flatMap { it.possibleTypes.asSequence() } // type not yet set
			.toSet()

		return oldTemplates.filter { it.key in siteTypes }
	}

	private fun setSiteTypes(tiles: Iterable<Tile>) {
		for (tile in tiles) {
			for (site in tile.sites ?: emptyArray()) {
				site.type = site.defaultType
			}
		}
	}

	private fun makeWireEnumerator(
		oldWE: WireEnumerator,
		usedWires: Set<Int> = emptySet()
	): WireEnumerator {
//		val maxWire = usedWires.max()!!
//		val wireDirections = arrayOfNulls<WireDirection>(maxWire+1)
//		val wireTypes = arrayOfNulls<WireType>(maxWire+1)
//		val wireNames = arrayOfNulls<String>(maxWire+1)
//		val we = WireEnumerator()
//		for (enum in usedWires) {
//			wireNames[enum] = oldWE.getWireName(enum)
//		}
//		we.wireDirections = wireDirections
//		we.wireTypes = wireTypes
//		we.wires = wireNames
//		we.wireMap = wireNames.withIndex()
//			.filter { it.value != null }
//			.map { it.value!! to it.index }
//			.toMap()
//		return we
		return oldWE
	}

	private fun Sequence<Wire>.andSinks(): Sequence<Sequence<Wire>> {
		return map {
			val sinks = it.wireConnections.asSequence()
				.map { it.sinkWire }
			sequenceOf(it) + sinks
		}
	}

	private fun buildClusterRouting(
		type: SiteType,
		templates: List<Site>,
		actual: Site,
		tileMaps: Map<Site, Map<Tile, Tile>>
	): Triple<Map<Tile, WireHashMap>, Map<Tile, WireHashMap>, Set<Int>> {
		System.out.println("Building clusterChain routing for " + type)
		val crb = ClusterRoutingBuilder(SWITCH_MATRIX_TILES)
		templates.forEach { crb.traverse(it, actual, tileMaps[it]!!) }
		crb.finish()
		return Triple(crb.forward!!, crb.reverse!!, crb.usedWires)
	}

	private fun findStaticSources(
		tileSet: Collection<Tile>, sources: Map<BelId, PinName>
	): Set<BelPin> {
		val sourcePins = HashSet<BelPin>()
		for (tile in tileSet) {
			val sites = tile.sites ?: continue
			for (site in sites) {
				if (site.type === TIEOFF_SITE_TYPE)
					continue
				for (bel in site.bels) {
					val pinName = sources[bel.id] ?: continue
					sourcePins += bel.getBelPin(pinName)
				}
			}
		}
		return sourcePins
	}

	private fun findDirectSourcesAndSinks(
		template: SitePackUnitTemplate.Builder,
		tileMaps: Map<Site, Map<Tile, Tile>>,
		templates: List<Site>,
		actualInstance : Site
	) {
		val finder = DirectConnectionFinder(SWITCH_MATRIX_TILES)

		for (instance in templates) {
			val tileMap = tileMaps[instance]!!
			finder.findSourcesAndSinks(instance, actualInstance, tileMap)
		}

		val (sources, sinks) = finder.finish()
		template.directSourcesOfCluster = sources
		template.directSinksOfCluster = sinks
	}
}

internal fun setMode(siteType: SiteType, instance: Site) {
	if (siteType in instance.possibleTypes)
		instance.type = siteType
	else
		System.err.println("Instance $instance cannot be cast to type $siteType")
}


// TODO move to utils package
inline fun <T : Hessian2Input?, R> T.use(block: (T) -> R): R {
	var closed = false
	try {
		return block(this)
	} catch (e: Exception) {
		closed = true
		try {
			this?.close()
		} catch (closeException: Exception) {
		}
		throw e
	} finally {
		if (!closed) {
			this?.close()
		}
	}
}


inline fun <T : Hessian2Output?, R> T.use(block: (T) -> R): R {
	var closed = false
	try {
		return block(this)
	} catch (e: Exception) {
		closed = true
		try {
			this?.close()
		} catch (closeException: Exception) {
		}
		throw e
	} finally {
		if (!closed) {
			this?.close()
		}
	}
}


