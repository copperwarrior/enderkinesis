package org.shipwrights.enderkinesis.dimension

import java.util.UUID
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.properties.BlockStateProperties

/**
 * Sureibjin entry: a player who successfully starts to sleep in any of the
 * other three ulder dimensions has their **full player state** snapshotted
 * via vanilla's `addAdditionalSaveData(CompoundTag)`, gets reset to a clean
 * dream baseline (empty inventory, full health/food/air, zero XP, no
 * effects, no fall distance), and is teleported to Sureibjin at a
 * hash-random N/S offset along the infinite coast.
 *
 * <p>On exit ([SureibjinExit.wakeUp]) the same NBT tag is fed back to
 * `readAdditionalSaveData(CompoundTag)`, which restores everything vanilla
 * normally persists across a save / restart cycle — inventory, ender
 * chest, food, XP, active effects, abilities, etc. That's how the player
 * "returns to themselves" without us having to enumerate each field.
 *
 * <p>State is held in memory only (same pattern as [SselithChatTeleport]).
 * A server restart drops the dream; the player wakes up in their normal
 * inventory back in the bed dim — see [SureibjinExit] for the fallback.
 */
object SureibjinEntry {

    /** World X of the arrival point. Just east of the coast meridian
     *  (which is at world X = 0 with ±29 wobble), so players land on the
     *  beach band facing the ocean. */
    private const val ARRIVAL_X: Double = 5.0
    /** Conservative high arrival Y — well above the sand surface. The
     *  player falls a few blocks onto the beach. */
    private const val ARRIVAL_Y: Double = 80.0
    /** Half-range of the random N/S spawn offset; players spawn at
     *  `Z ∈ [−Z_OFFSET_MAX, +Z_OFFSET_MAX]` so different dream entries land
     *  far apart along the coast. */
    private const val Z_OFFSET_MAX: Int = 4000
    /** Yaw facing world-east (+X) so the player wakes up looking at the
     *  ocean. Minecraft yaw conventions: 0 = south, −90 = east. */
    private const val ARRIVAL_YAW: Float = -90.0f

    /** Snapshot of everything we need to put the player back the way they
     *  were before the dream. `playerStateTag` is the result of
     *  `addAdditionalSaveData`; restoring it via `readAdditionalSaveData`
     *  rebuilds inventory, food, XP, effects, etc. in one call.
     *
     *  Persisted to disk via [SureibjinReturnSavedData] so a server
     *  restart while a player is mid-dream doesn't strand them in
     *  Sureibjin with no original-state restore on wake-up. */
    internal data class ReturnSnapshot(
        val dimension: ResourceKey<Level>,
        val x: Double, val y: Double, val z: Double,
        val yRot: Float, val xRot: Float,
        val playerStateTag: CompoundTag,
    )

    /** True when [dim] is one of the three ulder source dimensions —
     *  sleeping in any of them triggers Sureibjin entry. */
    @JvmStatic
    fun isUlderEntrySource(dim: ResourceKey<Level>): Boolean =
        dim == SselithRepertory.LEVEL_KEY ||
        dim == Wohlonnogondonia.LEVEL_KEY ||
        dim == YgannAbyss.LEVEL_KEY

    /** Called from `ServerLevelSureibjinWakeMixin` after vanilla's sleep
     *  cycle completes. Snapshot full player state, reset to dream
     *  defaults, teleport. */
    @JvmStatic
    fun enter(player: ServerPlayer, bedPos: BlockPos) {
        val server = player.server
        val sureibjinLevel = server.getLevel(Sureibjin.LEVEL_KEY) ?: return

        // 1. Snapshot the full player NBT (inventory, ender chest, food,
        //    XP, abilities, active effects — everything `addAdditionalSaveData`
        //    writes when the world saves). One tag, everything captured.
        val stateTag = CompoundTag()
        player.addAdditionalSaveData(stateTag)

        SureibjinReturnSavedData.get(server).put(
            player.uuid,
            ReturnSnapshot(
                dimension = player.level().dimension(),
                x = bedPos.x + 0.5, y = bedPos.y + 0.5, z = bedPos.z + 0.5,
                yRot = player.yRot, xRot = player.xRot,
                playerStateTag = stateTag,
            ),
        )

        // 2. Reset to a clean dream-baseline state. Empty inventory, full
        //    health/food/air, no XP, no potion effects, no carried fall
        //    distance. The exit hook will rebuild the original state from
        //    the snapshot above.
        resetToCleanState(player)

        // 3. Free the bed (BedBlock.use sets OCCUPIED before the sleep
        //    completes; vanilla wake handles it, but a defensive clear
        //    keeps the bed sane if the wake path changes).
        val sourceLevel = player.serverLevel()
        val bedState = sourceLevel.getBlockState(bedPos)
        if (bedState.hasProperty(BlockStateProperties.OCCUPIED)) {
            sourceLevel.setBlock(
                bedPos,
                bedState.setValue(BlockStateProperties.OCCUPIED, false),
                3,
            )
        }

        // 4. Hash-random N/S offset along the infinite coast.
        val arrivalZ = (player.random.nextInt(Z_OFFSET_MAX * 2) - Z_OFFSET_MAX).toDouble()

        player.teleportTo(
            sureibjinLevel,
            ARRIVAL_X, ARRIVAL_Y, arrivalZ,
            ARRIVAL_YAW, 0.0f,
        )
    }

    /** Resets a player to the dream's clean baseline. Idempotent — safe to
     *  call multiple times. */
    private fun resetToCleanState(player: ServerPlayer) {
        player.inventory.clearContent()
        player.health = player.maxHealth
        player.foodData.foodLevel = 20
        player.foodData.setSaturation(5.0f)
        player.foodData.setExhaustion(0.0f)
        player.airSupply = player.maxAirSupply
        player.experienceLevel = 0
        player.experienceProgress = 0.0f
        player.totalExperience = 0
        player.removeAllEffects()
        player.fallDistance = 0.0f
    }

    /** Pulled by [SureibjinExit] on the way out. Removes the stash and
     *  returns it for restoration — single-use per entry. Reads from
     *  [SureibjinReturnSavedData] so the snapshot survives a server
     *  restart that happens while the player is in Sureibjin. */
    internal fun takeReturnData(server: MinecraftServer, playerId: UUID): ReturnSnapshot? =
        SureibjinReturnSavedData.get(server).take(playerId)
}
