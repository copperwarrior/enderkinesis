package org.shipwrights.enderkinesis.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import org.joml.Matrix4f;
import org.shipwrights.enderkinesis.client.CloakBufferSource;
import org.shipwrights.enderkinesis.client.CloakedParticleBuffer;
import org.shipwrights.enderkinesis.client.CloakingMixinSupport;
import org.shipwrights.enderkinesis.client.ConcealmentBubbleRenderer;
import org.shipwrights.enderkinesis.client.ConcealmentPostEffect;
import org.shipwrights.enderkinesis.client.ConcealmentShipFB;
import org.shipwrights.enderkinesis.client.OrbBeamLineRenderer;
import org.shipwrights.enderkinesis.client.OrbBeamLineRenderType;
import org.shipwrights.enderkinesis.client.ScryingClient;
import org.shipwrights.enderkinesis.client.TomeSummonOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * End-of-world-render TAIL hook for our refraction effects.
 *
 * <p>Two responsibilities, in order:
 * <ol>
 *   <li>Populate side framebuffers for the cloak: flush cloaked entities and
 *       particles into the ship FB, draw the bubble ellipsoids into the mask FB.
 *       TAIL is correct because the main depth buffer must contain ALL world
 *       geometry (solid + cutout + entities + translucent) so the bubble's
 *       depth test (LEQUAL against shared depth) accurately occludes against
 *       everything in front of it.</li>
 *   <li>Dispatch the three refraction passes (concealment, scrying,
 *       tome-summon) via {@link org.shipwrights.enderkinesis.client.RefractionPass}.
 *       Each pass is gated internally on its own "active" condition so this
 *       inject is essentially free when none of the effects are running. Same
 *       frame moment vanilla's {@code PostChain} dispatches from — we just
 *       drive a direct fullscreen quad instead because {@code PostChain.process}
 *       doesn't land its output on screen under the Prism Flatpak Mesa GL
 *       extension on gfx1201 (see commit ca4195e for the PostChain-based
 *       predecessor).</li>
 * </ol>
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererBubblePassMixin {

    /** Reset ConcealmentShipFB's per-frame flags BEFORE any ship rendering /
     *  cloak event fires. Has to live on renderLevel's HEAD rather than on
     *  VS2's `shipsStartRendering*` events because the Sodium variant of that
     *  event fires per-pass (SOLID / CUTOUT / TRANSLUCENT), not once per frame —
     *  so using it as the reset point wipes accumulated SOLID-pass ship pixels
     *  the moment CUTOUT pass starts. */
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void enderkinesis$beginCloakFrame(
        PoseStack poseStack, float partialTick, long nanoTime, boolean renderBlockOutline,
        Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f projectionMatrix,
        CallbackInfo ci
    ) {
        ConcealmentShipFB.INSTANCE.beginFrame();
    }

    @Inject(method = "renderLevel", at = @At("TAIL"))
    private void enderkinesis$endOfWorldRefraction(
        PoseStack poseStack, float partialTick, long nanoTime, boolean renderBlockOutline,
        Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f projectionMatrix,
        CallbackInfo ci
    ) {
        // Cloaked entities (vertex data accumulated in CloakBufferSource via the
        // EntityRenderDispatcher swap) → side FB. Must run BEFORE the bubble
        // pass writes the mask so the bubble's LEQUAL depth test sees cloaked
        // entities in the shared main depth.
        CloakBufferSource.flushToShipFB();

        // Cloaked particles captured by ParticleEngineCloakingMixin earlier in the frame,
        // replayed here against the side FB. Flush point must match entities — flushing
        // from ParticleEngine.render's RETURN produces no visible side-FB pixels (cause
        // unidentified).
        CloakedParticleBuffer.flushToShipFB(poseStack, lightTexture, camera, partialTick);

        ConcealmentBubbleRenderer.renderBubbles(camera, projectionMatrix);

        // Orb-network beams anchored to cloaked ships routed their cloaked subset
        // into a separate bin during AFTER_TRANSLUCENT. Emit those bins now using
        // the BEAM_LINE_CONCEALED render type, whose output-state shard binds
        // [ConcealmentShipFB] at flush time — so the refraction shader picks them
        // up alongside the cloaked ship blocks instead of the additive blend
        // brightening the world behind the cloak.
        //
        // The lattice ocean mesh deliberately stays on main FB: the refraction
        // shader's `background = mainFB(originalUV)` path preserves it at full
        // intensity through the cloak transition. Routing it through the concealed
        // FB instead made it inherit the ship's fade-out curve and read as dim.
        if (CloakingMixinSupport.isAnyShipConcealed()) {
            ConcealmentShipFB.INSTANCE.copyMainDepth();   // no-op if already done this frame
            MultiBufferSource.BufferSource bufferSource = Minecraft.getInstance().renderBuffers().bufferSource();
            double camX = camera.getPosition().x;
            double camY = camera.getPosition().y;
            double camZ = camera.getPosition().z;
            OrbBeamLineRenderer.INSTANCE.renderConcealed(poseStack, bufferSource, camX, camY, camZ, partialTick);
            bufferSource.endBatch(OrbBeamLineRenderType.INSTANCE.getBEAM_LINE_CONCEALED());
        }

        // Each renderRefraction call is a no-op when its effect isn't active.
        // Order: concealment first so scrying/tome-summon see the refracted
        // composite if they're stacked on top of a cloaked view.
        ConcealmentPostEffect.renderRefraction();
        ScryingClient.renderRefraction();
        TomeSummonOverlay.renderRefraction(partialTick);
    }
}
