package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.renderer.texture.HttpTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(HttpTexture.class)
public interface HttpTextureAccessor {

    @Accessor("uploaded")
    boolean enderkinesis$isUploaded();
}
