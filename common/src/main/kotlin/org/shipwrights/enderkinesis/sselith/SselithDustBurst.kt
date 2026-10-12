package org.shipwrights.enderkinesis.sselith

import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import org.shipwrights.enderkinesis.registry.EKParticles

object SselithDustBurst {
    private const val COUNT = 40

    /** A burst of Cataloger dust through [player]'s body, seen by everyone near that spot in [level]. */
    fun at(level: ServerLevel, player: ServerPlayer) {
        val half = player.bbWidth / 2.0
        level.sendParticles(
            EKParticles.sselithDust(),
            player.x, player.y + player.bbHeight / 2.0, player.z,
            // The dust sets its own drift, so the speed argument is unused.
            COUNT, half, player.bbHeight / 2.0, half, 0.0,
        )
    }
}
