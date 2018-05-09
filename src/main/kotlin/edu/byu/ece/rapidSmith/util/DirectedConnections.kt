package edu.byu.ece.rapidSmith.util

import edu.byu.ece.rapidSmith.device.*

fun Wire.getWireConnections(forward: Boolean): Collection<Connection> =
	if (forward)
		wireConnections
	else
		reverseWireConnections


fun Wire.getConnectedPin(forward: Boolean): SitePin? =
	if (forward)
		connectedPin
	else
		reverseConnectedPin

fun Wire.getSitePinConnection(forward: Boolean): SitePinConnection? {
	val sitePin = if (forward) connectedPin else reverseConnectedPin
	if (sitePin == null) return null

	return when(this) {
		is TileWire -> SitePinConnection(sitePin, PinConnectionDirection.INWARD)
		is SiteWire -> SitePinConnection(sitePin, PinConnectionDirection.OUTWARD)
		else -> error("Illegal Wire Type")
	}
}

class SitePinConnection internal constructor(
	private val pin: SitePin,
	private val dir: PinConnectionDirection
) : Connection() {
	override fun getRoutethroughSite(): Site {
		TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
	}

	override fun getSinkTileWires(alreadyVisited: MutableList<Connection>?): MutableSet<Wire> {
		TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
	}

	override fun getSourceWire(): Wire = when (dir) {
		PinConnectionDirection.INWARD -> pin.externalWire
		PinConnectionDirection.OUTWARD -> pin.internalWire
	}

	override fun getSinkWire(): Wire = when (dir) {
		PinConnectionDirection.INWARD -> pin.internalWire
		PinConnectionDirection.OUTWARD -> pin.externalWire
	}

	override fun isWireConnection(): Boolean = false

	override fun isPip(): Boolean = false

	override fun isRouteThrough(): Boolean = false

	override fun isPinConnection(): Boolean = true

	override fun getSitePin(): SitePin = pin

	override fun getPip(): PIP? = null

	override fun isTerminal(): Boolean = false

	override fun getBelPin(): BelPin? = null
}

internal enum class PinConnectionDirection {
	INWARD, OUTWARD
}

fun Wire.getBelPinConnection(forward: Boolean): BelPinConnection? {
	val belPin = if (forward) terminal else source
	return if (belPin != null) BelPinConnection(belPin, belPin.wire) else null
}

class BelPinConnection(val pin: BelPin, val sinkWire: Wire)

fun Wire.getBelPin(forward: Boolean): BelPin? =
	if (forward)
		terminal
	else
		source

