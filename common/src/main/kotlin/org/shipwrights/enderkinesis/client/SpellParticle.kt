package org.shipwrights.enderkinesis.client

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.Particle
import net.minecraft.client.particle.ParticleProvider
import net.minecraft.client.particle.ParticleRenderType
import net.minecraft.client.particle.SpriteSet
import net.minecraft.client.particle.TextureSheetParticle
import net.minecraft.client.renderer.LightTexture
import net.minecraft.core.particles.SimpleParticleType
import org.shipwrights.enderkinesis.spell.Element

/**
 * The Mystic Scroll particles, drawn with vanilla particle textures (named in each one's
 * JSON under `assets/enderkinesis/particles`) tinted per [Kind]. Spawned server-side; the
 * velocity sent is the starting motion.
 */
class SpellParticle(
    level: ClientLevel, x: Double, y: Double, z: Double,
    dx: Double, dy: Double, dz: Double,
    private val kind: Kind, private val sprites: SpriteSet,
) : TextureSheetParticle(level, x, y, z, dx, dy, dz) {

    /** How each particle looks and moves. [animated] runs through the sprite set over its life. */
    enum class Kind(
        val rgb: Int, val size: Float, val life: Int, val gravity: Float,
        val glows: Boolean, val animated: Boolean,
    ) {
        /** Crystallized's shatter: bright shards that fall. */
        CRYSTAL_SHARD(Element.PURITY.color, 0.18f, 20, 0.6f, glows = true, animated = false),
        /** Rot: drifting green spores. */
        ROT_SPORE(Element.POISON.color, 0.12f, 40, -0.02f, glows = false, animated = false),
        /** Bleeding and Blood's drain: drops that fall. */
        BLOOD_DRIP(Element.BLOOD.color, 0.15f, 30, 0.9f, glows = false, animated = false),
        /** Void: motes drifting into the dark. */
        VOID_MOTE(Element.VOID.color, 0.14f, 30, 0f, glows = true, animated = true),
        /** The light shockwave of beams that loop: one expanding ring. */
        SHOCKWAVE_RING(0xF4F0FF, 3.0f, 16, 0f, glows = true, animated = true),
    }

    init {
        setSpriteFromAge(sprites)
        if (!kind.animated) pickSprite(sprites)
        rCol = ((kind.rgb shr 16) and 0xFF) / 255f
        gCol = ((kind.rgb shr 8) and 0xFF) / 255f
        bCol = (kind.rgb and 0xFF) / 255f
        quadSize = kind.size * (0.8f + random.nextFloat() * 0.4f)
        lifetime = (kind.life * (0.8f + random.nextFloat() * 0.4f)).toInt()
        gravity = kind.gravity
        hasPhysics = kind.gravity > 0f
        friction = 0.96f
        xd = dx; yd = dy; zd = dz
    }

    override fun tick() {
        super.tick()
        if (kind.animated) setSpriteFromAge(sprites)
        alpha = 1f - (age.toFloat() / lifetime).coerceIn(0f, 1f) * 0.8f
    }

    override fun getRenderType(): ParticleRenderType = ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT

    override fun getLightColor(partialTick: Float): Int =
        if (kind.glows) LightTexture.FULL_BRIGHT else super.getLightColor(partialTick)

    class Provider(private val sprites: SpriteSet, private val kind: Kind) : ParticleProvider<SimpleParticleType> {
        override fun createParticle(
            type: SimpleParticleType, level: ClientLevel,
            x: Double, y: Double, z: Double, dx: Double, dy: Double, dz: Double,
        ): Particle = SpellParticle(level, x, y, z, dx, dy, dz, kind, sprites)
    }
}
