package edu.byu.ece.rapidSmith.cad.route;

import edu.byu.ece.rapidSmith.cad.route.mazerouter.AStarRouter;
import edu.byu.ece.rapidSmith.cad.route.mazerouter.MazeRouter;
import edu.byu.ece.rapidSmith.cad.route.pathfinder.PathFinder;
import edu.byu.ece.rapidSmith.cad.route.pathfinder.PathFinderRouteTree;
import edu.byu.ece.rapidSmith.cad.route.pathfinder.WireUsage;
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.CadException;
import edu.byu.ece.rapidSmith.design.subsite.*;
import edu.byu.ece.rapidSmith.device.*;
import edu.byu.ece.rapidSmith.device.families.FamilyInfo;
import edu.byu.ece.rapidSmith.device.families.FamilyInfos;

import java.util.*;
import java.util.stream.Collectors;

/**
 * The main class for the RSVRoute inter-site router.
 */
public class RSVRoute {
	private CellDesign design;
	private Device device;
	private FamilyInfo familyInfo;
	private FamilyType familyType;
	private CellLibrary libCells;
	private Set<Bel> vccSourceBels;
	private Set<Bel> gndSourceBels;
	/** Whether to use site route-throughs. */
	private boolean useRoutethroughs;

	/**
	 * Constructor for RSVRoute.
	 * @param device the device the design is for
	 * @param design the cell design to route
	 * @param libCells the cell library
	 * @param useRoutethroughs whether site route-throughs are allowed to be used.
	 */
	public RSVRoute(Device device, CellDesign design, CellLibrary libCells, boolean useRoutethroughs, Set<Bel> vccSourceBels, Set<Bel> gndSourceBels) {
		this.device = device;
		familyType = device.getFamily();
		familyInfo = FamilyInfos.get(familyType);
		this.design = design;
		this.libCells = libCells;
		this.useRoutethroughs = useRoutethroughs;
		this.vccSourceBels = vccSourceBels;
		this.gndSourceBels = gndSourceBels;
	}

	public RSVRoute(Device device, CellDesign design, CellLibrary libCells, Set<Bel> vccSourceBels, Set<Bel> gndSourceBels) {
		this(device, design, libCells, false, vccSourceBels, gndSourceBels);
	}

	/**
	 * Routes the cell-design. Currently automatically creates inter-site route objects for all nets in the design
	 * (so all nets become routed) and uses the A* router as the maze router.
	 */
	public void routeDesign() throws CadException {
		// Perform necessary initialization, creating inter-site route objects for each net.
		ArrayList<IntersiteRoute> intersiteRoutes = createIntersiteRoutes();

		// Create a map from wires to WireUsage to keep track of how wires are used
		Map<Wire, WireUsage> wireUsageMap = new HashMap<>();

		// Choose a maze router to use
		MazeRouter mazeRouter = new AStarRouter(design, wireUsageMap, useRoutethroughs);

		// Start the pathfinder algorithm
		PathFinder pathFinder = new PathFinder(device, libCells, design, mazeRouter, wireUsageMap, vccSourceBels, gndSourceBels);
		pathFinder.execute(intersiteRoutes);
	}

	/**
	 * Creates an initial {@link RouteTree} object for the specified {@link CellNet}.
	 * This is the beginning of the physical route.
	 */
	private PathFinderRouteTree initSignalRoute(CellNet net) {
		Wire startWire;
		// If the source pin is a partition pin
		if (net.getSourcePin().isPartitionPin())
			startWire = net.getSourcePin().getPartPinWire();
		else {
			assert (net.getSourceSitePin() != null);
			startWire = net.getSourceSitePin().getExternalWire();
		}
		return createSourceRouteTree(startWire);
	}

	/**
	 * Using a given sink wire, use reverse wire connections to create a sink route tree that contains all
	 * necessary wires to get to the sink wire.
	 *
	 * @param sinkWire the true terminal (sink wire)
	 */
	private Map.Entry<PathFinderRouteTree, PathFinderRouteTree> createSinkRouteTree(CellNet net, Wire sinkWire) {
		Wire wire = sinkWire;
		Stack<Wire> wiresForTree = new Stack<>();

		while (wire.getReverseWireConnections().size() == 1) {
			Connection reverseConn = wire.getReverseWireConnections().iterator().next();

			if (!reverseConn.isRouteThrough()) {
				// Don't go back through site route-throughs. This can give us undesired results. For example,
				// we don't want to go from the IOB_PADOUT1 of an IOB33S to IOB_DIFFO_IN1. This would give us the
				// wrong wire to route to.
				wiresForTree.push(wire);
				wire = reverseConn.getSinkWire();
			} else {
				break;
			}
		}

		// If the net is a normal global clock net that is being used, the sink wire should be CLK_L0 or CLK_L1
		// (at least if it is routing to a slice). If this is an RM where the clock net isn't being used, it will
		// route to a LUT input pin. In this case, the sink wire at this point will not be CLK_L0 or CLK_L1. The sink
		// will instead need to be routed to by a GFAN pip. Find this and use it as the sink wire.
		if (net.isGlobalClkNet() && !wire.getName().contains("CLK")) {
			// Filter for just GFANs.
			Collection<Connection> filteredReverseConns = wire.getReverseWireConnections().stream()
					.filter(connection -> connection.getSinkWire().getName().contains("GFAN"))
					.collect(Collectors.toList());

			// Assuming there will only ever be one GFAN connecting to this wire.
			assert (filteredReverseConns.size() == 1);
			wiresForTree.push(wire);
			wire = filteredReverseConns.iterator().next().getSinkWire();
		}

		// TODO: If an RM doesn't use a global clock, it will be routed to a LUT's input pin.
		// In this case, there is still probably only one way to get from the wire (from the above loop)
		// to a GCLK wire. This is probably using a GFAN bounce pip. Figure out if this is the only way to get
		// there, and if so, make this the new sink wire to be routing to.

		// Create the route tree starting at the first wire that has multiple forward wire connections
		PathFinderRouteTree sinkRouteTree = new PathFinderRouteTree(wire);
		PathFinderRouteTree tree = sinkRouteTree;
		while (!wire.equals(sinkWire)) {
			assert (wiresForTree.size() > 0);
			Connection forwardConnection = wire.getWireConnection(wiresForTree.pop());
			tree = tree.connect(forwardConnection);
			wire = forwardConnection.getSinkWire();
		}

		// return a map entry from the sink route tree to the leaf node (the terminal) of the sink route tree
		return new AbstractMap.SimpleEntry<>(sinkRouteTree, tree);
	}

	/**
	 * Gets the neighboring interconnect tile from a CLB tile.
	 *
	 * @param clbTile the CLB tile.
	 * @return the neighboring interconnect tile.
	 */
	private Tile getNeighborIntTile(Tile clbTile) throws CadException {
		TileType clbType = clbTile.getType();
		TileDirection neighborDirection;

		if (familyInfo.clbrTiles().contains(clbType))
			neighborDirection = TileDirection.WEST;
		else if (familyInfo.clblTiles().contains(clbType))
			neighborDirection = TileDirection.EAST;
		else
			throw new CadException("Cannot find neighboring VCC/GND tie-off for tile " + clbTile.getName() + " of type " + clbType);

		Tile intTile = clbTile.getAdjacentTile(neighborDirection);
		if (!familyInfo.switchboxTiles().contains(intTile.getType()))
			throw new CadException("Cannot find VCC/GND tie-off in tile " + intTile.getName() + " of type " + intTile.getType());

		return intTile;
	}

	/**
	 * Gets the tie-off site within an interconnect tile.
	 *
	 * @param tile the interconnect tile
	 * @return the tie-off site.
	 */
	private Site getTieOffSite(Tile tile) throws CadException {
		Site tieOffSite = null;
		for (Site site : tile.getSites()) {
			if (site.getType().equals(SiteType.valueOf(familyType, "TIEOFF"))) {
				// Assuming there is only one tie-off site in an interconnect tile
				tieOffSite = site;
				break;
			}
		}

		if (tieOffSite == null) {
			throw new CadException("Cannot find tie-off site within tile " + tile.getName() + " of type " + tile.getType());
		}

		return tieOffSite;
	}

	/**
	 * Get the VCC/GND tie-off pin in an interconnect tile for a neighboring CLB tile.
	 *
	 * @param tieOffTile the INT tile with the tie-off
	 * @param isVcc      true to find the VCC tie-off, false to find the GND tie-off
	 * @return the Bel Pin of the VCC/GND tie-off.
	 */
	private SitePin getIntTieOffPin(Tile tieOffTile, boolean isVcc) throws CadException {
		String tileOffPinName = "HARD" + ((isVcc) ? "1" : "0");
		Site tieOffSite = getTieOffSite(tieOffTile);
		return tieOffSite.getSourcePin(tileOffPinName);
	}

	/**
	 * Get the tie-off wires located in INT tiles for a static net. Note that this does not gather all tie-off wires
	 * in a device, but only the tie-off wires located in the INT tiles next to used slices.
	 *
	 * @param staticNet the VCC/2GND net
	 * @return a collection of tie-off wires.
	 */
	private Collection<Wire> getTieOffWires(CellNet staticNet) throws CadException {
		Collection<Wire> wires = new HashSet<>();
		Set<Tile> staticClbTiles = staticNet.getSinkTiles().stream()
				.filter(tile -> familyInfo.clbTiles().contains(tile.getType()))
				.collect(Collectors.toSet());

		for (Tile sinkTile : staticClbTiles) {
			// Find the neighboring tile tie-off.
			SitePin tieOffPin = getIntTieOffPin(getNeighborIntTile(sinkTile), staticNet.isVCCNet());
			wires.add(tieOffPin.getExternalWire());
		}

		// Handle the I/O sinks.
		Set<Site> staticIoSites = staticNet.getSinkSites().stream()
				.filter(site -> familyInfo.ioSites().contains(site.getType()))
				.collect(Collectors.toSet());

		for (Site sinkSite : staticIoSites) {
			// index of 0 means it is the bottom site in the tile
			int siteIndex = sinkSite.getIndex();
			assert (siteIndex == 0 || siteIndex == 1);

			Tile sinkTile = sinkSite.getTile();
			int tileCol = sinkTile.getColumn();
			assert (tileCol == 0 || tileCol == device.getColumns() - 1);

			// There should be an INT tile to the left or right of the IO tile
			TileDirection tileDirection = (tileCol == 0) ? TileDirection.EAST : TileDirection.WEST;
			Tile intTile = sinkTile;
			TileType tileType = null;
			while (!familyInfo.switchboxTiles().contains(tileType)) {
				intTile = intTile.getAdjacentTile(tileDirection);
				tileType = intTile.getType();
			}

			// If index is 1, we need to go up 1 tile to get to the desired INT tile
			if (siteIndex == 0) {
				intTile = intTile.getAdjacentTile(TileDirection.NORTH);
			}
			assert (familyInfo.switchboxTiles().contains(intTile.getType()));

			// Find the VCC/GND source wire
			SitePin tieOffPin = getIntTieOffPin(intTile, staticNet.isVCCNet());
			wires.add(tieOffPin.getExternalWire());

		}

		return wires;
	}

	/**
	 * Creates the source route tree by building a tree until the first possible branch.
	 *
	 * @param sourceWire the root wire of the source tree
	 * @return the source route tree.
	 */
	private PathFinderRouteTree createSourceRouteTree(Wire sourceWire) {
		PathFinderRouteTree sourceTree = new PathFinderRouteTree(sourceWire);

		while (sourceTree.getWire().getWireConnections().size() == 1) {
			sourceTree = sourceTree.connect(sourceTree.getWire().getWireConnections().iterator().next());
		}
		return sourceTree;
	}

	private IntersiteRoute createIntersiteRoute(CellNet net) {
		PathFinderRouteTree startTree = initSignalRoute(net);
		Map<Wire, List<CellPin>> terminalWireCellPinMap = new HashMap<>();

		Collection<CellPin> allSinkPins = net.getSinkPins();

		// Include all alias sink pins
		for (CellNet alias : net.getAliases()) {
			allSinkPins.addAll(alias.getSinkPins());
		}

		Collection<CellPin> sinkPins = allSinkPins.stream()
				.filter(cellPin -> getSinkSitePins(cellPin) != null || net.getSinkPartitionPins() != null)
				.collect(Collectors.toList());
		Map<PathFinderRouteTree, PathFinderRouteTree> sinkRouteTreeMap = new HashMap<>();
		Map<PathFinderRouteTree, PathFinderRouteTree> terminalSinkTreeMap = new HashMap<>();

		for (CellPin sinkPin : sinkPins) {
			if (sinkPin instanceof PartitionPin) {
				Map.Entry<PathFinderRouteTree, PathFinderRouteTree> entry = createSinkRouteTree(net, sinkPin.getPartPinWire());
				sinkRouteTreeMap.put(entry.getKey(), entry.getValue());
				terminalSinkTreeMap.put(entry.getValue(), entry.getKey());
				List<CellPin> pinList = new ArrayList<>();
				pinList.add(sinkPin);
				terminalWireCellPinMap.put(sinkPin.getPartPinWire(), pinList);
			} else {
				// More than one cell pin may share the same site pin.
				// We need to make sure there is only one unique sink site pin tree.
				List<SitePin> sinkSitePins = getSinkSitePins(sinkPin);

				if (!net.getSourcePin().isPartitionPin()) {
					Site sourceSite = net.getSourcePin().getCell().getBel().getSite();
					Site sinkSite = sinkPin.getCell().getBel().getSite();

					if (sinkSitePins == null && sourceSite == sinkSite) {
						// If the pins are in the same site, no inter-site routing needed (this seems to only come up
						// for yosys-synthesized designs. Why?
						continue;
					}
				}

				assert (sinkSitePins != null);

				for (SitePin sitePin : sinkSitePins) {
					Wire terminalWire = sitePin.getExternalWire();

					if (terminalWireCellPinMap.containsKey(terminalWire)) {
						// Just add the cell pin to the existing list
						List<CellPin> cellPins = terminalWireCellPinMap.get(terminalWire);
						assert (cellPins != null);
						cellPins.add(sinkPin);
					} else {
						Map.Entry<PathFinderRouteTree, PathFinderRouteTree> entry = createSinkRouteTree(net, terminalWire);
						sinkRouteTreeMap.put(entry.getKey(), entry.getValue());
						terminalSinkTreeMap.put(entry.getValue(), entry.getKey());
						List<CellPin> cellPins = new ArrayList<>();
						cellPins.add(sinkPin);
						terminalWireCellPinMap.put(terminalWire, cellPins);
					}
				}
			}
		}
		assert (startTree.getChildren().size() == 0);
		return new IntersiteRoute(net, startTree, sinkRouteTreeMap, terminalSinkTreeMap, terminalWireCellPinMap);
	}

	/**
	 * Returns a list of {@link SitePin} objects that map to the passed in cell pin.
	 * This is usually only one SitePin, but is more than one in some cases (such as with some LUT RAM pins).
	 *
	 * @param cellPin the cell pin to use to find the site pin
	 * @return the SitePin that maps to the cellpin.
	 */
	public List<SitePin> getSinkSitePins(CellPin cellPin) {
		// Due to alias nets, the cell pin's net may not be the same as the net
		CellNet pinNet = cellPin.getNet();

		List<SitePin> sitePins = null;
		for (BelPin belPin : cellPin.getMappedBelPins()) {
			RouteTree routeTree = pinNet.getBelPinRouteTrees().get(belPin);

			// Get the route tree that starts at the site pin
			routeTree = routeTree.getRoot();
			SitePin sitePin = routeTree.getWire().getReverseConnectedPin();
			if (sitePin != null) {
				if (sitePins == null)
					sitePins = new ArrayList<>();
				sitePins.add(sitePin);
			}
		}
		return sitePins;
	}

	/**
	 * Creates and returns a global wire for VCC/GND. Forward connections to static source site output wires and
	 * tie-off wires are added. Reverse connections from those wires to the global wire are NOT added.
	 *
	 * @param staticNet the VCC/GND net.
	 * @return the global wire
	 */
	private GlobalWire createGlobalWire(CellNet staticNet) throws CadException {
		GlobalWire staticWire = new GlobalWire(device, staticNet.isVCCNet());
		Set<Connection> forwardStaticConnections = new HashSet<>();

		// Make connections for the tie-off wires in INT tiles
		Collection<Wire> tieOffWires = getTieOffWires(staticNet);
		for (Wire tieOffWire : tieOffWires) {
			forwardStaticConnections.add(new GlobalWireConnection(staticWire, tieOffWire));
		}

		staticWire.setConnections(forwardStaticConnections);
		return staticWire;
	}

	/**
	 * Create a static intersite route object for the specified static net.
	 * A global wire for the VCC/GND net is also created in this method.
	 *
	 * @param net the VCC/GND net
	 * @return the IntersiteRoute object.
	 */
	private IntersiteRoute createStaticIntersiteRoute(CellNet net) throws CadException {
		// Create a global wire for VCC/GND.
		GlobalWire staticWire = createGlobalWire(net);
		PathFinderRouteTree staticRouteTree = new PathFinderRouteTree(staticWire);

		// Get the inter-site sink route tree
		Map<PathFinderRouteTree, PathFinderRouteTree> sinkTreeMap = new HashMap<>();
		Map<PathFinderRouteTree, PathFinderRouteTree> terminalSinkTreeMap = new HashMap<>();
		Map<Wire, List<CellPin>> terminalWireCellPinMap = new HashMap<>();

		// Get cell pins with corresponding site pins.
		Collection<CellPin> sinkPins = net.getSinkPins().stream()
				.filter(cellPin -> net.getSinkSitePins(cellPin) != null)
				.collect(Collectors.toList());

		Collection<SitePin> sitePins = net.getSinkSitePins();
		Collection<Site> allSites = new ArrayList<>();
		Collection<Site> siteSet = new HashSet<>();

		for (SitePin sitePin : sitePins) {
			allSites.add(sitePin.getSite());
			siteSet.add(sitePin.getSite());
		}


		for (CellPin sinkPin : sinkPins) {
			List<SitePin> sinkSitePins = net.getSinkSitePins(sinkPin);

			for (SitePin sinkSitePin : sinkSitePins) {
				Wire terminalWire = sinkSitePin.getExternalWire();

				if (terminalWireCellPinMap.containsKey(terminalWire)) {
					// just add it to the list of cell pins
					List<CellPin> cellPins = terminalWireCellPinMap.get(terminalWire);
					assert (cellPins != null);
					cellPins.add(sinkPin);
				} else {
					// Get the inter-site sink route tree
					Map.Entry<PathFinderRouteTree, PathFinderRouteTree> entry = createSinkRouteTree(net, sinkSitePin.getExternalWire());
					sinkTreeMap.put(entry.getKey(), entry.getValue());
					terminalSinkTreeMap.put(entry.getValue(), entry.getKey());
					List<CellPin> cellPins = new ArrayList<>();
					cellPins.add(sinkPin);
					terminalWireCellPinMap.put(terminalWire, cellPins);
				}
			}
		}

		return new IntersiteRoute(net, staticRouteTree, sinkTreeMap, terminalSinkTreeMap, terminalWireCellPinMap);
	}

	/**
	 * Initializes nets by finding which site pins (and partition pins) they should route to and from.
	 */
	private ArrayList<IntersiteRoute> createIntersiteRoutes() throws CadException {
		ArrayList<IntersiteRoute> intersiteRoutes = new ArrayList<>();

		// Filter out intra-site static nets and nets with no sinks
		Collection<CellNet> staticNets = design.getNets().stream()
				.filter(CellNet::isStaticNet)
				.filter(cellNet -> !cellNet.isIntrasite())
				.filter(cellNet -> !cellNet.getRouteStatus().equals(RouteStatus.FULLY_ROUTED))
				.filter(cellNet -> !cellNet.getSinkPins().isEmpty())
				.collect(Collectors.toList());

		// Filter out static (VCC/GND), intra-site, and nets with no sinks.
		Collection<CellNet> logicNets = design.getNets().stream()
				.filter(cellNet -> !cellNet.isIntrasite())
				.filter(CellNet::isSourced)
				.filter(cellNet -> !cellNet.getRouteStatus().equals(RouteStatus.FULLY_ROUTED))
				.filter(cellNet -> !cellNet.isStaticNet())
				.filter(cellNet -> !cellNet.getSinkPins().isEmpty())
				.collect(Collectors.toList());

		// Create inter-site Route objects for each static net.
		for (CellNet net : staticNets) {
			IntersiteRoute intersiteRoute = createStaticIntersiteRoute(net);
			intersiteRoutes.add(intersiteRoute);
		}

		// For nets with aliases, make only one inter-site route object
		List<CellNet> aliasNets = logicNets.stream()
				.filter(net -> net.getAliases().size() > 0)
				.collect(Collectors.toList());
		logicNets.removeAll(aliasNets);

		// Keep only one net per alias-set
		Collection<CellNet> toRemove = new HashSet<>();
		ListIterator<CellNet> iter = aliasNets.listIterator();
		while (iter.hasNext()) {
			CellNet next = iter.next();
			if (toRemove.contains(next))
				iter.remove();
			else
				toRemove.addAll(next.getAliases());
		}
		logicNets.addAll(aliasNets);

		// Create inter-site route objects for all other nets.
		for (CellNet net : logicNets) {
			IntersiteRoute intersiteRoute = createIntersiteRoute(net);
			intersiteRoutes.add(intersiteRoute);
		}

		// Add all sink wires to the design's reserved wires (to prevent other nets from searching them)
		for (IntersiteRoute intersiteRoute : intersiteRoutes) {
			for (RouteTree sinkTree : intersiteRoute.getSinksToRoute()) {
				assert (sinkTree.getParent() == null);
				for (RouteTree routeTree : sinkTree) {
					design.addReservedNode(routeTree.getWire(), intersiteRoute.getNet());
				}
			}

			// Do the same thing for source tree wires
			// Iterate from the root of the start tree to the leaf node
			if (!intersiteRoute.getNet().isStaticNet()) {
				for (RouteTree routeTree : intersiteRoute.getRouteTree().getRoot()) {
					design.addReservedNode(routeTree.getWire(), intersiteRoute.getNet());
				}
			}
		}

		return intersiteRoutes;
	}
}
