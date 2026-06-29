package org.shipwrights.enderkinesis.dimension

import dev.architectury.event.EventResult
import dev.architectury.event.events.common.EntityEvent
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.LivingEntity

/**
 * Sureibjin exit: in the dream coast, lethal damage of any kind doesn't
 * kill the player — it wakes them up. Drown, fall, lava, void, `/kill`,
 * any custom mod damage — all routed to [wakeUp]: full player state
 * restored from the [SureibjinEntry] snapshot, teleported back to the
 * bed they fell asleep in.
 *
 * <p>Non-lethal damage is allowed through, so the cause of the wake-up
 * (the air-meter draining, the lava sound, the fall) still reads as the
 * cause. Only the killing hit gets intercepted.
 *
 * <p>If a player ends up in Sureibjin without entry data (e.g. via
 * `/execute in`) and would die, they still wake up — heal and drop them
 * at Overworld spawn so they're not stuck in the dream.
 */
object SureibjinExit {

    fun init() {
        EntityEvent.LIVING_HURT.register(::onLivingHurt)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onLivingHurt(
        entity: LivingEntity, source: DamageSource, amount: Float,
    ): EventResult {
        if (entity !is ServerPlayer) return EventResult.pass()
        if (entity.level().dimension() != Sureibjin.LEVEL_KEY) return EventResult.pass()

        // Only intercept lethal — non-lethal damage stays so the cause of
        // the wake-up still reads as the cause.
        if (entity.health - amount > 0f) return EventResult.pass()

        wakeUp(entity)
        return EventResult.interruptFalse()
    }

    /** Restore the snapshotted player state and teleport back to the bed.
     *  Public so a debug command could force-wake a stuck player. */
    fun wakeUp(player: ServerPlayer) {
        val server = player.server
        val snapshot = SureibjinEntry.takeReturnData(server, player.uuid)

        // No entry record — heal and drop at overworld spawn so they
        // aren't stuck mid-drown in the dream.
        if (snapshot == null) {
            healMinimal(player)
            val overworld = server.overworld()
            val spawn = overworld.sharedSpawnPos
            player.teleportTo(
                overworld,
                spawn.x + 0.5, spawn.y.toDouble(), spawn.z + 0.5,
                player.yRot, player.xRot,
            )
            return
        }

        // Source dim gone (datapack swap?) — overworld fallback.
        val targetLevel = server.getLevel(snapshot.dimension)
        if (targetLevel == null) {
            healMinimal(player)
            val overworld = server.overworld()
            val spawn = overworld.sharedSpawnPos
            player.teleportTo(
                overworld,
                spawn.x + 0.5, spawn.y.toDouble(), spawn.z + 0.5,
                player.yRot, player.xRot,
            )
            return
        }

        // Heal first so the lethal hit that triggered this doesn't kill
        // them on the next tick before the snapshot restores.
        healMinimal(player)

        // Restore full player state from the entry snapshot — vanilla's
        // readAdditionalSaveData rebuilds inventory, ender chest, food,
        // XP, effects, abilities, all in one call.
        player.inventory.clearContent()
        player.readAdditionalSaveData(snapshot.playerStateTag)

        player.teleportTo(
            targetLevel,
            snapshot.x, snapshot.y, snapshot.z,
            snapshot.yRot, snapshot.xRot,
        )
    }

    /** Minimal heal to survive the next tick — the snapshot restore (if
     *  any) sets the final values right after. */
    private fun healMinimal(player: ServerPlayer) {
        player.health = player.maxHealth
        player.foodData.foodLevel = 20
        player.airSupply = player.maxAirSupply
        player.fallDistance = 0.0f
    }
}
