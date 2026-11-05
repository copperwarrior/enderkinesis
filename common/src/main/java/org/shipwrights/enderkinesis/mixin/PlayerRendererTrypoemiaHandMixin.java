package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import org.shipwrights.enderkinesis.client.TrypoemiaRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// The first-person arm and sleeve are drawn here, outside LivingEntityRenderer.render.
@Mixin(PlayerRenderer.class)
public abstract class PlayerRendererTrypoemiaHandMixin {

    @WrapOperation(
        method = "renderHand",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/geom/ModelPart;render(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;II)V")
    )
    private void enderkinesis$trypoemiaHand(
        ModelPart part, PoseStack poseStack, VertexConsumer consumer, int light, int overlay,
        Operation<Void> original,
        @Local(argsOnly = true) MultiBufferSource buffers,
        @Local(argsOnly = true) AbstractClientPlayer player
    ) {
        VertexConsumer arm = TrypoemiaRender.begin(player, consumer, buffers, player.getSkinTextureLocation());
        try {
            original.call(part, poseStack, arm, light, overlay);
        } finally {
            if (arm != consumer) TrypoemiaRender.end();
        }
    }
}
