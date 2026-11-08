package org.shipwrights.enderkinesis.client

import dev.architectury.event.events.client.ClientTickEvent
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import org.joml.Vector3d
import org.shipwrights.enderkinesis.block.VoidHarnessBlock
import org.shipwrights.enderkinesis.blockentity.VoidHarnessBlockEntity
import org.shipwrights.enderkinesis.blockentity.VoidHarnessClientRegistry
import org.valkyrienskies.core.api.ships.ClientShip
import org.valkyrienskies.mod.common.getShipManagingPos
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import org.valkyrienskies.core.api.util.GameTickOnly

/** Particles that converge on each powered harness. They show the shape of its pull. */
@OptIn(GameTickOnly::class)
object VoidHarnessParticles {

    private const val WORLD_MAX_PER_TICK: Int = 12
    private const val SHIP_MAX_PER_TICK: Int = 2
    /** Bounds the search for a solid block in a sparse ship AABB. */
    private const val SHIP_BLOCK_ATTEMPTS: Int = 4

    /** Client thread only. */
    private val centre = Vector3d()
    private val blockPos = BlockPos.MutableBlockPos()

    fun init() {
        ClientTickEvent.CLIENT_LEVEL_POST.register(::tick)
    }

    private fun tick(level: ClientLevel) {
        for (be in VoidHarnessClientRegistry.all(level)) {
            if (be.isRemoved) continue
            val power = be.blockState.getValue(VoidHarnessBlock.POWER)
            if (power <= 0) continue
            val pos = be.blockPos
            val ship = level.getShipManagingPos(pos)
            centre.set(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
            ship?.transform?.shipToWorld?.transformPosition(centre)
            if (!CrepusculitePortalParticles.inRange(centre.x, centre.y, centre.z)) continue
            when (ship) {
                null -> emitSphere(level, power)
                is ClientShip -> emitFromShip(level, ship, pos, power)
            }
        }
    }

    /** The radius follows the force falloff, so the particle density matches the pull strength. */
    private fun emitSphere(level: ClientLevel, power: Int) {
        val rand = level.random
        repeat(CrepusculitePortalParticles.count(power, WORLD_MAX_PER_TICK)) {
            val r = min(
                VoidHarnessBlockEntity.SOFT_CORE_RADIUS -
                    VoidHarnessBlockEntity.EXP_FALLOFF_SCALE * ln(1.0 - rand.nextDouble()),
                VoidHarnessBlockEntity.PULL_RADIUS,
            )
            val z = 2.0 * rand.nextDouble() - 1.0
            val phi = rand.nextDouble() * 2.0 * PI
            val xy = sqrt(1.0 - z * z) * r
            spawn(level, xy * cos(phi), xy * sin(phi), z * r)
        }
    }

    /** Particles start in random solid blocks of the ship and flow to the harness. */
    private fun emitFromShip(level: ClientLevel, ship: ClientShip, harness: BlockPos, power: Int) {
        val tx = harness.x + 0.5
        val ty = harness.y + 0.5
        val tz = harness.z + 0.5
        val aabb = ship.shipAABB ?: return
        val rand = level.random
        repeat(CrepusculitePortalParticles.count(power, SHIP_MAX_PER_TICK)) {
            for (attempt in 0 until SHIP_BLOCK_ATTEMPTS) {
                // The shipAABB max is inclusive.
                blockPos.set(
                    aabb.minX() + rand.nextInt(aabb.maxX() - aabb.minX() + 1),
                    aabb.minY() + rand.nextInt(aabb.maxY() - aabb.minY() + 1),
                    aabb.minZ() + rand.nextInt(aabb.maxZ() - aabb.minZ() + 1),
                )
                if (level.getBlockState(blockPos).isAir) continue
                CrepusculitePortalParticles.spawnOnShip(
                    level, ship, tx, ty, tz,
                    blockPos.x + rand.nextDouble() - tx,
                    blockPos.y + rand.nextDouble() - ty,
                    blockPos.z + rand.nextDouble() - tz,
                )
                break
            }
        }
    }

    private fun spawn(level: ClientLevel, ox: Double, oy: Double, oz: Double) =
        CrepusculitePortalParticles.spawn(level, centre.x, centre.y, centre.z, ox, oy, oz)
}
