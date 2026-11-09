package org.shipwrights.enderkinesis.client

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.ParticleRenderType
import net.minecraft.client.particle.TextureSheetParticle
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.texture.TextureAtlas
import net.minecraft.resources.ResourceLocation
import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.ClientShip
import org.valkyrienskies.mod.common.shipObjectWorld
import kotlin.math.floor

/**
 * A soft tinted dot that keeps its spawn velocity and slows down like the gas flow does. With [dragon] it plays the
 * vanilla dragon-breath frames, drifts longer and grows. It collides with blocks, so it does not pass through walls.
 */
class GasFlowParticle(
    level: ClientLevel, x: Double, y: Double, z: Double,
    vx: Double, vy: Double, vz: Double, r: Float, g: Float, b: Float, private val dragon: Boolean,
    shipId: Long = NO_SHIP,
) : TextureSheetParticle(level, x, y, z, 0.0, 0.0, 0.0) {
    private val atlas: TextureAtlas
    // While on a ship, the particle moves and collides in shipyard coordinates, and [local] is its position there.
    private var shipId = shipId
    private val local = Vector3d()
    private val world = Vector3d()

    init {
        xd = vx; yd = vy; zd = vz
        rCol = r; gCol = g; bCol = b
        gravity = 0f
        friction = if (dragon) DRAGON_FRICTION else FRICTION
        hasPhysics = true
        quadSize = if (dragon) 0.12f + random.nextFloat() * 0.08f else 0.08f + random.nextFloat() * 0.07f
        lifetime = if (dragon) 30 + random.nextInt(20) else 32 + random.nextInt(24)
        // The particle atlas belongs to the texture manager, not the model manager, so getTextureAtlas() returns null.
        @Suppress("DEPRECATION")
        atlas = Minecraft.getInstance().textureManager.getTexture(TextureAtlas.LOCATION_PARTICLES) as TextureAtlas
        setSprite(atlas.getSprite(if (dragon) FRAMES[0] else DOT))
        startInFrame()
    }

    /**
     * Puts a ship particle into shipyard coordinates, and keeps the collision box inside its spawn cell. Vanilla collision
     * ignores a block that the box already overlaps, so a box that starts in a wall passes through it.
     */
    private fun startInFrame() {
        val ship = if (shipId == NO_SHIP) null else level.shipObjectWorld.loadedShips.getById(shipId) as? ClientShip
        if (ship == null) {
            shipId = NO_SHIP
            insetInCell(local.set(x, y, z))
            setPos(local.x, local.y, local.z)
            xo = x; yo = y; zo = z
            return
        }
        // The spawner placed it with the render transform; this undoes that, scale included, so the world speed stays.
        val t = ship.renderTransform
        t.worldToShip.transformPosition(local.set(x, y, z))
        insetInCell(local)
        t.worldToShip.transformDirection(world.set(xd, yd, zd))
        xd = world.x; yd = world.y; zd = world.z
        t.shipToWorld.transformPosition(world.set(local))
        setPos(world.x, world.y, world.z)
        xo = x; yo = y; zo = z
    }

    private fun insetInCell(p: Vector3d) {
        val cx = floor(p.x); val cy = floor(p.y); val cz = floor(p.z)
        p.set(
            p.x.coerceIn(cx + HALF_BOX, cx + 1.0 - HALF_BOX),
            p.y.coerceIn(cy, cy + 1.0 - BOX),
            p.z.coerceIn(cz + HALF_BOX, cz + 1.0 - HALF_BOX),
        )
    }

    override fun tick() {
        if (shipId == NO_SHIP) super.tick() else tickOnShip()
        val ageF = age.toFloat() / lifetime
        if (dragon) {
            setSprite(atlas.getSprite(FRAMES[(ageF * FRAMES.size).toInt().coerceIn(0, FRAMES.size - 1)]))
            quadSize += GROW
        }
        alpha = if (ageF < 0.2f) ageF / 0.2f * ALPHA else ALPHA * (1f - ageF) / 0.8f
    }

    // VS2 collides a world particle only with the ship's current pose, so a moving or turning hull lets it through.
    private fun tickOnShip() {
        val ship = level.shipObjectWorld.loadedShips.getById(shipId) as? ClientShip
        if (ship == null) {
            remove()
            return
        }
        val prevX = x; val prevY = y; val prevZ = z
        setPos(local.x, local.y, local.z)
        super.tick()
        local.set(x, y, z)
        val t = ship.transform
        t.shipToWorld.transformPosition(world.set(local))
        setPos(world.x, world.y, world.z)
        xo = prevX; yo = prevY; zo = prevZ
        val box = ship.worldAABB
        if (x < box.minX() || x > box.maxX() || y < box.minY() || y > box.maxY() || z < box.minZ() || z > box.maxZ()) {
            // Out of the hull it drifts on in the world, no longer carried by the ship.
            t.shipToWorld.transformDirection(world.set(xd, yd, zd))
            xd = world.x; yd = world.y; zd = world.z
            shipId = NO_SHIP
        }
    }

    override fun getRenderType(): ParticleRenderType = ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT

    override fun getLightColor(partialTick: Float): Int = LightTexture.FULL_BRIGHT

    private companion object {
        val DOT = ResourceLocation("minecraft", "generic_3")
        val FRAMES = Array(8) { ResourceLocation("minecraft", "generic_$it") }
        const val NO_SHIP = Long.MIN_VALUE
        const val DRAGON_FRICTION = 0.96f
        const val GROW = 0.004f
        const val FRICTION = 0.9f
        const val ALPHA = 0.8f
        // Vanilla Particle.setSize(0.2, 0.2): the box is 0.2 wide about x and z, and 0.2 tall from y up.
        const val BOX = 0.2
        const val HALF_BOX = 0.1
    }
}
