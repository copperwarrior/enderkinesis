package org.shipwrights.enderkinesis.mixin;

import com.mojang.blaze3d.vertex.BufferBuilder;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.RenderType;
import org.shipwrights.enderkinesis.client.SpellGlint;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.SortedMap;

/** Glint buffers draw at the end of the batch. If they draw earlier, the equal-depth test finds no item and nothing shows. */
@Mixin(RenderBuffers.class)
public abstract class RenderBuffersSpellGlintMixin {
    @Shadow @Final private SortedMap<RenderType, BufferBuilder> fixedBuffers;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void enderkinesis$spellGlintBuffer(CallbackInfo ci) {
        for (RenderType type : SpellGlint.TYPES) fixedBuffers.put(type, new BufferBuilder(type.bufferSize()));
    }
}
