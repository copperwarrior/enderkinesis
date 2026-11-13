package org.shipwrights.enderkinesis.duck;

import java.util.List;
import org.shipwrights.enderkinesis.vine.VineRecord;

/** Keep in {@code duck}: the Mixin package is closed to normal class loading. Implemented by {@code ProtoChunkWorldgenVinesMixin}. */
public interface WorldgenVineHolder {
    /** The vines that worldgen wrote into this proto chunk; empty when none. */
    List<VineRecord> enderkinesis$getWorldgenVines();

    void enderkinesis$setWorldgenVines(List<VineRecord> records);
}
