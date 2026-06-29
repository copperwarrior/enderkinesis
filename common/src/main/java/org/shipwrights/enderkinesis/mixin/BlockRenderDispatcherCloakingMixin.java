package org.shipwrights.enderkinesis.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.shipwrights.enderkinesis.client.CloakingMixinSupport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Staff of Concealment — skip rendering blocks at concealed shipyard positions.
 *
 * <p>Iris compat: Iris doesn't replace block dispatch; our cancel runs before any
 * draw call gets queued, so its deferred pipeline sees no geometry → renders nothing.
 *
 * <p>Sodium compat: Sodium *does* replace block dispatch (its own BlockRenderer
 * bypasses this method). A parallel mixin would be required for full Sodium support;
 * that's queued as a follow-up.
 */
@Mixin(BlockRenderDispatcher.class)
public abstract class BlockRenderDispatcherCloakingMixin {

    @Inject(method = "renderBatched", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$cancelIfConcealed(
        BlockState state, BlockPos pos, BlockAndTintGetter level, PoseStack poseStack,
        VertexConsumer consumer, boolean checkSides, RandomSource random, CallbackInfo ci
    ) {
        if (CloakingMixinSupport.isPositionConcealed(pos)) {
            ci.cancel();
        }
    }
}
