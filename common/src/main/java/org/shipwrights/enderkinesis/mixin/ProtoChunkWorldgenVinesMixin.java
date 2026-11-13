package org.shipwrights.enderkinesis.mixin;

import java.util.List;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.shipwrights.enderkinesis.duck.WorldgenVineHolder;
import org.shipwrights.enderkinesis.vine.VineRecord;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ProtoChunk.class)
public abstract class ProtoChunkWorldgenVinesMixin implements WorldgenVineHolder {

    // The worldgen worker writes it, and a save or the full-chunk step can read it on another thread.
    @Unique
    private volatile List<VineRecord> enderkinesis$worldgenVines = List.of();

    @Override
    public List<VineRecord> enderkinesis$getWorldgenVines() {
        return enderkinesis$worldgenVines;
    }

    @Override
    public void enderkinesis$setWorldgenVines(List<VineRecord> records) {
        enderkinesis$worldgenVines = List.copyOf(records);
    }
}
