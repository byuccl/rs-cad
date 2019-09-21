package edu.byu.ece.rapidSmith.cad.route.pathfinder;

import edu.byu.ece.rapidSmith.design.subsite.RouteTree;
import edu.byu.ece.rapidSmith.device.Wire;

import java.util.Collection;
import java.util.HashSet;

/**
 * The route tree to use for Path Finder. Includes cost variables and the ability to manually register leaves.
 */
public class PathFinderRouteTree extends RouteTree {
	/** The cost of using a single wire segment. */
	private double wireSegmentCost = 1;
	private double pathFinderCost = 1;
	/** The manually registered leaves of the tree. */
	private Collection<PathFinderRouteTree> leaves;

	public PathFinderRouteTree(Wire wire) {
		super(wire);
	}

	public double getPathFinderCost() {
		return pathFinderCost;
	}

	public void setPathFinderCost(double pathFinderCost) {
		this.pathFinderCost = pathFinderCost;
	}

	@Override
	protected PathFinderRouteTree newInstance(Wire wire) {
		return new PathFinderRouteTree(wire);
	}

	public double getWireSegmentCost() {
		return wireSegmentCost;
	}

	public void setWireSegmentCost(double wireSegmentCost) {
		this.wireSegmentCost = wireSegmentCost;
	}

	/**
	 * Register a PathFinderRouteTree as a leaf of this PathFinderRouteTree. The leaf tree is also registered as a
	 * leaf for all of this tree's ancestors.
	 *
	 * @param leaf the leaf tree
	 */
	public void registerLeaf(PathFinderRouteTree leaf) {
		if (leaves == null)
			leaves = new HashSet<>();
		leaves.add(leaf);

		if (this.isSourced()) {
			((PathFinderRouteTree) getParent()).registerLeaf(leaf);
		}
	}

	/**
	 * Unregister the leaves of this node and all of its children.
	 */
	public void unregisterLeaves() {
		Iterable<PathFinderRouteTree> typed = this.typedIterator();

		for (PathFinderRouteTree tree : typed) {
			tree.leaves.clear();
		}
	}

	@Override
	public Collection<PathFinderRouteTree> getLeaves() {
		return leaves;
	}

}