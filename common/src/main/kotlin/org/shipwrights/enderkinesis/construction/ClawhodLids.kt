@file:JvmName("ClawhodLids")

package org.shipwrights.enderkinesis.construction

import com.mojang.logging.LogUtils
import dev.architectury.event.events.common.LifecycleEvent
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.entity.BarrelBlockEntity
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity
import net.minecraft.world.level.block.state.properties.ChestType
import java.util.IdentityHashMap

// Server thread only. The levels where a hand holds a container open: a chest recheck elsewhere returns at once,
// with no allocation and no build-job data created for a level that has none.
private val openLevels = IdentityHashMap<Level, ContainerLids>()
private var lifecycleHooked = false
private val LID_LOG = LogUtils.getLogger()

// The JVM outlives a single-player world: a closed level's entry must not keep it alive.
private fun hookLifecycle() {
    if (lifecycleHooked) return
    lifecycleHooked = true
    LifecycleEvent.SERVER_LEVEL_UNLOAD.register { level -> openLevels.remove(level) }
    LifecycleEvent.SERVER_STOPPED.register { openLevels.clear() }
}

/** Clawhod hands at the container at [pos]: ContainerOpenersCounterClawhodMixin adds them to vanilla's opener count. */
fun clawhodHandsAt(level: Level, pos: BlockPos): Int {
    if (openLevels.isEmpty()) return 0
    val lids = openLevels[level] ?: return 0
    return lids.handsAt(pos)
}

/**
 * Clawhod hands at chests, barrels and shulker boxes, per container half. Chests and barrels count them with their
 * players in vanilla's own recheck (the mixin), so hands and players keep the lid open for each other and a lid
 * left open in an unloaded chunk shuts at its saved recheck tick, as for a player. Shulker boxes have no recheck:
 * their own opener count takes the hands through startOpen and stopOpen.
 */
internal class ContainerLids {
    private val counts = Long2IntOpenHashMap()

    /** Hands holding a container open in this level, for [openLevels]. */
    private var hands = 0

    fun handsAt(pos: BlockPos): Int = counts.get(pos.asLong())

    /** True when the container at [pos] has an open look, so a hand waits for the lid before it takes. */
    fun animates(level: ServerLevel, pos: BlockPos): Boolean = when (level.getBlockEntity(pos)) {
        is ChestBlockEntity, is BarrelBlockEntity, is ShulkerBoxBlockEntity -> true
        else -> false
    }

    fun open(level: ServerLevel, pos: BlockPos, actor: ServerPlayer) {
        val other = otherHalf(level, pos)
        if (hands++ == 0) {
            hookLifecycle()
            openLevels[level] = this
        }
        val first = counts.addTo(pos.asLong(), 1) == 0
        other?.let { counts.addTo(it.asLong(), 1) }
        update(level, pos, other, actor, if (first) SHULKER_OPEN else SHULKER_KEEP)
    }

    fun close(level: ServerLevel, pos: BlockPos, actor: ServerPlayer) {
        if (counts.get(pos.asLong()) <= 0) return
        if (--hands == 0) openLevels.remove(level)
        val other = otherHalf(level, pos)
        val last = drop(pos)
        other?.let(::drop)
        update(level, pos, other, actor, if (last) SHULKER_CLOSE else SHULKER_KEEP)
    }

    private fun drop(pos: BlockPos): Boolean {
        val key = pos.asLong()
        val n = counts.get(key)
        if (n <= 1) counts.remove(key) else counts.put(key, n - 1)
        return n <= 1
    }

    private fun otherHalf(level: ServerLevel, pos: BlockPos): BlockPos? {
        if (!level.isLoaded(pos)) return null
        val state = level.getBlockState(pos)
        if (state.block !is ChestBlock || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) return null
        return pos.relative(ChestBlock.getConnectedDirection(state))
    }

    // An unloaded container is left to its saved recheck tick, which finds no hands and shuts it.
    private fun update(level: ServerLevel, pos: BlockPos, other: BlockPos?, actor: ServerPlayer, shulker: Int) {
        if (!level.isLoaded(pos)) return
        when (val be = level.getBlockEntity(pos)) {
            is ChestBlockEntity -> {
                be.recheckOpen()
                if (other != null && level.isLoaded(other)) (level.getBlockEntity(other) as? ChestBlockEntity)?.recheckOpen()
                // Debug for the lid playtest: vanilla's count after the recheck must include the hands.
                LID_LOG.info("[ClawhodLid] chest {} hands={} vanillaCount={}", pos.toShortString(), handsAt(pos), ChestBlockEntity.getOpenCount(level, pos))
            }
            is BarrelBlockEntity -> be.recheckOpen()
            is ShulkerBoxBlockEntity -> when (shulker) {
                SHULKER_OPEN -> be.startOpen(actor)
                SHULKER_CLOSE -> be.stopOpen(actor)
            }
        }
    }

    private companion object {
        const val SHULKER_KEEP = 0
        const val SHULKER_OPEN = 1
        const val SHULKER_CLOSE = 2
    }
}
