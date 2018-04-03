package edu.byu.ece.rapidSmith.cad.cluster.site

import edu.byu.ece.rapidSmith.cad.cluster.DirectConnection
import edu.byu.ece.rapidSmith.cad.cluster.EndPackUnitIndex
import edu.byu.ece.rapidSmith.cad.cluster.EndSiteIndex
import edu.byu.ece.rapidSmith.device.*
import edu.byu.ece.rapidSmith.util.getBelPin
import edu.byu.ece.rapidSmith.util.getConnectedPin
import edu.byu.ece.rapidSmith.util.getWireConnections
import java.util.*
import kotlin.collections.HashSet

/**

 */
internal class DirectConnectionFinder(
	val SWITCH_MATRIX_TILES: Set<TileType>
) {
	private val sources: MutableList<DirectConnection> = ArrayList()
	private val sinks: MutableList<DirectConnection> = ArrayList()

	private var siteIndexes = HashMap<Site, EndSiteIndex>()
	private var newSites = HashMap<Int, SitePinPair>()
	// Map of BelPins to their EndSiteIndices.  This is stored as
	// siteIndex/PinName pairs to account for multiple templates
	private val belPinMap = HashMap<Int, HashMap<String, EndSiteIndex>>()
	private var nextUnusedIndex: Int = 0

	init {
		nextUnusedIndex = 0
		belPinMap.values.forEach { v ->
			val max = v.values.filterNotNull().max()!!
			if (nextUnusedIndex <= max)
				nextUnusedIndex = max + 1
		}
	}

	private class SitePinPair internal constructor(val site: Site, var sourcePin: BelPin) {
		val dc = ArrayList<DirectConnection>()
	}

	fun findSourcesAndSinks(
		template: Site, actual: Site, tileMap: Map<Tile, Tile>
	): DirectConnectionFinder {
		val sinksTraverser = Traverser(tileMap, sinks, true)
		val sourceTraverser = Traverser(tileMap, sources, false)

		for (bel in actual.bels) {
			for (sourcePin in bel.sources) {
				val templatePin = template.getBel(bel.name).getBelPin(sourcePin.name)
				sinksTraverser.run(templatePin)
			}
			for (sinkPin in bel.sinks) {
				val templatePin = template.getBel(bel.name).getBelPin(sinkPin.name)
				sourceTraverser.run(templatePin)
			}
		}

		siteIndexes.clear() // free up the memory
		newSites.clear() // free up the memory

		return this
	}

	fun finish(): Pair<List<DirectConnection>, List<DirectConnection>> {
		return Pair(sources.distinct(), sinks.distinct())
	}

	private inner class Traverser internal constructor(
		val tileMap: Map<Tile, Tile>,
		val dcs: MutableList<DirectConnection>,
		val forward: Boolean
	) {
		private val q: Queue<ExitWireWrapper> = ArrayDeque()
		private val visited = HashSet<Wire>()

		fun run(sourcePin: BelPin) {
			val untranslatedSourcePin = sourcePin
			val translatedSourcePin = translateBelPin(sourcePin, tileMap)

			q += ExitWireWrapper(sourcePin.wire)
			while (q.isNotEmpty()) {
				val wrapped = q.poll()
				val wire = wrapped.wire

				if (!visited.add(wire))
					continue

				wire.getWireConnections(forward).forEach { handleWireConnection(wrapped, it) }
				wire.getConnectedPin(forward)?.let {
					handlePinConnection(wrapped, it, untranslatedSourcePin, translatedSourcePin)
				}
			}

			assert(q.isEmpty())
			visited.clear()
		}

		fun handleWireConnection(source: ExitWireWrapper, c: Connection) {
			val sink = c.sinkWire

			// direct connections never pass through switch matrices
			if (sink.tile.type !in SWITCH_MATRIX_TILES) {
				// update the cluster exit.  The cluster exit is the last wire prior
				// to leaving the cluster ie entering a tile not in the tile map
				var exitWire = source.clusterExit
				if (exitWire == null && sink.tile !in tileMap)
					exitWire = translateWire(source.wire, tileMap)

				q.add(ExitWireWrapper(sink, exitWire, source.exitSite))
			}
		}

		fun handlePinConnection(
			source: ExitWireWrapper,
			sitePin: SitePin,
			untranslatedSourcePin: BelPin,
			translatedSourcePin: BelPin
		) {
			val sinkSite = sitePin.site
			val sinkTile = sinkSite.tile
			if (!source.exitedSite()) {
				// first time leaving the site, just exit it and continue traversing
				assert(source.exitSite == null)
				assert(source.clusterExit == null)

				// the site is marked as exited
				q += ExitWireWrapper(sitePin.externalWire, source.clusterExit, sinkSite)
			} else if (sinkSite != source.exitSite) {
				// Upon reaching another site, configure the site as all possible types and
				// search through it looking for direct connections

				// Connections back to the source site are never direct connections (they are
				// instead handle by cluster-level routing checks

				// If we haven't already left the cluster, entering into a new site exits the
				// cluster
				var exitWire = source.clusterExit
				if (exitWire == null) {
					exitWire = translateWire(source.wire, tileMap)
				}

				// Traverse through all possible configurations of the sink site.  Save the
				// original type to restore it at the end
				val origType = sinkSite.type
				for (type in sinkSite.possibleTypes) {
					sinkSite.type = type
					val typedSitePin = sinkTile.getSitePinOfWire(source.wire.wireEnum) ?: continue
					val siteWire = typedSitePin.internalWire
					val wrapper = ExitWireWrapper(siteWire, exitWire, null)
					val sinkSiteTraverser = SinkSiteTraverser(
						dcs, forward, untranslatedSourcePin, translatedSourcePin)
					sinkSiteTraverser.traverse(wrapper)
				}
				sinkSite.type = origType
			}
		}
	}

	private inner class SinkSiteTraverser internal constructor(
		private val dcs: MutableList<DirectConnection>,
		private val forward: Boolean,
		private val untranslatedSourcePin: BelPin,
		private val translatedSourcePin: BelPin
	) {
		private val q: Queue<ExitWireWrapper> = ArrayDeque()
		private val visited = HashSet<Wire>()

		fun traverse(source: ExitWireWrapper) {
			q += source
			while (q.isNotEmpty()) {
				val wire = q.poll()
				if (!visited.add(wire.wire))
					continue

				wire.wire.getWireConnections(forward).forEach {
					q += ExitWireWrapper(it.sinkWire, wire.clusterExit, wire.exitSite)
				}

				// ignore any exits from this BEL.  Should be a routethrough if such
				// a path exists anyway

				wire.wire.getBelPin(forward)?.let {
					handleTerminals(source, it)
				}
			}

			assert(q.isEmpty())
			visited.clear()
		}

		fun handleTerminals(source: ExitWireWrapper, sinkPin: BelPin) {
			val sinkSiteIndex = getSinkIndex(sinkPin, untranslatedSourcePin)
			// not using endSiteIndex
			val dc = DirectConnection(
				sinkPin.template, 0, sinkSiteIndex,
				translatedSourcePin, source.clusterExit!!
			)
			dcs.add(dc)
			val pair = newSites[sinkSiteIndex]
			pair?.dc?.add(dc)
		}
	}

	private class ExitWireWrapper(
		val wire: Wire,
		val clusterExit: Wire? = null,
		val exitSite: Site? = null
	) {
		fun exitedSite() = exitSite != null

		override fun equals(other: Any?): Boolean {
			if (this === other) return true
			if (other !is ExitWireWrapper) return false

			if (wire != other.wire) return false

			return true
		}

		override fun hashCode(): Int {
			return wire.hashCode()
		}

		override fun toString(): String {
			return "ExitWireWrapper(wire=$wire, clusterExit=$clusterExit, exitSite=$exitSite)"
		}


	}

	private fun getSinkIndex(sinkPin: BelPin, untranslatedSourcePin: BelPin): Int? {
		val sinkSite = sinkPin.bel.site
		var sinkSiteIndex = getBelPinMappedIndex(sinkPin)

		if (sinkSiteIndex == null) {
			// we haven't encountered this BelPin yet.  Check if we've seen the site
			// already, if so, use the already allocated index.  otherwise, create a new index
			sinkSiteIndex = siteIndexes.computeIfAbsent(sinkSite) {
				val value = nextUnusedIndex++ // the next available index
				val pair = SitePinPair(sinkSite, untranslatedSourcePin)
				newSites[value] = pair
				value
			}
			// store this guy in the bel pin index for later indexing
			setBelPinMappedIndex(sinkPin, sinkSiteIndex)
		} else {
			// we've seen this pin already, possibly in a different tile
			val existingSiteIndex = siteIndexes[sinkSite]
			if (existingSiteIndex != null) {
				if (existingSiteIndex != sinkSiteIndex) {
					if (existingSiteIndex in newSites) {
						val sitePinPair = newSites[existingSiteIndex]!!
						val oldSource = sitePinPair.sourcePin
						if (untranslatedSourcePin == oldSource) {
							newSites[sinkSiteIndex] = sitePinPair
							sinkSiteIndex = null
							updateIndices(sitePinPair, null) // multiple sinks for this pair
							sitePinPair.sourcePin = untranslatedSourcePin
						} else {
							newSites[sinkSiteIndex] = sitePinPair
							updateIndices(sitePinPair, sinkSiteIndex)
						}
					} else {
						assert(false) { "I can't do this" }
					}
				}
			} else {
				// we haven't seen this site yet, but we've seen an equivalent site in
				// a different tile.
				siteIndexes[sinkSite] = sinkSiteIndex
			}
		}
		return sinkSiteIndex
	}

	private fun updateIndices(pair: SitePinPair, newIndex: EndPackUnitIndex) {
		for (dc in pair.dc) {
			dc.endPackUnitIndex = newIndex
		}
		siteIndexes[pair.site] = newIndex
	}

	private fun getBelPinMappedIndex(sinkPin: BelPin): EndSiteIndex {
		var sinkIndex: Int? = null

		val sinkSite = sinkPin.bel.site
		val sinkSiteIndex = sinkSite.index
		val sinkPinName = sinkPin.name

		// look up the mapping of belpin to site index.  If this pin has
		// already been seen, it will be in here
		belPinMap[sinkSiteIndex]?.let { map ->
			map[sinkPinName]?.let { sinkIndex = it }
		}
		return sinkIndex
	}

	private fun setBelPinMappedIndex(sinkPin: BelPin, index: EndSiteIndex) {
		val sinkSite = sinkPin.bel.site
		belPinMap.computeIfAbsent(sinkSite.index) { HashMap() }.put(sinkPin.name, index)
	}
}

private fun translateBelPin(origPin: BelPin, tileMap: Map<Tile, Tile>): BelPin {
	val origBel = origPin.bel
	val origSite = origBel.site
	val origTile = origSite.tile

	val translatedTile = tileMap[origTile]!!
	val translatedSite = translatedTile.getSite(0)
	val translatedBel = translatedSite.getBel(origBel.name)
	return translatedBel.getBelPin(origPin.name)
}

private fun translateWire(origWire: Wire, tileMap: Map<Tile, Tile>): Wire {
	val translatedTile = tileMap[origWire.tile]!!

	return when (origWire) {
		is TileWire -> TileWire(translatedTile, origWire.getWireEnum())
		is SiteWire -> {
			val translatedSite = translatedTile.getSite(0)
			SiteWire(translatedSite, origWire.wireEnum)
		}
		else -> throw AssertionError("Illegal Value")
	}
}
