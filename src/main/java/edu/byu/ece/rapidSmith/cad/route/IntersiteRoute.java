package edu.byu.ece.rapidSmith.cad.route;

import edu.byu.ece.rapidSmith.cad.route.pathfinder.PathFinderRouteTree;
import edu.byu.ece.rapidSmith.design.subsite.CellNet;
import edu.byu.ece.rapidSmith.design.subsite.CellPin;
import edu.byu.ece.rapidSmith.device.Wire;

import java.util.*;

/**
 * Represents an inter-site route for a net. Contains helpful information for routing the inter-site net.
 */
public class IntersiteRoute implements Comparable<IntersiteRoute> {
	private CellNet net;
	/** The tree of the inter-site route tree to begin routing from. **/
	private PathFinderRouteTree routeTree;
	/** Map from terminal (sink) wires to their corresponding cell pin(s) **/
	private Map<Wire, List<CellPin>> terminalWireCellPinMap;
	/** Whether or not the net is a local clock net */
	private boolean isLocalClkNet;
	/** Whether or not the net is a global clock net */
	private boolean isGlobalClkNet;
	/** Whether the net is a clock buffer net */
	private boolean isClkBufferNet;
	/** Map from the start of sink trees (where we route to) to the terminal (leaf node connecting to a pin) of the sink trees **/
	private Map<PathFinderRouteTree, PathFinderRouteTree> sinkTerminalTreeMap;
	/** Map from terminal trees (true leaf nodes) to the first parent tree that needs to be routed to */
	private Map<PathFinderRouteTree, PathFinderRouteTree> terminalSinkTreeMap;
	/** Sinks that need to be routed. */
	private Collection<PathFinderRouteTree> sinksToRoute;
	/** Sinks that are currently routed without conflict. */
	private Set<PathFinderRouteTree> routedSinks;

	/**
	 * Public constructor for a normal Inter-site Route.
	 * @param net the {@link CellNet} for this inter-site route
	 * @param routeTree the tree of the inter-site route tree to begin routing from
	 * @param sinkTerminalTreeMap a map from sink trees to the true terminal (leaf) trees
	 * @param terminalSinkTreeMap a map from the true terminal (leaf) trees to the sink trees
	 * @param terminalWireCellPinMap a map from terminal (leaf) wires to their corresponding cell pins.
	 */
	public IntersiteRoute(CellNet net, PathFinderRouteTree routeTree,
						  Map<PathFinderRouteTree, PathFinderRouteTree> sinkTerminalTreeMap,
						  Map<PathFinderRouteTree, PathFinderRouteTree> terminalSinkTreeMap,
						  Map<Wire, List<CellPin>> terminalWireCellPinMap) {
		this.net = net;
		isGlobalClkNet = net.isGlobalClkNet();
		isLocalClkNet = net.isLocalClkNet();
		assert (!(isGlobalClkNet && isLocalClkNet));
		isClkBufferNet = net.isClkBufferNet();
		this.routeTree = routeTree;
		this.sinkTerminalTreeMap = sinkTerminalTreeMap;
		this.sinksToRoute = new ArrayList<>();
		sinksToRoute.addAll(sinkTerminalTreeMap.keySet());
		this.routedSinks = new HashSet<>();
		this.terminalSinkTreeMap = terminalSinkTreeMap;
		this.terminalWireCellPinMap = terminalWireCellPinMap;
	}

	/**
	 * Gets the inter-site sinks that have been routed to.
	 *
	 * @return a set of inter-site PathFinderRouteTree sinks
	 */
	public Set<PathFinderRouteTree> getRoutedSinks() {
		return routedSinks;
	}

	/**
	 * Set the inter-site sinks that have been routed to.
	 *
	 * @param routedSinks the set of inter-site PathFinderRouteTree sinks that have been routed to.
	 */
	public void setRoutedSinks(Set<PathFinderRouteTree> routedSinks) {
		this.routedSinks = routedSinks;
	}

	/**
	 * Get the inter-site sinks that need to be routed.
	 *
	 * @return a collection of inter-site PathFinderRouteTree sinks that need to be routed.
	 */
	public Collection<PathFinderRouteTree> getSinksToRoute() {
		return sinksToRoute;
	}

	/**
	 * Set the inter-site sinks that need to be routed.
	 *
	 * @param sinksToRoute
	 */
	public void setSinksToRoute(Collection<PathFinderRouteTree> sinksToRoute) {
		this.sinksToRoute = sinksToRoute;
	}

	public Map<PathFinderRouteTree, PathFinderRouteTree> getTerminalSinkTreeMap() {
		return terminalSinkTreeMap;
	}

	public Map<PathFinderRouteTree, PathFinderRouteTree> getSinkTerminalTreeMap() {
		return sinkTerminalTreeMap;
	}

	public PathFinderRouteTree getTerminalTree(PathFinderRouteTree sinkTree) {
		return sinkTerminalTreeMap.get(sinkTree);
	}

	public List<CellPin> getSinkCellPins(Wire terminalWire) {
		return terminalWireCellPinMap.get(terminalWire);
	}

	public Set<PathFinderRouteTree> getSinkRouteTrees() {
		return sinkTerminalTreeMap.keySet();
	}

	/**
	 * Compare inter-site routes based on the number of sinks they need to route to.
	 * @param o the other inter-site route
	 */
	@Override
	public int compareTo(IntersiteRoute o) {
		IntersiteRoute intersiteRoute = (IntersiteRoute) (o);
		if (this.getSinkRouteTrees().size() > intersiteRoute.getSinkRouteTrees().size()) {
			return 1;
		} else if (this.getSinkRouteTrees().size() < intersiteRoute.getSinkRouteTrees().size()) {
			return -1;
		}
		return 0;
	}

	/**
	 * Get the net of the inter-site route.
	 *
	 * @return the net
	 */
	public CellNet getNet() {
		return net;
	}

	/**
	 * Set the net of the inter-site route.
	 *
	 * @param net the net to set
	 */
	public void setNet(CellNet net) {
		this.net = net;
	}

	/**
	 * Get the route tree to start routing from.
	 *
	 * @return the route tree.
	 */
	public PathFinderRouteTree getRouteTree() {
		return routeTree;
	}

	/**
	 * Sets the route tree to start routing from.
	 *
	 * @param intersiteTree the tree
	 */
	public void setRouteTree(PathFinderRouteTree intersiteTree) {
		this.routeTree = intersiteTree;
	}

	/**
	 * Gets whether an inter-site route's net is a local clock net, determining what wires it can use.
	 *
	 * @return true if local clock, false otherwise
	 */
	public boolean isLocalClk() {
		return isLocalClkNet;
	}

	/**
	 * Gets whether an inter-site route's net is a global clock, determining what wires it can use.
	 *
	 * @return true if global clock, false otherwise
	 */
	public boolean isGlobalClk() {
		return isGlobalClkNet;
	}

	/**
	 * Gets whether an inter-site route's net is a clock buffer net, determining what wires it can use.
	 *
	 * @return true if clock buffer, false otherwise
	 */
	public boolean isClkBuffer() {
		return isClkBufferNet;
	}

	/**
	 * Gets whether an inter-site route's net is a VCC net.
	 *
	 * @return true if VCC, false otherwise
	 */
	public boolean isVcc() {
		return net.isVCCNet();
	}

	/**
	 * Gets whether an inter-site route's net is a GND net.
	 *
	 * @return true if GND, false otherwise
	 */
	public boolean isGnd() {
		return net.isGNDNet();
	}

	/**
	 * Gets whether an inter-site route's net is a static (VCC/GND) net.
	 *
	 * @return true if static, false otherwise
	 */
	public boolean isStatic() {
		return net.isStaticNet();
	}

}