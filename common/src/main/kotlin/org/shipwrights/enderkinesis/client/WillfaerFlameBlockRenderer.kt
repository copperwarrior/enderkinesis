package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.world.level.block.LanternBlock
import net.minecraft.world.level.block.WallTorchBlock
import net.minecraft.world.level.block.state.BlockState
import org.joml.Vector3d
import org.shipwrights.enderkinesis.blockentity.WillfaerFlameBlockEntity
import org.valkyrienskies.core.api.ships.ClientShip
import org.valkyrienskies.mod.common.getLoadedShipManagingPos

/** Render thread only. */
class WillfaerFlameBlockRenderer : BlockEntityRenderer<WillfaerFlameBlockEntity> {
    private val scratch = Vector3d()

    override fun render(
        be: WillfaerFlameBlockEntity,
        partialTick: Float,
        pose: PoseStack,
        buffers: MultiBufferSource,
        light: Int,
        overlay: Int,
    ) {
        val pos = be.blockPos
        val radius = flameCentre(be.blockState, scratch)
        scratch.add(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble())
        // On a ship the block position is in the shipyard. The flame stays upright, so only the position moves.
        (be.level?.getLoadedShipManagingPos(pos) as? ClientShip)?.renderTransform?.shipToWorld?.transformPosition(scratch)
        WillfaerFlameVolume.queue(scratch.x, scratch.y, scratch.z, radius, (pos.asLong() % SEED_SPREAD).toFloat())
    }

    /** Puts the flame centre in block coordinates into [out] and returns the flame radius. */
    private fun flameCentre(state: BlockState, out: Vector3d): Float {
        when {
            state.hasProperty(LanternBlock.HANGING) -> {
                val floor = if (state.getValue(LanternBlock.HANGING)) LANTERN_HANGING_FLOOR else LANTERN_FLOOR
                out.set(0.5, floor + FLAME_DROP * LANTERN_RADIUS, 0.5)
                return LANTERN_RADIUS
            }
            state.hasProperty(WallTorchBlock.FACING) -> {
                val away = state.getValue(WallTorchBlock.FACING).opposite
                out.set(
                    0.5 + WALL_TORCH_LEAN * away.stepX,
                    WALL_TORCH_BASE + FLAME_DROP * TORCH_RADIUS,
                    0.5 + WALL_TORCH_LEAN * away.stepZ,
                )
            }
            else -> out.set(0.5, TORCH_BASE + FLAME_DROP * TORCH_RADIUS, 0.5)
        }
        return TORCH_RADIUS
    }

    private companion object {
        const val SEED_SPREAD = 97L
        const val LANTERN_RADIUS = 5.5f / 16
        const val TORCH_RADIUS = 6f / 16

        // Cage floors of willfaer_lantern.json (y 1) and willfaer_lantern_hanging.json (y 2).
        const val LANTERN_FLOOR = 1.0 / 16
        const val LANTERN_HANGING_FLOOR = 2.0 / 16

        // 1 px below the stick ends of willfaer_torch.json (y 8) and willfaer_wall_torch.json (8 px stick
        // tilted 22.5 degrees), so no gap shows under the flame.
        const val TORCH_BASE = 7.0 / 16
        const val WALL_TORCH_BASE = 9.97 / 16
        const val WALL_TORCH_LEAN = 5.32 / 16

        // Keep equal to -FLAME_BASE in willfaer_flame_volume.fsh, so the flame base sits where it is set.
        const val FLAME_DROP = 0.55
    }
}
