package org.shipwrights.enderkinesis.client

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.Particle
import net.minecraft.client.particle.ParticleProvider
import net.minecraft.client.particle.ParticleRenderType
import net.minecraft.client.particle.SpriteSet
import net.minecraft.client.particle.TextureSheetParticle
import net.minecraft.client.renderer.LightTexture
import net.minecraft.core.particles.SimpleParticleType
import net.minecraft.world.entity.player.Player

/** Staff-of-Sundering SUNDER glyph-ring particle. One particle per
 *  angular slot (six total — S, U, N, D, E, R); each particle is bound to
 *  its slot index and re-derives its world position every tick from the
 *  **local player's current beam tip** (same orbit center the rest of the
 *  staff's visuals use).
 *
 *  Because position is recomputed each tick rather than baked at spawn,
 *  the letter ORBITS the beam axis with `gameTime` — it stays at its slot
 *  as the ring rotates, instead of being a stationary blob at the spawn
 *  angle. New particles spawn every tick on top of older ones at the same
 *  current slot position, so the additive lifetime envelope just keeps a
 *  bright, readable letter at each slot rather than smearing into a trail.
 *
 *  Only spawned for the local player (see [SunderingClient.spawnGlyphRing])
 *  because the particle's tick reads `Minecraft.getInstance().player` for
 *  its orbit anchor. Remote-player glyph rings would track the wrong beam
 *  tip. */
class SunderingGlyphParticle(
    level: ClientLevel,
    x: Double, y: Double, z: Double,
    private val slotIndex: Int,
) : TextureSheetParticle(level, x, y, z) {

    init {
        // Full-bright untinted — SGA atlas already carries the visible glyph
        // shape; we want the ring readable as ink, not coloured fire.
        rCol = 1f; gCol = 1f; bCol = 1f
        setAlpha(0f)                                           // fade in via tick()
        xd = 0.0; yd = 0.0; zd = 0.0

        quadSize *= 0.4f                                       // small glyph readable at ring radius
        // Self-managed lifetime — the particle removes itself in [tick]
        // when the local player stops wielding the staff, so one spawn per
        // glyph slot survives the whole wielding session. Set to a sentinel
        // far past any realistic real-world session length so the vanilla
        // `age >= lifetime` auto-remove never triggers.
        lifetime = Int.MAX_VALUE
        gravity = 0f
        hasPhysics = false
    }

    override fun tick() {
        xo = x; yo = y; zo = z
        val player = Minecraft.getInstance().player
        if (player == null || !SunderingClient.isLocalWieldingSundering(player)) {
            remove()
            return
        }
        // Snap to the live slot position — the ring rotates as gameTime
        // advances inside [SunderingClient.glyphRingPosition].
        val pos = SunderingClient.glyphRingPosition(player, slotIndex, 1f)
        setPos(pos.x, pos.y, pos.z)

        // Fade in over the first FADE_IN_TICKS, then hold at full alpha
        // for the rest of the wielding session.
        val alphaTarget = (age.toFloat() / FADE_IN_TICKS).coerceIn(0f, 1f)
        setAlpha(alphaTarget)
        age++
    }

    /** Emissive — the glyph should read through any backdrop. */
    override fun getLightColor(partialTick: Float): Int = LightTexture.FULL_BRIGHT

    override fun getRenderType(): ParticleRenderType =
        ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT

    class Provider(private val sprites: SpriteSet) : ParticleProvider<SimpleParticleType> {
        override fun createParticle(
            type: SimpleParticleType, level: ClientLevel,
            x: Double, y: Double, z: Double,
            vx: Double, vy: Double, vz: Double,
        ): Particle {
            // `vx` smuggles the slot index (0..5 → SUNDER position);
            // `vy` smuggles the SGA sprite index (0..25 → letter).
            val slotIdx = vx.toInt().coerceIn(0, 5)
            val letterIdx = vy.toInt().coerceIn(0, 25)
            val p = SunderingGlyphParticle(level, x, y, z, slotIdx)
            // `SpriteSet.get(frame, totalFrames)` picks
            // `floor(spriteCount × frame / totalFrames)`; with
            // totalFrames=26 and frame=letterIdx, this returns exactly the
            // sga_<letter> sprite in alphabetical order.
            p.setSprite(sprites.get(letterIdx, 26))
            return p
        }
    }

    companion object {
        /** Ticks the spawn-time alpha ramp takes to reach full opacity. After
         *  this the glyph holds full alpha for the rest of the session. */
        private const val FADE_IN_TICKS: Int = 8
    }
}
