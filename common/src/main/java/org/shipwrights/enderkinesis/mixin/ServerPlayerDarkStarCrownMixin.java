package org.shipwrights.enderkinesis.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.shipwrights.enderkinesis.item.CrownOfTheDarkStar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerDarkStarCrownMixin {

    @Unique
    private boolean enderkinesis$crownWorn;

    @Inject(method = "tick", at = @At("TAIL"))
    private void enderkinesis$trackCrown(CallbackInfo ci) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        boolean worn = CrownOfTheDarkStar.isWorn(self);
        if (worn == enderkinesis$crownWorn) return;
        enderkinesis$crownWorn = worn;
        CrownOfTheDarkStar.onWornChanged(self, worn);
    }

    @Inject(method = "getTabListDisplayName", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$crownTabName(CallbackInfoReturnable<Component> cir) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (CrownOfTheDarkStar.isWorn(self)) cir.setReturnValue(CrownOfTheDarkStar.obfuscatedName(self));
    }
}
