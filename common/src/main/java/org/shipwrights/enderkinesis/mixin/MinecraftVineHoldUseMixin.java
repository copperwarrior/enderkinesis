package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.Minecraft;
import org.shipwrights.enderkinesis.client.VineCutHighlight;
import org.shipwrights.enderkinesis.client.VineHoldClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftVineHoldUseMixin {

    // The vine is not in the crosshair hit, so without this the press and its 4-tick repeats use the block behind it.
    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$holdVine(CallbackInfo ci) {
        // Before vanilla's use packet, so a server vine pick on this use takes the drawn rope.
        VineCutHighlight.sendAim((Minecraft) (Object) this);
        boolean takes = VineHoldClient.INSTANCE.takesUseKey();
        if (takes) ci.cancel();
    }
}
