package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.shipwrights.enderkinesis.client.WohlonAtmosphere;
import org.shipwrights.enderkinesis.client.WohlonBiomeFog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The {@link WohlonAtmosphere} drift on the sky colour; {@link FogRendererWohlonBiomeMixin} puts the same tint on the fog. */
@Mixin(ClientLevel.class)
public abstract class ClientLevelWohlonSkyTintMixin {

    @ModifyReturnValue(method = "getSkyColor(Lnet/minecraft/world/phys/Vec3;F)Lnet/minecraft/world/phys/Vec3;", at = @At("RETURN"))
    private Vec3 enderkinesis$tintWohlonSky(Vec3 colour) {
        if (WohlonBiomeFog.getCurrentBlend() <= 0f || WohlonAtmosphere.getSkyTintSuppressed()) return colour;
        return colour.multiply(
            WohlonAtmosphere.INSTANCE.getTintR(), WohlonAtmosphere.INSTANCE.getTintG(), WohlonAtmosphere.INSTANCE.getTintB());
    }
}
