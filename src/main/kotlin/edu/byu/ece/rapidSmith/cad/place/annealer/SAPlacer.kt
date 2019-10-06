package edu.byu.ece.rapidSmith.cad.place.annealer

import edu.byu.ece.rapidSmith.cad.cluster.Cluster
import edu.byu.ece.rapidSmith.cad.cluster.ClusterSite
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.CadException
import edu.byu.ece.rapidSmith.cad.place.Placer
import edu.byu.ece.rapidSmith.cad.place.annealer.configurations.DisplacementRandomInitialPlacer
import edu.byu.ece.rapidSmith.cad.place.annealer.configurations.HPWLCostFunctionFactory
import edu.byu.ece.rapidSmith.design.subsite.CellDesign
import edu.byu.ece.rapidSmith.device.Device
import java.util.*

/**
 * This placer is a very simple implementation of simulated annealing. At the beginning of the
 * anneal, most moves are accepted even if they increase the system cost. As it cools, fewer moves
 * are accepted.
 */
class SimulatedAnnealingPlacer<S : ClusterSite>(
	private val csgFactory: ClusterSiteGridFactory<S>,
	private val gprFactory: GroupPlacementRegionFactory<S>,
	private val validator: MoveValidator<S>,
	private val coolingScheduleFactory: CoolingScheduleFactory<S> = DefaultCoolingScheduleFactory(),
	private val costFunctionFactory: CostFunctionFactory<S> = HPWLCostFunctionFactory(),
	private val random: Random = Random(),
	private val initPlacer: InitialPlacer<S> = DisplacementRandomInitialPlacer(validator, random)
) : Placer<S>() {
	/**
	 * The placer starts out with a random placement. At the beginning of the
	 * anneal, most moves are accepted even if they increase the system cost.
	 * As it cools, fewer moves are accepted.
	 *
	 * This particular annealing schedule is based heavily on VPR. See
	 * "VPR: A New Packing, Placement and Routing Tool for FPGA Research"
	 * by Betz and Rose.
	 */
	override fun place(device: Device, design: CellDesign, clusters: List<Cluster<*, S>>) {
		val pdesign = PlacerDesign(clusters, design)
		val pdevice = PlacerDevice(device, design, csgFactory)
		val state = PlacerState(pdesign, pdevice, gprFactory, random, costFunctionFactory.make(pdesign))
		val coolingSchedule = coolingScheduleFactory.make(state, random)

		// Perform initial placement
		val allGroups = ArrayList(pdesign.groups)
		val initialPlaceSuccessful = initPlacer.initialPlace(pdesign, pdevice, state)

		// Check to see if the initial placer was successful or not
		if (!initialPlaceSuccessful) {
			throw CadException("Unsuccessful initial place")
		}

		coolingSchedule.initialize(pdesign, pdevice, validator)
		var currCost = state.currentCost
		val initialCost = currCost

		// Initialize time counter
		val initTime = System.currentTimeMillis()
		var currTime = initTime
		var lastTime: Long
		var prevNumMoves = 0

		// Flag that indicates whether another temperature iteration should proceed
		var numMoves = 0

		// Outer annealing loop. This loop will be called once for each temperature.
		while (coolingSchedule.keepGoing) {
			var numMovesAccepted = 0

			// This may also take forever (the while move == null loop)
			// This loop will perform a single move. It will be done "stepsPerTemp" times.
			for (unused in 0 until coolingSchedule.stepsPerTemp) {
				// Identify a move
				var move: PlacerMove<S>? = null
				while (move == null) {
					// TODO: this will skip groups that are difficult to place. If the group cannot be
					// placed, it looks at a different group.
					val toSwapIdx = random.nextInt(allGroups.size)
					val toSwap = allGroups[toSwapIdx]!!
					// TODO factor the rangeLimit into the placement regions
					val rangeLimit = coolingSchedule.rangeLimit
					move = proposeSwap(state, toSwap, rangeLimit, pdesign, validator)
				}
				move.perform(state)
				val newCost = state.currentCost
				val deltaCost = newCost - currCost

				val acceptMove = if (deltaCost < 0) {
					// if the cost is lowered, always accept the move.
					true
				} else {
					// Accept some moves that increase the cost. The higher the increase in
					// cost, the lowerDifferencePercentage = tempDiffCost/oldTempCost*100r the probability it will be accepted.
					val r = random.nextDouble()
					val moveThreshold = Math.exp(-deltaCost / coolingSchedule.temperature)
					r < moveThreshold
				}

				if (acceptMove) {
					currCost = newCost
					numMovesAccepted++
				} else {
					move.undo(state)
				}//reject the rest of the moves

				numMoves++
			}

			// Compute Time
			lastTime = currTime
			currTime = System.currentTimeMillis()
			val moves = numMoves - prevNumMoves
			val dTime = currTime - lastTime
			val movesPerMiliSecond = moves.toDouble() / dTime
			//println("\tTime: ${dTime.toDouble() / 1000} seconds. $moves moves. " +
			//	"Moves per second: ${movesPerMiliSecond * 1000}")
			prevNumMoves = numMoves

			coolingSchedule.update(coolingSchedule.stepsPerTemp, numMovesAccepted)
		}

		// Done. Reached the ending condition.

		//System.out.println("Final cost: " + currCost);
		val timeInMiliSeconds = System.currentTimeMillis() - initTime
		val movesPerSecond = numMoves.toDouble() / timeInMiliSeconds * 1000
		//println("Final cost: " + currCost + " (" + currCost / initialCost * 100 + "% of initial cost:" +
		//	initialCost + ")")
		//println(numMoves.toString() + " Moves in " + timeInMiliSeconds.toDouble() / 1000 + " seconds (" + movesPerSecond + " moves per second)")
		finalizePlacement(state, pdesign)
		pdesign.commit()

		// VCC and GND could possibly be fully routed now, so re-compute their route status if they have no site route trees
		if (design.vccNet.sinkSitePinRouteTrees.isEmpty())
			design.vccNet.computeRouteStatus()
		if (design.gndNet.sinkSitePinRouteTrees.isEmpty())
			design.gndNet.computeRouteStatus()
	}
}
