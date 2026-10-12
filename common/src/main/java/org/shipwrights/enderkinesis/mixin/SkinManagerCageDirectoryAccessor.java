package org.shipwrights.enderkinesis.mixin;

import java.io.File;
import net.minecraft.client.resources.SkinManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read handle for {@code SkinManager.skinsDirectory} — the on-disk skin cache root
 * ({@code assets/skins} under the game dir). The Ga'Mylan cage's crystal-colour
 * sampler resolves the cached PNG {@code HttpTexture} wrote (same
 * {@code sha1(hash).substring(0,2)/sha1(hash)} layout {@code SkinManager.registerTexture}
 * uses) so it can pixel-sample the skin without a second download.
 */
@Mixin(SkinManager.class)
public interface SkinManagerCageDirectoryAccessor {

    @Accessor("skinsDirectory")
    File enderkinesis$getSkinsDirectory();
}
