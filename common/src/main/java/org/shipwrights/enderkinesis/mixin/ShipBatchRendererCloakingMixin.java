package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import java.util.ArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import org.joml.Matrix4f;
import org.shipwrights.enderkinesis.client.ClientShipCloakingState;
import org.shipwrights.enderkinesis.client.CloakingMixinSupport;
import org.shipwrights.enderkinesis.client.ConcealmentShipFB;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.mod.common.render.batched.ShipBatchRenderer;
import org.valkyrienskies.mod.common.render.batched.ShipMaterialUniforms;
import org.valkyrienskies.mod.common.render.batched.ShipRenderObject;

/** Sends cloaking ships' draws from the batched renderer to {@link ConcealmentShipFB}, so the post-shader can refract them. */
@Mixin(value = ShipBatchRenderer.class, remap = false)
public abstract class ShipBatchRendererCloakingMixin {

    @Shadow @Final private ArrayList<ShipRenderObject> drawOrder;

    /**
     * Bring the main FB's depth into the side FB's OWN depth attachment at the
     * start of each layer's per-ship loop: a blit at the first layer, a merge at
     * the later opaque layers. Lets cloaked ships depth-test
     * against current terrain in side FB (so terrain occludes the ship
     * correctly) while their depth WRITES go to side FB depth — main FB depth
     * is never polluted, so cutout terrain, other ships, and translucents
     * render unaffected. Skipped when no ship in drawOrder is cloaking, so
     * non-cloak frames pay nothing.
     */
    @Inject(method = "drawLayer", at = @At("HEAD"), remap = false)
    private void enderkinesis$copyDepthOnLayerStart(
        RenderType renderType, PoseStack poseStack,
        double camX, double camY, double camZ,
        Matrix4f projectionMatrix, Frustum frustum,
        CallbackInfo ci
    ) {
        for (ShipRenderObject obj : drawOrder) {
            if (ClientShipCloakingState.INSTANCE.getProgress(obj.ship.getId()) > 0.001f) {
                // Translucent terrain (water, glass) is in the main depth at this layer. A
                // merge then removes each ship pixel behind water, so only the first-layer
                // copy is permitted here.
                if (renderType == RenderType.translucent() || renderType == RenderType.tripwire()) {
                    ConcealmentShipFB.INSTANCE.copyMainDepth();
                } else {
                    ConcealmentShipFB.INSTANCE.mergeMainDepth();
                }
                return;
            }
        }
    }

    // drawPreparedLayer sets each ship's transform before any of its draws, so this is the ship of the next draws.
    @Unique private long enderkinesis$drawShipId;

    // ShipFrameData is private, so it is coerced to Object. No descriptor: the AP does not remap a mod method's descriptor.
    @WrapOperation(
        method = "drawPreparedLayer",
        at = @At(value = "INVOKE", target = "Lorg/valkyrienskies/mod/common/render/batched/ShipBatchRenderer;setShipTransform"),
        remap = false
    )
    private void enderkinesis$trackDrawShip(
        @Coerce Object data, boolean usingBatchedShader, int shipIndexLoc, Uniform modelViewUniform,
        Operation<Void> original
    ) {
        enderkinesis$drawShipId = ((ShipFrameDataAccessor) data).enderkinesis$getShip().getId();
        original.call(data, usingBatchedShader, shipIndexLoc, modelViewUniform);
    }

    @WrapOperation(
        method = "drawPreparedLayer",
        at = @At(value = "INVOKE", target = "Lorg/valkyrienskies/mod/common/render/batched/ShipBatchRenderer;drawSections"),
        remap = false
    )
    private void enderkinesis$redirectCloakingSections(
        @Coerce Object data, int layer, Uniform chunkOffset, ShipMaterialUniforms uniforms, Operation<Void> original
    ) {
        final boolean sideFb = enderkinesis$bindSideFbIfCloaking();
        try {
            original.call(data, layer, chunkOffset, uniforms);
        } finally {
            if (sideFb) Minecraft.getInstance().getMainRenderTarget().bindWrite(true);
        }
    }

    @WrapOperation(
        method = "drawPreparedLayer",
        at = @At(value = "INVOKE", target = "Lorg/valkyrienskies/mod/common/render/batched/ShipBatchRenderer;drawBlendedHull"),
        remap = false
    )
    private void enderkinesis$redirectCloakingBlendedHull(
        @Coerce Object data, Uniform chunkOffset, ShipMaterialUniforms uniforms, Operation<Void> original
    ) {
        final boolean sideFb = enderkinesis$bindSideFbIfCloaking();
        try {
            original.call(data, chunkOffset, uniforms);
        } finally {
            if (sideFb) Minecraft.getInstance().getMainRenderTarget().bindWrite(true);
        }
    }

    // The @At target is a Minecraft method, so it must be remapped; on Fabric prod "draw" is method_35665.
    @WrapOperation(
        method = "drawPreparedLayer",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/VertexBuffer;draw()V",
            remap = true
        ),
        remap = false
    )
    private void enderkinesis$redirectCloakingShipDraw(VertexBuffer instance, Operation<Void> original) {
        final boolean sideFb = enderkinesis$bindSideFbIfCloaking();
        try {
            original.call(instance);
        } finally {
            if (sideFb) Minecraft.getInstance().getMainRenderTarget().bindWrite(true);
        }
    }

    @Unique
    private boolean enderkinesis$bindSideFbIfCloaking() {
        final long shipId = enderkinesis$drawShipId;
        final float progress = ClientShipCloakingState.INSTANCE.getProgress(shipId);
        // Inside its own bubble the ship stays in main FB, so it depth-sorts against entities and hand items.
        if (progress <= 0.001f || CloakingMixinSupport.isCameraInsideBubbleOf(shipId)) return false;
        // The side FB has its own depth, seeded from main depth at drawLayer HEAD, so cloaked
        // writes never reach main depth and overlapping cloaked ships still occlude each other.
        ConcealmentShipFB.INSTANCE.bindForDraw();
        return true;
    }
}
