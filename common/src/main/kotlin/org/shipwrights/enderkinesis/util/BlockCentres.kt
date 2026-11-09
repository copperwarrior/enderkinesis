package org.shipwrights.enderkinesis.util

import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import org.joml.Vector3d
import org.valkyrienskies.mod.common.getShipManagingPos

// VS2's Level.toWorldCoordinates(x, y, z, dest) does not write dest for a ship position, so it cannot reuse [out].
/** The world centre of block [pos], which can be a shipyard position, written into [out]. */
fun Level.blockCentreInWorld(pos: BlockPos, out: Vector3d): Vector3d {
    out.set(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
    getShipManagingPos(pos)?.shipToWorld?.transformPosition(out)
    return out
}
