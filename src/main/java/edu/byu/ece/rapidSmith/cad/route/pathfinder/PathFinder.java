package edu.byu.ece.rapidSmith.cad.route.pathfinder;

import edu.byu.ece.rapidSmith.cad.route.GlobalWire;
import edu.byu.ece.rapidSmith.cad.route.GlobalWireConnection;
import edu.byu.ece.rapidSmith.cad.route.IntersiteRoute;
import edu.byu.ece.rapidSmith.cad.route.mazerouter.MazeRouter;
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.CadException;
import edu.byu.ece.rapidSmith.design.subsite.*;
import edu.byu.ece.rapidSmith.device.*;
import edu.byu.ece.rapidSmith.device.families.FamilyInfo;
import edu.byu.ece.rapidSmith.device.families.FamilyInfos;
import edu.byu.ece.rapidSmith.util.Sorting;

import java.util.*;
import java.util.stream.Collectors;

/**
 * An implementation of the Path Finder negotiated congestion routing algorithm. Based in part after the implementation
 * found in Verilog-to-Routing (VTR).
 */
public class PathFinder {
    private CellDesign design;
    private CellLibrary libCells;
    private FamilyInfo familyInfo;
    private Set<Bel> vccSourceBels;
    private Set<Bel> gndSourceBels;
    /** The maze router to use in the inner loop of PathFinder */
    private MazeRouter mazeRouter;
    /** Present congestion factor */
    private static double presentCongestionFactor;
    /** How much to multiply the present congestion factor by after each iteration */
    private static double presentCongestionMultFactor;
    /** Historical congestion factor */
    private static double historyFactor;
    /** The initial value for searching for static sources */
    private static int initStaticSearchSize;
    /** How much to increase the static search size per iteration */
    private static int staticSearchSizeFactor;
    /** Map from wires to their corresponding wire usage. */
    private Map<Wire, WireUsage> wireUsageMap;

    public PathFinder(Device device, CellLibrary libCells, CellDesign design, MazeRouter mazeRouter, Map<Wire, WireUsage> wireUsageMap, Set<Bel> vccSourceBels, Set<Bel> gndSourceBels) {
        this.familyInfo = FamilyInfos.get(device.getFamily());
        this.design = design;
        this.libCells = libCells;
        this.mazeRouter = mazeRouter;
        this.wireUsageMap = wireUsageMap;
        presentCongestionFactor = 1; //1;
        presentCongestionMultFactor = 1.3; //1.3;
        historyFactor = 1; // 1
        staticSearchSizeFactor = 4;
        this.vccSourceBels = vccSourceBels;
        this.gndSourceBels = gndSourceBels;
    }

    /**
     * Execute the pathfinder algorithm.
     * @param intersiteRoutes The inter-site routes for PathFinder to route
     */
    public void execute(ArrayList<IntersiteRoute> intersiteRoutes) throws CadException {
        // Initialize the static search size (the tile distance to search for static sources)
        int staticSearchSize = initStaticSearchSize;

        boolean routed = false;
        int iteration = 1;

        // Make a list of nets to route
        List<IntersiteRoute> toRoute = new ArrayList<>(intersiteRoutes);

        // Sort the nets by decreasing number of sinks
        Sorting.quickSort(toRoute, 0, toRoute.size() - 1);

        // Loop until all nets are routed
        while (!routed) {
            int numRouted = 1;

            // Inner Loop of PathFinder - Route unrouted nets using a maze router
            for (IntersiteRoute intersiteRoute : toRoute) {
                if (iteration > 1) {
                    ripUpRoute(intersiteRoute);
                }

                if (intersiteRoute.isStatic()) {
                   // addPossibleStaticSources(intersiteRoute, staticSearchSize);
                }

                System.out.println("[INFO] Finding route for " + intersiteRoute.getNet().getName() + " (" + numRouted + "/" + toRoute.size() + ")");
                if (mazeRouter.routeNet(intersiteRoute)) {
                    // Update the occupancy and present congestion of every node in the new route
                    updateWireUsage(intersiteRoute);
                } else {
                    // Route could not be found
                    System.err.println("[WARNING] " + intersiteRoute.getNet().getName() + " could not be routed.");
                    intersiteRoutes.remove(intersiteRoute);
                }

                numRouted++;
            }

            // Calculate conflicts and update usage
            Set<IntersiteRoute> unrouted = new HashSet<>();
            Set<WireUsage> congestedWires = new HashSet<>();
            int numCongestedRoutes;

            // Identify congested wires by iterating through all routes just made
            for (IntersiteRoute intersiteRoute : toRoute) {
                // Search the inter-site tree for conflicted wires
                PathFinderRouteTree root = intersiteRoute.getRouteTree().getRoot();
                Iterable<PathFinderRouteTree> typed = root.typedIterator();
                for (PathFinderRouteTree rt : typed) {
                    WireUsage wireUsage = wireUsageMap.get(rt.getWire());
                    assert (wireUsage != null);
                    assert (wireUsage.getRoutes() != null);
                    assert (wireUsageMap.get(rt.getWire()) != null);

                    if (wireUsage.getRoutes().size() > wireUsageMap.get(rt.getWire()).getCapacity()) {
                        congestedWires.add(wireUsage);

                        // TODO: Remove me! Only here for some debugging.
                        for (IntersiteRoute inter : wireUsage.getRoutes()) {
                            if (inter.getNet().getName().equals("ecg_inst/ins1/ins11/ins4/z[191]")) {
                                System.out.println("Wire is: " + rt.getWire().getFullName());
                            }
                        }

                    }
                }

                // Reset the inter-sites sinks to route
                intersiteRoute.getSinksToRoute().clear();

                // Set all sinks as routed, so they aren't routed in the next iteration
                // Conflicts are later found and dealt with.
                intersiteRoute.setRoutedSinks(new HashSet<>(intersiteRoute.getSinkRouteTrees()));
            }

            // After each iteration, recompute the present congestion and historical congestion for each congested
            // wire. Also create a list of routes that need to be re-routed.
            for (WireUsage wireUsage : congestedWires) {
                // Add to list of inter-site routes to re-route (we will re-route every net that used this congested wire)
                unrouted.addAll(wireUsage.getRoutes());



                // update the historical congestion factor
                wireUsage.incrementHistory(historyFactor);
            }

            // Update the set of routed and un-routed sinks for each of the inter-site routes that aren't fully routed.
            for (IntersiteRoute intersiteRoute : unrouted) {
                updateSinkStatus(intersiteRoute);
            }

            // Finish preparing for the next iteration, if there needs to be one
            numCongestedRoutes = unrouted.size();
            if (numCongestedRoutes == 0) {
                routed = true;
            } else {
                toRoute = new ArrayList<>(unrouted);

                // Update the present congestion factor for every single wire ever used.
                // This includes congested wires, wires used in just-created routes that weren't congested,
                // AND wires that were used in previous iterations.
                for (WireUsage wireUsage : wireUsageMap.values()) {
                    wireUsage.updatePresentCongestion(presentCongestionFactor);
                }

                // increase the present congestion factor
                iteration++;
                presentCongestionFactor *= presentCongestionMultFactor;
                staticSearchSize += (iteration * staticSearchSizeFactor);

                // Re-sort the list of inter-site routes to route
                Sorting.quickSort(toRoute, 0, toRoute.size() - 1);
            }

            System.out.println("[INFO] " + congestedWires.size() + " wires still congested.");
            System.out.println("[INFO] " + unrouted.size() + " routes still congested.\n");
        }

        // Apply the inter-site routes and add any static source LUTs
        applyRoutes(intersiteRoutes);

        // System.out.println("PathFinder took " + runTime.getTotalTime() + " seconds!");
    }

    /**
     * Set the present congestion factor for Path Finder.
     * @param presentCongestionFactor the factor
     */
    public static void setPresentCongestionFactor(double presentCongestionFactor) {
        PathFinder.presentCongestionFactor = presentCongestionFactor;
    }

    /**
     * Set the historical congestion factor for Path Finder
     * @param historyFactor the factor
     */
    public static void setHistoryFactor(double historyFactor) {
        PathFinder.historyFactor = historyFactor;
    }

    /**
     * Sets the size (in tiles) to search for static sources from sinks on the first iteration.
     * @param initStaticSearchSize size in tile distance
     */
    public static void setInitStaticSearchSize(int initStaticSearchSize) {
        PathFinder.initStaticSearchSize = initStaticSearchSize;
    }

    /**
     * Set the factor to increase the static search for by each iteration
     * @param staticSearchSizeFactor the factor
     */
    public static void setStaticSearchSizeFactor(int staticSearchSizeFactor) {
        PathFinder.staticSearchSizeFactor = staticSearchSizeFactor;
    }

    /**
     * Rip up the congested parts of an inter-site route, preserving the uncongested portions.
     * @param intersiteRoute the route to rip up.
     */
    private void ripUpRoute(IntersiteRoute intersiteRoute) {
        assert (intersiteRoute.getRouteTree() != null);

        if (intersiteRoute.getNet().getName().equals("ecg_inst/ins1/ins11/ins4/z[191]"))
            System.out.println("this one");

        // Update the congestion for every node (and its wires)
        for (RouteTree rt : intersiteRoute.getRouteTree().getRoot()) {
            // Update for every wire in the node
            for (Wire wire : rt.getWire().getWiresInNode()) {
                assert (wireUsageMap.containsKey(wire));
                WireUsage wireUsage = wireUsageMap.get(wire);
                wireUsage.removeRoute(intersiteRoute);
                wireUsage.updatePresentCongestion(presentCongestionFactor);
            }
        }

        // Remove the congested cell pins from the list of routed sinks
        for (PathFinderRouteTree rt : intersiteRoute.getSinksToRoute()) {
            Wire terminalWire = intersiteRoute.getSinkTerminalTreeMap().get(rt).getWire();
            for (CellPin cellPin : intersiteRoute.getSinkCellPins(terminalWire)) {
                CellNet net = cellPin.getNet();
                net.removeRoutedSink(cellPin);
            }
        }

        Set<PathFinderRouteTree> terminalsToKeep = new HashSet<>();
        for (PathFinderRouteTree rt : intersiteRoute.getRoutedSinks()) {
            terminalsToKeep.add(intersiteRoute.getSinkTerminalTreeMap().get(rt));
        }
        intersiteRoute.getRouteTree().prune(terminalsToKeep);
        intersiteRoute.getRouteTree().unregisterLeaves();

        //assert (!intersiteRoute.getSinksToRoute().isEmpty());
        if (intersiteRoute.getSinksToRoute().isEmpty()) {
            System.out.println("PROBLEM");
        }

    }

    /**
     * Finds the conflicted sinks of an inter-site route and moves them from the set of routed sinks to the set
     * of sinks to route. NOTE: Also detaches trees to conflicted children!
     * @param intersiteRoute the inter-site route to search
     */
    private void updateSinkStatus(IntersiteRoute intersiteRoute) {
        Stack<PathFinderRouteTree> stack = new Stack<>();
        stack.push(intersiteRoute.getRouteTree().getRoot());

        // Find the conflicted branches of the tree, moving the conflicted sinks from the set of routed sinks to
        // the set of sinks to route
        while (!stack.isEmpty()) {
            PathFinderRouteTree tree = stack.pop();
            Wire wire = tree.getWire();
            WireUsage wireUsage = wireUsageMap.get(wire);

            // If there is congestion
            if (wireUsage.isCongested()) {
                // Remove the sinks of the conflicted wire from the inter-site route's list of routed sinks
                // and add them to the list of sinks to route
                for (PathFinderRouteTree terminal : tree.getLeaves()) {

                    if (intersiteRoute.getNet().getName().equals("ecg_inst/ins1/ins11/ins4/z[191]"))
                        System.out.println("dummy one");


                    PathFinderRouteTree sink = intersiteRoute.getTerminalSinkTreeMap().get(terminal);
                    intersiteRoute.getRoutedSinks().remove(sink);

                    // ADDED recently!
                    // Remove the route from the sink and its children's wire usage
                    Iterable<PathFinderRouteTree> typed = sink.typedIterator();
                    for (PathFinderRouteTree sinkNode : typed) {
                        WireUsage sinkWireUsage = wireUsageMap.get(sinkNode.getWire());
                        assert (sinkWireUsage != null);
                        assert (sinkWireUsage.getRoutes() != null);
                        assert (wireUsageMap.get(sinkNode.getWire()) != null);
                        sinkWireUsage.removeRoute(intersiteRoute);
                    }

                    // Detach the tree from its parent
                    assert (sink.getParent() != null);
                    sink.getParent().disconnect(sink);
                    intersiteRoute.getSinksToRoute().add(sink);
                    // No need to keep iterating through this branch's children as they will all have the
                    // same leaves
                }
            } else {
                // Continue searching down this branch's children for conflicts
                for (RouteTree child : tree.getChildren()) {
                    stack.push((PathFinderRouteTree)child);
                }
            }
        }

        if (intersiteRoute.getSinksToRoute().isEmpty()) {
            System.out.println("PROBLEM! Should not be empty!");
        }

        //assert(!intersiteRoute.getSinksToRoute().isEmpty());
    }

    /**
     * Apply the found routes to the RapidSmith2 data structures. This includes setting the inter-site route tree(s)
     * of CellNets and adding static source LUT Bels to the placement data structures. Note that individual cell pins
     * are added to a CellNet's list of routed cell pins within the maze router.
     * @param intersiteRoutes the inter-site routes to apply
     */
    private void applyRoutes(ArrayList<IntersiteRoute> intersiteRoutes) {
        // Apply Route Trees to RS2 data structures.
        for (IntersiteRoute intersiteRoute : intersiteRoutes) {
            CellNet net = intersiteRoute.getNet();
            net.setIntersiteRouteTrees(null);
            assert (intersiteRoute.getRouteTree() != null);

            // Add the inter-site route tree to the cell net
            if (intersiteRoute.isStatic()) {
                // For static nets, every child tree of the start tree (beginning at a global wire) is an inter-site tree
                RouteTree globalTree = intersiteRoute.getRouteTree();
                Collection<RouteTree> intersiteTrees = new ArrayList<>(intersiteRoute.getRouteTree().getChildren());
                for (RouteTree rt : intersiteTrees) {
                    // Disconnect the inter-site tree from the global wire
                    globalTree.disconnect(rt);
                    net.addIntersiteRouteTree(rt);
                }

                // Find any LUT BELs used as static sources and add them
                addStaticSourceLutCells(intersiteRoute);
            } else {
                net.addIntersiteRouteTree(intersiteRoute.getRouteTree());
            }
            net.setRouteStatus(RouteStatus.FULLY_ROUTED);
        }
    }

    /**
     * Adds possible static source LUTs that are within staticSearch size tiles of the inter-site route's
     * unrouted sinks. Each start tree starts at the output pin wire of a site whose corresponding LUT can be used as
     * a static source. Static source LUTs are unused LUTs that can reach an output pin of a site. This method only
     * considers leaving on the A/B/C/D pins, using the corresponding USED site PIP. Additionally, this method only
     * considers using the O6 output pin of LUT BELs.
     * TODO: Make the search more efficient by only searching through tiles that haven't been searched during
     * previous iterations.
     * @param intersiteRoute the static (VCC/GND) inter-site route
     * @param staticSearchSize the distance in tiles to search for sources
     */
    private void addPossibleStaticSources(IntersiteRoute intersiteRoute, int staticSearchSize) {
        Device device = design.getDevice();
        FamilyInfo familyInfo = FamilyInfos.get(device.getFamily());
        List<Wire> staticSourceWires = new ArrayList<>();

        for (PathFinderRouteTree sinkTree : intersiteRoute.getSinksToRoute()) {
            // Search for possible sources staticSearchSize tiles to the left, right, etc.
            Tile sinkTile = sinkTree.getWire().getTile();

            // Get the SLICEL/SLICEM sites in our search space
            int minRow = Math.max(sinkTile.getRow() - staticSearchSize, 0);
            int minCol = Math.max(sinkTile.getColumn() - staticSearchSize, 0);
            int maxRow = Math.min(device.getRows() - 1, sinkTile.getRow() + staticSearchSize);
            int maxCol = Math.min(device.getColumns() - 1, sinkTile.getColumn() + staticSearchSize);

            Collection<Tile> clbTiles = device.getTiles(minRow, minCol, maxRow, maxCol).stream()
                    .filter(tile -> familyInfo.clbTiles().contains(tile.getType()))
                    .collect(Collectors.toList());

            for (Tile tile : clbTiles) {
                for (Site site : tile.getSites()) {
                    Set<Bel> freeLutBels = site.getBels().stream()
                            .filter(bel -> bel.getType().equals("LUT6") || bel.getType().equals("LUT_OR_MEM6"))
                            .filter(bel -> !design.isBelUsed(bel))
                            .collect(Collectors.toSet());

                    for (Bel lutBel : freeLutBels) {
                        // Check if the corresponding site pips are used
                        String lutLetter = lutBel.getName().substring(0, 1);

                        if (design.getPIPInputValsAtSite(site) != null) {
                            boolean outputUsed = design.getPIPInputValsAtSite(site).containsKey(lutLetter + "USED");
                            boolean outMuxUsed = design.getPIPInputValsAtSite(site).containsKey(lutLetter + "OUTMUX");
                            boolean ffMuxUsed = design.getPIPInputValsAtSite(site).containsKey(lutLetter + "FFMUX");

                            if (outMuxUsed || outputUsed || ffMuxUsed)
                                continue;
                        }

                        Wire outWire = site.getPin(lutLetter).getExternalWire();

                        // There is always more than one PIP from the site-pin output wire, so we already don't need to find
                        // the first wire that branches.
                        staticSourceWires.add(outWire);
                    }
                }
            }
        }

        // Get the global wire
        Wire globalWire = intersiteRoute.getRouteTree().getWire();
        assert (globalWire instanceof GlobalWire);

        // Add the new connections
        for (Wire wire : staticSourceWires) {
            ((GlobalWire) globalWire).addConnection(new GlobalWireConnection(globalWire, wire));
        }
    }


    /**
     * Searches a routed static net for used static source LUTs and adds them to the design.
     * Pseudo cells are made for each static source and they are placed onto the corresponding BEL.
     * @param staticIntersiteRoute the inter-site route for the static (VCC/GND) net
     */
    private void addStaticSourceLutCells(IntersiteRoute staticIntersiteRoute) {
        CellNet net = staticIntersiteRoute.getNet();
        String cellPrefix = (net.isVCCNet()) ? "StaticVCCSource_" : "StaticGNDSource_";
        LibraryCell libCell = libCells.get("LUT1");

        for (RouteTree rt : net.getIntersiteRouteTreeList()) {
            Wire startWire = rt.getRoot().getWire();
            SitePin startPin = startWire.getReverseConnectedPin();
            if (startPin != null && familyInfo.sliceSites().contains(startPin.getSite().getType())) {
                // Start pins of inter-site routes should be bel-pins.
                // If it is a site pin, the intra-site portion of the route is missing
                String pinName = startPin.getName();
                assert (pinName.equals("A") || pinName.equals("B") || pinName.equals("C") || pinName.equals("D"));
                Bel staticSourceBel = startPin.getSite().getBel(pinName + "6LUT");

                // No cell should be placed at the bel yet.
                assert (design.getCellAtBel(staticSourceBel) == null);

                // Finally add a pseudo cell for the bel
                Cell staticSourceCell = new Cell(cellPrefix + staticSourceBel.getFullName(), libCell, true);
                design.addCell(staticSourceCell);
                design.placeCell(staticSourceCell, staticSourceBel);
                design.addPipInputValAtSite(staticSourceBel.getSite(), staticSourceBel.getName().charAt(0) + "USED", "0");

                // Add the BEL to the list of VCC/GND BELs
                if (net.isVCCNet()) {
                    vccSourceBels.add(staticSourceBel);
                } else if (net.isGNDNet()) {
                    gndSourceBels.add(staticSourceBel);
                }

                // TODO: RapidSmith2 currently does not represent the intra-site source trees for static nets.
                // Add this functionality and save the tree (O6 -> USED.0 -> USED.OUT -> A.A/B.B/etc.)
            }
        }
    }

    /**
     * Creates wire usage for a wire if the wire is not already present in the wire usage map (if it has not been
     * seen before).
     * @param wire the wire to create or get the wire usage for.
     * @return the wire usage for the wire
     */
    private WireUsage computeWireUsage(Wire wire) {
        WireUsage wireUsage = wireUsageMap.get(wire);

        if (wireUsage == null) {
            wireUsage = new WireUsage();
            wireUsageMap.put(wire, wireUsage);
        }

        return wireUsage;
    }

    /**
     * Updates the usage of all the wires of an inter-site route. This adds any used wires to the wireUsageMap and
     * updates their congestion.
     * @param intersiteRoute the intersite route
     */
    private void updateWireUsage(IntersiteRoute intersiteRoute) {
        RouteTree routeTree = intersiteRoute.getRouteTree().getRoot();
        // Iterate through every used wire and add it to the wireUsage map

        for (RouteTree rt : routeTree) {

            if (rt.getConnection() != null && !rt.getConnection().isPip()) {
                // Skip since we will have already processed it with the getWiresInNode call
                continue;
            }

            // Every wire in the node is now occupied by this route
            for (Wire wire : rt.getWire().getWiresInNode()) {
                WireUsage wireUsage = computeWireUsage(wire);
                wireUsage.addRoute(intersiteRoute);
                wireUsage.updatePresentCongestion(presentCongestionFactor);
            }

        }
    }

}
