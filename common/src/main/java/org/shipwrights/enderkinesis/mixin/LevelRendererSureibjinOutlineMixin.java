package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.shipwrights.enderkinesis.dimension.Sureibjin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererSureibjinOutlineMixin {

    @Shadow @Nullable private ClientLevel level;

    // The call site, not the HEAD of renderHitOutline: VS2 (feature.hit_outline.MixinLevelRenderer) cancels at
    // that HEAD and draws the outline itself, so a HEAD cancel here does not always run.
    @WrapWithCondition(
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;renderHitOutline(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/world/entity/Entity;DDDLnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V"
        )
    )
    private boolean enderkinesis$hideSureibjinOutline(
        LevelRenderer self, PoseStack poseStack, VertexConsumer consumer, Entity entity,
        double camX, double camY, double camZ, BlockPos pos, BlockState state
    ) {
        ClientLevel lvl = this.level;
        return lvl == null || !lvl.dimension().equals(Sureibjin.INSTANCE.getLEVEL_KEY());
    }
}
