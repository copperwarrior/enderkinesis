package org.shipwrights.enderkinesis.duck;

import org.shipwrights.enderkinesis.item.WoodHearts;

/** Keep in {@code duck}: the Mixin package is closed to normal class loading. Implemented by {@code PlayerAntleredCrownMixin}. */
public interface AntleredCrownHolder {
    WoodHearts enderkinesis$woodHearts();

    /** The wood fraction, synced to every tracking client for the vein layer. */
    float enderkinesis$veinGrowth();

    void enderkinesis$setVeinGrowth(float growth);
}
