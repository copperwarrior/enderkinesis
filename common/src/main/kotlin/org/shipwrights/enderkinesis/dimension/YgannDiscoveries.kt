package org.shipwrights.enderkinesis.dimension

import dev.architectury.event.events.common.TickEvent
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.shipObjectWorld

/**
 * Awards the "Ygann Abyss" advancement category from server code, keyed off the LIVE
 * position of the two generated structures. Both get assembled into VS2 ships that drift
 * away from where they generated, so vanilla's `location`/`in_structure` trigger — which
 * matches the player against the static StructureStart bounds — can never fire at the
 * ship's new spot. Tracking a moving structure needs its live ship position.
 *
 * This is cheaper than it looks: VS2 only keeps a ship in [loadedShips] while a player is
 * near enough to load its chunks, so the loop only ever touches ships that are already
 * loaded because someone is close. With the 1 Hz throttle and the player-less-level skip,
 * the real work is "distance-check the few loaded ships near players, once a second" —
 * effectively event-driven, just polled. Each structure ship is recorded in
 * [YgannStructureShips] at assembly; the advancements use `minecraft:impossible` criteria
 * and only ever complete through [grant].
 */
object YgannDiscoveries {

    const val KIND_GEODE = "geode"
    const val KIND_ROAMING_END_SHIP = "roaming_end_ship"

    private val ROOT = EnderkinesisMod.id("ygann_abyss/root")
    private val GEODE_ADV = EnderkinesisMod.id("ygann_abyss/foreign_matter")
    private val SHIP_ADV = EnderkinesisMod.id("ygann_abyss/hitching_a_ride")

    fun init() {
        TickEvent.SERVER_POST.register { server ->
            if (server.tickCount % CHECK_INTERVAL != 0) return@register
            for (level in server.allLevels) {
                val players = level.players()
                if (players.isEmpty()) continue

                // The category tab appears once you set foot in the Abyss.
                if (level.dimension() == YgannAbyss.LEVEL_KEY) {
                    for (p in players) grant(p, ROOT)
                }

                // Discovery: proximity to a marked structure ship at its current position.
                // `loadedShips` holds the ships of all dimensions.
                val structureShips = YgannStructureShips.get(server)
                val dimId = level.dimensionId
                for (ship in level.shipObjectWorld.loadedShips) {
                    if (ship.chunkClaimDimension != dimId) continue
                    val adv = when (structureShips.kindOf(ship.id)) {
                        KIND_GEODE -> GEODE_ADV
                        KIND_ROAMING_END_SHIP -> SHIP_ADV
                        else -> continue
                    }
                    val pos = ship.transform.positionInWorld
                    for (p in players) {
                        if (p.distanceToSqr(pos.x(), pos.y(), pos.z()) <= DISCOVER_RANGE_SQ) grant(p, adv)
                    }
                }
            }
        }
    }

    /** Records a freshly assembled structure ship. Server thread only. */
    fun mark(server: MinecraftServer, shipId: Long, kind: String) {
        YgannStructureShips.get(server).mark(shipId, kind)
    }

    private fun grant(player: ServerPlayer, id: ResourceLocation) {
        val advancement = player.server.advancements.getAdvancement(id) ?: return
        val progress = player.advancements.getOrStartProgress(advancement)
        if (progress.isDone) return
        for (criterion in progress.remainingCriteria) player.advancements.award(advancement, criterion)
    }

    private const val CHECK_INTERVAL = 20
    private const val DISCOVER_RANGE = 64.0
    private const val DISCOVER_RANGE_SQ = DISCOVER_RANGE * DISCOVER_RANGE
}
