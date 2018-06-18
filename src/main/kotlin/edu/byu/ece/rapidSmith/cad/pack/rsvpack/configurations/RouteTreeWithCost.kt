package edu.byu.ece.rapidSmith.cad.pack.rsvpack.configurations

import edu.byu.ece.rapidSmith.design.subsite.RouteTree
import edu.byu.ece.rapidSmith.device.Wire

class RouteTreeWithCost(wire: Wire) : RouteTree(wire), Comparable<RouteTreeWithCost> {
    var cost = 0

    override fun newInstance(wire: Wire): RouteTree = RouteTreeWithCost(wire)

    override fun compareTo(other: RouteTreeWithCost): Int {
        return Integer.compare(cost, other.cost)
    }
}
