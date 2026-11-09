package org.shipwrights.enderkinesis.block

import dev.architectury.registry.registries.RegistrySupplier
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.world.Containers
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.DirectionalBlock
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
import net.minecraft.world.phys.BlockHitResult
import org.shipwrights.enderkinesis.blockentity.ShulkerPufferBlockEntity
import org.shipwrights.enderkinesis.registry.EKBlockEntities
import org.shipwrights.enderkinesis.registry.EKSounds

/**
 * The Shulker Puffer — a redstone-controlled, fuel-burning thruster for VS2 ships. Visually it's
 * a dispenser silhouette: front face is the nozzle, the other five faces wear a purpur-pillar
 * skin. Placement follows the standard dispenser/observer pattern — [FACING] is set to the
 * opposite of the player's look direction, so the nozzle ends up pointing where you were aiming.
 *
 *  ## Operation
 *
 *  - **Redstone**: the [BlockStateProperties.POWER] property mirrors the strongest neighbour
 *    signal (0–15). Stamped at placement and refreshed via [neighborChanged] so it tracks
 *    analog comparator/wire input. The block entity reads `POWER` each tick to scale thrust;
 *    `0` means "off" (no force, no particles, no fuel burn).
 *  - **Fuel**: a single inventory slot accepts dragon's breath. While the puffer is powered
 *    and has fuel ticks remaining, thrust is multiplied (see
 *    [ShulkerPufferBlockEntity.FUEL_BOOST]). One bottle yields
 *    [ShulkerPufferBlockEntity.FUEL_TICKS_PER_DOSE] ticks of boosted operation. Insert by
 *    right-clicking with dragon's breath, withdraw by right-clicking empty-handed.
 *  - **Thrust direction**: opposite of [FACING] — the nozzle blows the exhaust *out* the
 *    front face, and the reaction pushes the ship *into* the back face (Newton's third).
 */
class ShulkerPufferBlock(properties: BlockBehaviour.Properties) :
    DirectionalBlock(properties), EntityBlock {

    init {
        registerDefaultState(
            stateDefinition.any()
                .setValue(BlockStateProperties.FACING, Direction.NORTH)
                .setValue(BlockStateProperties.POWER, 0)
                .setValue(FUELED, false),
        )
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(BlockStateProperties.FACING, BlockStateProperties.POWER, FUELED)
    }

    @Suppress("OVERRIDE_DEPRECATION") // Forced by vanilla 1.20.1.
    override fun rotate(state: BlockState, rotation: Rotation): BlockState = state.rotateFacing(rotation)

    @Suppress("OVERRIDE_DEPRECATION") // Forced by vanilla 1.20.1.
    override fun mirror(state: BlockState, mirror: Mirror): BlockState = state.mirrorFacing(mirror)

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState? {
        // Dispenser convention: FACING = opposite of nearest look direction. The front face
        // (nozzle) ends up where the player was pointing.
        val initialPower = context.level.getBestNeighborSignal(context.clickedPos)
        return defaultBlockState()
            .setValue(BlockStateProperties.FACING, context.nearestLookingDirection.opposite)
            .setValue(BlockStateProperties.POWER, initialPower)
    }

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        ShulkerPufferBlockEntity(pos, state)

    override fun <T : BlockEntity> getTicker(
        level: Level, state: BlockState, type: BlockEntityType<T>,
    ): BlockEntityTicker<T>? {
        if (level.isClientSide) return null
        if (type != EKBlockEntities.SHULKER_PUFFER.get()) return null
        return BlockEntityTicker { lvl, p, st, be ->
            (be as? ShulkerPufferBlockEntity)?.serverTick(
                lvl as net.minecraft.server.level.ServerLevel, p, st,
            )
        }
    }

    /** Track redstone signal level changes on neighbour updates; the BE reads
     *  [BlockStateProperties.POWER] each tick to scale thrust. Writing it into the blockstate
     *  (rather than computing in the BE) keeps the value observable to comparators / debug
     *  tooling and survives block reads from non-tick contexts. */
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Forced by vanilla 1.20.1.
    override fun neighborChanged(
        state: BlockState, level: Level, pos: BlockPos,
        block: Block, fromPos: BlockPos, moving: Boolean,
    ) {
        if (level.isClientSide) return
        val newPower = level.getBestNeighborSignal(pos)
        val oldPower = state.getValue(BlockStateProperties.POWER)
        if (oldPower != newPower) {
            val newState = state.setValue(BlockStateProperties.POWER, newPower)
            level.setBlock(pos, newState, 3)
            when {
                oldPower == 0 -> {
                    playSound(level, pos, EKSounds.SHULKER_PUFFER_POWER_ON)
                    ShulkerPufferBlockEntity.burst(level, pos, newState, ShulkerPufferBlockEntity.BURST_POWER_ON)
                }
                newPower == 0 -> {
                    playSound(level, pos, EKSounds.SHULKER_PUFFER_POWER_OFF)
                    ShulkerPufferBlockEntity.burst(level, pos, newState, ShulkerPufferBlockEntity.BURST_POWER_OFF)
                }
            }
        }
    }

    // Not a BaseEntityBlock, so the block event does not reach the block entity without this.
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Forced by vanilla 1.20.1.
    override fun triggerEvent(state: BlockState, level: Level, pos: BlockPos, id: Int, param: Int): Boolean =
        level.getBlockEntity(pos)?.triggerEvent(id, param) ?: false

    /** Drop the fuel slot's contents when the block breaks. */
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Forced by vanilla 1.20.1.
    override fun onRemove(
        state: BlockState, level: Level, pos: BlockPos, newState: BlockState, isMoving: Boolean,
    ) {
        if (!state.`is`(newState.block)) {
            (level.getBlockEntity(pos) as? ShulkerPufferBlockEntity)?.let {
                Containers.dropContents(level, pos, it)
            }
            super.onRemove(state, level, pos, newState, isMoving)
        }
    }

    /** Hand-managed fuelling: right-click with a dragon's breath to load, right-click empty-
     *  handed to unload. No GUI — single slot doesn't need one and other thrusters (VStuff
     *  Continued's, etc.) use the same hand-interaction pattern for fuel. */
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Forced by vanilla 1.20.1.
    override fun use(
        state: BlockState, level: Level, pos: BlockPos, player: Player,
        hand: InteractionHand, hit: BlockHitResult,
    ): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
        val be = level.getBlockEntity(pos) as? ShulkerPufferBlockEntity ?: return InteractionResult.PASS
        val held = player.getItemInHand(hand)
        val cur = be.getItem(0)
        return when {
            held.`is`(Items.DRAGON_BREATH) && cur.isEmpty -> {
                val one = held.copyWithCount(1)
                be.setItem(0, one)
                if (!player.abilities.instabuild) held.shrink(1)
                playSound(level, pos, EKSounds.SHULKER_PUFFER_FUEL_INSERT)
                InteractionResult.CONSUME
            }
            held.isEmpty && !cur.isEmpty -> {
                player.setItemInHand(hand, cur)
                be.setItem(0, ItemStack.EMPTY)
                playSound(level, pos, EKSounds.SHULKER_PUFFER_FUEL_REMOVE)
                InteractionResult.CONSUME
            }
            else -> InteractionResult.PASS
        }
    }

    private fun playSound(level: Level, pos: BlockPos, event: RegistrySupplier<SoundEvent>) {
        level.playSound(null, pos, event.get(), SoundSource.BLOCKS, SOUND_VOLUME, 1f)
    }

    companion object {
        /** Lets the client pick the fuelled loop. */
        val FUELED: BooleanProperty = BooleanProperty.create("fueled")
        private const val SOUND_VOLUME = 0.8f
    }
}
