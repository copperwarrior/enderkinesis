package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.Containers
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.block.state.properties.DirectionProperty
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.blockentity.MagicMissileLauncherBlockEntity
import org.shipwrights.enderkinesis.registry.EKBlockEntities
import org.shipwrights.enderkinesis.registry.EKItems

/**
 * Magic Missile Launcher block. A 4×4 grid of magic-missile slots is exposed on the
 * front face; players right-click a specific slot to insert (with a missile in hand) or
 * extract (with empty hand). A hopper above can feed missiles in through the top, a
 * hopper below pulls spent slots out through the bottom. Redstone power launches one
 * stored missile per second along the facing direction.
 *
 * **Block-state shape.** [FACING] is one of N/S/E/W and selects which face is "the
 * front." [POWERED] tracks redstone signal so the BE can start/stop firing on edges.
 * [FULL] flips between the `_full` and `_empty` block-models — true iff at least one
 * slot currently holds a missile.
 *
 * **Slot pick.** [pickSlot] computes which of the 16 slots a [BlockHitResult] on the
 * front face landed in by mapping the hit's face-local (u, v) into the 4×4 grid. Slot
 * centres are at the user-spec'd `(14, 14), (10, 14), …, (2, 2)` pixels — slot 0 is
 * top-right, slot 15 is bottom-left, viewed from outside the front face.
 */
class MagicMissileLauncherBlock(properties: BlockBehaviour.Properties) :
    Block(properties), EntityBlock {

    init {
        registerDefaultState(
            stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(POWERED, false)
        )
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING, POWERED)
    }

    override fun getStateForPlacement(ctx: BlockPlaceContext): BlockState {
        // Match vanilla dispenser: front face points toward the player so they can
        // see / click the slots, including for up/down placements (e.g. ceiling
        // mount → front faces down).
        val face = ctx.nearestLookingDirection.opposite
        val powered = ctx.level.hasNeighborSignal(ctx.clickedPos)
        return defaultBlockState()
            .setValue(FACING, face)
            .setValue(POWERED, powered)
    }

    override fun rotate(state: BlockState, rotation: Rotation): BlockState =
        state.setValue(FACING, rotation.rotate(state.getValue(FACING)))

    override fun mirror(state: BlockState, mirror: Mirror): BlockState =
        state.rotate(mirror.getRotation(state.getValue(FACING)))

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        MagicMissileLauncherBlockEntity(pos, state)

    override fun <T : BlockEntity> getTicker(
        level: Level, state: BlockState, type: BlockEntityType<T>,
    ): BlockEntityTicker<T>? {
        if (level.isClientSide) return null
        if (type != EKBlockEntities.MAGIC_MISSILE_LAUNCHER.get()) return null
        return BlockEntityTicker { lvl, p, s, be ->
            (be as? MagicMissileLauncherBlockEntity)?.serverTick(lvl as ServerLevel, p, s)
        }
    }

    override fun use(
        state: BlockState, level: Level, pos: BlockPos,
        player: Player, hand: InteractionHand, hit: BlockHitResult,
    ): InteractionResult {
        if (hit.direction != state.getValue(FACING)) return InteractionResult.PASS
        val slot = pickSlot(state, pos, hit) ?: return InteractionResult.PASS
        val be = level.getBlockEntity(pos) as? MagicMissileLauncherBlockEntity
            ?: return InteractionResult.PASS
        if (level.isClientSide) {
            // Mirror the conditions the server checks so the click flashes a success
            // when something will happen and a fail otherwise — feels responsive.
            val held = player.getItemInHand(hand)
            val current = be.getItem(slot)
            val willDo = (current.isEmpty && !held.isEmpty && held.item == EKItems.MAGIC_MISSILE.get()) ||
                !current.isEmpty
            return if (willDo) InteractionResult.SUCCESS else InteractionResult.PASS
        }
        val held = player.getItemInHand(hand)
        return if (be.toggleSlot(player, slot, held)) InteractionResult.CONSUME
        else InteractionResult.PASS
    }

    @Deprecated("Deprecated in Java")
    override fun neighborChanged(
        state: BlockState, level: Level, pos: BlockPos,
        block: Block, fromPos: BlockPos, moving: Boolean,
    ) {
        if (level.isClientSide) return
        val powered = level.hasNeighborSignal(pos)
        if (powered != state.getValue(POWERED)) {
            level.setBlock(pos, state.setValue(POWERED, powered), Block.UPDATE_CLIENTS)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRemove(state: BlockState, level: Level, pos: BlockPos, newState: BlockState, moved: Boolean) {
        if (!state.`is`(newState.block)) {
            val be = level.getBlockEntity(pos) as? MagicMissileLauncherBlockEntity
            if (be != null) {
                Containers.dropContents(level, pos, be)
                level.updateNeighbourForOutputSignal(pos, this)
            }
        }
        super.onRemove(state, level, pos, newState, moved)
    }

    // No setPlacedBy override needed — per-slot rendering is handled by the BlockEntity-
    // Renderer reading inventory state directly, so there's nothing on the blockstate
    // that needs syncing from inventory NBT at place time.

    @Deprecated("Deprecated in Java")
    override fun hasAnalogOutputSignal(state: BlockState): Boolean = true

    @Deprecated("Deprecated in Java")
    override fun getAnalogOutputSignal(state: BlockState, level: Level, pos: BlockPos): Int {
        val be = level.getBlockEntity(pos) as? MagicMissileLauncherBlockEntity ?: return 0
        var filled = 0
        for (i in 0 until MagicMissileLauncherBlockEntity.SLOT_COUNT) {
            if (!be.getItem(i).isEmpty) filled++
        }
        return if (filled == 0) 0 else 1 + (filled * 14 / MagicMissileLauncherBlockEntity.SLOT_COUNT)
    }

    companion object {
        /** All six directions — N/S/E/W plus up/down. Lets the launcher mount to a
         *  ceiling or floor and fire vertically as well as horizontally. */
        val FACING: DirectionProperty = BlockStateProperties.FACING
        val POWERED: BooleanProperty = BlockStateProperties.POWERED

        // Per-slot occupancy is no longer encoded in the blockstate — the original
        // per-slot multipart approach (16 boolean properties) would explode the state
        // space to 2^16 × 4 facings × 2 powered = 524,288 entries, which is enough to
        // make game-startup hang while Minecraft enumerates the blockstate cache. The
        // [MagicMissileLauncherRenderer] reads inventory state from the BlockEntity at
        // render time and draws per-slot overlay quads directly, so the blockstate only
        // needs the 8 entries from FACING × POWERED.

        /** Translate a hit on the front face into a slot index 0..15, or null if the
         *  hit is outside the 4×4 grid (e.g. on the block's edge). Slot index follows
         *  the user-spec'd order: rows top→bottom, within each row right→left.
         *
         *  The face-local `(u, v)` is computed for each of the 4 horizontal facing
         *  directions so the rightmost slot from the viewer's perspective is always
         *  slot 0 in its row. */
        fun pickSlot(state: BlockState, pos: BlockPos, hit: BlockHitResult): Int? {
            val facing = state.getValue(FACING)
            val local: Vec3 = hit.location.subtract(
                Vec3(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble())
            )
            val u: Double
            val v: Double
            // (u, v) is the face-local 2D coord. u increases toward "viewer's left"
            // and v toward "top of the face" so the existing colFromRight / rowFromTop
            // slot math works for every facing. For the vertical faces (UP/DOWN), v
            // runs along the world z axis since the face has no inherent up/down;
            // we pick a convention that's consistent with horizontal facings (top of
            // the visible face = world +Z for UP, world -Z for DOWN — i.e. "north of
            // the face" matches the natural reading orientation when looking down
            // from above or up from below).
            when (facing) {
                Direction.NORTH -> { u = local.x;       v = local.y }       // viewer's left = +X
                Direction.SOUTH -> { u = 1.0 - local.x; v = local.y }       // viewer's left = -X
                Direction.EAST  -> { u = local.z;       v = local.y }       // viewer's left = +Z
                Direction.WEST  -> { u = 1.0 - local.z; v = local.y }       // viewer's left = -Z
                Direction.UP    -> { u = local.x;       v = local.z }       // top = +Z (south)
                Direction.DOWN  -> { u = local.x;       v = 1.0 - local.z } // top = -Z (north)
            }
            if (u !in 0.0..1.0 || v !in 0.0..1.0) return null
            val cellX = (u * 16.0).toInt().coerceIn(0, 15) / 4  // 0..3 left→right
            val cellY = (v * 16.0).toInt().coerceIn(0, 15) / 4  // 0..3 bottom→top
            val rowFromTop = 3 - cellY
            val colFromRight = 3 - cellX
            return rowFromTop * 4 + colFromRight
        }
    }

    @Deprecated("Deprecated in Java")
    override fun getShadeBrightness(state: BlockState, level: BlockGetter, pos: BlockPos): Float = 1.0f

    @Deprecated("Deprecated in Java")
    override fun getLightBlock(state: BlockState, level: BlockGetter, pos: BlockPos): Int = 0
}
