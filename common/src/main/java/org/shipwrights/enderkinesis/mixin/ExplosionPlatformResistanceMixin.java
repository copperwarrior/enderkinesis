package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.shipwrights.enderkinesis.blockentity.ConstriliarPlatformBlockEntity;
import org.shipwrights.enderkinesis.registry.EKBlocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Explosion resistance inheritance for the constriliar platform: vanilla's resistance
 * hook is per-Block (no position), so the animated source block's resistance is
 * substituted at the explosion calculator call site instead.
 */
@Mixin(Explosion.class)
public abstract class ExplosionPlatformResistanceMixin {

    @WrapOperation(
        method = "explode",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/ExplosionDamageCalculator;getBlockExplosionResistance(Lnet/minecraft/world/level/Explosion;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;)Ljava/util/Optional;"
        )
    )
    private Optional<Float> enderkinesis$inheritPlatformResistance(
        ExplosionDamageCalculator calculator,
        Explosion explosion,
        BlockGetter level,
        BlockPos pos,
        BlockState state,
        FluidState fluid,
        Operation<Optional<Float>> original
    ) {
        Optional<Float> result = original.call(calculator, explosion, level, pos, state, fluid);
        if (state.is(EKBlocks.INSTANCE.getCONSTRILIAR_PLATFORM().get())) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof ConstriliarPlatformBlockEntity plate && plate.getSourceState() != null) {
                float source = plate.getSourceState().getBlock().getExplosionResistance();
                return Optional.of(Math.max(result.orElse(0f), source));
            }
        }
        return result;
    }
}
