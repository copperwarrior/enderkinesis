package org.shipwrights.enderkinesis.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.shipwrights.enderkinesis.client.CloakingMixinSupport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Staff of Concealment — skip rendering BlockEntities at concealed shipyard positions.
 * Catches chest swings, sign text, beacon beams, custom BE renderers from third-party
 * mods, etc. Works under Sodium and Iris (neither replaces the BE dispatcher).
 */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherCloakingMixin {

    @Inject(
        method = "render(Lnet/minecraft/world/level/block/entity/BlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void enderkinesis$cancelIfConcealed(
        BlockEntity blockEntity, float partialTick, PoseStack poseStack,
        MultiBufferSource buffer, CallbackInfo ci
    ) {
        if (CloakingMixinSupport.isPositionConcealed(blockEntity.getBlockPos())) {
            ci.cancel();
        }
    }
}
