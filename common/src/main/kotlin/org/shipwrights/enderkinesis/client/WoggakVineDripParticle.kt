package org.shipwrights.enderkinesis.client

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.Particle
import net.minecraft.client.particle.ParticleProvider
import net.minecraft.client.particle.ParticleRenderType
import net.minecraft.client.particle.SpriteSet
import net.minecraft.client.particle.TextureSheetParticle
import net.minecraft.client.renderer.BiomeColors
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.particles.SimpleParticleType

/**
 * A vanilla-timed water drip that hangs, falls and splashes, with the biome water colour on every stage.
 * Vanilla hard-codes its drip blue and does not pass the colour on to the falling drop.
 */
class WoggakVineDripParticle(
    level: ClientLevel,
    x: Double, y: Double, z: Double,
    private val sprites: SpriteSet,
) : TextureSheetParticle(level, x, y, z) {
    private val scratch = BlockPos.MutableBlockPos()
    private var falling = false
    private var life = HANG_TICKS

    init {
        setSize(HITBOX, HITBOX)
        gravity = GRAVITY * HANG_GRAVITY_SCALE
        val water = BiomeColors.getAverageWaterColor(level, BlockPos.containing(x, y, z))
        setColor(((water shr 16) and 0xFF) / 255f, ((water shr 8) and 0xFF) / 255f, (water and 0xFF) / 255f)
        setSprite(sprites.get(0, 1))
    }

    override fun tick() {
        xo = x; yo = y; zo = z
        if (life-- <= 0) {
            if (!falling) startFall() else {
                remove()
                return
            }
        }
        yd -= gravity
        move(xd, yd, zd)
        if (!falling) {
            xd *= HANG_DAMP; yd *= HANG_DAMP; zd *= HANG_DAMP
        } else if (onGround) {
            remove()
            splash()
            return
        }
        xd *= DRAG; yd *= DRAG; zd *= DRAG
        scratch.set(x, y, z)
        val fluid = level.getFluidState(scratch)
        if (!fluid.isEmpty && fluid.getHeight(level, scratch) + scratch.y > y) remove()
    }

    private fun startFall() {
        falling = true
        gravity = GRAVITY
        life = (FALL_TICKS / (random.nextDouble() * 0.8 + 0.2)).toInt()
        setSprite(sprites.get(1, 1))
    }

    private fun splash() {
        val engine = Minecraft.getInstance().particleEngine
        val particle = engine.createParticle(ParticleTypes.SPLASH, x, y, z, 0.0, 0.0, 0.0) ?: return
        particle.setColor(rCol, gCol, bCol)
        engine.add(particle)
    }

    override fun getRenderType(): ParticleRenderType = ParticleRenderType.PARTICLE_SHEET_OPAQUE

    class Provider(private val sprites: SpriteSet) : ParticleProvider<SimpleParticleType> {
        override fun createParticle(
            type: SimpleParticleType, level: ClientLevel,
            x: Double, y: Double, z: Double,
            xd: Double, yd: Double, zd: Double,
        ): Particle = WoggakVineDripParticle(level, x, y, z, sprites)
    }

    private companion object {
        const val HITBOX = 0.01f
        const val GRAVITY = 0.06f
        const val HANG_GRAVITY_SCALE = 0.02f
        const val HANG_DAMP = 0.02
        const val HANG_TICKS = 40
        const val FALL_TICKS = 64.0
        const val DRAG = 0.98
    }
}
