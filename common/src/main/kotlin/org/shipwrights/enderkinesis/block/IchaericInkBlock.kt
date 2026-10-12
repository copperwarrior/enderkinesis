package org.shipwrights.enderkinesis.block

import dev.architectury.core.block.ArchitecturyLiquidBlock
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.FlowingFluid
import net.minecraft.world.level.material.FluidState
import org.shipwrights.enderkinesis.registry.EKFluids
import java.util.function.Supplier

/** Ichaeric Ink blinds anything whose eyes are under it. Swimming is in [IchaericInkPhysics]. */
class IchaericInkBlock(fluid: Supplier<out FlowingFluid>, properties: Properties) :
    ArchitecturyLiquidBlock(fluid, properties) {

    @Deprecated("Deprecated in Java")
    override fun entityInside(state: BlockState, level: Level, pos: BlockPos, entity: Entity) {
        if (level.isClientSide || entity !is LivingEntity) return
        val fluid = level.getFluidState(pos)
        val eyes = BlockPos.containing(entity.eyePosition)
        val eyeFluid = level.getFluidState(eyes)
        if (eyeFluid.type.isSame(fluid.type) && entity.eyeY < eyes.y + eyeFluid.getHeight(level, eyes)) {
            entity.addEffect(MobEffectInstance(MobEffects.BLINDNESS, BLINDNESS_TICKS, 0, false, false, true))
        }
    }

    @Deprecated("Deprecated in Java")
    override fun getFluidState(state: BlockState): FluidState {
        val flowing = EKFluids.ICHAERIC_INK_FLOWING.get()
        return if (state.getValue(LEVEL) == IchaericInkFlowingFluid.PUDDLE_BLOCK_LEVEL && flowing is IchaericInkFlowingFluid) {
            IchaericInkFlowingFluid.puddleState(flowing)
        } else {
            super.getFluidState(state)
        }
    }

    /** A partly drained source cannot be bucketed: that would turn the bottles left back into a full bucket. */
    override fun pickupBlock(level: LevelAccessor, pos: BlockPos, state: BlockState): ItemStack {
        if (level is ServerLevel && IchaericInkReserve.of(level).isPartlyDrained(pos)) return ItemStack.EMPTY
        return super.pickupBlock(level, pos, state)
    }

    @Deprecated("Deprecated in Java")
    override fun onRemove(state: BlockState, level: Level, pos: BlockPos, newState: BlockState, moved: Boolean) {
        if (level is ServerLevel && !newState.`is`(this)) IchaericInkReserve.of(level).forget(pos)
        super.onRemove(state, level, pos, newState, moved)
    }

    companion object {
        private const val BLINDNESS_TICKS = 40
    }
}
