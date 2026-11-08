package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.PortalParticle
import net.minecraft.client.renderer.texture.TextureAtlas
import net.minecraft.resources.ResourceLocation
import org.joml.Matrix4dc
import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.ClientShip
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.core.api.util.GameTickOnly

/** A PortalParticle with its start and target in shipyard space, so it moves with the ship. */
@OptIn(GameTickOnly::class)
class ShipBoundPortalParticle(
    level: ClientLevel,
    private val ship: ClientShip,
    private val targetX: Double, private val targetY: Double, private val targetZ: Double,
    private val offsetX: Double, private val offsetY: Double, private val offsetZ: Double,
) : PortalParticle(level, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0) {

    private val scratch = Vector3d()

    init {
        // Same sprites as minecraft:particles/portal.json. getTextureAtlas() only knows ModelManager atlases, not particles.
        val particles = Minecraft.getInstance().textureManager.getTexture(PARTICLE_ATLAS) as TextureAtlas
        setSprite(particles.getSprite(GENERIC_SPRITES[random.nextInt(GENERIC_SPRITES.size)]))
        place(0f, ship.transform.shipToWorld)
        xo = x; yo = y; zo = z
    }

    override fun tick() {
        xo = x; yo = y; zo = z
        if (age++ >= lifetime || level.shipObjectWorld.loadedShips.getById(ship.id) !== ship) {
            remove()
            return
        }
        // The tick position is only for the light sample. render() places the quad.
        place(age.toFloat(), ship.transform.shipToWorld)
    }

    override fun render(buffer: VertexConsumer, camera: Camera, partialTicks: Float) {
        // x is the position at `age`, xo at `age - 1`.
        place((age - 1 + partialTicks).coerceAtLeast(0f), ship.renderTransform.shipToWorld)
        xo = x; yo = y; zo = z
        super.render(buffer, camera, partialTicks)
    }

    /** Vanilla PortalParticle.tick() path, in the ship frame. */
    private fun place(age: Float, shipToWorld: Matrix4dc) {
        val progress = age / lifetime
        val f = 1f - (-progress + progress * progress * 2f)
        scratch.set(
            targetX + offsetX * f,
            targetY + offsetY * f + (1f - progress),
            targetZ + offsetZ * f,
        )
        shipToWorld.transformPosition(scratch)
        x = scratch.x; y = scratch.y; z = scratch.z
    }

    private companion object {
        /** Sprites change on a resource reload, so cache only their names. */
        val GENERIC_SPRITES: List<ResourceLocation> = List(8) { ResourceLocation("minecraft", "generic_$it") }
    }
}
