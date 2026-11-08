package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import org.shipwrights.enderkinesis.blockentity.EnderAstrolabeBlockEntity
import org.shipwrights.enderkinesis.item.AlmanacOfEverywhereItem
import org.shipwrights.enderkinesis.registry.EKBlockEntities

/**
 * Ender Astrolabe. Right-click with an Almanac of Everywhere to cycle the destination dimension
 * (its obfuscated name is echoed to the player). Right-click with a different item, or an empty
 * hand, to start or cancel the jump charge.
 */
class EnderAstrolabeBlock(properties: BlockBehaviour.Properties) : Block(properties), EntityBlock {

    init {
        registerDefaultState(stateDefinition.any().setValue(CHARGING, false))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(CHARGING)
    }

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        EnderAstrolabeBlockEntity(pos, state)

    override fun <T : BlockEntity> getTicker(
        level: net.minecraft.world.level.Level, state: BlockState, type: BlockEntityType<T>,
    ): BlockEntityTicker<T>? {
        if (level.isClientSide) return null
        if (type != EKBlockEntities.ENDER_ASTROLABE.get()) return null
        return BlockEntityTicker { lvl, p, _, be ->
            (be as? EnderAstrolabeBlockEntity)?.serverTick(lvl as ServerLevel, p)
        }
    }

    // Drawn by EnderAstrolabeRenderer (translucent, no backface culling) instead of the static
    // chunk model, so vanilla must not render the block model itself.
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Forced by vanilla 1.20.1.
    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.ENTITYBLOCK_ANIMATED

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Forced by vanilla 1.20.1.
    override fun getShape(
        state: BlockState, level: BlockGetter, pos: BlockPos, ctx: CollisionContext,
    ): VoxelShape = SHAPE

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Forced by vanilla 1.20.1.
    override fun getCollisionShape(
        state: BlockState, level: BlockGetter, pos: BlockPos, ctx: CollisionContext,
    ): VoxelShape = SHAPE

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Forced by vanilla 1.20.1.
    override fun use(
        state: BlockState, level: Level, pos: BlockPos,
        player: Player, hand: InteractionHand, hit: BlockHitResult
    ): InteractionResult {
        val held = player.getItemInHand(hand)
        if (held.item is AlmanacOfEverywhereItem) {
            if (!level.isClientSide) {
                (level.getBlockEntity(pos) as? EnderAstrolabeBlockEntity)?.cycleDestination(player, held)
            }
            return InteractionResult.sidedSuccess(level.isClientSide)
        }
        // PASS lets the off-hand almanac cycle the destination.
        if (hand == InteractionHand.MAIN_HAND && player.offhandItem.item is AlmanacOfEverywhereItem) {
            return InteractionResult.PASS
        }
        if (!level.isClientSide) {
            (level.getBlockEntity(pos) as? EnderAstrolabeBlockEntity)?.toggleJump(level, pos)
        }
        return InteractionResult.sidedSuccess(level.isClientSide)
    }

    companion object {
        // Not POWERED: other mods read that property as a redstone state.
        val CHARGING: BooleanProperty = BooleanProperty.create("charging")

        /** Voxel approximation of the astrolabe silhouette: a flat pedestal, a thin shaft,
         *  and a single block-spanning band that bounds *both* the yaw/pitch rings and the
         *  sphere + spyglass-shafts above. Collapsed from a separate `ringBand` + `sphereCap`
         *  pair into one `(3,6,3) → (13,14,13)` box — the two were adjacent and only differed
         *  by a couple of pixels in X/Z and Y, so the union AABB reads the same against the
         *  cursor without the extra shape-union work. */
        private val SHAPE: VoxelShape = run {
            val pedestal = Block.box(5.0, 0.0, 5.0, 11.0, 1.0, 11.0)
            val shaft = Block.box(7.0, 1.0, 7.0, 9.0, 6.0, 9.0)
            val ringsAndSphere = Block.box(3.0, 6.0, 3.0, 13.0, 14.0, 13.0)
            Shapes.or(pedestal, shaft, ringsAndSphere)
        }
    }
}
