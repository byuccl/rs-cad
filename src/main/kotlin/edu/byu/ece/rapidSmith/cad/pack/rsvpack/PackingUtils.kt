package edu.byu.ece.rapidSmith.cad.pack.rsvpack

import edu.byu.ece.rapidSmith.cad.cluster.Cluster
import edu.byu.ece.rapidSmith.cad.cluster.ClusterDesign
import edu.byu.ece.rapidSmith.cad.cluster.PackUnit
import edu.byu.ece.rapidSmith.design.subsite.CellDesign

/**
 * Class for prepping and finishing a design.
 */
abstract class PackingUtils<in T: PackUnit> {
	abstract fun prepareDesign(design: CellDesign)
	abstract fun finish(design: List<Cluster<T, *>>)
}
