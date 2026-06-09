package org.shipwrights.enderkinesis.mixin;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelTimeAccess;
import org.shipwrights.enderkinesis.client.WohlonBiomeSkyState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lerps {@code getTimeOfDay} toward Wohlon's dusk using the SLOW {@code timeBlend} (decoupled
 * from the fast {@code currentBlend} so visual identity snaps quickly while sun motion stays
 * gentle ≤10°/s).
 *
 * **Target gotcha:** {@link LevelTimeAccess} is the only valid mixin target — 1.20.1 declares
 * {@code getTimeOfDay(F)F} as a default method on this interface, NOT on Level/ClientLevel.
 */
@Mixin(LevelTimeAccess.class)
public interface LevelTimeAccessGetTimeOfDayWohlonBiomeMixin {

    @Inject(method = "getTimeOfDay(F)F", at = @At("RETURN"), cancellable = true)
    private void enderkinesis$blendWohlonTimeOfDay(float partialTick, CallbackInfoReturnable<Float> cir) {
        if (WohlonBiomeSkyState.getCurrentBlend() <= 0f) return;
        // Server-side guard: in single-player the integrated server thread
        // shares the JVM with the client. Guard against server-thread reads
        // picking up the blended (client-only) value.
        Object self = this;
        if (self instanceof Level level && !level.isClientSide) return;
        float actual = cir.getReturnValueF();
        float blended = WohlonBiomeSkyState.blendTimeOfDay(actual);
        if (blended != actual) cir.setReturnValue(blended);
    }
}
