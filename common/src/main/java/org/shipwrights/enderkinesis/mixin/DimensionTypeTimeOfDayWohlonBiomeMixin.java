package org.shipwrights.enderkinesis.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.world.level.dimension.DimensionType;
import org.shipwrights.enderkinesis.client.WohlonBiomeSkyState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Snaps the time of day to Wohlon's dusk once the biome blend reaches the sky threshold. A lerp
 * swirls the sun and moon; {@link LevelSkyDarkenSmoothMixin} smooths the brightness step instead.
 *
 * Forge's Mixin 0.8.5 cannot inject into interfaces, so this hooks the class method that
 * {@code LevelTimeAccess.getTimeOfDay} calls. The integrated server runs off the render thread.
 */
@Mixin(DimensionType.class)
public abstract class DimensionTypeTimeOfDayWohlonBiomeMixin {

    @Inject(method = "timeOfDay", at = @At("RETURN"), cancellable = true)
    private void enderkinesis$blendWohlonTimeOfDay(long dayTime, CallbackInfoReturnable<Float> cir) {
        if (WohlonBiomeSkyState.getCurrentBlend() <= 0f || !RenderSystem.isOnRenderThread()) return;
        float actual = cir.getReturnValueF();
        float blended = WohlonBiomeSkyState.blendTimeOfDay(actual);
        if (blended != actual) cir.setReturnValue(blended);
    }
}
