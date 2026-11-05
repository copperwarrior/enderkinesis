package org.shipwrights.enderkinesis.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import org.shipwrights.enderkinesis.client.TrypoemiaRender;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ModelPart.Cube.class)
public abstract class ModelPartCubeTrypoemiaMixin {
    @Shadow @Final private ModelPart.Polygon[] polygons;

    @Inject(method = "compile", at = @At("HEAD"))
    private void enderkinesis$trypoemiaCube(
        PoseStack.Pose pose, VertexConsumer consumer, int light, int overlay, float r, float g, float b, float a,
        CallbackInfo ci
    ) {
        TrypoemiaRender.beginCube(polygons, pose.pose());
    }
}
