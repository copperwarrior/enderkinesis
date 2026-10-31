package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.sounds.SoundSource
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.ItemUtils
import net.minecraft.world.item.Items
import net.minecraft.world.Containers
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.AttachFace
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.block.state.properties.IntegerProperty
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import org.shipwrights.enderkinesis.blockentity.OarscribeBlockEntity
import org.shipwrights.enderkinesis.registry.EKBlockEntities
import org.shipwrights.enderkinesis.registry.EKItems
import org.shipwrights.enderkinesis.registry.EKSounds

class OarscribeBlock(properties: Properties) : FaceAttachedHorizontalDirectionalBlock(properties), EntityBlock {

    private val shapes: Map<BlockState, VoxelShape> = getShapeForEachState(::shapeFor)

    init {
        registerDefaultState(
            stateDefinition.any().setValue(FACE, AttachFace.FLOOR).setValue(FACING, Direction.NORTH)
                .setValue(PENFEATHER, false).setValue(WRITING, false).setValue(TURN, 0).setValue(MIRRORED, false),
        )
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACE, FACING, PENFEATHER, WRITING, TURN, MIRRORED)
    }

    // The clicked face decides the mount, because canSurvive is always true and the vanilla look-order search
    // would otherwise pick a wall when the player clicks a floor at a shallow angle.
    override fun getStateForPlacement(context: BlockPlaceContext): BlockState {
        val clicked = context.clickedFace
        val state = defaultBlockState()
        return when (clicked) {
            Direction.UP -> state.setValue(FACE, AttachFace.FLOOR).setValue(FACING, context.horizontalDirection)
            Direction.DOWN -> state.setValue(FACE, AttachFace.CEILING).setValue(FACING, context.horizontalDirection)
            else -> state.setValue(FACE, AttachFace.WALL).setValue(FACING, clicked)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun canSurvive(state: BlockState, level: LevelReader, pos: BlockPos): Boolean = true

    @Deprecated("Deprecated in Java")
    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, ctx: CollisionContext): VoxelShape =
        shapes.getValue(state)

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = OarscribeBlockEntity(pos, state)

    override fun <T : BlockEntity> getTicker(
        level: Level, state: BlockState, type: BlockEntityType<T>,
    ): BlockEntityTicker<T>? {
        if (type != EKBlockEntities.OARSCRIBE.get()) return null
        return BlockEntityTicker { lvl, pos, st, be -> (be as? OarscribeBlockEntity)?.tick(lvl, pos, st) }
    }

    @Deprecated("Deprecated in Java")
    override fun use(
        state: BlockState, level: Level, pos: BlockPos,
        player: Player, hand: InteractionHand, hit: BlockHitResult,
    ): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
        val oarscribe = level.getBlockEntity(pos) as? OarscribeBlockEntity ?: return InteractionResult.PASS
        val held = player.getItemInHand(hand)
        if (held.isEmpty) {
            val mirror = player.isShiftKeyDown
            val turned = if (mirror) {
                state.cycle(MIRRORED)
            } else {
                state.setValue(TURN, (state.getValue(TURN) + 1) % TURNS)
            }
            level.setBlock(pos, turned, UPDATE_ALL)
            val sound = if (mirror) EKSounds.OARSCRIBE_MIRROR else EKSounds.OARSCRIBE_TURN
            level.playSound(null, pos, sound.get(), SoundSource.BLOCKS, 1f, 1f)
            return InteractionResult.CONSUME
        }
        if (oarscribe.loadPenfeather(held)) {
            level.playSound(null, pos, EKSounds.OARSCRIBE_LOAD_QUILL.get(), SoundSource.BLOCKS, 1f, 1f)
            return InteractionResult.CONSUME
        }
        val (amount, empty, sound) = when {
            held.`is`(EKItems.ICHAERIC_INK_BUCKET.get()) ->
                Triple(OarscribeBlockEntity.INK_PER_BUCKET, Items.BUCKET, EKSounds.ICHAERIC_INK_BUCKET_EMPTY)
            held.`is`(EKItems.ICHAERIC_INK_BOTTLE.get()) ->
                Triple(OarscribeBlockEntity.INK_PER_BOTTLE, Items.GLASS_BOTTLE, EKSounds.ICHAERIC_INK_JAR_POUR.get())
            else -> return InteractionResult.PASS
        }
        if (oarscribe.inkSpace < amount) {
            showInk(player, oarscribe)
            return InteractionResult.CONSUME
        }
        oarscribe.fillInk(amount)
        player.setItemInHand(hand, ItemUtils.createFilledResult(held, player, ItemStack(empty)))
        level.playSound(null, pos, sound, SoundSource.BLOCKS, 1f, 1f)
        showInk(player, oarscribe)
        return InteractionResult.CONSUME
    }

    private fun showInk(player: Player, oarscribe: OarscribeBlockEntity) {
        val bottles = "%.1f".format(oarscribe.ink.toDouble() / OarscribeBlockEntity.INK_PER_BOTTLE)
        val capacity = OarscribeBlockEntity.INK_CAPACITY / OarscribeBlockEntity.INK_PER_BOTTLE
        player.displayClientMessage(Component.translatable(IchaericInkJarBlock.CONTENTS_KEY, bottles, capacity), true)
    }

    @Deprecated("Deprecated in Java")
    override fun onRemove(state: BlockState, level: Level, pos: BlockPos, newState: BlockState, moved: Boolean) {
        if (!state.`is`(newState.block)) {
            (level.getBlockEntity(pos) as? OarscribeBlockEntity)?.let { Containers.dropContents(level, pos, it) }
        }
        super.onRemove(state, level, pos, newState, moved)
    }

    companion object {
        val FACE = BlockStateProperties.ATTACH_FACE
        val FACING = BlockStateProperties.HORIZONTAL_FACING
        val PENFEATHER: BooleanProperty = BooleanProperty.create("penfeather")

        // The client draws the glyph trail from this.
        val WRITING: BooleanProperty = BooleanProperty.create("writing")

        const val TURNS = 8

        /** The hand, quill and drive turn about the hand axis in 45 degree steps. */
        val TURN: IntegerProperty = IntegerProperty.create("turn", 0, TURNS - 1)

        /** Mirrors the quill and the drive. */
        val MIRRORED: BooleanProperty = BooleanProperty.create("mirrored")

        /** Out of the mounting surface. */
        fun up(state: BlockState): Direction = when (state.getValue(FACE)) {
            AttachFace.FLOOR -> Direction.UP
            AttachFace.CEILING -> Direction.DOWN
            AttachFace.WALL -> state.getValue(FACING)
        }

        // Model elements on the floor. The model has the same shape from all four sides.
        private val FLOOR_BOXES = listOf(
            doubleArrayOf(0.0, 0.0, 0.0, 16.0, 2.0, 16.0),
            doubleArrayOf(4.0, 2.0, 4.0, 12.0, 3.0, 12.0),
            doubleArrayOf(6.0, 3.0, 6.0, 10.0, 10.25, 10.0),
        )

        private fun shapeFor(state: BlockState): VoxelShape =
            FLOOR_BOXES.fold(Shapes.empty()) { shape, b -> Shapes.or(shape, mount(b, up(state))) }

        private fun mount(b: DoubleArray, up: Direction): VoxelShape {
            val (x1, y1, z1, x2, y2) = b
            val z2 = b[5]
            return when (up) {
                Direction.DOWN -> box(x1, 16.0 - y2, z1, x2, 16.0 - y1, z2)
                Direction.NORTH -> box(x1, z1, 16.0 - y2, x2, z2, 16.0 - y1)
                Direction.SOUTH -> box(x1, z1, y1, x2, z2, y2)
                Direction.WEST -> box(16.0 - y2, z1, x1, 16.0 - y1, z2, x2)
                Direction.EAST -> box(y1, z1, x1, y2, z2, x2)
                Direction.UP -> box(x1, y1, z1, x2, y2, z2)
            }
        }
    }
}
