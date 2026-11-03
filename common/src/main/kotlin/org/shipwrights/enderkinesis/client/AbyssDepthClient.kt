package org.shipwrights.enderkinesis.client

import net.minecraft.world.entity.player.Player
import org.shipwrights.enderkinesis.item.IncantationOfTheAbyssItem
import org.shipwrights.enderkinesis.registry.EKParticles
import org.shipwrights.enderkinesis.spell.SpellMote
import kotlin.math.sqrt

/** Ygann particles of the Incantation of the Abyss: writhing-mass dust that sinks into the sphere, eyes that open in
 *  it, and void motes that glint along the [AbyssTentacles]. */
object AbyssDepthClient {
    private const val DUST_PER_TICK = 3
    private const val EYE_CHANCE = 0.12f
    private const val TENTACLE_MOTE_CHANCE = 0.5f
    private const val MOTE_RISE = 0.01
    private const val DUST_SPEED = 0.12

    /** Dust starts in the outer shell of the sphere, between this share of the radius and the rim. */
    private const val DUST_INNER = 0.75

    /** Eyes open inside this share of the radius, where the ink is thick. */
    private const val EYE_OUTER = 0.6

    fun spawn(player: Player) {
        val level = player.level()
        val rnd = level.random
        val centre = IncantationOfTheAbyssItem.focus(level, player, 1f)
        val radius = IncantationOfTheAbyssItem.RADIUS

        fun direction(): DoubleArray {
            // A normal-distributed vector has a uniform direction.
            val x = rnd.nextGaussian()
            val y = rnd.nextGaussian()
            val z = rnd.nextGaussian()
            val len = sqrt(x * x + y * y + z * z).coerceAtLeast(1.0e-6)
            return doubleArrayOf(x / len, y / len, z / len)
        }

        repeat(DUST_PER_TICK) {
            val d = direction()
            val r = radius * (DUST_INNER + rnd.nextDouble() * (1.0 - DUST_INNER))
            level.addParticle(
                EKParticles.mote(SpellMote.MASS_DUST),
                centre.x + d[0] * r, centre.y + d[1] * r, centre.z + d[2] * r,
                -d[0] * DUST_SPEED, -d[1] * DUST_SPEED, -d[2] * DUST_SPEED,
            )
        }
        if (rnd.nextFloat() < EYE_CHANCE) {
            val d = direction()
            val r = radius * EYE_OUTER * rnd.nextDouble()
            level.addParticle(
                EKParticles.mote(SpellMote.YGANN_EYE),
                centre.x + d[0] * r, centre.y + d[1] * r, centre.z + d[2] * r,
                0.0, 0.0, 0.0,
            )
        }

        if (rnd.nextFloat() < TENTACLE_MOTE_CHANCE) {
            val palm = IncantationOfWindsClient.castingPalm(player, 1f).position
            val cloud = AbyssDepthVolume.centreOf(player.id) ?: centre
            val p = AbyssTentacles.point(rnd.nextInt(AbyssTentacles.COUNT), rnd.nextDouble(), player.ticksUsingItem.toFloat(), palm, cloud)
            level.addParticle(EKParticles.mote(SpellMote.VOID_MOTE), p.x, p.y, p.z, 0.0, MOTE_RISE, 0.0)
        }
    }
}
