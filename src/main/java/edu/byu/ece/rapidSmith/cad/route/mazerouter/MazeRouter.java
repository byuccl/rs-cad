package edu.byu.ece.rapidSmith.cad.route.mazerouter;

import edu.byu.ece.rapidSmith.cad.route.GlobalWire;
import edu.byu.ece.rapidSmith.cad.route.IntersiteRoute;
import edu.byu.ece.rapidSmith.cad.route.pathfinder.WireUsage;
import edu.byu.ece.rapidSmith.design.subsite.CellDesign;
import edu.byu.ece.rapidSmith.design.subsite.CellNet;
import edu.byu.ece.rapidSmith.design.subsite.RouteTree;
import edu.byu.ece.rapidSmith.device.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Abstract class for maze routers. A maze router is used within the inner loop of PathFinder.
 */
public abstract class MazeRouter {
    protected CellDesign design;
    protected FamilyType family;
    /** A map from wires to their wire usage. */
    protected Map<Wire, WireUsage> wireUsageMap;
    /** Whether to use site routethroughs */
    private boolean useRoutethroughs;

    /**
     * MazeRouter constructor.
     * @param design the cell design we are routing
     * @param wireUsageMap a map from wires to their usage.
     * @param useRoutethroughs whether to use site route-throughs
     */
    public MazeRouter(CellDesign design, Map<Wire, WireUsage> wireUsageMap, boolean useRoutethroughs) {
        this.design = design;
        this.family = design.getFamily();
        this.wireUsageMap = wireUsageMap;
        this.useRoutethroughs = useRoutethroughs;
    }

    /**
     * Routes the specified {@link CellNet} using the maze router.
     */
    abstract public boolean routeNet(IntersiteRoute intersiteRoute);

    /**
     * Calculates the Manhattan distance between the specified {@link RouteTree} and {@link Tile} objects.
     * The Tile of the wire within {@code tree} is used for the comparison. The Manhattan distance from
     * a {@link RouteTree} to the final destination tile is used for "H" in the A* Router.
     * @param tree        {@link RouteTree}
     * @param compareTile {@link Tile}
     * @return The Manhattan distance between {@code tree} and {@code compareTile}
     */
    protected int manhattanDistance(RouteTree tree, Tile compareTile) {
        Wire wire = tree.getWire();

     //   if (wire.getTile() == compareTile)
      //  	System.out.println("OK");

        if (wire instanceof GlobalWire) {
            return 0;
        } else {
            Tile currentTile = wire.getTile();
            return currentTile.getIndexManhattanDistance(compareTile);
        }
    }

    /**
     * Gets a list of filtered connections from a route tree, taking into account the type of the net, the type of the
     * connection, whether the sink wire is reserved, and whether the sink wire is included in a list of wires that
     * should not be considered.
     * @param intersiteRoute the inter-site route for the CellNet
     * @param routeTree the route tree to get connections for
     * @param excludeWires sink wires to not include
     * @return the list of filtered connections
     */
    protected Collection<Connection> getFilteredConnections(IntersiteRoute intersiteRoute, Wire terminalWire, RouteTree routeTree, Set<Wire> excludeWires) {
        return routeTree.getWire().getWireConnections().stream()
                .filter(conn -> !excludeWires.contains(conn.getSinkWire()))
                .filter(conn -> isConnectionValid(intersiteRoute, conn, terminalWire))
                .filter(conn -> design.isWireAvailable(intersiteRoute.getNet(), conn.getSinkWire()))
                .collect(Collectors.toList());
    }

    /**
     * Returns whether a given connection is valid, taking into account the type of net that is being routed, the type of
     * sink wire, and the type of the connection.
     */
    private boolean isConnectionValid(IntersiteRoute intersiteRoute, Connection connection, Wire terminalWire) {
        Wire wire = connection.getSinkWire();
        Tile sinkTile = wire.getTile();

        boolean clk = wire.getName().contains("CLK");
        boolean gfan = wire.getName().contains("GFAN");

        // Don't make connections outside of a partial device
        if (sinkTile.getType().equals(TileType.valueOf(family, "OOC_WIRE")))
            return false;

        // If the connection is a route-through, check that it can be used
        if (connection.isRouteThrough()) {
            if (!useRoutethroughs)
                return false;
            else if (!canUseRoutethrough(connection))
                return false;
        }

        // Ensure certain types of nets only use certain wires
        if (intersiteRoute.isGlobalClk()) {
            // Global clock nets can only use "CLK" and "GFAN" wire sinks.
            return clk || gfan;
        } else if (intersiteRoute.isLocalClk()) {
            // local clocks can use normal local routing resources, clk sink wires, HCLK, LIO, etc.
            // It seems like they can use anything besides GFAN
            return !gfan;
        } else if (intersiteRoute.isClkBuffer()) {
            // It seems that clk buffers can use just about any wire, including LIO, IOI, HCLK,CLK_HROW, CLK_BUFG,
            // CGF_CENTER_MID, INT_INTERFACE, INT, etc.
            return true;
        } else if (intersiteRoute.isStatic()) {
			if (intersiteRoute.isGnd() && (wire.getName().equals("GND_WIRE"))) {
				// For speed, don't allow tie-offs from other tiles
				return intersiteRoute.isLocalTieOff(terminalWire.getTile(), sinkTile);
			} else if (intersiteRoute.isVcc() && (wire.getName().equals("VCC_WIRE"))) {
				return intersiteRoute.isLocalTieOff(terminalWire.getTile(), sinkTile);
			}
			// static nets can connect to clock sinks
            if (clk)
                return true;
        } else {
            // Normal nets can connect to anything that isn't a clock wire
            return !clk;
        }
        return true;
    }

    /**
     * Returns whether a site route-through can be used to make a route. Currently allows a very limited set of
     * slice routethroughs to be used and allows all other site types to be routed through.
     * TODO: Add proper support for using site route-throughs. Additional information on site routethroughs (such
     * as what makes up each site route-through) would be helpful.
     * @param connection the site route-through connection
     * @return whether the site route-through can be used
     */
    private boolean canUseRoutethrough(Connection connection) {
        SiteType siteType = connection.getSite().getType();
        if (siteType.equals(SiteType.valueOf(family, "SLICEM")) || siteType.equals(SiteType.valueOf(family, "SLICEL"))) {
            // Make the assumption that if the site-routethrough is an output-to-output routethrough,
            // we can use it even if the site is used.
            SitePin sourceSitePin = connection.getSourceWire().getReverseConnectedPin();
            SitePin sinkSitePin = connection.getSinkWire().getReverseConnectedPin();

            // If output-to-output
            if (sourceSitePin != null && sinkSitePin != null) {
                // TODO: Optimize
                switch (sourceSitePin.getName()) {
                    case "A":
                        if ("AMUX".equals(sinkSitePin.getName())) {
                            if (design.isSitePipAtSiteUsed(connection.getSite(), "AOUTMUX")) {
                                return false;
                            }
                        } else {
                            System.err.println("Unexpected site routethrough: A-> " + sinkSitePin.getName());
                        }
                        break;
                    case "B":
                        if ("BMUX".equals(sinkSitePin.getName())) {
                            if (design.isSitePipAtSiteUsed(connection.getSite(), "BOUTMUX")) {
                                return false;
                            }
                        } else {
                            System.err.println("Unexpected site routethrough: B-> " + sinkSitePin.getName());
                        }
                        break;
                    case "C":
                        if ("CMUX".equals(sinkSitePin.getName())) {
                            if (design.isSitePipAtSiteUsed(connection.getSite(), "COUTMUX")) {
                                return false;
                            }
                        } else {
                            System.err.println("Unexpected site routethrough: C-> " + sinkSitePin.getName());
                        }
                        break;
                    case "D":
                        if ("DMUX".equals(sinkSitePin.getName())) {
                            if (design.isSitePipAtSiteUsed(connection.getSite(), "DOUTMUX")) {
                                return false;
                            }
                        } else {
                            System.err.println("Unexpected site routethrough: D-> " + sinkSitePin.getName());
                        }
                        break;
                    case "COUT":
                        /*
                        if ("DMUX".equals(sinkSitePin.getName())) {
                            // COUTUSED is used and DOUTMUX with CY as the input pin.
                            if (design.isSitePipAtSiteUsed(connection.getSite(), "DOUTMUX")) {
                                return false;
                            }
                        }
                        */
                        return true;
                    default:
                        System.err.println("Unexpected site routethrough: " + sourceSitePin.getName() + "-> " + sinkSitePin.getName());
                        break;

                }
            } else {
                // Assume the site-route-through is from a site's input pin to a site's output pin
                // If the site is used at all, don't use it for routing
                //return !design.isSiteUsed(connection.getSite());
                return false;
            }
        }

        // Allow all other site route-throughs.
        return true;

    }
}
