package edu.byu.ece.rapidSmith.cad.pack.rsvpack

import edu.byu.ece.rapidSmith.cad.cluster.Cluster
import edu.byu.ece.rapidSmith.cad.cluster.ClusterFactory
import edu.byu.ece.rapidSmith.cad.cluster.PackUnit
import edu.byu.ece.rapidSmith.design.subsite.Cell
import edu.byu.ece.rapidSmith.design.subsite.CellDesign
import edu.byu.ece.rapidSmith.device.Bel


/**
 * The selector for identifying and choosing BELs for a cell.
 *
 * Expected order of calls:
 * 1: initCluster
 * 2: initCell
 * 3: nextBel
 * 4: goto 3, 5, 7, 9 or 11
 * 5: commitBels
 * 6: goto 2 or 11
 * 7: revertToLastCommit
 * 8: goto 2 or 11
 * 9: rollBackLastCommit
 * 10: goto 2 or 11
 * 11: cleanupCluster
 * 12: goto 1
 */
interface BelSelector<in T: PackUnit> {
	fun init(design: CellDesign)

	// Called when a cluster is created to allow the cluster.  The cluster should be empty.
	// Should not change the state of the cluster or netlist.
	fun initCluster(cluster: Cluster<T, *>)

	// Called before a new molecule is packed into the cluster.  The molecule should be
	// valid.  Should follow either initCluster or commitBels.
	fun initCell(cell: Cell, forcedAnchors: Collection<Bel>?)

	// Called to obtain the next bel in the molecule.  Should not be called
	// until after initCell.  Bel should be unused in the cluster.  If reqBels is not
	// null, then choose from on of the BELs in the collection.  Otherwise, choose any
	// unused BEL in the cluster.  BELs in reqBels may already be occupied in the cluster.
	fun nextBel(): Bel?

	/**
	 * Stores the previous state prior to committing the BEL, then updates the costs
	 * of each BEL and cleans the priority queue.
	 */
	fun commitBels(bels: Collection<Bel>)

	fun revertToLastCommit()
	fun rollBackLastCommit()

	/** Removes all ells from the cluster */
	fun cleanupCluster()
}

/**
 * Selector for identifying the best remaining cell to pack.
 */
interface CellSelector<in T: PackUnit> {
	fun init(design: CellDesign)
	fun initCluster(cluster: Cluster<T, *>, seed: Cell)
	fun nextCell(): Cell?
	fun commitCells(cells: Collection<Cell>, conditionals: Collection<Cell>?)
	fun cleanupCluster()

	fun rollBackLastCommit()
}

/**
 * Finds a seed molecule for the clustering.  If no suitable molecule is
 * found, returns null.
 */
interface SeedSelector<in T: PackUnit> {
	fun init(packUnits: Collection<PackUnit>, design: CellDesign)
	fun nextSeed(): Cell
	fun commitCluster(cluster: Cluster<T, *>)
}

/**
 *  Calculates the cost of a built cluster.
 */
interface ClusterCostCalculator<T: PackUnit> {
	fun init(generator: ClusterFactory<T, *>)
	fun calculateCost(cluster: Cluster<T, *>): Double
}
