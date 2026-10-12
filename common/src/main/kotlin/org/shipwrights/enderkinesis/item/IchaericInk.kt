package org.shipwrights.enderkinesis.item

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.stats.Stats
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.ItemUtils
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.gameevent.GameEvent
import net.minecraft.world.level.material.FluidState
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.block.IchaericInkFlowingFluid
import org.shipwrights.enderkinesis.block.IchaericInkReserve
import org.shipwrights.enderkinesis.registry.EKFluids
import org.shipwrights.enderkinesis.registry.EKItems

object IchaericInk {

    // Vanilla BottleItem's reach.
    private const val REACH = 5.0

    /** A glass bottle aimed at an Ichaeric Ink source fills with ink, as one aimed at water fills with water. */
    @JvmStatic
    fun fillBottle(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack>? {
        val stack = player.getItemInHand(hand)
        val eye = player.eyePosition
        val end = eye.add(player.getViewVector(1f).scale(REACH))
        val pos = inkAt(level, player, eye, end, ClipContext.Fluid.SOURCE_ONLY) { it.isSource }
            // A source-only ray goes through a puddle, because a puddle is a flowing state.
            ?: inkAt(level, player, eye, end, ClipContext.Fluid.ANY, IchaericInkFlowingFluid::isPuddle)
            ?: return null
        // Ink is finite: a puddle holds one bottle, and a source empties after its last bottle.
        val emptied = level is ServerLevel &&
            (!level.getFluidState(pos).isSource || IchaericInkReserve.of(level).takeBottle(pos))
        if (emptied) level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL)
        level.playSound(player, player.x, player.y, player.z, SoundEvents.BOTTLE_FILL, SoundSource.NEUTRAL, 1f, 1f)
        level.gameEvent(player, GameEvent.FLUID_PICKUP, pos)
        player.awardStat(Stats.ITEM_USED.get(stack.item))
        val filled = ItemUtils.createFilledResult(stack, player, ItemStack(EKItems.ICHAERIC_INK_BOTTLE.get()))
        return InteractionResultHolder.sidedSuccess(filled, level.isClientSide)
    }

    private inline fun inkAt(
        level: Level, player: Player, eye: Vec3, end: Vec3, mode: ClipContext.Fluid, accept: (FluidState) -> Boolean,
    ): BlockPos? {
        val hit = level.clip(ClipContext(eye, end, ClipContext.Block.OUTLINE, mode, player))
        if (hit.type != HitResult.Type.BLOCK || !level.mayInteract(player, hit.blockPos)) return null
        val fluid = level.getFluidState(hit.blockPos)
        return hit.blockPos.takeIf { fluid.type.isSame(EKFluids.ICHAERIC_INK.get()) && accept(fluid) }
    }
}
