package org.shipwrights.enderkinesis.mixin;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelTimeAccess;
import org.shipwrights.enderkinesis.client.WohlonBiomeSkyState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Blend the level's {@code getTimeOfDay(F)F} toward Wohlon's fixed dusk pose when the
 * player is inside a Wohlon biome patch. Rendering-only — server logic reads
 * {@code getDayTime()} instead.
 *
 * <p>{@link LevelTimeAccess} is the only valid mixin target: in 1.20.1 vanilla declares
 * {@code getTimeOfDay(F)F} as a default method on this interface, NOT on {@code Level}
 * or {@code ClientLevel}. Injecting on those fails with "could not find any targets
 * matching method_30274(F)F". Hooking the interface default catches every implementer.
 */
@Mixin(LevelTimeAccess.class)
public interface LevelTimeAccessGetTimeOfDayWohlonBiomeMixin {

    @Inject(method = "getTimeOfDay(F)F", at = @At("RETURN"), cancellable = true)
    private void enderkinesis$blendWohlonTimeOfDay(float partialTick, CallbackInfoReturnable<Float> cir) {
        if (WohlonBiomeSkyState.currentBlend <= 0f) return;
        // Server-side guard: in single-player, the integrated
        // server thread shares the JVM with the client. Guard
        // against server-thread reads picking up the blended value.
        Object self = this;
        if (self instanceof Level level && !level.isClientSide) return;
        float actual = cir.getReturnValueF();
        float blended = WohlonBiomeSkyState.blendTimeOfDay(actual);
        if (blended != actual) cir.setReturnValue(blended);
    }
}
