package org.shipwrights.enderkinesis.client

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.Particle
import net.minecraft.core.particles.ParticleTypes
import org.valkyrienskies.core.api.ships.ClientShip
import org.valkyrienskies.core.api.util.GameTickOnly

/** PORTAL particles in crepusculite pink that converge on a target. */
@OptIn(GameTickOnly::class)
internal object CrepusculitePortalParticles {

    /** The same cull distance that vanilla uses for a particle that is not forced. */
    private const val CULL_RANGE_SQR: Double = 32.0 * 32.0

    // Dark rose to highlight pink, from the crepusculite block texture.
    private const val DARK_R: Float = 0xA5 / 255f
    private const val DARK_G: Float = 0x72 / 255f
    private const val DARK_B: Float = 0x79 / 255f
    private const val LIGHT_R: Float = 0xFF / 255f
    private const val LIGHT_G: Float = 0xE5 / 255f
    private const val LIGHT_B: Float = 0xE9 / 255f

    fun inRange(x: Double, y: Double, z: Double): Boolean =
        Minecraft.getInstance().gameRenderer.mainCamera.position.distanceToSqr(x, y, z) <= CULL_RANGE_SQR

    fun count(power: Int, max: Int): Int = scaledCount((power * max / 15).coerceAtLeast(1))

    /** PortalParticle starts at `target + (ox, oy, oz)` and moves in to `target`. */
    fun spawn(level: ClientLevel, tx: Double, ty: Double, tz: Double, ox: Double, oy: Double, oz: Double) {
        val particle = Minecraft.getInstance().particleEngine.createParticle(
            ParticleTypes.PORTAL, tx, ty, tz, ox, oy, oz,
        ) ?: return
        tint(level, particle)
    }

    /** Shipyard coordinates. The particle follows [ship]. */
    fun spawnOnShip(
        level: ClientLevel, ship: ClientShip,
        tx: Double, ty: Double, tz: Double, ox: Double, oy: Double, oz: Double,
    ) {
        val particle = ShipBoundPortalParticle(level, ship, tx, ty, tz, ox, oy, oz)
        tint(level, particle)
        Minecraft.getInstance().particleEngine.add(particle)
    }

    private fun tint(level: ClientLevel, particle: Particle) {
        val t = level.random.nextFloat()
        particle.setColor(
            DARK_R + (LIGHT_R - DARK_R) * t,
            DARK_G + (LIGHT_G - DARK_G) * t,
            DARK_B + (LIGHT_B - DARK_B) * t,
        )
    }
}
