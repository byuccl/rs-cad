package edu.byu.ece.rapidSmith.cad.place

import edu.byu.ece.rapidSmith.cad.cluster.ClusterDesign
import edu.byu.ece.rapidSmith.device.Device

/**
 *
 */
abstract class Placer<in D: ClusterDesign<*, *>> {
	abstract fun place(design: D, device: Device): Boolean
}



