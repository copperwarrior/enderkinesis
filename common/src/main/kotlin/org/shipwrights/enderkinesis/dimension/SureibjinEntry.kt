package org.shipwrights.enderkinesis.dimension

import java.util.UUID
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import org.shipwrights.enderkinesis.EnderkinesisMod

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
 * <p>The snapshot is on disk in [SureibjinReturnSavedData], so a dream survives a server restart.
 */
object SureibjinEntry {

    /** World X of the arrival column. Just east of the coast meridian
     *  (which is at world X = 0 with ±29 wobble), so players land on the
     *  beach band facing the ocean. */
    private const val ARRIVAL_X: Int = 5
    /** Columns east of [ARRIVAL_X] that [findArrival] tries when a tendril or rock fills a column. */
    private const val ARRIVAL_SEARCH_COLUMNS: Int = 8
    /** Used only when no searched column has an open spot. */
    private const val ARRIVAL_FALLBACK_Y: Int = 80
    /** Half-range of the random N/S spawn offset; players spawn at
     *  `Z ∈ [−Z_OFFSET_MAX, +Z_OFFSET_MAX]` so different dream entries land
     *  far apart along the coast. */
    private const val Z_OFFSET_MAX: Int = 4000
    /** Yaw facing world-east (+X) so the player wakes up looking at the
     *  ocean. Minecraft yaw conventions: 0 = south, −90 = east. */
    private const val ARRIVAL_YAW: Float = -90.0f

    /** One Minecraft day. Measured in overworld game time, which `/time set` and the night skip do not change. */
    private const val REENTRY_COOLDOWN_TICKS: Long = 24_000L

    private val ADV_ROOT: ResourceLocation = EnderkinesisMod.id("sureibjin/root")
    private val ADV_DISTANT_DREAMS: ResourceLocation = EnderkinesisMod.id("sureibjin/distant_dreams")
    internal val ADV_KNOWLEDGE_OF_DEATH: ResourceLocation = EnderkinesisMod.id("sureibjin/knowledge_of_death")

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

    /** Ticks until [player] can dream again. Zero when no cooldown applies. */
    @JvmStatic
    fun reentryCooldownRemaining(player: ServerPlayer): Long {
        val server = player.server
        val lastExit = SureibjinReturnSavedData.get(server).lastExit(player.uuid) ?: return 0L
        return (lastExit + REENTRY_COOLDOWN_TICKS - server.overworld().gameTime).coerceAtLeast(0L)
    }

    internal fun markExit(player: ServerPlayer) {
        val server = player.server
        SureibjinReturnSavedData.get(server).markExit(player.uuid, server.overworld().gameTime)
    }

    /** Called from `ServerLevelSureibjinWakeMixin` after vanilla's sleep
     *  cycle completes. Snapshot full player state, reset to dream
     *  defaults, teleport. */
    @JvmStatic
    fun enter(player: ServerPlayer, bedPos: BlockPos) {
        val server = player.server
        val sureibjinLevel = server.getLevel(Sureibjin.LEVEL_KEY) ?: return

        // A snapshot from a dream that ended with no restore. The new snapshot replaces it.
        SureibjinReturnSavedData.get(server).take(player.uuid)?.let { returnStaleItems(player, it) }

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

        // 4. Random N/S offset along the infinite coast.
        val arrival = findArrival(sureibjinLevel, player.random.nextInt(Z_OFFSET_MAX * 2) - Z_OFFSET_MAX)

        player.teleportTo(
            sureibjinLevel,
            arrival.x + 0.5, arrival.y.toDouble(), arrival.z + 0.5,
            ARRIVAL_YAW, 0.0f,
        )

        grantAdvancement(player, ADV_ROOT)
        grantAdvancement(player, ADV_DISTANT_DREAMS)
    }

    /** The highest open spot below the barrier, at the arrival column or east of it. The block reads
     *  generate the arrival chunk on the server thread, one time for each dream. */
    private fun findArrival(level: ServerLevel, z: Int): BlockPos {
        val pos = BlockPos.MutableBlockPos()
        val top = SureibjinChunkGenerator.BARRIER_START_Y - 2
        for (x in ARRIVAL_X until ARRIVAL_X + ARRIVAL_SEARCH_COLUMNS) {
            for (y in top downTo level.seaLevel + 1) {
                if (isOpen(level, pos.set(x, y, z)) && isOpen(level, pos.set(x, y + 1, z)) &&
                    !isOpen(level, pos.set(x, y - 1, z))
                ) return BlockPos(x, y, z)
            }
        }
        return BlockPos(ARRIVAL_X, ARRIVAL_FALLBACK_Y, z)
    }

    private fun isOpen(level: ServerLevel, pos: BlockPos): Boolean {
        val state = level.getBlockState(pos)
        return state.getCollisionShape(level, pos).isEmpty && state.fluidState.isEmpty
    }

    private fun returnStaleItems(player: ServerPlayer, stale: ReturnSnapshot) {
        val items = Inventory(player)
        items.load(stale.playerStateTag.getList("Inventory", Tag.TAG_COMPOUND.toInt()))
        for (slot in 0 until items.containerSize) {
            val stack = items.getItem(slot)
            if (!stack.isEmpty) player.inventory.placeItemBackInInventory(stack)
        }
    }

    /** Marks every remaining criterion of [id] complete on [player].
     *  No-op if the advancement doesn't exist or is already finished. */
    internal fun grantAdvancement(player: ServerPlayer, id: ResourceLocation) {
        val advancement = player.server.advancements.getAdvancement(id) ?: return
        val progress = player.advancements.getOrStartProgress(advancement)
        if (progress.isDone) return
        for (criterion in progress.remainingCriteria) {
            player.advancements.award(advancement, criterion)
        }
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
