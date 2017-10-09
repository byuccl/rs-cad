package edu.byu.ece.rapidSmith.cad.cluster

import edu.byu.ece.rapidSmith.device.BelPin
import edu.byu.ece.rapidSmith.device.BelPinTemplate
import edu.byu.ece.rapidSmith.device.Wire
import java.io.Serializable

typealias EndSiteIndex = Int?
typealias EndPackUnitIndex = Int?

/**
 *  Represents a connection between two clusters not passing through
 *  general routing fabric.
 *
 *  @property endPin The type of pin this connection drives.
 *  @property endSiteIndex The index of the site in the sink tile, null if not used
 *  @property endPackUnitIndex Distinguishing element between different sink
 *      clusters.  `null` if multiple clusters can be driven on this pin.
 *  @property clusterPin The BelPin sourcing this connection.
 *  @property clusterExit The wire this connection leaves the cluster on
 */
class DirectConnection(
	val endPin: BelPinTemplate,
	val endSiteIndex: EndSiteIndex,
	var endPackUnitIndex: EndPackUnitIndex,
	val clusterPin: BelPin,
	val clusterExit: Wire
) : Serializable {
	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (other !is DirectConnection) return false

		if (endPin != other.endPin) return false
		if (endSiteIndex != other.endSiteIndex) return false
		if (endPackUnitIndex != other.endPackUnitIndex) return false
		if (clusterPin != other.clusterPin) return false

		return true
	}

	override fun hashCode(): Int {
		var result = endPin.hashCode()
		result = 31 * result + (endSiteIndex ?: 0)
		result = 31 * result + (endPackUnitIndex ?: 0)
		result = 31 * result + clusterPin.hashCode()
		return result
	}
}
