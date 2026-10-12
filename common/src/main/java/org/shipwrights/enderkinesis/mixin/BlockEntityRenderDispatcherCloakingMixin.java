package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.shipwrights.enderkinesis.client.CloakBufferSource;
import org.shipwrights.enderkinesis.client.CloakingMixinSupport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Staff of Concealment — redirect BlockEntities at concealed shipyard positions into
 * [CloakBufferSource] so they land in the side FB and FADE with the ship's cloak
 * progress (the post shader composites the side FB at progress-driven alpha),
 * exactly like {@link EntityRenderDispatcherCloakingMixin} does for entities.
 * Catches chest swings, sign text, beacon beams, third-party BE renderers.
 *
 * <p>Redirect, not cancel: a HEAD-cancel made every BE vanish on the first cloak
 * frame while the ship blocks were still mid-fade beside them.
 */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherCloakingMixin {

    @ModifyVariable(
        method = "render(Lnet/minecraft/world/level/block/entity/BlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V",
        at = @At("HEAD"),
        argsOnly = true
    )
    private MultiBufferSource enderkinesis$redirectIfConcealed(
        MultiBufferSource buffer, BlockEntity blockEntity
    ) {
        if (CloakingMixinSupport.isPositionConcealed(blockEntity.getBlockPos())) {
            return CloakBufferSource.get();
        }
        return buffer;
    }
}
