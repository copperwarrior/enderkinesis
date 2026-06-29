package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.LecternBlock
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * Sselith Lectern — deepslate-tile-tinted lectern that replaces the vanilla
 * lectern wherever the Sselith chunk generator or a Sselith Ruin template
 * would have placed one.
 *
 * Behaviourally identical to a vanilla lectern: subclasses [LecternBlock] so
 * book-holding, FACING, HAS_BOOK, POWERED, the librarian POI hook and the
 * book BER all come for free. The voxelshape is the one departure — the
 * Sselith model is a chunkier 13-voxel-tall solid plinth instead of vanilla's
 * thin base + post, so we override the outline / collision / occlusion to
 * reflect that.
 *
 * The vanilla {@link net.minecraft.world.level.block.entity.LecternBlockEntity}
 * is reused; [org.shipwrights.enderkinesis.EnderkinesisMod] augments
 * {@code BlockEntityType.LECTERN}'s validBlocks at init so chunk reload keeps
 * the saved book on this block instead of discarding the BE.
 */
class SselithLecternBlock(properties: BlockBehaviour.Properties) : LecternBlock(properties) {

    override fun getShape(
        state: BlockState, level: BlockGetter, pos: BlockPos, ctx: CollisionContext,
    ): VoxelShape = outlineFor(state.getValue(LecternBlock.FACING))

    override fun getCollisionShape(
        state: BlockState, level: BlockGetter, pos: BlockPos, ctx: CollisionContext,
    ): VoxelShape = COLLISION_SHAPE

    override fun getOcclusionShape(state: BlockState, level: BlockGetter, pos: BlockPos): VoxelShape =
        COLLISION_SHAPE

    /** Faces are sturdy on every direction except UP — torches, levers,
     *  redstone components and signs all mount on the bottom + four
     *  sides, but nothing wall-mounts on the slanted reading surface. */
    override fun getBlockSupportShape(state: BlockState, level: BlockGetter, pos: BlockPos): VoxelShape =
        SUPPORT_SHAPE

    companion object {
        // Block model elements (1/16-voxel units):
        //   base plinth: 0..16, 0..13, 0..16  (the chunky body)
        //   back ridge:  0..16, 13..16, on the side OPPOSITE the FACING low slant
        //                (clipped from model's 13..19 — voxelshapes can't extend
        //                past y=16 / one block).
        //
        // The blockstate JSON rotates the model with FACING, so the voxel outline
        // has to rotate to match. FACING points toward the LOW SLANT (the side
        // the user reads from) — the back ridge is on the opposite side.
        // The slanted reading top itself isn't represented in voxelshape; like
        // vanilla's open book + top plate, it sits above and the player walks
        // over it freely.
        private val BASE: VoxelShape = Block.box(0.0, 0.0, 0.0, 16.0, 13.0, 16.0)

        // FACING=NORTH ⇒ low slant on -Z (north), back ridge on +Z (south).
        private val OUTLINE_NORTH: VoxelShape = Shapes.or(
            BASE, Block.box(0.0, 13.0, 11.0, 16.0, 16.0, 16.0),
        )
        // FACING=SOUTH ⇒ low slant on +Z (south), back ridge on -Z (north).
        private val OUTLINE_SOUTH: VoxelShape = Shapes.or(
            BASE, Block.box(0.0, 13.0, 0.0, 16.0, 16.0, 5.0),
        )
        // FACING=EAST  ⇒ low slant on +X (east), back ridge on -X (west).
        private val OUTLINE_EAST: VoxelShape = Shapes.or(
            BASE, Block.box(0.0, 13.0, 0.0, 5.0, 16.0, 16.0),
        )
        // FACING=WEST  ⇒ low slant on -X (west), back ridge on +X (east).
        private val OUTLINE_WEST: VoxelShape = Shapes.or(
            BASE, Block.box(11.0, 13.0, 0.0, 16.0, 16.0, 16.0),
        )

        private fun outlineFor(facing: Direction): VoxelShape = when (facing) {
            Direction.NORTH -> OUTLINE_NORTH
            Direction.SOUTH -> OUTLINE_SOUTH
            Direction.EAST  -> OUTLINE_EAST
            Direction.WEST  -> OUTLINE_WEST
            else            -> OUTLINE_NORTH
        }

        // Collision is just the boxy base — the back ridge is cosmetic, and it's
        // the same shape regardless of facing because it's a full-X-Z slab. No
        // per-direction switching needed.
        private val COLLISION_SHAPE: VoxelShape = BASE

        // Support shape — distinct from the visible outline. Vanilla
        // `Block.isFaceSturdy` defaults to `SupportType.FULL`, which only counts
        // a face as sturdy if the face projects as a full 16×16 area. The
        // visible chunky base is 16×13 on each side (Y stops at 13), so its
        // sides would test as NOT sturdy and torches / levers / signs couldn't
        // attach. Instead this support shape extends to y=16 on every face,
        // and has a 14×14 hole punched in the top centre so the UP face is
        // not full and the slanted reading surface stays "not sturdy" — the
        // narrow 1-voxel rim around the top edge stitches the side projections
        // back to full.
        private val SUPPORT_SHAPE: VoxelShape = Shapes.or(
            Shapes.or(
                Block.box(0.0, 0.0, 0.0, 16.0, 15.0, 16.0),
                Block.box(0.0, 15.0, 0.0, 1.0, 16.0, 16.0),
            ),
            Shapes.or(
                Block.box(15.0, 15.0, 0.0, 16.0, 16.0, 16.0),
                Shapes.or(
                    Block.box(1.0, 15.0, 0.0, 15.0, 16.0, 1.0),
                    Block.box(1.0, 15.0, 15.0, 15.0, 16.0, 16.0),
                ),
            ),
        )
    }
}
