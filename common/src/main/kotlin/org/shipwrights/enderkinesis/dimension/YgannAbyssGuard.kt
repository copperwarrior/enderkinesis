package org.shipwrights.enderkinesis.dimension

import dev.architectury.event.EventResult
import dev.architectury.event.events.common.BlockEvent
import dev.architectury.event.events.common.InteractionEvent
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BucketItem
import org.valkyrienskies.mod.common.getLoadedShipManagingPos

/**
 * Placement gate for [YgannAbyss]: in that dimension a block may only land **inside a VS2
 * shipyard** (i.e., on a real ship). Anywhere else in the dimension, the placement is rejected
 * with a burst of ender (portal) particles and an enderman-teleport sound — visually echoing
 * the dimension's lore that only what the void permits is allowed to remain.
 *
 * Why this check works for ship placement: VS2 routes a player click against a ship's visible
 * world hull to the corresponding shipyard `BlockPos` before block placement runs. By the time
 * [BlockEvent.PLACE] fires, [pos] is already in the ship's shipyard frame if the placement
 * targeted a ship — and `getLoadedShipManagingPos(pos)` returns that ship. Free placements in
 * the open void resolve to ordinary world coords, where no ship's shipyard sits.
 */
object YgannAbyssGuard {

    fun init() {
        BlockEvent.PLACE.register(::onPlace)
        // Fluids from buckets bypass [BlockEvent.PLACE] on Forge (vanilla doesn't fire
        // EntityPlaceEvent for liquid placement) — gate the right-click-bucket flow
        // separately so the Abyss rejects water/lava/powder-snow at intent time. The
        // sweeper still catches anything that slips through (e.g. flowing residue).
        InteractionEvent.RIGHT_CLICK_BLOCK.register(::onRightClickBlock)
    }

    private fun onPlace(
        level: net.minecraft.world.level.Level,
        pos: net.minecraft.core.BlockPos,
        state: net.minecraft.world.level.block.state.BlockState,
        placer: net.minecraft.world.entity.Entity?,
    ): EventResult {
        if (level.isClientSide) return EventResult.pass()
        val server = level as? ServerLevel ?: return EventResult.pass()
        if (server.dimension() != YgannAbyss.LEVEL_KEY) return EventResult.pass()
        if (server.getLoadedShipManagingPos(pos) != null) return EventResult.pass()
        rejectAt(server, pos)
        return EventResult.interruptFalse()
    }

    /** Right-click guard that catches the *bucket → liquid* flow. Vanilla's [BucketItem]
     *  calls `Level.setBlock` directly when emptying, never going through the entity-place
     *  pipeline Architectury's [BlockEvent.PLACE] is wired to on Forge — so without this
     *  hook a player can pour water/lava in the Abyss and the placement guard misses it.
     *  The target voxel is the face neighbour of the clicked block (where vanilla puts the
     *  liquid). If that neighbour isn't shipyard-managed, reject. */
    private fun onRightClickBlock(
        player: Player, hand: InteractionHand, pos: BlockPos, face: net.minecraft.core.Direction,
    ): EventResult {
        if (player.level().isClientSide) return EventResult.pass()
        val server = player.level() as? ServerLevel ?: return EventResult.pass()
        if (server.dimension() != YgannAbyss.LEVEL_KEY) return EventResult.pass()
        val item = player.getItemInHand(hand).item
        if (item !is BucketItem) return EventResult.pass()

        // Vanilla places the liquid at the face-neighbour of the clicked block (or at the
        // clicked block itself if it's replaceable). Check both to be safe.
        val replaceable = server.getBlockState(pos).canBeReplaced()
        val target = if (replaceable) pos else pos.relative(face)
        if (server.getLoadedShipManagingPos(target) != null) return EventResult.pass()

        rejectAt(server, target)
        return EventResult.interruptFalse()
    }

    private fun rejectAt(level: ServerLevel, pos: BlockPos) {
        // Reject the placement and stage a small ender-particle burst at the would-be voxel.
        // sendParticles broadcasts to nearby players; the count/spread is tuned to read as a
        // "this block dissolved" puff rather than a permanent effect.
        level.sendParticles(
            ParticleTypes.PORTAL,
            pos.x + 0.5, pos.y + 0.5, pos.z + 0.5,
            24, 0.4, 0.4, 0.4, 0.6,
        )
        level.playSound(
            null, pos,
            SoundEvents.ENDERMAN_TELEPORT, SoundSource.BLOCKS,
            0.5f, 1.4f,
        )
    }
}
