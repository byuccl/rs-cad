package edu.byu.ece.rapidSmith.cad.pack.rsvpack

import edu.byu.ece.rapidSmith.cad.cluster.Cluster
import edu.byu.ece.rapidSmith.cad.cluster.PackUnit
import edu.byu.ece.rapidSmith.design.subsite.Cell
import edu.byu.ece.rapidSmith.design.subsite.CellDesign
import edu.byu.ece.rapidSmith.device.Bel

/**
 * Prepacker factory class.
 */
abstract class PrepackerFactory<in T: PackUnit> {
	private var initialized = false
	fun initialize(design: CellDesign) {
		if (!initialized) {
			init(design)
			initialized = true
		}
	}
	/** hook allowing for any data structure building prior to packing. */
	open fun init(design: CellDesign) { /* do nothing */ }
	abstract fun make(): Prepacker<T>
}

/**
 * Utility for identifying cells that must be included in the cluster for the
 * cluster to work.
 */
abstract class Prepacker<in T: PackUnit> {
	/**
	 * Identify cells that must be included in this cluster and add them.  If no
	 * cells are added, return [PrepackStatus.UNCHANGED].  If cells were added,
	 * return [PrepackStatus.CHANGED].  If cells needed to be added but could not,
	 * return [PrepackStatus.INFEASIBLE].  Add any added cells to the [changedCells]
	 * parameter.
	 */
	abstract fun packRequired(
		cluster: Cluster<T, *>,
		changedCells: MutableMap<Cell, Bel>
	) : PrepackStatus
}

/** Return value for a prepacker. */
enum class PrepackStatus {
	UNCHANGED, INFEASIBLE, CHANGED;

	fun meet(other: PrepackStatus): PrepackStatus {
		return when (this) {
			PrepackStatus.UNCHANGED -> other
			PrepackStatus.INFEASIBLE -> INFEASIBLE
			PrepackStatus.CHANGED -> if (other == INFEASIBLE) INFEASIBLE else this
		}
	}
}



