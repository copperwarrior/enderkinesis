package org.shipwrights.enderkinesis.duck;

/** Other result containers strip imbues, because their results copy the input NBT.
 *  Keep this type in {@code duck}: the Mixin package is closed to normal class loading. */
public interface ImbueKeepingResult {
    void enderkinesis$keepImbues();
}
