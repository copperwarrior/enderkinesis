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

/**
 * Sodium-path mirror of {@link LightEngineSpellDarknessMixin}: Sodium's mesher reads light
 * from its own copies of the light arrays in {@code WorldSlice.getBrightness}, never asking the
 * light engine, so places a spell has darkened ({@link DarkZones}) are darkened there too.
 *
 * <p>Gated by {@link EnderkinesisMixinPlugin#shouldApplyMixin} on Sodium's presence.
 */
@Mixin(value = WorldSlice.class, remap = false)
public abstract class WorldSliceSpellDarknessMixin {

    @Inject(
        method = "getBrightness(Lnet/minecraft/world/level/LightLayer;Lnet/minecraft/core/BlockPos;)I",
        at = @At("RETURN"),
        cancellable = true,
        // 0: as the orb's mirror, a missed name mapping on Embeddium must not stop the launch.
        require = 0,
        remap = true
    )
    private void enderkinesis$darken(LightLayer layer, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        if (DarkZones.isEmpty(true)) return;
        // The slice's own level field is named differently under Embeddium: the client's level is the one meshed.
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        int levels = DarkZones.levelsAt(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        if (levels <= 0) return;
        cir.setReturnValue(Math.max(0, cir.getReturnValueI() - levels));
    }
}
