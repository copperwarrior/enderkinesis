package org.shipwrights.enderkinesis.client

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.Particle
import net.minecraft.client.particle.ParticleProvider
import net.minecraft.client.particle.SpriteSet
import net.minecraft.core.particles.SimpleParticleType
import net.minecraft.util.Mth
import org.joml.Vector3d
import org.shipwrights.enderkinesis.entity.RaisedSkeletonEntity

class GrandStaffGlyphParticle(
    level: ClientLevel,
    x: Double, y: Double, z: Double,
    private val skeleton: RaisedSkeletonEntity,
) : SselithGlyphParticle(level, x, y, z) {

    private val jitterX = (random.nextDouble() - 0.5) * JITTER
    private val jitterY = (random.nextDouble() - 0.5) * JITTER
    private val jitterZ = (random.nextDouble() - 0.5) * JITTER
    private val point = Vector3d()

    init {
        lifetime = LIFETIME_TICKS
        xd = 0.0
        yd = 0.0
        zd = 0.0
        setAlpha(0f)
    }

    override fun tick() {
        xo = x
        yo = y
        zo = z
        val s = age++.toFloat() / lifetime
        if (age > lifetime || !GrandStaffClient.pointOnTendril(skeleton, s.toDouble(), point)) {
            remove()
            return
        }
        setPos(point.x + jitterX, point.y + jitterY, point.z + jitterZ)
        setAlpha(Mth.sin(s * Mth.PI))
    }

    // The provider reads the skeleton's entity id from the xd argument. SimpleParticleType has no data channel.
    class Provider(private val sprites: SpriteSet) : ParticleProvider<SimpleParticleType> {
        override fun createParticle(
            type: SimpleParticleType, level: ClientLevel,
            x: Double, y: Double, z: Double,
            xd: Double, yd: Double, zd: Double,
        ): Particle? {
            val skeleton = level.getEntity(xd.toInt()) as? RaisedSkeletonEntity ?: return null
            val p = GrandStaffGlyphParticle(level, x, y, z, skeleton)
            p.pickSprite(sprites)
            return p
        }
    }

    private companion object {
        const val LIFETIME_TICKS = 16
        const val JITTER = 0.2
    }
}
