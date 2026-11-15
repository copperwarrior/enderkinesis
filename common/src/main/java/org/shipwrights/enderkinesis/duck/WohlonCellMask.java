package org.shipwrights.enderkinesis.duck;

/** Keep in {@code duck}: the Mixin package is closed to normal class loading. Implemented by {@code PalettedContainerWohlonMaskMixin}. */
public interface WohlonCellMask {
    /** Biome containers only: bit {@code y << 4 | z << 2 | x} is set when that cell is Wohlon. */
    long enderkinesis$wohlonMask();
}
