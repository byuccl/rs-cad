package edu.byu.ece.rapidSmith.cad.pack.rsvpack.rules

import edu.byu.ece.rapidSmith.cad.cluster.Cluster
import edu.byu.ece.rapidSmith.cad.cluster.PackUnitList
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.PackRule
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.PackRuleFactory
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.PackRuleResult
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.PackStatus
import edu.byu.ece.rapidSmith.design.subsite.Cell
import edu.byu.ece.rapidSmith.device.Bel

/**

 */
enum class Routability {
	VALID, INFEASIBLE, CONDITIONAL;

	fun meet(other: Routability): Routability {
		if (this == INFEASIBLE || other == INFEASIBLE)
			return INFEASIBLE
		if (this == CONDITIONAL || other == CONDITIONAL)
			return CONDITIONAL
		return VALID
	}
}

/**

 */
data class RoutabilityResult(
	val routability: Routability,
	val conditionals: Map<Cell, Set<Bel>>?
)

/**

 */
interface RoutabilityChecker {
	fun check(changed: Collection<Cell>): RoutabilityResult
	fun checkpoint()
	fun rollback()

	fun cleanup() {
		// do nothing
	}
}

/**

 */
interface RoutabilityCheckerFactory {
	fun create(
		cluster: Cluster<*, *>, packUnits: PackUnitList<*>
	): RoutabilityChecker
}

/**
 *
 */
class RoutabilityCheckerPackRuleFactory(
	private val factory: RoutabilityCheckerFactory,
	private val packUnits: PackUnitList<*>
) : PackRuleFactory {
	override fun make(cluster: Cluster<*, *>): PackRule {
		return RoutabilityCheckerPackRule(factory.create(cluster, packUnits))
	}
}

class RoutabilityCheckerPackRule(val checker: RoutabilityChecker) : PackRule {
	override fun validate(changedCells: Collection<Cell>): PackRuleResult {
		checker.checkpoint()
		val (result, conditionals) = checker.check(changedCells)

		when (result) {
			Routability.VALID ->
				return PackRuleResult(PackStatus.VALID, null)
			Routability.CONDITIONAL ->
				return PackRuleResult(PackStatus.CONDITIONAL, conditionals)
			Routability.INFEASIBLE ->
				return PackRuleResult(PackStatus.INFEASIBLE, null)
		}
	}

	override fun revert() {
		checker.rollback()
	}

	override fun cleanup() {
		checker.cleanup()
	}
}
