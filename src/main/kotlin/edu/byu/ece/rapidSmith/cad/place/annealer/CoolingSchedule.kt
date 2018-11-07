package edu.byu.ece.rapidSmith.cad.place.annealer

import edu.byu.ece.rapidSmith.cad.cluster.ClusterSite
import java.util.*

interface CoolingScheduleFactory<S: ClusterSite> {
	fun  make(placerState: PlacerState<S>, random: Random): CoolingSchedule<S>
}

interface CoolingSchedule<S: ClusterSite> {
	fun initialize(
		design: PlacerDesign<S>, device: PlacerDevice<S>, validator: MoveValidator<S>
	)
	fun update(numTempMoves: Int, numTempMovesAccepted: Int)

	val keepGoing: Boolean
	val stepsPerTemp: Int
	val rangeLimit: Int
	val temperature: Double
}

/**
 * A type that indicates the effort level of the placer
 */
enum class EffortLevel {
	LOW, MEDIUM, HIGH, NORMAL, HIGH_L, HIGH_M, HIGH_H
}

class DefaultCoolingScheduleFactory<S: ClusterSite>(
	private val effortLevel: EffortLevel = EffortLevel.NORMAL
) : CoolingScheduleFactory<S> {
	override fun make(
		placerState: PlacerState<S>, random: Random
	): CoolingSchedule<S> {
		return DefaultCoolingSchedule(placerState, effortLevel, random)
	}
}

class DefaultCoolingSchedule<S: ClusterSite>(
	val state: PlacerState<S>,
	effortLevel: EffortLevel,
	val random: Random
): CoolingSchedule<S> {
	// Constants
	private val qualityMultiplier: Double
	private val percentageThreshold: Double
	private val usePercentMode: Boolean
	private val MAX_TEMPERATURES_BELOW_COST_THRESHOLD: Int

	init {
		when (effortLevel) {
			EffortLevel.LOW -> {
				qualityMultiplier = HIGHER_QUALITY_MULTIPLIER * .4
				usePercentMode = true
				percentageThreshold = 5.0
				MAX_TEMPERATURES_BELOW_COST_THRESHOLD = 3
			}
			EffortLevel.MEDIUM -> {
				qualityMultiplier = HIGHER_QUALITY_MULTIPLIER * .75
				usePercentMode = true
				percentageThreshold = 1.0
				MAX_TEMPERATURES_BELOW_COST_THRESHOLD = 5
			}
			EffortLevel.HIGH -> {
				qualityMultiplier = HIGHER_QUALITY_MULTIPLIER
				usePercentMode = true
				percentageThreshold = 0.5
				MAX_TEMPERATURES_BELOW_COST_THRESHOLD = 10
			}
			EffortLevel.NORMAL -> {
				qualityMultiplier = HIGHER_QUALITY_MULTIPLIER
				usePercentMode = false
				percentageThreshold = 1.0
				MAX_TEMPERATURES_BELOW_COST_THRESHOLD = 5
			}
			EffortLevel.HIGH_L -> {
				qualityMultiplier = HIGHER_QUALITY_MULTIPLIER * .4
				usePercentMode = true
				percentageThreshold = 0.5
				MAX_TEMPERATURES_BELOW_COST_THRESHOLD = 10
			}
			EffortLevel.HIGH_M -> {
				qualityMultiplier = HIGHER_QUALITY_MULTIPLIER * .75
				usePercentMode = true
				percentageThreshold = 0.5
				MAX_TEMPERATURES_BELOW_COST_THRESHOLD = 10
			}
			EffortLevel.HIGH_H -> {
				qualityMultiplier = HIGHER_QUALITY_MULTIPLIER * 1.5
				usePercentMode = true
				percentageThreshold = 0.5
				MAX_TEMPERATURES_BELOW_COST_THRESHOLD = 10
			}
		}
	}

	// Post initialize constants
	private var COST_THRESHOLD: Double = -1.0
	private var MAX_RANGE_LIMIT: Int = -1
	private var design: PlacerDesign<*>? = null

	// Private variable fields
	private var oldCost: Double = -1.0
	private var numTemperaturesBelowCostThreshold = 0

	// public temperature state variables
	override var keepGoing = true
		private set

	override var stepsPerTemp: Int = -1
		private set

	override var rangeLimit: Int = -1
		private set

	override var temperature: Double = -1.0
		private set

	override fun initialize(
		design: PlacerDesign<S>, device: PlacerDevice<S>, validator: MoveValidator<S>
	) {
		// Initialize annealing schedule
		val currCost = state.currentCost
		oldCost = currCost

		val groups = design.groups.toTypedArray()
		temperature = 1.5 * findInitialTemperature(
			state, groups, currCost, random, design, validator)
		val numRealNets = getRealNets(design).size

		// TODO: Use the constraint rather than the device size
		MAX_RANGE_LIMIT = device.columns + device.rows
		rangeLimit = MAX_RANGE_LIMIT

		// TODO: this will skip groups that are difficult to place. If the group cannot be
		// placed, it looks at a different group.

		stepsPerTemp = (Math.pow(groups.size.toDouble(), 1.33) * qualityMultiplier).toInt()
//		println("Max Range Limit = $MAX_RANGE_LIMIT steps per temp=$stepsPerTemp")

		COST_THRESHOLD = .05 * currCost / numRealNets
		this.design = design
	}

	override fun update(numTempMoves: Int, numTempMovesAccepted: Int) {
		val currentCost = state.currentCost
		val tempDiffCost = currentCost - oldCost
		val differencePercentage = tempDiffCost / currentCost * 100
		val percentThresholdExceeded = differencePercentage <= 0 &&
			-differencePercentage >= percentageThreshold
		val tempThresholdExceeded = tempDiffCost > 0 || -tempDiffCost < COST_THRESHOLD
		if ((usePercentMode && !percentThresholdExceeded) || (!usePercentMode && tempThresholdExceeded)) {
			numTemperaturesBelowCostThreshold++
			if (numTemperaturesBelowCostThreshold >= MAX_TEMPERATURES_BELOW_COST_THRESHOLD) {
				keepGoing = false
//				if (!usePercentMode)
//					println("Did not meet threshold of $COST_THRESHOLD for " +
//						"$numTemperaturesBelowCostThreshold consecutive temperatures")
//				else
//					println("The delta cost percent fell below -$percentageThreshold% for " +
//						"$numTemperaturesBelowCostThreshold consecutive times.")
			}
		} else {
			numTemperaturesBelowCostThreshold = 0
		}

		// Compute new cost
		val fractionOfMovesAccepted = numTempMovesAccepted.toDouble() / numTempMoves.toDouble()
		temperature = findNewTemperature(fractionOfMovesAccepted, temperature)

//		val diffPercent = String.format("%3.3f", tempDiffCost / currentCost * 100)
//		println("\tNew cost=$currentCost delta cost: $tempDiffCost ($diffPercent%)")
		rangeLimit = findNewRangeLimit(fractionOfMovesAccepted, rangeLimit, MAX_RANGE_LIMIT)

//		println("\tRange Limit: " + rangeLimit)

		//work harder in more productive parts of the anneal
		//TODO: make this vary directly with alpha? We want alpha=.44, and
		//we want to make lots of moves at that alpha.
		if (rangeLimit < MAX_RANGE_LIMIT) {
			stepsPerTemp = (Math.pow(design!!.groups.size.toDouble(), 1.33) * qualityMultiplier).toInt()
		}
		oldCost = currentCost
	}
}

/**
 * Updates the temperature of the anneal based on the last temperature
 * and the percentage of moves that were accepted during the last
 * temperature cycle (alpha).
 *
 * @param fractionOfMovesAccepted
 * @param curTemp
 * @return
 */
private fun findNewTemperature(fractionOfMovesAccepted: Double, curTemp: Double): Double {
	val newTemp: Double
	val alpha = when {
		fractionOfMovesAccepted > 0.96 -> 0.7
		fractionOfMovesAccepted > 0.8 -> 0.9
		fractionOfMovesAccepted > 0.25 -> 0.95
		else -> 0.8
	}

	newTemp = alpha * curTemp
	val fiftyPercentCostAccept = (-Math.log(.5)) * newTemp
	//println("New temp=" + newTemp + " " + (fractionOfMovesAccepted * 100).toInt() +
	//	"% accepted Alpha=" + alpha +
	//	" 50% delta cost accept=" + fiftyPercentCostAccept)
	return newTemp
}

/**
 * Find the range limit - that is, the maximum distance that any group can be
 * moved from its existing placement.
 *
 * @param fractionOfMovesAccepted
 * @param oldLimit
 * @param maxRangeLimit
 * @return
 */
private fun findNewRangeLimit(fractionOfMovesAccepted: Double, oldLimit: Int, maxRangeLimit: Int): Int {
	val computedLimit = oldLimit * (1 - TARGET_ALPHA + fractionOfMovesAccepted)
	val newLimit = computedLimit.toInt().coerceIn(1, maxRangeLimit)
//	if (newLimit != oldLimit)
//		println("\tNew range=$computedLimit old range=$oldLimit")
	return newLimit
}

/**
 * Returns the initial temperature for the anneal. This is based on the standard
 * deviation of the cost of a move from the initial placement. This is a little
 * lower than the 20X standard deviation suggested in the VPR paper; this means
 * an initial close placement is not completely "blown up" into a random placement.
 *
 * @param groups
 * @param initCost
 * @return
 */
private fun <S: ClusterSite> findInitialTemperature(
	state: PlacerState<S>, groups: Array<PlacementGroup<S>>, initCost: Double,
	random: Random, design: PlacerDesign<S>, validator: MoveValidator<S>
): Double {
	val allMoveDeltaCosts = ArrayList<Double>()
	val allMoveCosts = ArrayList<Double>()
	for (i in groups.indices) {
		var move: PlacerMove<S>? = null
		while (move == null) {
			val toSwapIdx = random.nextInt(groups.size)
			val toSwap = groups[toSwapIdx]
			// maximum range, shift right once to avoid overflow
			move = proposeSwap(state, toSwap, Int.MAX_VALUE ushr 1, design, validator)
		}
		move.perform(state)
		val newCost = state.currentCost
		allMoveDeltaCosts.add(newCost - initCost)
		allMoveCosts.add(newCost)
		move.undo(state)
	}
	val stdDev = calcStdDev(allMoveDeltaCosts)
	val temperature = stdDev / 15

	// Print debug messages regarding the computation of the initial temperature
//	println("Initial temperature = " + temperature + " computed from " + allMoveDeltaCosts.size + " moves.")
//	println("\tAvg delta cost= " + calcMean(allMoveDeltaCosts) + " std=" + stdDev)
//	println("\tAvg move cost=" + calcMean(allMoveCosts) + " std dev=" + calcStdDev(allMoveCosts) +
//		" temp would be " + 20 * calcStdDev(allMoveCosts))
	return temperature
}

/**
 * Computes the mean of the doubles in the values List.
 *
 * @param values
 * @return
 */
private fun calcMean(values: Collection<Double>): Double {
	var total = 0.0
	for (f in values) {
		total += f
	}
	return total / values.size
}

/**
 * Computes the standard deviation of the doubles
 * in the values List.
 *
 * @param values
 * @return
 */
private fun calcStdDev(values: Collection<Double>): Double {
	val mean = calcMean(values)
	val sqDiffsFromMean = java.util.ArrayList<Double>()
	for (f in values) {
		sqDiffsFromMean.add(Math.pow(f - mean, 2.0))
	}
	return Math.sqrt(calcMean(sqDiffsFromMean))
}

/**
 * The percentage of moves made that are actually accepted should be
 * around 44% for best results. See "Performance of a New Annealing
 * Schedule" by Lam and Delosme.
 */
private val TARGET_ALPHA = .15 //.44 originally

/**
 * Determines how many moves are made per temperature. Higher values
 * of these parameters lead to more moves per temperature, a higher
 * quality of result, and a longer execution time. The lower quality
 * version is used at the early stages of the anneal, and the higher
 * quality version is used later on, once we're close to the target
 * alpha.
 */
//		val LOWER_QUALITY_MULTIPLIER = 0.15
private val HIGHER_QUALITY_MULTIPLIER = 0.5
