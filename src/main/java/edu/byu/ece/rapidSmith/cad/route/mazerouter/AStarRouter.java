package edu.byu.ece.rapidSmith.cad.route.mazerouter;

import edu.byu.ece.rapidSmith.cad.route.*;
import edu.byu.ece.rapidSmith.cad.route.pathfinder.PathFinder;
import edu.byu.ece.rapidSmith.cad.route.pathfinder.PathFinderRouteTree;
import edu.byu.ece.rapidSmith.cad.route.pathfinder.WireUsage;
import edu.byu.ece.rapidSmith.design.subsite.CellDesign;
import edu.byu.ece.rapidSmith.design.subsite.CellNet;
import edu.byu.ece.rapidSmith.design.subsite.CellPin;
import edu.byu.ece.rapidSmith.design.subsite.RouteTree;
import edu.byu.ece.rapidSmith.device.*;
import edu.byu.ece.rapidSmith.timing.Path;

import java.util.*;

/**
 * An A* maze router. Intended to be used within the inner loop of PathFinder.
 */
public class AStarRouter extends MazeRouter {
    /** The target tile for the current sink */
    private Tile targetTile;
    /** Comparator for comparing route trees by cost */
    private final Comparator<PathFinderRouteTree> routeTreeComparator;
    /** The start tile to use when comparing two sink trees */
    private Tile sinkCompareStartTile;
    /** Comparator for comparing and sorting sink trees by distance from the source tile */
    private final Comparator<PathFinderRouteTree> sinkTreeComparator;

    /**
     * Constructor for A* Router.
     * @param design the cell design we are routing
     * @param wireUsage a map from wires to their usage.
     * @param useRoutethroughs whether to use site route-throughs
     */
    public AStarRouter(CellDesign design, Map<Wire, WireUsage> wireUsage, boolean useRoutethroughs) {
        super(design, wireUsage, useRoutethroughs);

        // Set up the comparator to use for comparing Route Trees.
        // This is used whenever a tree is added to the priority queue.
        routeTreeComparator = (one, two) -> {
            // distance from source + distance remaining + pathfinder cost
            Double costOne = one.getWireSegmentCost() + manhattanDistance(one, targetTile) + one.getPathFinderCost();
            Double costTwo = two.getWireSegmentCost() + manhattanDistance(two, targetTile) + two.getPathFinderCost();
            return costOne.compareTo(costTwo);
        };

        // Compare the sink trees by distance from the driver (source) tile.
        // If the sinks are in the same tile, arbitrarily compare by wire enum.
        sinkTreeComparator = (one, two) -> {
            int costOne = one.getWire().getTile().getIndexManhattanDistance(sinkCompareStartTile);
            int costTwo = two.getWire().getTile().getIndexManhattanDistance(sinkCompareStartTile);
            if (costOne == costTwo) {
                costOne = one.getWire().getWireEnum();
                costTwo = two.getWire().getWireEnum();
            }
            return costTwo - costOne;
        };
    }

	/**
	 *
	 * @param intersiteRoute
	 * @param startTree
	 * @param processedWires
	 * @param priorityQueue
	 * @param currSink
	 */
    private void makePreviousSinksFree(IntersiteRoute intersiteRoute, PathFinderRouteTree startTree, Set<Wire> processedWires, PriorityQueue<PathFinderRouteTree> priorityQueue, PathFinderRouteTree currSink) {
    	if (intersiteRoute.isStatic()) {
			// Since VCC/GND have multiple inter-site routes, all previous sinks can't be free.
			// Only consider sinks that originate from (a) neighboring tie-offs free and (b)
			// sinks that are driven by static source LUTs.

			// First, add the global wire's connections back
			//PathFinderRouteTree startCost = (PathFinderRouteTree) startTree;
			processedWires.add(startTree.getWire());
			priorityQueue.add(startTree);

			for (RouteTree childTree : startTree.getChildren()) {
				// If the child tree is a VCC/GND wire, it and its children can only be free if it originates from
				// a tie-off that neighbors the current sink
				if (childTree.getWire().getName().equals("VCC_WIRE") || childTree.getWire().getName().equals("GND_WIRE")) {

					if (childTree.getWire().getTile() == currSink.getWire().getTile()) {
						// Make this and all of its children free
						Iterable<PathFinderRouteTree> pfChildTree = childTree.typedIterator();
						for (PathFinderRouteTree rt : pfChildTree) {
							rt.freeWireSegmentCost();
							rt.freePathFinderCost();
							processedWires.add(rt.getWire());
							priorityQueue.add(rt);
						}
					}
				} else {
					// Make non tie-offs not quite free since tie-offs should be preferred, but still cheap.
					// It would be nice to do this more intelligently.
					Iterable<PathFinderRouteTree> pfChildTree = childTree.typedIterator();
					for (PathFinderRouteTree rt : pfChildTree) {
						rt.setWireSegmentCost(1);

						// The path finder cost shouldn't be equal for every single tie-off.
						rt.setPathFinderCost(4);
						processedWires.add(rt.getWire());
						priorityQueue.add(rt);
					}
				}
			}
		} else {
			Iterable<PathFinderRouteTree> pfStartTree = startTree.typedIterator();
			for (PathFinderRouteTree rt : pfStartTree) {
				// Re-using wires that have already been used for prior sinks should be considered "free"
				rt.setWireSegmentCost(0);
				rt.setPathFinderCost(0);
				processedWires.add(rt.getWire());
				priorityQueue.add(rt);
			}
		}

    }

    /**
     * Routes the specified {@link IntersiteRoute} using the A* routing algorithm.
     * @return True if successful, false otherwise.
     */
    public boolean routeNet(IntersiteRoute intersiteRoute) {
        // Priority Queue of route trees, sorted by decreasing cost to the sink
        PriorityQueue<PathFinderRouteTree> priorityQueue = new PriorityQueue<>(routeTreeComparator);

        // Routed terminals for the current net
        Set<PathFinderRouteTree> terminals = new HashSet<>();

        // Initialize the route
        PathFinderRouteTree startTree = intersiteRoute.getRouteTree();

        // Find the pins that need to be routed for the net
        List<PathFinderRouteTree> sinksToRoute = new ArrayList<>(intersiteRoute.getSinksToRoute());
        assert !sinksToRoute.isEmpty() : "CellNet object should have at least one sink in order to route it";

        // Sort the sinks by decreasing distance from source tile
        if (!intersiteRoute.isStatic()) {
            sinkCompareStartTile = intersiteRoute.getRouteTree().getWire().getTile();
            sinksToRoute.sort(sinkTreeComparator);
        }

        // Existing wires contains sink wires that the A* Router has tried to use (and possibly actually used)
        // for this net so far. This set is used to prevent the A* Router from getting stuck in loops, checking
        // the same connections infinitely. After a sink is found, processedWires is reset to include only
        // the wires used to reach the found terminals. This way, new trees are not made to reach wires that
        // existing trees have already connected to.
        Set<Wire> processedWires;


        // Add the terminals of the routed sinks to the set of terminals
        for (PathFinderRouteTree sink : intersiteRoute.getRoutedSinks()) {
            terminals.add(intersiteRoute.getTerminalTree(sink));
        }

        // Iterate over each sink and find a valid route to it.
        for (PathFinderRouteTree sinkTree : sinksToRoute) {
        	assert (sinkTree != null);
            assert (intersiteRoute.getSinkTerminalTreeMap().get(sinkTree) != null);

			Wire terminalWire = intersiteRoute.getTerminalTree(sinkTree).getWire();
			Wire targetWire = sinkTree.getWire();
			targetTile = targetWire.getTile();

			// If there are remaining sinks to route for this net, reset the existing branches to only include
			// wires already in the final route tree. These route trees will be added to the priority queue, but
			// adding their wires to processedWires will prevent duplicate route trees from being added.
			processedWires = new HashSet<>();
			priorityQueue.clear();
			makePreviousSinksFree(intersiteRoute, startTree, processedWires, priorityQueue, sinkTree);
            boolean routeFound = false;

            // This loop actually builds the routing data structure
            while (!routeFound) {
                // Grab the lowest cost route from the queue
                if (priorityQueue.size() == 0) {
                    System.err.println("[WARNING] " + intersiteRoute.getNet().getName() + " sink " + sinkTree.getWire().getFullName() + " could not be routed.");
                    return false;
                }

                PathFinderRouteTree currTree = priorityQueue.poll();

                // Search all connections for the wire of the current RouteTree
                Wire currWire = currTree.getWire();

                // If the currWire is the solution
               if (currWire.equals(targetWire)) {
                    if (currTree.getParent() == null) {
                        // Assume this is the direct-connection case (like a COUT to CIN net)
                        startTree = sinkTree;
                    } else {
                        // Non-direct only case. This can occur if an earlier sink routed here.
                        // Connect the new route tree
						// must preserve sink tree for inter-site maps, etc.
						PathFinderRouteTree parent = currTree.getParent();
						Connection mainCon = currTree.getConnection();
						parent.disconnect(currTree);
						Collection<RouteTree> oldChildren = new ArrayList<>(currTree.getChildren());
						for (RouteTree rt : oldChildren) {
							Connection oldCon = rt.getConnection();
							currTree.disconnect(rt);
							sinkTree.connect(oldCon, rt);
						}
						parent.connect(mainCon, sinkTree);

                    }
                    terminals.add(intersiteRoute.getTerminalTree(sinkTree));
                    break;
                } else if (currWire.equals(terminalWire)) {
                    // Not "direct connection", but there is only one way for the source to make it to the sink.
                    // Basically, uses PIP junctions that only have one source wire and sink wire (so it is a pseudo
                    // direct connection). This is common with BRAM/DSP nets.
                    terminals.add(intersiteRoute.getTerminalTree(sinkTree));
                    break;
                }

                // Add possible connections to the queue
                Collection<Connection> currConnections = getFilteredConnections(intersiteRoute, terminalWire, currTree, processedWires);
                for (Connection connection : currConnections) {
                    // If a connection is the solution, don't bother processing the remaining connections
                    if (connection.getSinkWire().equals(targetWire)) {
                    	sinkTree = currTree.connect(connection, sinkTree);
                        terminals.add(intersiteRoute.getTerminalTree(sinkTree));
                        routeFound = true;
                        break;
                    } else {
                        PathFinderRouteTree connTree = processConnection(intersiteRoute, currTree, connection, processedWires);
                        if (!priorityQueue.contains(connTree)) {
                            priorityQueue.add(connTree);
                        }
                    }
                }
            }

            // A route for the sink has been found.
            // Mark all cell pins corresponding to this sink as routed. Remember a cell pin may correspond to
            // multiple site pins and that a site pin may correspond to multiple cell pins!
            for (CellPin cellPin : intersiteRoute.getSinkCellPins(terminalWire)) {
                CellNet net = cellPin.getNet();
                net.addRoutedSink(cellPin);
            }

            startTree.prune(terminals);
        }

        // Register the leaves for the inter-site route tree
        for (PathFinderRouteTree leaf : terminals) {
            leaf.registerLeaf(leaf);
        }

        intersiteRoute.setRouteTree(startTree);
        return true;
    }

    /**
     * Makes a connection (that is not the solution) and returns the sink tree. Also sets the cost for using this
     * connection and adds the sink wire to the list of searched wires.
     * @param parent the parent route tree
     * @param connection the connection to process
     * @param searchedWires the set of wires that have already been considered
     * @return the sink tree
     */
    private PathFinderRouteTree processConnection(IntersiteRoute intersiteRoute, PathFinderRouteTree parent, Connection connection, Set<Wire> searchedWires) {
        Wire sinkWire = connection.getSinkWire();
		assert(!sinkWire.equals(parent.getWire()));
        PathFinderRouteTree sinkTree = parent.connect(connection);

		WireUsage wireUsage = this.wireUsageMap.get(sinkWire);
		double pfCost = (wireUsage == null) ? 1 : wireUsage.getPFCost();

		if (!connection.isPip()) {
			sinkTree.setWireSegmentCost(parent.getWireSegmentCost());
			sinkTree.setPathFinderCost(parent.getPathFinderCost());
		} else {
			sinkTree.setPathFinderCost(parent.getPathFinderCost() + pfCost);

			// Make it cheaper to connect within the same tile (bounce pips, etc.)
			if (connection.getSourceWire().getTile() == connection.getSinkWire().getTile()) {
				sinkTree.setWireSegmentCost(parent.getWireSegmentCost() + 0.65);
			} else {
				sinkTree.setWireSegmentCost(parent.getWireSegmentCost() + 1);
			}
		}
        searchedWires.add(sinkWire);
        return sinkTree;
    }
}
