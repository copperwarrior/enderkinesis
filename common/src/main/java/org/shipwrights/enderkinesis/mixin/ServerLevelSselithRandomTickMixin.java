package org.shipwrights.enderkinesis.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.shipwrights.enderkinesis.dimension.SselithBlockTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drives {@link SselithBlockTracker}'s random tick from vanilla's own
 * random-tick site. Piggybacking on {@code tickChunk} means tracked
 * positions inherit the correct semantics for free — only loaded chunks,
 * only within simulation distance, paused when the level is frozen — and
 * the {@code randomTickSpeed} gamerule arrives as the method's own
 * argument.
 *
 * <p>Unlike vanilla's sweep, the tracker ticks positions whose blocks have
 * no random tick of their own (and positions that are now air), so it
 * cannot reuse the vanilla path and runs as a sibling pass.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelSselithRandomTickMixin {

    @Inject(method = "tickChunk", at = @At("HEAD"))
    private void enderkinesis$tickSselithEdits(
        LevelChunk chunk, int randomTickSpeed, CallbackInfo ci
    ) {
        SselithBlockTracker.INSTANCE.tickChunk(
            (ServerLevel) (Object) this, chunk, randomTickSpeed
        );
    }
}
