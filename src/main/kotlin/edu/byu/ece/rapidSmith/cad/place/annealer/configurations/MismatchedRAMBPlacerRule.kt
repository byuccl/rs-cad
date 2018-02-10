package edu.byu.ece.rapidSmith.cad.place.annealer.configurations

import edu.byu.ece.rapidSmith.cad.cluster.site.SiteClusterSite
import edu.byu.ece.rapidSmith.cad.cluster.site.SitePackUnit
import edu.byu.ece.rapidSmith.cad.place.annealer.*
import edu.byu.ece.rapidSmith.device.SiteType
import edu.byu.ece.rapidSmith.device.Tile
import edu.byu.ece.rapidSmith.device.families.Artix7.SiteTypes.*

class MismatchedRAMBValidator : PlacerRule<SiteClusterSite> {
	override fun validate(
		state: PlacerState<SiteClusterSite>,
		component: MoveComponent<SiteClusterSite>
	): Boolean {
		val type = (component.group.type as SitePackUnit).siteType
		return when (type) {
			RAMB18E1 -> canRelocate(state, component.group, component.newAnchor!!, RAMB36E1)
			RAMB36E1 -> canRelocate(state, component.group, component.newAnchor!!, RAMB18E1)
			else -> true
		}
	}

	private fun canRelocate(
		state: PlacerState<SiteClusterSite>,
		groupToPlace: PlacementGroup<SiteClusterSite>,
		newSite: SiteClusterSite, against: SiteType
	): Boolean {
		val stateHelper = StateHelper(state)
		val relocatedTiles = stateHelper.getRelocatedTiles(groupToPlace, newSite)
		return !stateHelper.anyOfTypeUsed(state.device, against, relocatedTiles)
	}
}

private class StateHelper(
	val state: PlacerState<SiteClusterSite>
) {
	fun getRelocatedSites(
		group: PlacementGroup<SiteClusterSite>,
		newSite: SiteClusterSite
	): Collection<SiteClusterSite> {
		return state.getSitesForGroup(group, newSite)!!
	}

	internal fun getRelocatedTiles(
		groupToPlace: PlacementGroup<SiteClusterSite>,
		anchor: SiteClusterSite
	): Set<Tile> {
		val origSites = getRelocatedSites(groupToPlace, anchor)
		return origSites.map { it.site.tile }.toSet()
	}

	fun getAdjacentSitesOfType(
		device: PlacerDevice<SiteClusterSite>, siteType: SiteType, tiles: Set<Tile>
	): List<SiteClusterSite> {
		return tiles.asSequence()
			.flatMap { it.sites?.asSequence() ?: emptySequence() }
			.filter { siteType in it.possibleTypes }
			.flatMap { device.getRelatedClusterSites(it).asSequence() }
			.toList()
	}

	fun anySitesOccupied(sites: List<SiteClusterSite>): Boolean {
		return sites.any { it in state.usedSites }
	}

	fun anyOfTypeUsed(
		device: PlacerDevice<SiteClusterSite>,
		type: SiteType, tiles: Set<Tile>
	): Boolean {
		val relocatedSites = getAdjacentSitesOfType(device, type, tiles)
		return anySitesOccupied(relocatedSites)
	}
}
