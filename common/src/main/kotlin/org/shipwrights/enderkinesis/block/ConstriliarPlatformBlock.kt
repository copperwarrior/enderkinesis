package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.pathfinder.PathComputationType
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import org.shipwrights.enderkinesis.blockentity.ConstriliarPlatformBlockEntity
import org.shipwrights.enderkinesis.util.ShipMoves
import org.valkyrienskies.mod.common.getShipObjectManagingPos
import org.valkyrienskies.mod.common.shipObjectWorld

/**
 * The Constriliar's back plate inside its cargo ship's shipyard. Visually empty but
 * hosts the BER that draws the body + legs in ship space; fully intangible (no
 * collision, no outline) so the walker never wedges against its own ship and clicks
 * pass through to the entity.
 */
class ConstriliarPlatformBlock(props: BlockBehaviour.Properties) : Block(props), EntityBlock {

    init {
        registerDefaultState(stateDefinition.any().setValue(LIGHT, 0))
    }

    override fun createBlockStateDefinition(
        builder: net.minecraft.world.level.block.state.StateDefinition.Builder<Block, BlockState>,
    ) {
        builder.add(LIGHT)
    }

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        ConstriliarPlatformBlockEntity(pos, state)

    /** Mining feel inherits from the animated source block (stored on the BE):
     *  an obsidian-skinned constriliar digs like obsidian, tool rules included. */
    override fun getDestroyProgress(
        state: BlockState,
        player: net.minecraft.world.entity.player.Player,
        level: BlockGetter,
        pos: BlockPos,
    ): Float {
        val source = (level.getBlockEntity(pos) as? ConstriliarPlatformBlockEntity)?.sourceState
        return source?.getDestroyProgress(player, level, pos)
            ?: super.getDestroyProgress(state, player, level, pos)
    }

    // Deliberately NOT RenderShape.INVISIBLE: VS2 skips rendering ships whose sections
    // bake to empty meshes (BERs included), so the model contributes one degenerate
    // fully-transparent quad to keep the section non-empty.

    /** This block IS the constriliar's life: destroying it (explosion, /setblock, mod
     *  interaction) kills the otherwise-invulnerable construct. Destruction is
     *  SYNCHRONOUS — deferring even one tick leaves a bare mass-0 dynamic ship for the
     *  physics thread to crash on ("invMass is not finite"); deleteShip inside the
     *  removal is the only ordering physics never observes. */
    override fun onRemove(
        state: BlockState,
        level: net.minecraft.world.level.Level,
        pos: BlockPos,
        newState: BlockState,
        movedByPiston: Boolean,
    ) {
        // VS2 empties the block entity before a move removes the plate, so it reads as orphaned.
        if (!level.isClientSide && !newState.`is`(this) && level is net.minecraft.server.level.ServerLevel &&
            !ShipMoves.isMovingBlocks(level)
        ) {
            val be = level.getBlockEntity(pos) as? ConstriliarPlatformBlockEntity
            val owner = (level.getEntity(be?.ownerEntityId ?: -1) ?: be?.ownerUuid?.let { level.getEntity(it) })
                as? org.shipwrights.enderkinesis.entity.ConstriliarEntity
            if (owner != null && owner.isAlive && !owner.releasingShip && !owner.servicingPlatform) {
                owner.destroyByShapeLoss()
            } else if (owner == null) {
                // Orphaned plate (dead-linkage debris): breaking it is the cleanup —
                // delete the ship synchronously, same mass-0 reasoning as the owned
                // death path.
                val ship = level.getShipObjectManagingPos(pos)
                if (ship != null) {
                    org.shipwrights.enderkinesis.entity.ConstriliarEntity.dropShipCargo(level, ship, pos)
                    org.valkyrienskies.mod.common.assembly.ShipAssembler.deleteShip(level, ship, false, false)
                }
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston)
    }

    // Empty outline — any outline shape here sits exactly over the rendered plate
    // and steals the interaction raycast, so right-clicks aimed at the entity became
    // dead block-uses. Cargo placement goes through the entity's mobInteract instead.
    // Context-split: entity contexts (player crosshair picking) get EMPTY so clicks
    // reach the pet — but the context-FREE query gets the real slab, because VS2's
    // voxelizer reads getShape (not getCollisionShape) for isSolid states via
    // CollisionContext.empty(); an empty outline there fell back to a FULL-CUBE
    // physics voxel half a block below the visible plate.
    override fun getShape(
        state: BlockState,
        level: BlockGetter,
        pos: BlockPos,
        context: CollisionContext,
    ): VoxelShape =
        if (context is net.minecraft.world.phys.shapes.EntityCollisionContext && context.entity != null) {
            Shapes.empty()
        } else {
            PLATE_COLLISION
        }

    /** Real collision, unlike the outline, for three reasons: VS2 only builds a physics
     *  body from collision voxels (a bare platform-only ship was force-inert and
     *  couldn't move); the full-width top face is sturdy, so non-full cargo (torches,
     *  slabs, rails) can be placed; and the ghost brain is noPhysics, so the old
     *  self-wedging concern is gone. */
    override fun getCollisionShape(
        state: BlockState,
        level: BlockGetter,
        pos: BlockPos,
        context: CollisionContext,
    ): VoxelShape = PLATE_COLLISION

    override fun isPathfindable(
        state: BlockState,
        level: BlockGetter,
        pos: BlockPos,
        type: PathComputationType,
    ): Boolean = false

    companion object {

        /** Emission inherited from the animated source block (set at placement). */
        val LIGHT: net.minecraft.world.level.block.state.properties.IntegerProperty =
            net.minecraft.world.level.block.state.properties.IntegerProperty.create("light", 0, 15)

        // Thin and HIGH on purpose: this collision exists to give VS2 a physics voxel
        // (bare ships need a body) and a sturdy top for cargo — NOT to touch terrain.
        // A 4px slab reached 0.25 lower and its corners caught the ground when a
        // cargo-raised COM pitched the walker slightly, feeding a trip loop that
        // face-planted it. 2px keeps ~0.63 ground clearance at stance.
        private val PLATE_COLLISION: VoxelShape = box(0.0, 14.0, 0.0, 16.0, 16.0, 16.0)
    }
}
