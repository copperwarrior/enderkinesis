package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.renderer.LevelRenderer;
import org.shipwrights.enderkinesis.client.OwlSightPasses;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererOwlSightMixin {

    // Anchored on the profiler string: Forge adds a Frustum parameter to ParticleEngine.render.
    // Before particles so they don't count as occluders; terrain, entities and BEs have written depth.
    @Inject(
        method = "renderLevel",
        at = @At(
            value = "INVOKE_STRING",
            target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V",
            args = "ldc=particles"
        )
    )
    private void enderkinesis$owlSightMask(CallbackInfo ci) {
        OwlSightPasses.INSTANCE.onBeforeParticles();
    }
}
