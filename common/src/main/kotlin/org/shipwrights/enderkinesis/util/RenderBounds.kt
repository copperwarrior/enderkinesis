package org.shipwrights.enderkinesis.util

import net.minecraft.world.phys.AABB

/**
 * Return this from a block entity's `getRenderBoundingBox()` when its visual leaves the block. Forge culls a block
 * entity by that box (`IForgeBlockEntity`, which common code cannot name; the JVM binds by signature). Fabric has no such test.
 */
val INFINITE_RENDER_BOX: AABB = AABB(
    Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY,
    Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
)
