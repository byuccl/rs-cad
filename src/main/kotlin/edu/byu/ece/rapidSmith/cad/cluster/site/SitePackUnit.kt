package edu.byu.ece.rapidSmith.cad.cluster.site

import edu.byu.ece.rapidSmith.cad.cluster.DirectConnection
import edu.byu.ece.rapidSmith.cad.cluster.PackUnit
import edu.byu.ece.rapidSmith.cad.cluster.PackUnitTemplate
import edu.byu.ece.rapidSmith.cad.cluster.PackUnitType
import edu.byu.ece.rapidSmith.device.*
import java.io.Serializable

class SitePackUnit(
	override val type: SitePackUnitType,
	override val template: SitePackUnitTemplate
) : PackUnit(type, template), Serializable {
	val siteType: SiteType get() = type.type
	override fun toString(): String {
		return "SitePackUnit(${type.type})"
	}
}

data class SitePackUnitType(val type: SiteType) :
	PackUnitType(type.name()), Serializable

/**
 *
 */
class SitePackUnitTemplate(
	override val bels: List<Bel>,
	override val vccSources: Set<BelPin>,
	override val gndSources: Set<BelPin>,
	override val directSourcesOfCluster: List<DirectConnection>,
	override val directSinksOfCluster: List<DirectConnection>,
	override val outputs: List<Wire>,
	private val _inputs: Map<BelPin, List<Wire>>,
	override val device: Device
) : PackUnitTemplate(), Serializable {
	private var inputCache: Set<Wire>? = null

	val anchor: Bel
		get() = bels[0]

	fun relocateBel(curBel: Bel, curAnchor: Bel, newAnchor: Bel): Bel {
		assert(newAnchor.id == anchor.id)
		assert(curAnchor.id == anchor.id)

		val newSite = newAnchor.site
		return newSite.getBel(curBel.id)
	}

	fun relocateBelPin(belPin: BelPin, curAnchor: Bel, newAnchor: Bel): BelPin {
		assert(newAnchor.id == anchor.id)
		assert(curAnchor.id == anchor.id)

		val curBel = belPin.bel
		val rSite = newAnchor.site
		val rBel = rSite.getBel(curBel.id)
		return rBel.getBelPin(belPin.name)
	}

	fun relocateWire(oldWire: Wire, curAnchor: Bel, newAnchor: Bel): Wire {
		assert(newAnchor.id == anchor.id)
		assert(curAnchor.id == anchor.id)

		if (oldWire is SiteWire) {
			return SiteWire(newAnchor.site, oldWire.wireEnum)
		} else if (oldWire is TileWire) {
			throw AssertionError("This doesn't support tile wires")
		} else {
			throw AssertionError("Unknown wire class")
		}
	}

	fun relocateConnection(
		sourceWire: Wire, conn: Connection,
		curAnchor: Bel, newAnchor: Bel
	): Connection {
		assert(newAnchor.id == anchor.id)
		assert(curAnchor.id == anchor.id)
		return sourceWire.wireConnections
			.first { c -> c.sinkWire.wireEnum == conn.sinkWire.wireEnum }
	}

	override fun getInputsOfSink(sinkPin: BelPin): List<Wire>? {
		return _inputs[sinkPin]
	}

	override val inputs: Set<Wire>
		get() {
			var inputs: Set<Wire>? = inputCache
			if (inputs == null) {
				inputs = _inputs.values.flatten().toSet()
				inputCache = inputs
			}

			return inputs
		}

	class Builder {
		var device: Device? = null
		var site: Site? = null
		var bels: List<Bel>? = null
		var vccSources: Set<BelPin>? = null
		var gndSources : Set<BelPin>? = null
		var directSourcesOfCluster: List<DirectConnection>? = null
		var directSinksOfCluster: List<DirectConnection>? = null
		var outputs: List<Wire>? = null
		var inputs: Map<BelPin, List<Wire>>? = null

		fun build(): SitePackUnitTemplate {
			return SitePackUnitTemplate(
				bels = requireNotNull(bels) { "bels not initialized" },
				vccSources = requireNotNull(vccSources) { "vccSources not initialized" },
				gndSources = requireNotNull(gndSources) { "gndSources not initialized" },
				directSourcesOfCluster = requireNotNull(directSourcesOfCluster) {
					"directSourcesOfCluster not initialized"
				},
				directSinksOfCluster = requireNotNull(directSinksOfCluster) {
					"directSinksOfCluster not initialized"
				},
				outputs = requireNotNull(outputs) { "outputs not initialized" },
				_inputs = requireNotNull(inputs) { "inputs not initialized" },
				device = requireNotNull(device) { "device not initialized" }
			)
		}
	}
}

