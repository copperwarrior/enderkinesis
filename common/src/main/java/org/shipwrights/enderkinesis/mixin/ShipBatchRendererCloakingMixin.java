package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
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
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.mod.common.render.batched.ShipBatchRenderer;
import org.valkyrienskies.mod.common.render.batched.ShipRenderObject;

/**
 * Redirect cloaking ships' per-ship draws inside {@link ShipBatchRenderer#drawLayer}
 * to {@link ConcealmentShipFB}.
 *
 * <p>VS2's batched renderer draws all visible ships in one call to {@code drawLayer},
 * but it does so in a per-ship loop that issues an independent {@code VertexBuffer.draw()}
 * for each ship (translucent path issues one per section). By wrapping that draw call,
 * we can decide on a per-ship basis whether the geometry lands in the main FB
 * (non-cloaking ships, default behaviour) or the side FB (cloaking ships).
 *
 * <p>The result: the cloaking ship is fully removed from the main FB (so it's invisible
 * outside the bubble silhouette), and present in the side FB (so the post-shader can
 * sample it for the refraction). The mesh data comes from VS2's own batched compiler —
 * no chunk-pipeline duplication, no lighting wrapper, no shader uniform plumbing.
 */
@Mixin(value = ShipBatchRenderer.class, remap = false)
public abstract class ShipBatchRendererCloakingMixin {

    @Shadow @Final private ArrayList<ShipRenderObject> drawOrder;

    /**
     * Blit the main FB's depth into the side FB's OWN depth attachment at the
     * start of each layer's per-ship loop. Lets cloaked ships depth-test
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
                ConcealmentShipFB.INSTANCE.copyMainDepth();
                return;
            }
        }
    }

    // @Mixin/@WrapOperation stay remap=false because ShipBatchRenderer
    // and drawLayer are VS2 names (not in MC mappings). But the @At
    // target IS a Minecraft method — `VertexBuffer.draw()` — so it must
    // be remapped Mojang→intermediary; otherwise on Fabric prod the
    // literal "draw" name is searched on class_291 and matches nothing
    // (the actual intermediary name is method_35665).
    @WrapOperation(
        method = "drawLayer",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/VertexBuffer;draw()V",
            remap = true
        ),
        remap = false
    )
    private void enderkinesis$redirectCloakingShipDraw(
        VertexBuffer instance, Operation<Void> original,
        @Local(name = "shipIdx") int shipIdx
    ) {
        // shipIdx is the per-ship loop variable inside drawLayer (both the opaque
        // and the translucent paths share it). drawOrder.get(shipIdx) is the ship
        // whose mesh is about to be drawn.
        if (shipIdx < 0 || shipIdx >= drawOrder.size()) {
            original.call(instance);
            return;
        }
        final ShipRenderObject renderObject = drawOrder.get(shipIdx);
        final long shipId = renderObject.ship.getId();
        final float progress = ClientShipCloakingState.INSTANCE.getProgress(shipId);

        // If the camera is physically inside THIS ship's bubble, treat the
        // ship as non-cloaking for redirect purposes — it renders normally to
        // main FB and depth-sorts against entities, hand items, and the rest
        // of the scene through the standard pipeline. The post-shader's
        // inside-observer mode then warbles only the world OUTSIDE this
        // bubble; the ship itself stays crisp because it's just part of main
        // FB. Without this, the side FB ship would be composited over
        // entities/hand items by the post-shader (no depth test in the
        // composite).
        if (progress > 0.001f && !CloakingMixinSupport.isCameraInsideBubbleOf(shipId)) {
            // Cloaking — send this draw to the side FB instead of main FB. The
            // side FB has its OWN depth attachment (NOT main's). The HEAD
            // inject above blitted current main FB depth into it at the start
            // of this drawLayer, so terrain occludes the ship in side FB
            // correctly. The ship's own depth WRITES go to side depth only —
            // main depth stays clean for the rest of the world render. Inside
            // the layer's per-ship loop, side depth accumulates each cloaked
            // ship's writes, so overlapping cloaked ships also depth-occlude
            // each other correctly within the layer.
            ConcealmentShipFB.INSTANCE.bindForDraw();
            try {
                original.call(instance);
            } finally {
                // Restore main FB for the next ship's draw (this loop iterates
                // many ships per drawLayer call).
                Minecraft.getInstance().getMainRenderTarget().bindWrite(true);
            }
        } else {
            // Non-cloaking ship — draw normally to whatever's currently bound
            // (main FB).
            original.call(instance);
        }
    }
}
