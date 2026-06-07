package org.shipwrights.enderkinesis.client

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.shipwrights.enderkinesis.block.OrbOfLinkingBlock
import org.valkyrienskies.core.api.ships.ClientShip
import org.valkyrienskies.mod.common.getShipManagingPos

/**
 * Shared client-side helper for finding an orb's current world-space centre. Mirrors the
 * server's [org.shipwrights.enderkinesis.blockentity.OrbOfLinkingBlockEntity.orbWorldCenter]
 * but uses the **render** transform on ships so the result follows the visually-interpolated
 * ship position, eliminating the one-tick lag from the physics transform.
 *
 * Returns null if the orb's chunk isn't loaded (the level lookup returns AIR), or if the
 * block at [pos] isn't an orb anymore (just broken / unloaded). Callers should treat null
 * as "endpoint vanished, stop rendering whatever uses it."
 */
object OrbClientGeometry {

    /** Visual offset (blocks) from the block centre toward the active face. Matches the
     *  artist's Blockbench source for the orb model: the "Orb" element runs from y = 3 to
     *  y = 11 (pixels), so its centre sits 1 pixel = 1/16 block below block centre for
     *  facing=DOWN. Generalised: the orb is offset by 1/16 block toward the active face. */
    private const val ACTIVE_FACE_OFFSET: Double = 1.0 / 16.0

    fun worldCenter(level: ClientLevel, pos: BlockPos): Vec3? {
        val state = level.getBlockState(pos)
        if (!state.hasProperty(OrbOfLinkingBlock.FACING)) return null
        val facing: Direction = state.getValue(OrbOfLinkingBlock.FACING)
        val ship = level.getShipManagingPos(pos)
        val v = Vector3d(
            pos.x + 0.5 + facing.stepX * ACTIVE_FACE_OFFSET,
            pos.y + 0.5 + facing.stepY * ACTIVE_FACE_OFFSET,
            pos.z + 0.5 + facing.stepZ * ACTIVE_FACE_OFFSET,
        )
        when (ship) {
            is ClientShip -> ship.renderTransform.shipToWorld.transformPosition(v)
            null -> {}
            else -> ship.shipToWorld.transformPosition(v)
        }
        return Vec3(v.x, v.y, v.z)
    }
}
