package org.shipwrights.enderkinesis.body

import net.minecraft.server.level.ServerLevel

/** True when the cage controller already cancels gravity, i.e. in every state but free fall. See [GamylanCageBodyForce]. */
fun GamylanCageData.holdsOwnGravity(): Boolean =
    OrbGrabState.targetFor(bodyId) != null || jointId != null || homePedestalPos != null ||
        !fadeComplete || !terrainReady

/** True when the body's own controller cancels its gravity, so a second cancel would lift it. */
fun ServerLevel.bodyHoldsOwnGravity(bodyId: Long): Boolean =
    OrbBodyRegistry.contains(this, bodyId) || GamylanCageRegistry.get(this, bodyId)?.holdsOwnGravity() == true
