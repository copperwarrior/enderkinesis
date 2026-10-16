package org.shipwrights.enderkinesis.client

import dev.architectury.event.events.client.ClientTickEvent
import net.minecraft.client.multiplayer.ClientLevel
import org.joml.Vector3d
import org.joml.Vector3f
import org.shipwrights.enderkinesis.block.VoidHookBlock
import org.shipwrights.enderkinesis.blockentity.VoidHookBlockEntity
import org.shipwrights.enderkinesis.blockentity.VoidHookClientRegistry
import org.shipwrights.enderkinesis.util.ConeSampler
import org.valkyrienskies.mod.common.getShipManagingPos

/** Particles from the pull cone of each powered hook. They converge on the crystal. */
object VoidHookParticles {

    private const val MAX_PER_TICK: Int = 10

    // Centre of void_hook_crystal.json in the up-facing model, relative to the block centre.
    private const val CRYSTAL_X: Float = 0.25f / 16f
    private const val CRYSTAL_Y: Float = 4f / 16f
    private const val CRYSTAL_Z: Float = 1.75f / 16f

    /** Client thread only. */
    private val centre = Vector3d()
    private val crystal = Vector3d()
    private val facing = Vector3d()
    private val crystalLocal = Vector3f()
    private val offset = Vector3d()
    private val cone = ConeSampler()

    fun init() {
        ClientTickEvent.CLIENT_LEVEL_POST.register(::tick)
    }

    private fun tick(level: ClientLevel) {
        for (be in VoidHookClientRegistry.all(level)) {
            if (be.isRemoved) continue
            val state = be.blockState
            val power = state.getValue(VoidHookBlock.POWER)
            if (power <= 0) continue
            val pos = be.blockPos
            val dir = state.getValue(VoidHookBlock.FACING)
            VoidHookBlock.modelRotation(dir).transform(crystalLocal.set(CRYSTAL_X, CRYSTAL_Y, CRYSTAL_Z))
            centre.set(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
            crystal.set(centre).add(crystalLocal.x.toDouble(), crystalLocal.y.toDouble(), crystalLocal.z.toDouble())
            facing.set(dir.stepX.toDouble(), dir.stepY.toDouble(), dir.stepZ.toDouble())
            level.getShipManagingPos(pos)?.transform?.let {
                it.shipToWorld.transformPosition(centre)
                it.shipToWorld.transformPosition(crystal)
                it.shipToWorldRotation.transform(facing)
            }
            if (!CrepusculitePortalParticles.inRange(crystal.x, crystal.y, crystal.z)) continue
            emitCone(level, power)
        }
    }

    /** The cone apex stays at the block centre, where the pull cone starts. Only the end point moves to the crystal. */
    private fun emitCone(level: ClientLevel, power: Int) {
        cone.setAxis(facing.x, facing.y, facing.z)
        repeat(CrepusculitePortalParticles.count(power, MAX_PER_TICK)) {
            cone.sample(level.random, VoidHookBlockEntity.PULL_RADIUS, VoidHookBlockEntity.CONE_HALF_ANGLE_TAN, offset)
            CrepusculitePortalParticles.spawn(
                level, crystal.x, crystal.y, crystal.z,
                centre.x + offset.x - crystal.x,
                centre.y + offset.y - crystal.y,
                centre.z + offset.z - crystal.z,
            )
        }
    }
}
