package org.shipwrights.enderkinesis.mixin;

import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.DataLayerStorageMap;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * VS2 ship-move mitigation: relocating a ship through the shipyard enqueues block-light
 * updates for unallocated {@link DataLayer} sections — NPEs from {@code getStoredLevel}'s
 * {@code dataLayer.get(...)} and {@code setStoredLevel}'s {@code copyDataLayer.put(...)}.
 * Substitute 0 on the read and cancel the write; MC relights the section normally once
 * it exists.
 */
@Mixin(LayerLightSectionStorage.class)
public abstract class LayerLightSectionStorageMixin {

    @Shadow
    @SuppressWarnings("rawtypes")
    protected volatile DataLayerStorageMap updatingSectionData;

    @Redirect(
        method = "getStoredLevel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/DataLayer;get(III)I"
        )
    )
    private int enderkinesis$nullSafeStoredLevel(DataLayer dataLayer, int x, int y, int z) {
        return dataLayer == null ? 0 : dataLayer.get(x, y, z);
    }

    /**
     * Bail out of {@code setStoredLevel} when the target section's {@link DataLayer} is
     * unallocated. The parameter vanilla calls {@code blockPos} is a packed block position
     * — the map is keyed by <em>section</em>, so convert via
     * {@link SectionPos#blockToSection(long)} or the lookup misses every non-aligned write
     * and cancels every light update.
     */
    @Inject(method = "setStoredLevel", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$skipWriteForUnallocatedSection(
        long blockPos, int level, CallbackInfo ci
    ) {
        long sectionPos = SectionPos.blockToSection(blockPos);
        if (updatingSectionData != null && updatingSectionData.getLayer(sectionPos) == null) {
            ci.cancel();
        }
    }
}
