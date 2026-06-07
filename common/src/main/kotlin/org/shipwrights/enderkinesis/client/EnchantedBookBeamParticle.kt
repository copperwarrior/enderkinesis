package org.shipwrights.enderkinesis.client

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.Particle
import net.minecraft.client.particle.ParticleProvider
import net.minecraft.client.particle.ParticleRenderType
import net.minecraft.client.particle.SpriteSet
import net.minecraft.client.particle.TextureSheetParticle
import net.minecraft.core.particles.SimpleParticleType

/**
 * Reusable bezier-bound enchant-glyph beam particle.
 *
 * Each particle is bound to a [BeamPath] in [BeamRegistry] by a numeric `pathId`, plus a
 * parameter `t ∈ [0, 1]` along the curve and a fixed angular offset `theta` for the cone-radius
 * offset. Every tick the particle re-evaluates the *current* bezier (start, control, end) at
 * its current `t`, advances `t` by the path's `flowRate`, and re-derives its world position
 * with the cone-radius offset applied perpendicular to the bezier tangent.
 *
 * Encoding in [SimpleParticleType] spawn params: `(xd, yd, zd) = (t, theta, pathId-as-double)`.
 * `pathId` is round-tripped through a double, so callers should keep ids ≤ 2^53 — see
 * [BeamRegistry.allocate].
 *
 * If the bound path is removed from the registry (or its fields are cleared), the next tick
 * removes the particle so the beam snaps off cleanly.
 */
class EnchantedBookBeamParticle(
    level: ClientLevel,
    x: Double, y: Double, z: Double,
    private var t: Double,
    private val theta: Double,
    private val pathId: Long,
) : TextureSheetParticle(level, x, y, z) {

    init {
        // Vanilla ETP-style brightness wash, optionally re-tinted to the path's accent colour
        // (each particle independently rolls `accentChance` once; the choice sticks for the
        // particle's whole lifetime so glyphs don't flicker between colours).
        val j = random.nextFloat() * 0.6f + 0.4f
        val path = BeamRegistry.get(pathId)
        val accent = path?.accentColor
        if (accent != null && random.nextDouble() < path.accentChance) {
            val ar = ((accent ushr 16) and 0xFF) / 255f
            val ag = ((accent ushr 8) and 0xFF) / 255f
            val ab = (accent and 0xFF) / 255f
            rCol = ar * j
            gCol = ag * j
            bCol = ab * j
        } else {
            rCol = j * 0.9f
            gCol = j * 0.9f
            bCol = j
        }
        // Per-particle alpha is locked at construction (the path's alpha is sampled once);
        // the particle keeps that alpha for its whole lifetime so glyphs don't pop in/out of
        // visibility if the path is reconfigured mid-flight.
        setAlpha(path?.alpha ?: DEFAULT_ALPHA)
        // Halve the default TextureSheetParticle quadSize (0.1) so the SGA glyphs read as a
        // fine stream of runes rather than a thick beam-of-letters.
        scale(GLYPH_SCALE)
        // Lifetime is a hard cap; the usual exit path is `t > 1.0` (reached the target end)
        // or the path being cleared mid-flight.
        lifetime = (Math.random() * 6.0 + 8.0).toInt()
        hasPhysics = false
        // Snap to current bezier position so xo/yo/zo start sensible — without this the first
        // frame interpolates from the constructor-supplied (x,y,z) to the bezier point.
        updateBoundPosition()
        xo = this.x; yo = this.y; zo = this.z
    }

    override fun getRenderType(): ParticleRenderType =
        ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT

    override fun tick() {
        xo = x; yo = y; zo = z
        val path = BeamRegistry.get(pathId)
        // Path gone or cleared → beam ended; vanish immediately rather than freezing in place.
        if (path?.start == null) {
            remove()
            return
        }
        if (age++ >= lifetime) {
            remove()
            return
        }
        t += path.flowRate
        if (t > 1.0) {
            remove()
            return
        }
        updateBoundPosition()
    }

    /** Re-evaluate the bezier at the current `t` and place this particle at the cone-offset
     *  point on the path. Called from [tick] every game tick and once from [init]. */
    private fun updateBoundPosition() {
        val path = BeamRegistry.get(pathId) ?: return
        val start = path.start ?: return
        val control = path.control ?: return
        val end = path.end ?: return
        val u = 1.0 - t
        // B(t)
        val px = u * u * start.x + 2.0 * u * t * control.x + t * t * end.x
        val py = u * u * start.y + 2.0 * u * t * control.y + t * t * end.y
        val pz = u * u * start.z + 2.0 * u * t * control.z + t * t * end.z
        // B'(t)
        val txd = 2.0 * u * (control.x - start.x) + 2.0 * t * (end.x - control.x)
        val tyd = 2.0 * u * (control.y - start.y) + 2.0 * t * (end.y - control.y)
        val tzd = 2.0 * u * (control.z - start.z) + 2.0 * t * (end.z - control.z)
        val tanLen = Math.sqrt(txd * txd + tyd * tyd + tzd * tzd)
        if (tanLen < 1e-6) {
            this.x = px; this.y = py; this.z = pz
            return
        }
        // Normalised tangent and an orthonormal perpendicular basis.
        val axX = txd / tanLen
        val axY = tyd / tanLen
        val axZ = tzd / tanLen
        // Choose a helper vector not collinear with the tangent so the first cross product is
        // non-degenerate.
        val helperX: Double
        val helperY: Double
        val helperZ: Double
        if (Math.abs(axY) < 0.95) { helperX = 0.0; helperY = 1.0; helperZ = 0.0 }
        else                       { helperX = 1.0; helperY = 0.0; helperZ = 0.0 }
        var uX = axY * helperZ - axZ * helperY
        var uY = axZ * helperX - axX * helperZ
        var uZ = axX * helperY - axY * helperX
        val uLen = Math.sqrt(uX * uX + uY * uY + uZ * uZ)
        uX /= uLen; uY /= uLen; uZ /= uLen
        val vX = axY * uZ - axZ * uY
        val vY = axZ * uX - axX * uZ
        val vZ = axX * uY - axY * uX
        // Radius profile along the beam — selected per-path so the Wylland Tome stays a
        // sweeping cone while orb beams pinch in the middle (hourglass, equally significant
        // at both ends).
        val r = when (path.profile) {
            BeamProfile.LINEAR_TAPER -> path.radius * (1.0 - t)
            // 4·(t−0.5)² is 1 at the endpoints and 0 at the midpoint — symmetric hourglass.
            BeamProfile.HOURGLASS -> {
                val d = t - 0.5
                path.radius * (4.0 * d * d)
            }
        }
        val cosT = Math.cos(theta) * r
        val sinT = Math.sin(theta) * r
        this.x = px + uX * cosT + vX * sinT
        this.y = py + uY * cosT + vY * sinT
        this.z = pz + uZ * cosT + vZ * sinT
    }

    companion object {
        /** Fallback alpha when a particle is constructed before its path is registered. */
        const val DEFAULT_ALPHA = 0.4f

        /** Multiplier on the default [TextureSheetParticle] quadSize (0.1). 0.5 → glyphs
         *  half the size of vanilla enchant-table particles, so the beam reads as a fine
         *  stream of runes rather than a thick text-on-text smear. */
        private const val GLYPH_SCALE = 0.5f
    }

    class Provider(private val sprites: SpriteSet) : ParticleProvider<SimpleParticleType> {
        override fun createParticle(
            type: SimpleParticleType, level: ClientLevel,
            x: Double, y: Double, z: Double,
            xd: Double, yd: Double, zd: Double,
        ): Particle {
            // Encoding: xd = initial t, yd = theta (radians), zd = pathId.
            val p = EnchantedBookBeamParticle(level, x, y, z, xd, yd, Math.round(zd))
            p.pickSprite(sprites)
            return p
        }
    }
}
