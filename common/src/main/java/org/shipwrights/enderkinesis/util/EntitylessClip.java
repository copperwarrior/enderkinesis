package org.shipwrights.enderkinesis.util;

import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.Vec3;

/** Vanilla takes a null entity (it becomes CollisionContext.empty()), but the mapped parameter is non-null, so Kotlin warns. */
public final class EntitylessClip {
    private EntitylessClip() {}

    public static ClipContext of(Vec3 from, Vec3 to, ClipContext.Block block, ClipContext.Fluid fluid) {
        return new ClipContext(from, to, block, fluid, null);
    }
}
