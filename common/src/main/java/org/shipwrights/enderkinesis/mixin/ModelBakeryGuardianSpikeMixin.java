package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.resources.ResourceLocation;
import org.shipwrights.enderkinesis.client.GuardianSpikeModels;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BiFunction;

// Forge fires ModifyBakingResult after bakeModels returns, so this TAIL runs first on both loaders.
@Mixin(ModelBakery.class)
public abstract class ModelBakeryGuardianSpikeMixin {
    @Inject(method = "bakeModels", at = @At("TAIL"))
    private void enderkinesis$retiltGuardianSpikes(
        BiFunction<ResourceLocation, Material, TextureAtlasSprite> spriteGetter, CallbackInfo ci
    ) {
        GuardianSpikeModels.retilt(((ModelBakery) (Object) this).getBakedTopLevelModels());
    }
}
