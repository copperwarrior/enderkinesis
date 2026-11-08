package org.shipwrights.enderkinesis.item

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import org.shipwrights.enderkinesis.blockentity.CrepusculiteLatticeBlockEntity
import org.shipwrights.enderkinesis.physics.CrepusculiteLatticeForceInducer
import org.valkyrienskies.mod.common.getLoadedShipManagingPos
import org.valkyrienskies.core.api.util.GameTickOnly
import org.valkyrienskies.core.api.VsBeta

@OptIn(GameTickOnly::class, VsBeta::class)
object ShellOfTidesBinding {

    const val REACH_BLOCKS: Double = 64.0
    private const val REACH_SQ = REACH_BLOCKS * REACH_BLOCKS
    private const val NBT_POS = "EkBoundLattice"
    private const val NBT_DIM = "EkBoundDim"

    /** [pos] is the lattice's own BlockPos, which is in SHIPYARD coordinates on a ship. */
    fun bind(stack: ItemStack, level: Level, pos: BlockPos) {
        val tag = stack.orCreateTag
        tag.putLong(NBT_POS, pos.asLong())
        tag.putString(NBT_DIM, level.dimension().location().toString())
    }

    fun isBound(stack: ItemStack): Boolean = stack.tag?.contains(NBT_POS) == true

    /** The bound lattice when it is loaded, in this dimension, and within [REACH_BLOCKS] of [user]. */
    fun latticeInReach(level: Level, user: Entity, stack: ItemStack): CrepusculiteLatticeBlockEntity? {
        val tag = stack.tag ?: return null
        if (!tag.contains(NBT_POS) || tag.getString(NBT_DIM) != level.dimension().location().toString()) return null
        val pos = BlockPos.of(tag.getLong(NBT_POS))
        val lattice = level.getBlockEntity(pos) as? CrepusculiteLatticeBlockEntity ?: return null
        return if (distanceSq(level, pos, user) <= REACH_SQ) lattice else null
    }

    /** Only a ship's authority lattice pushes its sea level to physics, so nudges must go there. */
    fun oceanOwner(level: ServerLevel, lattice: CrepusculiteLatticeBlockEntity): CrepusculiteLatticeBlockEntity =
        level.getLoadedShipManagingPos(lattice.blockPos)
            ?.getAttachment(CrepusculiteLatticeForceInducer::class.java)
            ?.authorityHolder
            ?.takeUnless { it.isRemoved }
            ?: lattice

    private fun distanceSq(level: Level, pos: BlockPos, user: Entity): Double {
        val sx = pos.x + 0.5
        val sy = pos.y + 0.5
        val sz = pos.z + 0.5
        val m = level.getLoadedShipManagingPos(pos)?.transform?.shipToWorld
        val dx = (if (m == null) sx else m.m00() * sx + m.m10() * sy + m.m20() * sz + m.m30()) - user.x
        val dy = (if (m == null) sy else m.m01() * sx + m.m11() * sy + m.m21() * sz + m.m31()) - user.y
        val dz = (if (m == null) sz else m.m02() * sx + m.m12() * sy + m.m22() * sz + m.m32()) - user.z
        return dx * dx + dy * dy + dz * dz
    }
}
