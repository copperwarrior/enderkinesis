package org.shipwrights.enderkinesis.mixin;

import net.minecraft.core.Holder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.shipwrights.enderkinesis.dimension.WohlonBiomePresence;
import org.shipwrights.enderkinesis.duck.WohlonCellMask;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PalettedContainer.class)
public abstract class PalettedContainerWohlonMaskMixin<T> implements WohlonCellMask {

    private static final int CELLS = 64;

    @Shadow
    public abstract T get(int x, int y, int z);

    // Mesh threads read it. Client biome containers are replaced on each biome packet (LevelChunkSection.readBiomes),
    // never set in place, so a new container is the invalidation; read() also clears it.
    @Unique
    private volatile Long enderkinesis$wohlonMask;

    @Override
    @SuppressWarnings("unchecked")
    public long enderkinesis$wohlonMask() {
        Long cached = enderkinesis$wohlonMask;
        if (cached != null) return cached;
        long mask = 0L;
        for (int i = 0; i < CELLS; i++) {
            if (WohlonBiomePresence.IS_WOHLON.test((Holder<Biome>) get(i & 3, i >> 4, (i >> 2) & 3))) mask |= 1L << i;
        }
        enderkinesis$wohlonMask = mask;
        return mask;
    }

    @Inject(method = "read(Lnet/minecraft/network/FriendlyByteBuf;)V", at = @At("HEAD"))
    private void enderkinesis$clearWohlonMask(FriendlyByteBuf buf, CallbackInfo ci) {
        enderkinesis$wohlonMask = null;
    }
}
