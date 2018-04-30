package edu.byu.ece.rapidSmith.cad.place

import edu.byu.ece.rapidSmith.cad.cluster.Cluster
import edu.byu.ece.rapidSmith.cad.cluster.ClusterDesign
import edu.byu.ece.rapidSmith.cad.cluster.ClusterSite
import edu.byu.ece.rapidSmith.design.subsite.CellDesign
import edu.byu.ece.rapidSmith.device.Device

/**
 *
 */
abstract class Placer<S: ClusterSite> {
	abstract fun place(device: Device, design: CellDesign, clusters: List<Cluster<*, S>>)
}



