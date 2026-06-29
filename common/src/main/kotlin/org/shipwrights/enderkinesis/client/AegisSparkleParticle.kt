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
import org.shipwrights.enderkinesis.item.AegisBox
import org.shipwrights.enderkinesis.registry.EKItems

/**
 * Tiny glowing sparkle inside the Staff-of-Aegis shield box. Reuses
 * vanilla's `minecraft:glow_*` glow-squid sprite atlas via
 * aegis_sparkle.json for a soft "drop of light" look.
 *
 * **Box-tracking**: AegisClient passes the particle's *local-space offset*
 * inside the box (in the box's body frame: ±WIDTH/2, ±HEIGHT/2, ±DEPTH/2)
 * through the [SpriteSet] call's vx/vy/vz args. Each tick, we look up the
 * local player's current box [AegisBox.Frame] and snap our world position
 * to `centre + axisX·lx + axisY·ly + axisZ·lz`. That keeps the sparkle
 * locked to its slot in the box no matter how the player walks or turns.
 * If the local player stops wielding the staff, the particle removes
 * itself rather than hanging in stale world coordinates.
 */
class AegisSparkleParticle(
    level: ClientLevel,
    x: Double, y: Double, z: Double,
    /** Local-space offset within the box's frame at spawn time. */
    private val localX: Double,
    private val localY: Double,
    private val localZ: Double,
    sprites: SpriteSet,
) : TextureSheetParticle(level, x, y, z) {

    init {
        pickSprite(sprites)
        xd = 0.0; yd = 0.0; zd = 0.0
        gravity = 0f
        friction = 1f
        hasPhysics = false
        scale(0.4f)
        lifetime = 25 + random.nextInt(15)                 // ~1.25 – 2.0 s
        rCol = 1f; gCol = 1f; bCol = 1f
        alpha = 0.9f
    }

    override fun tick() {
        xo = x; yo = y; zo = z
        if (age++ >= lifetime) {
            remove()
            return
        }
        // Box-track: snap to local-offset position inside the LOCAL player's
        // current box frame. Particles spawned for remote players (rare,
        // multiplayer only) get killed here — that's intentional, the local
        // client's box is the only one we can track accurately.
        val player = Minecraft.getInstance().player
        if (player == null || !isLocalWielding(player)) {
            remove()
            return
        }
        val frame = AegisBox.forPlayer(player, 1f)
        val newX = frame.center.x + frame.axisX.x * localX + frame.axisY.x * localY + frame.axisZ.x * localZ
        val newY = frame.center.y + frame.axisX.y * localX + frame.axisY.y * localY + frame.axisZ.y * localZ
        val newZ = frame.center.z + frame.axisX.z * localX + frame.axisY.z * localY + frame.axisZ.z * localZ
        setPos(newX, newY, newZ)
    }

    private fun isLocalWielding(player: Player): Boolean {
        if (!player.isUsingItem) return false
        return player.useItem.item === EKItems.STAFF_OF_AEGIS.get()
    }

    /** Full-bright: the shield glows, the sparkles should too. */
    override fun getLightColor(partialTick: Float): Int = LightTexture.FULL_BRIGHT

    override fun getRenderType(): ParticleRenderType = ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT

    class Provider(private val sprites: SpriteSet) : ParticleProvider<SimpleParticleType> {
        override fun createParticle(
            type: SimpleParticleType, level: ClientLevel,
            x: Double, y: Double, z: Double,
            vx: Double, vy: Double, vz: Double,
        ): Particle = AegisSparkleParticle(level, x, y, z, vx, vy, vz, sprites)
    }
}
