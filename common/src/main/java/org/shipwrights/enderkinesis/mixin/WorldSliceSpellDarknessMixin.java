package org.shipwrights.enderkinesis.mixin;

import me.jellysquid.mods.sodium.client.world.WorldSlice;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.shipwrights.enderkinesis.spell.DarkZones;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Sodium mirror of {@link LightEngineSpellDarknessMixin}: Sodium reads light from its own array copies, not from the light engine. */
@Mixin(value = WorldSlice.class, remap = false)
public abstract class WorldSliceSpellDarknessMixin {

    @Inject(
        method = "getBrightness(Lnet/minecraft/world/level/LightLayer;Lnet/minecraft/core/BlockPos;)I",
        at = @At("RETURN"),
        cancellable = true,
        // 0: a missed name mapping on Embeddium must not stop the launch.
        require = 0,
        remap = true
    )
    private void enderkinesis$darken(LightLayer layer, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        if (DarkZones.isEmpty(true)) return;
        // Embeddium uses a different name for the slice level field, so use the client level.
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        int levels = DarkZones.levelsAt(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        if (levels <= 0) return;
        cir.setReturnValue(Math.max(0, cir.getReturnValueI() - levels));
    }
}
