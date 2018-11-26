package edu.byu.ece.rapidSmith.cad.cluster.site

import edu.byu.ece.rapidSmith.device.*
import edu.byu.ece.rapidSmith.util.getConnectedPin
import edu.byu.ece.rapidSmith.util.getWireConnections
import java.util.*

/**
 *  Find outputs and inputs of cluster.
 */
class OutputsAndInputsFinder(val SWITCH_MATRIX_TILES: Set<TileType>) {
	val inputs: MutableMap<BelPin, List<Wire>> = HashMap()
	val outputs: MutableSet<Wire> = LinkedHashSet()

	fun traverse(template: Site, actual: Site, tileMap: Map<Tile, Tile>):
		Pair<Map<BelPin, List<Wire>>, Set<Wire>> {
		val oTraverser = Traverser(tileMap, true, outputs)

		// template may be of different type than the pack unit type
		// use the actual to get the pins of interest, template should have all
		// matching pins

		for (actualBel in actual.bels) {
			val templateBel = template.getBel(actualBel.name)

			for (actualPin in actualBel.sources) {
				val templatePin = templateBel.getBelPin(actualPin.name)
				oTraverser.run(templatePin)
			}

			for (actualPin in actualBel.sinks) {
				val templatePin = templateBel.getBelPin(actualPin.name)
				val inputsForPin = LinkedHashSet<Wire>()
				Traverser(tileMap, false, inputsForPin).run(templatePin)
				inputs.put(translatePin(templatePin, tileMap), inputsForPin.toList())
			}
		}
		return Pair(inputs, outputs)
	}

	private inner class Traverser (
		private val tileMap: Map<Tile, Tile>,
		private val forward: Boolean,
		private val outputs: MutableCollection<Wire>
	) {
		fun run(sourcePin: BelPin) {
			val sourceWire = sourcePin.wire
			val visited = LinkedHashSet<Wire>()
			val q: Queue<Wire> = ArrayDeque<Wire>()
			q += sourceWire

			while (q.isNotEmpty()) {
				val w = q.poll()
				if (!visited.add(w))
					continue

				w.getWireConnections(forward).forEach { c ->
					val sinkWire = c.sinkWire
					val sinkTile = sinkWire.tile

					if (sinkTile.type in SWITCH_MATRIX_TILES) {
						outputs.add(translateWire(w, tileMap))
					} else if (sinkTile in tileMap) {
						q += sinkWire
					}
				}
				w.getConnectedPin(forward)?.let {
					if (w is SiteWire)
						q += it.externalWire
					else
						q += it.internalWire
				}
			}
		}
	}
}

private fun translateWire(origWire: Wire, tileMap: Map<Tile, Tile>): Wire {
	val translatedTile = tileMap[origWire.tile]!!

	assert(origWire is TileWire)
	return TileWire(translatedTile, origWire.wireEnum)
}

private fun translatePin(sourcePin: BelPin, tileMap: Map<Tile, Tile>): BelPin {
	val sourceBel = sourcePin.bel
	val sourceSite = sourceBel.site
	val sourceTile = sourceSite.tile

	val translatedTile = tileMap[sourceTile]!!
	val translatedSite = translatedTile.getSite(0)
	val translatedBel = translatedSite.getBel(sourceBel.id)
	return translatedBel.getBelPin(sourcePin.name)
}

