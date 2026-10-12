package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import org.jetbrains.annotations.Nullable;
import org.shipwrights.enderkinesis.dimension.Sureibjin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererSureibjinOutlineMixin {

    @Shadow @Nullable private ClientLevel level;

    @Inject(method = "renderHitOutline", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$hideSureibjinOutline(CallbackInfo ci) {
        ClientLevel lvl = this.level;
        if (lvl != null && lvl.dimension().equals(Sureibjin.INSTANCE.getLEVEL_KEY())) ci.cancel();
    }
}
