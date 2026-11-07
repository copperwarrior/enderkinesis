package org.shipwrights.enderkinesis.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.shipwrights.enderkinesis.ritual.Rituals;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// CandleBlock does not override onPlace, so lighting a vanilla candle reaches this method. A block that overrides it without super cannot be a light-trigger center.
@Mixin(BlockBehaviour.class)
public abstract class BlockBehaviourCandleRiteMixin {

    @Inject(method = "onPlace", at = @At("HEAD"))
    private void enderkinesis$candleRite(
        BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston, CallbackInfo ci
    ) {
        if (level.isClientSide || !state.hasProperty(BlockStateProperties.LIT)) return;
        Rituals.onLitBlockPlaced(level, pos, state, oldState);
    }
}
