package org.shipwrights.enderkinesis.entity

import net.minecraft.world.entity.LivingEntity

/** Kills this prey with no drops or XP; false when the hit did not kill it. */
fun LivingEntity.eatWhole(predator: LivingEntity): Boolean {
    // Return value ignored: a duplicate tag is harmless.
    // The tag must be on before hurt(): death drops run inside it.
    addTag(ShilleagkDeerEntity.EATEN_TAG)
    val killed = hurt(damageSources().mobAttack(predator), Float.MAX_VALUE)
    if (killed) {
        // XP drops later, in tickDeath.
        skipDropExperience()
    } else {
        removeTag(ShilleagkDeerEntity.EATEN_TAG)
    }
    return killed
}
