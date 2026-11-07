package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import org.shipwrights.enderkinesis.client.SelvesClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// A player in another player's body wears that player's skin.
@Mixin(AbstractClientPlayer.class)
public abstract class AbstractClientPlayerSelvesMixin {

    @ModifyReturnValue(method = "getSkinTextureLocation", at = @At("RETURN"))
    private ResourceLocation enderkinesis$selvesSkin(ResourceLocation original) {
        return SelvesClient.skin((AbstractClientPlayer) (Object) this, original);
    }

    @ModifyReturnValue(method = "getModelName", at = @At("RETURN"))
    private String enderkinesis$selvesModel(String original) {
        return SelvesClient.modelName((AbstractClientPlayer) (Object) this, original);
    }
}
