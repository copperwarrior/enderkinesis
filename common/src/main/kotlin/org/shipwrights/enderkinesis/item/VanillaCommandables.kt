package org.shipwrights.enderkinesis.item

import dev.architectury.event.events.common.TickEvent
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.item.PrimedTnt
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BellBlock
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.level.block.DispenserBlock
import net.minecraft.world.level.block.DoorBlock
import net.minecraft.world.level.block.FenceGateBlock
import net.minecraft.world.level.block.LeverBlock
import net.minecraft.world.level.block.NoteBlock
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.TntBlock
import net.minecraft.world.level.block.TrapDoorBlock
import net.minecraft.world.level.block.piston.PistonBaseBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.gameevent.GameEvent

/** Staff-of-Command handlers for vanilla blocks with no [org.shipwrights.enderkinesis.blockentity.Commandable]
 *  block entity. One `when`-dispatch per gesture; [supports] gates what the
 *  staff will add to its list.
 *
 *  Behaviours (trigger = right click, aim = left click):
 *   - lever → toggle; button → press; bell → ring; door / trapdoor / fence
 *     gate → toggle open; dispenser + dropper → fire once; TNT → prime.
 *   - piston → extend, then retract 1 s later (via [retractQueue]).
 *   - note block → aim cycles the note (and previews it); trigger plays it.
 */
object VanillaCommandables {

    /** Ticks a commanded piston's virtual signal stays on before the pulse
     *  expires and vanilla retracts it. Head extension animation eats ~3
     *  ticks, so 40 gives a clearly readable ~1.8 s dwell at full extension. */
    private const val PISTON_RETRACT_DELAY_TICKS: Long = 40L

    private data class PistonPulse(val dim: ResourceKey<Level>, val pos: BlockPos, val expiresAt: Long)

    /** Active piston pulses. While a piston is in here,
     *  `PistonBaseBlockCommandSignalMixin` reports a redstone signal for it,
     *  so every vanilla signal re-check (extend event guard, checkIfExtend
     *  reconciliation) behaves as if a lever were on. Server thread only. */
    private val pistonPulses = ArrayList<PistonPulse>()

    /** Read by the piston signal mixin — true while `pos` has a live pulse. */
    @JvmStatic
    fun isPistonPulsed(level: net.minecraft.world.level.SignalGetter, pos: BlockPos): Boolean {
        // The client thread of an integrated server calls this for client pistons. It must
        // not read the list.
        if (level !is net.minecraft.server.level.ServerLevel || pistonPulses.isEmpty()) return false
        val dim = level.dimension()
        return pistonPulses.any { it.dim == dim && it.pos == pos }
    }

    fun init() {
        TickEvent.SERVER_LEVEL_POST.register { level ->
            if (pistonPulses.isEmpty()) return@register
            val now = level.gameTime
            val it = pistonPulses.iterator()
            while (it.hasNext()) {
                val pulse = it.next()
                if (pulse.dim != level.dimension()) continue
                if (now < pulse.expiresAt) continue
                it.remove()
                // Signal just dropped — poke the piston so vanilla's own
                // checkIfExtend runs, notices "extended, no signal", and
                // retracts through its normal block-event path.
                val state = level.getBlockState(pulse.pos)
                if (state.block is PistonBaseBlock) {
                    @Suppress("DEPRECATION")
                    state.neighborChanged(level, pulse.pos, state.block, pulse.pos, false)
                }
            }
        }
    }

    fun supports(state: BlockState): Boolean = when (state.block) {
        is LeverBlock, is ButtonBlock, is PistonBaseBlock, is NoteBlock, is BellBlock,
        is DispenserBlock, is DoorBlock, is TrapDoorBlock, is FenceGateBlock, is TntBlock,
        -> true
        else -> false
    }

    /** Right-click "Go To / Trigger". Returns true when the block responded.
     *  [player] is null for autonomous (Shulkonaut) dispatch — vanilla accepts a
     *  null igniter/game-event source everywhere this uses it. */
    fun trigger(player: Player?, level: ServerLevel, pos: BlockPos, state: BlockState): Boolean {
        return when (val block = state.block) {
            is LeverBlock -> {
                block.pull(state, level, pos)
                level.playSound(
                    null, pos, SoundEvents.LEVER_CLICK, SoundSource.BLOCKS,
                    0.3f, if (level.getBlockState(pos).getValue(BlockStateProperties.POWERED)) 0.6f else 0.5f,
                )
                true
            }
            is ButtonBlock -> {
                if (state.getValue(BlockStateProperties.POWERED)) return false
                block.press(state, level, pos)
                true
            }
            is PistonBaseBlock -> triggerPiston(level, pos, state, block)
            is NoteBlock -> {
                level.blockEvent(pos, block, 0, 0)
                level.gameEvent(player, GameEvent.INSTRUMENT_PLAY, pos)
                true
            }
            is BellBlock -> block.attemptToRing(level, pos, null)
            // Dropper is a DispenserBlock subclass; both fire through the
            // scheduled tick, exactly like a redstone rising edge.
            is DispenserBlock -> {
                level.scheduleTick(pos, block, 4)
                true
            }
            is DoorBlock -> {
                block.setOpen(null, level, state, pos, !block.isOpen(state))
                true
            }
            is TrapDoorBlock -> {
                val open = !state.getValue(BlockStateProperties.OPEN)
                level.setBlock(pos, state.setValue(BlockStateProperties.OPEN, open), 3)
                // Vanilla's per-BlockSetType open/close sounds live on a private
                // field; the metal/wood level events cover the audible split.
                val metal = state.soundType == SoundType.METAL
                val event = if (metal) (if (open) 1037 else 1036) else (if (open) 1007 else 1013)
                level.levelEvent(null, event, pos, 0)
                true
            }
            is FenceGateBlock -> {
                val open = !state.getValue(BlockStateProperties.OPEN)
                level.setBlock(pos, state.setValue(BlockStateProperties.OPEN, open), 10)
                level.levelEvent(null, if (open) 1008 else 1014, pos, 0)
                true
            }
            is TntBlock -> {
                val tnt = PrimedTnt(level, pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5, player)
                level.addFreshEntity(tnt)
                level.playSound(null, tnt.x, tnt.y, tnt.z, SoundEvents.TNT_PRIMED, SoundSource.BLOCKS, 1f, 1f)
                level.gameEvent(player, GameEvent.PRIME_FUSE, pos)
                level.removeBlock(pos, false)
                true
            }
            else -> false
        }
    }

    /** Left-click "Aim To". Only the note block responds (cycle + preview);
     *  every other vanilla block ignores the aim gesture. */
    fun aim(player: Player, level: ServerLevel, pos: BlockPos, state: BlockState): Boolean {
        val block = state.block
        if (block !is NoteBlock) return false
        val cycled = state.cycle(NoteBlock.NOTE)
        level.setBlock(pos, cycled, 3)
        level.blockEvent(pos, block, 0, 0)
        level.gameEvent(player, GameEvent.BLOCK_CHANGE, pos)
        return true
    }

    /** Register the virtual-signal pulse, then poke the piston so vanilla's
     *  checkIfExtend sees the (mixin-supplied) signal and extends through its
     *  ordinary path — block event, moveBlocks, sound, the lot. When the
     *  pulse expires the tick loop pokes it again and vanilla retracts. */
    private fun triggerPiston(
        level: ServerLevel, pos: BlockPos, state: BlockState, block: PistonBaseBlock,
    ): Boolean {
        if (state.getValue(BlockStateProperties.EXTENDED)) return false
        if (isPistonPulsed(level, pos)) return false
        pistonPulses.add(PistonPulse(level.dimension(), pos.immutable(), level.gameTime + PISTON_RETRACT_DELAY_TICKS))
        @Suppress("DEPRECATION")
        state.neighborChanged(level, pos, block, pos, false)
        return true
    }
}
