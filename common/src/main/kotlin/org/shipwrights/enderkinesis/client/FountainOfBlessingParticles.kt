package org.shipwrights.enderkinesis.client

import net.minecraft.client.Minecraft
import net.minecraft.client.particle.Particle
import net.minecraft.client.renderer.BiomeColors
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.world.level.Level
import org.shipwrights.enderkinesis.blockentity.FountainOfBlessingBlockEntity

/** Fountain particles. Water particles take the pool's colour, as the mesh does. Client thread only. */
object FountainOfBlessingParticles {

    var red = 1f
        private set
    var green = 1f
        private set
    var blue = 1f
        private set

    /** Vanilla water tint under the Wohlon murk; WohlonWaterMurk's MAX_MURK is the same share. */
    fun updateWaterColour(level: Level, pos: BlockPos) {
        val tint = BiomeColors.getAverageWaterColor(level, pos)
        red = murked(tint shr 16, WohlonAtmosphere.murkRed())
        green = murked(tint shr 8, WohlonAtmosphere.murkGreen())
        blue = murked(tint, WohlonAtmosphere.murkBlue())
    }

    private fun murked(channel: Int, murk: Float): Float {
        val water = (channel and 0xFF) / 255f
        return (water + (murk - water) * MURK_MIX).coerceIn(0f, 1f)
    }

    fun tick(be: FountainOfBlessingBlockEntity, level: Level) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val pos = be.blockPos
        if (player.distanceToSqr(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5) > MAX_DISTANCE_SQ) return
        updateWaterColour(level, pos)
        val rng = level.random
        val cx = pos.x + 0.5
        val cz = pos.z + 0.5
        val vigour = 1f + be.charge(level)
        val depth = be.surfaceY - be.springY
        if (rng.nextFloat() < BUBBLE_CHANCE * vigour) {
            water(
                ParticleTypes.BUBBLE_COLUMN_UP,
                cx + (rng.nextDouble() - 0.5) * 1.2, be.springY + rng.nextDouble() * depth, cz + (rng.nextDouble() - 0.5) * 1.2,
                0.0, 0.0, 0.0, rng.nextInt(3),
            )
        }
        if (depth <= 0.5) return
        if (rng.nextFloat() < POP_CHANCE * vigour) {
            val a = rng.nextDouble() * Math.PI * 2.0
            val r = rng.nextDouble() * SURFACE_SPREAD
            water(ParticleTypes.BUBBLE_POP, cx + Math.cos(a) * r, be.surfaceY, cz + Math.sin(a) * r, 0.0, 0.0, 0.0, rng.nextInt(3))
        }
        // Spray thrown up and out of the boil.
        repeat((SPRAY_PER_TICK * vigour).toInt()) {
            val a = rng.nextDouble() * Math.PI * 2.0
            val r = rng.nextDouble() * SPRAY_SPREAD
            val out = SPRAY_OUT_MIN + rng.nextDouble() * SPRAY_OUT_SPREAD
            val up = SPRAY_UP_MIN + rng.nextDouble() * SPRAY_UP_SPREAD
            water(
                ParticleTypes.SPLASH, cx + Math.cos(a) * r, be.surfaceY + 0.3, cz + Math.sin(a) * r,
                0.0, 0.0, 0.0, rng.nextInt(3),
            )?.setParticleSpeed(Math.cos(a) * out, up * vigour, Math.sin(a) * out)
        }
        // Water rains down from the heart into the fountain.
        if (!be.heartY.isNaN()) {
            repeat(HEART_RAIN_PER_TICK) {
                val a = rng.nextDouble() * Math.PI * 2.0
                val r = Math.sqrt(rng.nextDouble()) * HEART_RAIN_SPREAD
                water(
                    ParticleTypes.FALLING_WATER, cx + Math.cos(a) * r, be.heartY - 0.1, cz + Math.sin(a) * r,
                    0.0, 0.0, 0.0, rng.nextInt(3),
                )
            }
        }
        if (rng.nextFloat() < GLOW_CHANCE * vigour) {
            level.addParticle(
                ParticleTypes.GLOW,
                cx + (rng.nextDouble() - 0.5) * 2.0, be.surfaceY + 0.2, cz + (rng.nextDouble() - 0.5) * 2.0,
                0.0, 0.04, 0.0,
            )
        }
    }

    fun burst(be: FountainOfBlessingBlockEntity, level: Level) {
        updateWaterColour(level, be.blockPos)
        val rng = level.random
        val cx = be.blockPos.x + 0.5
        val cz = be.blockPos.z + 0.5
        repeat(BURST_SPLASHES) {
            val a = rng.nextDouble() * Math.PI * 2.0
            val s = 0.1 + rng.nextDouble() * 0.25
            water(ParticleTypes.SPLASH, cx, be.surfaceY + 0.3, cz, 0.0, 0.0, 0.0, 1)
                ?.setParticleSpeed(Math.cos(a) * s, SPRAY_UP_MIN + rng.nextDouble() * SPRAY_UP_SPREAD * 2.0, Math.sin(a) * s)
        }
        repeat(BURST_GLOWS) {
            level.addParticle(
                ParticleTypes.GLOW,
                cx + (rng.nextDouble() - 0.5) * 2.0, be.surfaceY + rng.nextDouble() * 1.5, cz + (rng.nextDouble() - 0.5) * 2.0,
                0.0, 0.05 + rng.nextDouble() * 0.08, 0.0,
            )
        }
    }

    // ParticleEngine.createParticle gives the particle back, so it can be tinted; LevelRenderer.addParticle does not.
    // A vanilla splash only hops 0.1 up from its arguments, so spray sets its speed on the returned particle.
    private fun water(
        type: ParticleOptions, x: Double, y: Double, z: Double, dx: Double, dy: Double, dz: Double, roll: Int,
    ): Particle? {
        val mc = Minecraft.getInstance()
        if (!WohlonnogondoniaFireflies.particleSettingAllows(mc, roll)) return null
        return mc.particleEngine.createParticle(type, x, y, z, dx, dy, dz)?.also { it.setColor(red, green, blue) }
    }

    private const val MURK_MIX = 0.8f
    private const val MAX_DISTANCE_SQ = 32.0 * 32.0
    private const val HEART_RAIN_PER_TICK = 2
    private const val HEART_RAIN_SPREAD = 0.7
    private const val BUBBLE_CHANCE = 0.3f
    private const val POP_CHANCE = 0.35f
    private const val SURFACE_SPREAD = 1.4
    private const val SPRAY_PER_TICK = 3
    private const val SPRAY_SPREAD = 0.6
    private const val SPRAY_OUT_MIN = 0.05
    private const val SPRAY_OUT_SPREAD = 0.15
    private const val SPRAY_UP_MIN = 0.18
    private const val SPRAY_UP_SPREAD = 0.22
    private const val GLOW_CHANCE = 0.2f
    private const val BURST_SPLASHES = 50
    private const val BURST_GLOWS = 24
}
