package edu.byu.ece.rapidSmith.cad.pack

import edu.byu.ece.rapidSmith.cad.cluster.*
import edu.byu.ece.rapidSmith.design.subsite.CellDesign

/**

 */
interface Packer<out T: PackUnit> {
	fun pack(design: CellDesign): List<Cluster<T, *>>
}
