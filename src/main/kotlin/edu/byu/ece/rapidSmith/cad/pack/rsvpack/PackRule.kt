package edu.byu.ece.rapidSmith.cad.pack.rsvpack

import edu.byu.ece.rapidSmith.cad.cluster.Cluster
import edu.byu.ece.rapidSmith.design.subsite.Cell
import edu.byu.ece.rapidSmith.design.subsite.CellDesign
import edu.byu.ece.rapidSmith.device.Bel

/**
 * Rule for checking legality of a cluster.
 */
interface PackRule {
	fun validate(changedCells: Collection<Cell>): PackRuleResult
	fun revert()
	fun cleanup() {
		// Do nothing
	}
}

/**
 * Result of a [PackRule] check.
 */
data class PackRuleResult(
	/** The pack status of the cluster as determined by this check. */
	val status: PackStatus,

	/**
	 * The conditional cells that should be analyzed to make this cluster valid.
	 * The value is only legal if status is [PackStatus.CONDITIONAL].  `null`
	 * otherwise.
	 */
	val conditionals: Map<Cell, Set<Bel>>?
)

/**
 * Factory class for creating a fresh pack rule for a cluster.
 */
interface PackRuleFactory {
	/**
	 * 	Method called prior to packing the design to allow for analyzing the design
	 * 	and building any structures necessary.
	 */
	fun init(design: CellDesign) {
		// do nothing
	}

	/**
	 * Tells the pack strategy that all ells in [cluster] are packed.
	 */
	fun commitCluster(cluster: Cluster<*, *>) {
		// do nothing
	}

	fun make(cluster: Cluster<*, *>): PackRule
}


