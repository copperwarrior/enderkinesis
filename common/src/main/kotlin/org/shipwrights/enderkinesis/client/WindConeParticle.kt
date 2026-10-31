package org.shipwrights.enderkinesis.client

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.Particle
import net.minecraft.client.particle.ParticleProvider
import net.minecraft.client.particle.ParticleRenderType
import net.minecraft.client.particle.SpriteSet
import net.minecraft.client.particle.TextureSheetParticle
import net.minecraft.client.renderer.LightTexture
import net.minecraft.core.particles.SimpleParticleType
import net.minecraft.util.Mth
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Incantation of Winds stream wisp. The spawn "velocity" is the full travel vector from the casting
 * hand to the wisp's end point in the cone; the wisp eases out along it, so the stream bursts from
 * the hand and slows as it spreads.
 */
class WindConeParticle(
    level: ClientLevel,
    x: Double, y: Double, z: Double,
    private val travelX: Double, private val travelY: Double, private val travelZ: Double,
    private val sprites: SpriteSet,
) : TextureSheetParticle(level, x, y, z) {

    private val startX = x
    private val startY = y
    private val startZ = z
    private val reach = sqrt(travelX * travelX + travelY * travelY + travelZ * travelZ)
    private val rollSpeed = (0.05f + random.nextFloat() * 0.10f) * (if (random.nextBoolean()) 1f else -1f)

    private var driftX = 0.0
    private var driftY = 0.0
    private var driftZ = 0.0
    private var driftVx = 0.0
    private var driftVy = 0.0
    private var driftVz = 0.0
    private var launchX = 0.0
    private var launchY = 0.0
    private var launchZ = 0.0

    init {
        rCol = 1.00f
        gCol = 0.92f
        bCol = 0.55f
        gravity = 0.0f
        hasPhysics = false
        xd = 0.0
        yd = 0.0
        zd = 0.0
        lifetime = 16 + random.nextInt(7)
        quadSize = START_SIZE
        roll = random.nextFloat() * Mth.TWO_PI
        oRoll = roll
        alpha = 0f
        setSpriteFromIndex(0)
    }

    /** A start velocity, blocks/tick, that dies away, so the wisp leaves the palm along its normal and then joins the stream. */
    fun launch(vx: Double, vy: Double, vz: Double) {
        launchX = vx
        launchY = vy
        launchZ = vz
    }

    override fun tick() {
        xo = x
        yo = y
        zo = z
        oRoll = roll
        if (age++ >= lifetime) {
            remove()
            return
        }
        val f = age.toFloat() / lifetime
        val inv = 1.0 - f
        val travelled = 1.0 - inv * inv

        // The turbulence grows with distance, so the stream is tight at the hand and ragged at the far end.
        val kick = TURBULENCE * travelled
        driftVx = driftVx * DRIFT_DAMPING + (random.nextDouble() - 0.5) * kick
        driftVy = driftVy * DRIFT_DAMPING + (random.nextDouble() - 0.5) * kick
        driftVz = driftVz * DRIFT_DAMPING + (random.nextDouble() - 0.5) * kick
        driftX += driftVx
        driftY += driftVy
        driftZ += driftVz
        val launched = age * exp(-age / LAUNCH_DECAY_TICKS)
        setPos(
            startX + travelX * travelled + driftX + launchX * launched,
            startY + travelY * travelled + driftY + launchY * launched,
            startZ + travelZ * travelled + driftZ + launchZ * launched,
        )

        roll += rollSpeed
        setSpriteFromIndex((f * FRAMES).toInt().coerceIn(0, LAST_FRAME))
        quadSize = (START_SIZE + reach * travelled * GROWTH).toFloat()
        val fadeIn = (f / FADE_IN).coerceAtMost(1f)
        alpha = ALPHA * fadeIn * inv.toFloat() * inv.toFloat()
    }

    private fun setSpriteFromIndex(index: Int) {
        // `SpriteSet.get(age, lifetime)` gives index `age * (size - 1) / lifetime`.
        setSprite(sprites.get(index, SPRITE_SET_SIZE - 1))
    }

    override fun getRenderType(): ParticleRenderType = ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT

    override fun getLightColor(partialTick: Float): Int = LightTexture.FULL_BRIGHT

    companion object {
        private const val ALPHA = 0.32f
        private const val START_SIZE = 0.06f

        /** Half-size gained per block travelled; tracks the cone's widening at about a third of its radius. */
        private const val GROWTH = 0.08

        /** Fraction of life a wisp takes to fade in, so it does not pop at the hand. */
        private const val FADE_IN = 0.12f

        private const val TURBULENCE = 0.06
        private const val DRIFT_DAMPING = 0.85
        private const val LAUNCH_DECAY_TICKS = 3.0

        /** Same layout as `sselith_dust.json`; wisps use only the first 8 (smoke) frames. */
        private const val SPRITE_SET_SIZE = 16
        private const val FRAMES = 8
        private const val LAST_FRAME = 7
    }

    class Provider(private val sprites: SpriteSet) : ParticleProvider<SimpleParticleType> {
        override fun createParticle(
            type: SimpleParticleType, level: ClientLevel,
            x: Double, y: Double, z: Double,
            xd: Double, yd: Double, zd: Double,
        ): Particle = WindConeParticle(level, x, y, z, xd, yd, zd, sprites)
    }
}
