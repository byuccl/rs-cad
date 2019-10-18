package edu.byu.ece.rapidSmith.cad.route.pathfinder;

import edu.byu.ece.rapidSmith.cad.route.IntersiteRoute;

import java.util.HashSet;
import java.util.Set;

/**
 * WireUsage class for keeping track of which inter-site routes (and thereby, nets) are currently using a wire.
 */
public class WireUsage {
	/** The capacity of the wire (how many nets can use it at once). */
	private final int capacity = 1;
	/** The base cost to use the wire. */
	private double wireCost;
	/** The inter-site Routes currently using the wire */
	private Set<IntersiteRoute> routes;
	/** The historical usage of the wire */
	private double history;
	/** The present congestion of the wire (related to how many nets are currently using it) */
	private double presentCongestion;

	/**
	 * Public constructor.
	 */
	public WireUsage() {
		wireCost = 1;
		history = 1;
		presentCongestion = 1;
		routes = new HashSet<>();
	}

	/**
	 * Gets the PathFinder cost for the wire. Uses the VPR congestion cost function, where all terms are multiplied
	 * together to avoid having to normalize b(n) and h(n).
	 * The original PathFinder cost function is [h(n) + b(n)] * congestion(n).
	 * @return the PathFinder cost.
	 */
	public double getPFCost() {
		return wireCost * history * presentCongestion;
	}

	/**
	 * Adds a route to the list of inter-site routes that are currently using the wire.
	 * @param route the route to add
	 */
	public void addRoute(IntersiteRoute route) {
		routes.add(route);
	}

	/**
	 * Increments the historical usage if the wire by the historyFactor.
	 * @param historyFactor the factor by which to increment the historical usage.
	 */
	public void incrementHistory(double historyFactor) {
		this.history += Math.max(0, (routes.size() - 1) * historyFactor);
	}

	/**
	 * Updates the present congestion of the wire depending upon the number of
	 * routes/nets using the wire and the presentCongestionFactor.
	 * @param presentCongestionFactor the factor by which the congestion is incremented (if the wire is congested).
	 */
	public void updatePresentCongestion(double presentCongestionFactor) {
		if (routes.size() < capacity)
			this.presentCongestion = 1;
		else if (routes.size() == capacity)
			this.presentCongestion = 1 + presentCongestionFactor;
		else
			this.presentCongestion = 1 + Math.max(0, routes.size() * presentCongestionFactor);
	}

	/**
	 * Removes the specified inter-site route from the list of routes that wire is using.
	 * @param intersiteRoute the route to remove
	 */
	public void removeRoute(IntersiteRoute intersiteRoute) {
		routes.remove(intersiteRoute);
	}

	/**
	 * Gets the capacity of the wire (the number of unrelated nets that can use the wire)
	 * @return the capacity
	 */
	public int getCapacity() {
		return capacity;
	}

	/**
	 * Get the inter-site routes that are currently using the wire.
	 * @return the set of inter-site routes using the wire.
	 */
	public Set<IntersiteRoute> getRoutes() {
		return routes;
	}

	public boolean isCongested() {
		return routes.size() > capacity;
	}

}
