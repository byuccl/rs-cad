package edu.byu.ece.rapidSmith.cad.pack.rsvpack.configurations

import edu.byu.ece.rapidSmith.cad.cluster.Cluster
import edu.byu.ece.rapidSmith.cad.cluster.ClusterFactory
import edu.byu.ece.rapidSmith.cad.cluster.site.SitePackUnit
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.ClusterCostCalculator

/**

 */
class SiteClusterCostCalculator: ClusterCostCalculator<SitePackUnit> {
	private var generator: ClusterFactory<SitePackUnit, *>? = null
	private var maxNumOfTypes: Int = 0

	override fun init(generator: ClusterFactory<SitePackUnit, *>) {
		this.generator = generator
		maxNumOfTypes = -1
		for (type in generator.supportedPackUnits) {
			val numTypes = generator.getNumRemaining(type)
			if (numTypes > maxNumOfTypes)
				maxNumOfTypes = numTypes
		}
	}

	override fun calculateCost(cluster: Cluster<SitePackUnit, *>): Double {
		val belUtilization = calculateBelUtilization(cluster)
		val pinUtilization = calcPinUtilization(cluster)
		val availability = calcAvailability(cluster)

		return 1.0 / (belUtilization * BEL_UTILIZATION_FACTOR +
			pinUtilization * PIN_UTILIZATION_FACTOR +
			availability * REMAINING_TYPES_FACTOR)
	}

	private fun calculateBelUtilization(cluster: Cluster<SitePackUnit, *>): Double {
		val numCells = cluster.cells.size
		val numBels = cluster.type.template.bels.size
		return numCells.toDouble() / numBels
	}

	private fun calcPinUtilization(cluster: Cluster<SitePackUnit, *>): Double {
		var cellPins = 0
		var belPins = 0

		for (bel in cluster.type.template.bels) {
			belPins += bel.sources.size
			belPins += bel.sinks.size
		}

		for (cell in cluster.cells) {
			for (pin in cell.pins) {
				if (pin.isConnectedToNet)
					cellPins += 1
			}
		}

		return cellPins.toDouble() / belPins
	}

	private fun calcAvailability(cluster: Cluster<SitePackUnit, *>): Double {
		return generator!!.getNumRemaining(cluster.type) / maxNumOfTypes.toDouble()
	}

	companion object {
		private val BEL_UTILIZATION_FACTOR = 0.5
		private val PIN_UTILIZATION_FACTOR = 0.2
		private val REMAINING_TYPES_FACTOR = 0.3
	}
}
