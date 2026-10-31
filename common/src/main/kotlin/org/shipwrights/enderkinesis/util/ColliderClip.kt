package org.shipwrights.enderkinesis.util

import net.minecraft.world.entity.Entity
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.Vec3

// Vanilla takes a null entity as an empty collision context, but Mojang's annotations mark the parameter non-null.
@Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
fun colliderClip(from: Vec3, to: Vec3, entity: Entity? = null): ClipContext =
    ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity)
