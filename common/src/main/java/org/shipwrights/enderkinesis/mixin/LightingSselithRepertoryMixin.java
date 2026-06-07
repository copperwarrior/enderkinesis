package org.shipwrights.enderkinesis.mixin;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.shipwrights.enderkinesis.client.SselithRepertoryLighting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Aims the world-level diffuse light vectors at the fixed sun in
 * {@link org.shipwrights.enderkinesis.dimension.SselithRepertory}.
 *
 * <p>Vanilla {@code Lighting.setupLevel} hardcodes two symmetric world-space directions
 * ({@code DIFFUSE_LIGHT_0/1}) and transforms them through the view matrix. Those are the two
 * lights the block/entity shaders sample for face shading. In the Repertory we want the
 * shading direction to agree with the visible sun at 34.5°/34.5°/34.5° — otherwise entities
 * shade as if lit from straight up and items in the world look pasted on rather than lit by
 * the same source as the sky.
 *
 * <p>We compute the sun direction in
 * {@link SselithRepertoryLighting#fillShaderLights(Matrix4f, Vector3f, Vector3f)}, with a
 * softer opposing fill so back faces don't go black, then call {@code setShaderLights} and
 * cancel vanilla. This is per-frame, but the inputs are cached, so it's just two vector
 * transforms.
 */
@Mixin(Lighting.class)
public abstract class LightingSselithRepertoryMixin {

    @Inject(method = "setupLevel", at = @At("HEAD"), cancellable = true)
    private static void enderkinesis$sselithSetupLevel(Matrix4f viewMatrix, CallbackInfo ci) {
        if (!SselithRepertoryLighting.INSTANCE.isInRepertory()) return;
        Vector3f sun = new Vector3f();
        Vector3f fill = new Vector3f();
        SselithRepertoryLighting.INSTANCE.fillShaderLights(viewMatrix, sun, fill);
        RenderSystem.setShaderLights(sun, fill);
        ci.cancel();
    }
}
