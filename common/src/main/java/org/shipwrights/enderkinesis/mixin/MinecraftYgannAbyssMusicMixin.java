package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.sounds.Music;
import net.minecraft.sounds.Musics;
import org.jetbrains.annotations.Nullable;
import org.shipwrights.enderkinesis.dimension.YgannAbyss;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The Abyss is silent by its biome music entry, but vanilla selects the Creative music before it
 * reads the biome. In the Abyss the biome music replaces that selection.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftYgannAbyssMusicMixin {

    @Shadow @Nullable public LocalPlayer player;

    @ModifyReturnValue(method = "getSituationalMusic", at = @At("RETURN"))
    private Music enderkinesis$noCreativeMusicInAbyss(Music original) {
        LocalPlayer p = this.player;
        if (original != Musics.CREATIVE || p == null) return original;
        if (p.level().dimension() != YgannAbyss.INSTANCE.getLEVEL_KEY()) return original;
        return p.level().getBiome(p.blockPosition()).value().getBackgroundMusic().orElse(original);
    }
}
