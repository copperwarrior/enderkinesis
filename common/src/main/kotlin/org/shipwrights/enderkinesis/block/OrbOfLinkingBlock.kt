package org.shipwrights.enderkinesis.block

import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.DirectionProperty
import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape
import org.shipwrights.enderkinesis.blockentity.OrbOfLinkingBlockEntity
import org.shipwrights.enderkinesis.item.DisintegrationTomeOrbBehavior
import org.shipwrights.enderkinesis.registry.EKBlockEntities
import org.shipwrights.enderkinesis.registry.EKItems

/**
 * Orb of Linking — the generic substrate the whole tome suite shares.
 *
 * An orb stores zero or more links per tome, in either direction (see
 * [OrbOfLinkingBlockEntity]). The same orb can be a Signal SEND, a Binding RECEIVE, and a
 * Chaining SEND all at once; each tome's network is independent.
 *
 * **Active face.** Every orb has a [FACING] direction pointing toward the block it was
 * placed against — the "active face." Tomes that interface with the outside world (Signal
 * redstone, future Transportation item I/O) read input from and write output to that face
 * only; the orb's other five sides are inert. This makes orbs work like buttons or levers
 * mounted on a wall instead of omnidirectional redstone components.
 *
 * The model is offset toward the active face and the voxel shape follows, so the orb
 * visibly sits against the block it's attached to.
 */
class OrbOfLinkingBlock(properties: BlockBehaviour.Properties) : Block(properties), EntityBlock {

    init {
        registerDefaultState(
            stateDefinition.any()
                .setValue(FACING, Direction.DOWN)
                .setValue(POWER, 0)
                .setValue(ORB_ROLE, OrbRole.UNBOUND)
        )
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING, POWER, ORB_ROLE)
    }

    /** Place against the clicked face — FACING points from the orb TOWARD its support block,
     *  matching the clicked-face's opposite. E.g., clicking the TOP of a stone block puts the
     *  orb on top with FACING=DOWN (toward the stone below). */
    override fun getStateForPlacement(context: BlockPlaceContext): BlockState? =
        defaultBlockState().setValue(FACING, context.clickedFace.opposite)

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        OrbOfLinkingBlockEntity(pos, state)

    /** Server ticker — delegates to the BE, which forwards to every outgoing tome's behavior. */
    override fun <T : BlockEntity> getTicker(
        level: Level, state: BlockState, type: BlockEntityType<T>,
    ): BlockEntityTicker<T>? {
        if (level.isClientSide) return null
        if (type != EKBlockEntities.ORB_OF_LINKING.get()) return null
        return BlockEntityTicker { lvl, _, _, be ->
            (be as? OrbOfLinkingBlockEntity)?.serverTick(lvl as ServerLevel)
        }
    }

    /** Per-FACING voxel shape — the orb visibly sits against its active face. */
    override fun getShape(
        state: BlockState, level: BlockGetter, pos: BlockPos, ctx: CollisionContext,
    ): VoxelShape = when (state.getValue(FACING)) {
        Direction.DOWN -> SHAPE_DOWN
        Direction.UP -> SHAPE_UP
        Direction.NORTH -> SHAPE_NORTH
        Direction.SOUTH -> SHAPE_SOUTH
        Direction.EAST -> SHAPE_EAST
        Direction.WEST -> SHAPE_WEST
    }

    /** Always a potential signal source — the per-tick power is whatever the Signal tome has
     *  computed and stamped into [POWER]. Wires reading us will only see signal on the active
     *  face's adjacent block (see [getSignal]). */
    @Deprecated("Deprecated in Java")
    override fun isSignalSource(state: BlockState): Boolean = true

    /** Weak signal in **every** direction, mirroring vanilla button/lever semantics — any
     *  block or wire adjacent to the orb reads the current [POWER] level directly, not just
     *  the support. The previous repeater-style "weak signal only in one direction" was
     *  more restrictive than the user wanted and didn't feel like direct redstone power. */
    @Deprecated("Deprecated in Java")
    override fun getSignal(
        state: BlockState, level: BlockGetter, pos: BlockPos, direction: Direction,
    ): Int = state.getValue(POWER)

    /** Direct (strong) signal in the [FACING] direction only — into the support block.
     *  Matches vanilla button/lever's [LeverBlock.getDirectSignal] pattern: strong signal
     *  to whatever the device is mounted on, so the support becomes strongly powered and
     *  passes the signal through to wires on its other faces. The `direction` argument is
     *  the side of THIS block being queried — the support (in the FACING direction) scans
     *  us via the opposite step, so we emit when `direction == facing.opposite`. */
    @Deprecated("Deprecated in Java")
    override fun getDirectSignal(
        state: BlockState, level: BlockGetter, pos: BlockPos, direction: Direction,
    ): Int = if (direction == state.getValue(FACING).opposite) state.getValue(POWER) else 0

    /** Right-click hook for the per-orb enchanted book ([OrbOfLinkingBlockEntity.book]):
     *
     *   - **Enchanted book in hand** → swap, **but only if the orb is currently a
     *     Tome of Disintegration SEND end** (has outgoing disintegration links). Other
     *     orbs (no disintegration role, receive-end, or only used by other tomes) reject
     *     the book with a status message — books are a disintegration-only feature, and
     *     letting them bind elsewhere would be a confusing footgun. Old book (if any)
     *     goes back to the player; one of the new book is consumed from the player's
     *     stack (full stack in creative).
     *   - **Empty hand** → release the orb's book back to the player. Always allowed,
     *     even if the orb is no longer a disintegration SEND — the player needs to be
     *     able to retrieve a book they bound earlier, even if they've since dismantled
     *     the link. If there's no book to release, returns `PASS` so vanilla / tome
     *     handlers still fire.
     *   - **Anything else** → `PASS` so [org.shipwrights.enderkinesis.item.LinkingTomeItem]
     *     and friends' `useOn` still see the click.
     *
     *  Sneak doesn't modify this — it's an item-driven gesture, not a context modifier. */
    @Deprecated("Deprecated in Java")
    override fun use(
        state: BlockState, level: Level, pos: BlockPos,
        player: Player, hand: InteractionHand, hit: BlockHitResult,
    ): InteractionResult {
        val itemInHand = player.getItemInHand(hand)
        val be = level.getBlockEntity(pos) as? OrbOfLinkingBlockEntity
            ?: return super.use(state, level, pos, player, hand, hit)

        // --- Tome of Filtering — works on every orb regardless of link
        //     state, role, or which hand holds the tome. ---
        //
        // If EITHER hand carries the tome, the OTHER hand's stack becomes
        // the orb's filter (empty other-hand clears it). We handle the
        // gesture here in Block.use — *before* the enchanted-book and
        // empty-hand branches below — so the tome works even when the
        // filter target is something that would otherwise be intercepted
        // (e.g. an enchanted book in main hand which the book-set
        // restriction below would reject on a non-disintegration-SEND
        // orb), AND so it works when held in offhand with main hand
        // empty (the book-release branch would otherwise consume the
        // click before the offhand tome's useOn could fire).
        val otherHand = if (hand == InteractionHand.MAIN_HAND) InteractionHand.OFF_HAND
        else InteractionHand.MAIN_HAND
        val itemInOther = player.getItemInHand(otherHand)
        val filteringTome = EKItems.TOME_OF_FILTERING.get()
        if (itemInHand.`is`(filteringTome)) {
            return applyTomeOfFilteringGesture(level, be, player, itemInOther)
        }
        if (itemInOther.`is`(filteringTome)) {
            return applyTomeOfFilteringGesture(level, be, player, itemInHand)
        }

        if (itemInHand.`is`(Items.ENCHANTED_BOOK)) {
            // Books are only useful on disintegration SEND orbs; reject otherwise so a
            // book can't get silently stranded on an orb that won't use it.
            val isDisintegrationSend =
                be.outgoingPeers(DisintegrationTomeOrbBehavior.tomeKind).isNotEmpty()
            if (!isDisintegrationSend) {
                if (!level.isClientSide) {
                    player.displayClientMessage(
                        Component.translatable("item.enderkinesis.orb.book_only_disintegration_send")
                            .withStyle(ChatFormatting.RED),
                        true,
                    )
                }
                return InteractionResult.FAIL
            }
            if (level.isClientSide) return InteractionResult.SUCCESS
            val oldBook = be.book.copy()
            val newBook = itemInHand.copy().also { it.count = 1 }
            if (!player.abilities.instabuild) itemInHand.shrink(1)
            be.setBook(newBook)
            if (!oldBook.isEmpty && !player.addItem(oldBook)) player.drop(oldBook, false)
            player.displayClientMessage(
                Component.translatable("item.enderkinesis.orb.book_set").withStyle(ChatFormatting.GRAY),
                true,
            )
            return InteractionResult.CONSUME
        }
        if (itemInHand.isEmpty) {
            if (be.book.isEmpty) return super.use(state, level, pos, player, hand, hit)
            if (level.isClientSide) return InteractionResult.SUCCESS
            val book = be.book.copy()
            be.setBook(ItemStack.EMPTY)
            if (!player.addItem(book)) player.drop(book, false)
            player.displayClientMessage(
                Component.translatable("item.enderkinesis.orb.book_released").withStyle(ChatFormatting.GRAY),
                true,
            )
            return InteractionResult.CONSUME
        }
        return super.use(state, level, pos, player, hand, hit)
    }

    /** Stamp the orb's filter to [filterTarget] and notify the player.
     *  No link-state guard — the Tome of Filtering is explicitly
     *  intended to work on every orb, including idle / unlinked ones,
     *  so the player can pre-configure a filter before wiring the orb
     *  into a network. */
    private fun applyTomeOfFilteringGesture(
        level: Level, be: OrbOfLinkingBlockEntity, player: Player, filterTarget: ItemStack,
    ): InteractionResult {
        if (level.isClientSide) return InteractionResult.sidedSuccess(true)
        be.setFilter(filterTarget)
        val msg: Component = if (filterTarget.isEmpty) {
            Component.translatable("item.enderkinesis.tome_of_filtering.cleared")
        } else {
            Component.translatable("item.enderkinesis.tome_of_filtering.set", filterTarget.hoverName)
        }
        player.displayClientMessage(
            (msg as net.minecraft.network.chat.MutableComponent).withStyle(ChatFormatting.GRAY),
            true,
        )
        return InteractionResult.CONSUME
    }

    /** Forward neighbour changes to every outgoing tome's behavior. */
    @Deprecated("Deprecated in Java")
    override fun neighborChanged(
        state: BlockState, level: Level, pos: BlockPos,
        neighborBlock: Block, neighborPos: BlockPos, isMoving: Boolean,
    ) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, isMoving)
        if (level.isClientSide) return
        (level.getBlockEntity(pos) as? OrbOfLinkingBlockEntity)?.onNeighborChanged(level as ServerLevel)
    }

    /** Block destroyed / replaced: clear every link through this orb across all tomes. */
    @Deprecated("Deprecated in Java")
    override fun onRemove(
        state: BlockState, level: Level, pos: BlockPos, newState: BlockState, isMoving: Boolean,
    ) {
        if (!state.`is`(newState.block) && !level.isClientSide) {
            (level.getBlockEntity(pos) as? OrbOfLinkingBlockEntity)?.onBlockDestroyed(level as ServerLevel)
        }
        super.onRemove(state, level, pos, newState, isMoving)
    }

    companion object {
        /** Direction the orb's active face points — set at placement from `clickedFace.opposite`.
         *  Tomes that interface with the outside world (Signal redstone, Transportation item
         *  I/O) only operate on the block adjacent to this side. */
        val FACING: DirectionProperty = BlockStateProperties.FACING

        /** Aggregate redstone power (0–15) — set by [org.shipwrights.enderkinesis.item.SignalTomeOrbBehavior]
         *  to the max level across all incoming Signal senders. */
        val POWER = BlockStateProperties.POWER

        /** High-level role used to pick the block model + drive the
         *  pulsing-haze BER. Synced from the BE whenever links change
         *  via [OrbOfLinkingBlockEntity.refreshOrbRoleState]. */
        val ORB_ROLE: EnumProperty<OrbRole> = EnumProperty.create("orb_role", OrbRole::class.java)

        // Per-facing voxel shapes — the orb is a 12×12×12 cube flush against the active face.
        // SHAPE_DOWN sits on the floor; the others are reflections/rotations onto the other
        // five faces. Generated by translating the inset cube to each face.
        private val SHAPE_DOWN: VoxelShape = box(2.0, 0.0, 2.0, 14.0, 12.0, 14.0)
        private val SHAPE_UP: VoxelShape = box(2.0, 4.0, 2.0, 14.0, 16.0, 14.0)
        private val SHAPE_NORTH: VoxelShape = box(2.0, 2.0, 0.0, 14.0, 14.0, 12.0)
        private val SHAPE_SOUTH: VoxelShape = box(2.0, 2.0, 4.0, 14.0, 14.0, 16.0)
        private val SHAPE_EAST: VoxelShape = box(4.0, 2.0, 2.0, 16.0, 14.0, 14.0)
        private val SHAPE_WEST: VoxelShape = box(0.0, 2.0, 2.0, 12.0, 14.0, 14.0)
    }
}
