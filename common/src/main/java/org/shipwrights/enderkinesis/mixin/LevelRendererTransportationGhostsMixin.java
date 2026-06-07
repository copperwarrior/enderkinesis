package org.shipwrights.enderkinesis.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import org.shipwrights.enderkinesis.client.TransportationGhostRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hook that lets {@link TransportationGhostRenderer} draw the Tome of Transportation's
 * in-flight item visuals inside {@code LevelRenderer.renderLevel} after the opaque /
 * cutout / entity passes but before translucent blocks. Same string-constant injection
 * point as {@code LevelRendererSselithChainsMixin} — at the "translucent" profiler
 * transition the depth buffer holds the full opaque geometry, so the items depth-test
 * correctly (occluded by walls, visible through air) and the subsequent translucent pass
 * still draws over them where it should.
 *
 * <p>This replaces the previous client-only {@code ItemEntity} approach, which had
 * F3+B-visible hitboxes (entities always do) — the renderer call here draws the item
 * directly with no entity in any subsystem.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererTransportationGhostsMixin {

    @Inject(
        method = "renderLevel",
        at = @At(
            value = "CONSTANT",
            args = "stringValue=translucent"
        )
    )
    private void enderkinesis$renderTransportationGhosts(
        PoseStack poseStack,
        float partialTick,
        long finishNanoTime,
        boolean renderBlockOutline,
        Camera camera,
        GameRenderer gameRenderer,
        LightTexture lightTexture,
        Matrix4f projection,
        CallbackInfo ci
    ) {
        TransportationGhostRenderer.INSTANCE.render(poseStack, camera, partialTick);
    }
}
