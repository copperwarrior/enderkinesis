package org.shipwrights.enderkinesis.client

import net.minecraft.client.Minecraft
import net.minecraft.client.ParticleStatus
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.Particle
import net.minecraft.client.particle.ParticleProvider
import net.minecraft.client.particle.ParticleRenderType
import net.minecraft.client.particle.SpriteSet
import net.minecraft.client.particle.TextureSheetParticle
import net.minecraft.core.particles.SimpleParticleType
import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.ClientShip
import org.valkyrienskies.core.api.util.GameTickOnly
import org.valkyrienskies.mod.common.getShipManagingPos

/** Full-bright glitter mote that follows a [Path] over its life: the Wohlon portal swirl and the ritual tendrils. */
class WohlonMoteParticle private constructor(
    level: ClientLevel,
    private val path: Path,
    life: Int,
    colour: FloatArray,
    size: Float,
    private val sprites: SpriteSet,
) : TextureSheetParticle(level, 0.0, 0.0, 0.0) {

    /** Position at life fraction `t` in [0, 1], written to `out`. */
    fun interface Path {
        fun at(t: Float, out: Vector3d)
    }

    private val scratch = Vector3d()

    init {
        lifetime = life
        rCol = colour[0]
        gCol = colour[1]
        bCol = colour[2]
        quadSize = size
        hasPhysics = false
        gravity = 0f
        path.at(0f, scratch)
        setPos(scratch.x, scratch.y, scratch.z)
        xo = x; yo = y; zo = z
        alpha = 0f
        setSprite(sprites.get(0, SPRITE_SET_SIZE - 1))
    }

    override fun tick() {
        xo = x; yo = y; zo = z
        if (age++ >= lifetime) {
            remove()
            return
        }
        val t = age.toFloat() / lifetime
        path.at(t, scratch)
        setPos(scratch.x, scratch.y, scratch.z)
        // Fade in fast, out slow, so the end of a path dissolves instead of popping.
        alpha = minOf(t / FADE_IN, 1f, (1f - t) / FADE_OUT)
        setSprite(sprites.get((age / FRAME_TICKS) % SPRITE_SET_SIZE, SPRITE_SET_SIZE - 1))
    }

    override fun getLightColor(partialTick: Float): Int = 0xF000F0

    override fun getRenderType(): ParticleRenderType = ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT

    /** Registered for the sprite set; a command spawn makes a mote that stays where it is. */
    class Provider(sprites: SpriteSet) : ParticleProvider<SimpleParticleType> {
        init {
            spriteSet = sprites
        }

        override fun createParticle(
            type: SimpleParticleType, level: ClientLevel,
            x: Double, y: Double, z: Double,
            xd: Double, yd: Double, zd: Double,
        ): Particle = WohlonMoteParticle(level, { _, out -> out.set(x, y, z) }, 40, DEFAULT_COLOUR, 0.06f, checkNotNull(spriteSet))
    }

    companion object {
        private const val SPRITE_SET_SIZE = 8
        private const val FRAME_TICKS = 3
        private const val FADE_IN = 0.1f
        private const val FADE_OUT = 0.35f
        private val DEFAULT_COLOUR = floatArrayOf(0.557f, 0.831f, 0.784f)

        // Set when the particle provider registers, on the render thread, before any spawn.
        private var spriteSet: SpriteSet? = null

        /** Applies the vanilla particle setting, like a non-forced level particle. Client thread only. */
        @OptIn(GameTickOnly::class)
        fun spawn(level: ClientLevel, path: Path, life: Int, colour: FloatArray, size: Float) {
            val sprites = spriteSet ?: return
            val mc = Minecraft.getInstance()
            when (mc.options.particles().get()) {
                ParticleStatus.MINIMAL -> return
                ParticleStatus.DECREASED -> if (level.random.nextInt(3) == 0) return
                else -> {}
            }
            // Adding to the engine skips VS2's level.addParticle transform, so a ship path maps to world here.
            val start = Vector3d().also { path.at(0f, it) }
            val ship = level.getShipManagingPos(start.x.toInt() shr 4, start.z.toInt() shr 4) as? ClientShip
            val worldPath = if (ship == null) path else Path { t, out ->
                path.at(t, out)
                ship.transform.shipToWorld.transformPosition(out)
            }
            mc.particleEngine.add(WohlonMoteParticle(level, worldPath, life, colour, size, sprites))
        }
    }
}
