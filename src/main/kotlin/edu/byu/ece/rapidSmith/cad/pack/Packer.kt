package edu.byu.ece.rapidSmith.cad.pack

import edu.byu.ece.rapidSmith.cad.cluster.*
import edu.byu.ece.rapidSmith.design.subsite.CellDesign

/**

 */
interface Packer<T: PackUnit> {
	fun <D: ClusterDesign<T, *>> pack(design: CellDesign): D
}
