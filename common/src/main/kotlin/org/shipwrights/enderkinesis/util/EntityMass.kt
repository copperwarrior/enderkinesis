package org.shipwrights.enderkinesis.util

import net.minecraft.world.entity.Entity
import kotlin.math.max

// kg per cubic metre of hitbox: a player is about 78 kg.
private const val ENTITY_DENSITY = 120.0
private const val MIN_ENTITY_MASS = 10.0

/** The mass of an entity for physics: [ENTITY_DENSITY] times its hitbox volume, never under [MIN_ENTITY_MASS]. */
fun Entity.hitboxMass(): Double {
    val box = boundingBox
    return max(MIN_ENTITY_MASS, ENTITY_DENSITY * box.xsize * box.ysize * box.zsize)
}
