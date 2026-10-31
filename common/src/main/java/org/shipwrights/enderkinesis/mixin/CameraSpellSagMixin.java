package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import org.shipwrights.enderkinesis.client.SpellScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Weighted tilts the view with a roll ({@link SpellScreen#roll}), not a camera move, so the crosshair stays on the target. */
@Mixin(Camera.class)
public abstract class CameraSpellSagMixin {
    @Inject(method = "setup", at = @At("TAIL"))
    private void enderkinesis$sag(BlockGetter level, Entity entity, boolean detached, boolean mirrored, float partialTick, CallbackInfo ci) {
        SpellScreen.camera(entity);
    }
}
