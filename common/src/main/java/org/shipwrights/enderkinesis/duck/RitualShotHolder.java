package org.shipwrights.enderkinesis.duck;

import org.jetbrains.annotations.Nullable;

/** A projectile shot from a ritual circle; the shot is not saved, so a reload disarms it. */
public interface RitualShotHolder {
    @Nullable Object enderkinesis$getRitualShot();

    void enderkinesis$setRitualShot(@Nullable Object shot);
}
