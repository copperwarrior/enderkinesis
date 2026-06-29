package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import org.shipwrights.enderkinesis.dimension.SureibjinEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Allow vanilla beds to be used (no explosion) in the three ulder source
 * dimensions (Sselith / Wohlonnogondonia / Ygann Abyss). Without this,
 * the {@code dimension_type.bed_works = false} setting on those dimensions
 * triggers {@link BedBlock#canSetSpawn} to return false, which sends
 * {@link BedBlock#use} down the explode path.
 *
 * <p>Once the explosion is bypassed, vanilla proceeds to call
 * {@code player.startSleepInBed} — which is then intercepted by
 * {@link ServerPlayerSureibjinSleepMixin} to teleport the player to
 * Sureibjin instead of putting them to sleep in place.
 *
 * <p>Sureibjin itself stays at {@code bed_works = false} (and isn't an
 * entry-source dim), so beds inside the dream still explode as expected —
 * the dream world isn't a normal sleepable place either.
 */
@Mixin(BedBlock.class)
public abstract class BedBlockSureibjinAllowMixin {

    @WrapOperation(
        method = "use",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/BedBlock;canSetSpawn(Lnet/minecraft/world/level/Level;)Z"
        )
    )
    private boolean enderkinesis$allowBedInUlderDims(
        Level level,
        Operation<Boolean> original
    ) {
        if (SureibjinEntry.isUlderEntrySource(level.dimension())) return true;
        return original.call(level);
    }
}
