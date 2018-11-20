package edu.byu.ece.rapidSmith.cad.pack.rsvpack.router

import edu.byu.ece.rapidSmith.cad.cluster.Cluster
import edu.byu.ece.rapidSmith.cad.cluster.PackUnit
import edu.byu.ece.rapidSmith.design.subsite.CellNet
import edu.byu.ece.rapidSmith.design.subsite.CellPin
import edu.byu.ece.rapidSmith.design.subsite.RouteTree
import edu.byu.ece.rapidSmith.device.Bel
import edu.byu.ece.rapidSmith.device.BelPin

/**
 * Routing -- adapted from now IncrementalClusterRouter
 * Created by Haroldsen on 4/8/2015.
 */
interface ClusterRouterFactory<in T: PackUnit> {
	fun get(packUnit: T): ClusterRouter<T>
}

interface ClusterRouter<in T: PackUnit> {
	fun route(cluster: Cluster<T, *>): ClusterRouterResult
}

class ClusterRouterResult(
	val success: Boolean,
	val routeTreeMap: Map<CellNet, List<RouteTree>> = emptyMap(),
	val belPinMap: Map<CellNet, Map<CellPin, List<BelPin>>> = emptyMap()
)

typealias PinMapper = (Cluster<*, *>, CellPin, Bel) -> List<BelPin>?
