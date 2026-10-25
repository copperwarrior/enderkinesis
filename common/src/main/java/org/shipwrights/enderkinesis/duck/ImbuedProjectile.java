package org.shipwrights.enderkinesis.duck;

import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

/**
 * A projectile's imbued spell, carried from the weapon that fired it. Implemented on every
 * {@code Projectile} by {@code ProjectileImbueMixin}, which also saves it with the entity.
 * See {@code org.shipwrights.enderkinesis.spell.Imbues}.
 */
public interface ImbuedProjectile {
    @Nullable CompoundTag enderkinesis$getImbue();

    void enderkinesis$setImbue(@Nullable CompoundTag imbue);
}
