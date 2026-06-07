package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
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
import net.minecraft.world.phys.BlockHitResult
import org.shipwrights.enderkinesis.blockentity.CrepusculiteLatticeBlockEntity
import org.shipwrights.enderkinesis.physics.CrepusculiteLatticeForceInducer
import org.shipwrights.enderkinesis.registry.EKBlockEntities
import org.shipwrights.enderkinesis.registry.EKItems
import org.valkyrienskies.mod.api.isBlockInShipyard
import org.valkyrienskies.mod.common.assembly.ShipAssembler
import org.valkyrienskies.mod.common.getLoadedShipManagingPos

/**
 * Crepusculite lattice. Sits at the centre of an end meteorite geode.
 *
 * Projects a virtual ocean around the ship it sits on (see [CrepusculiteLatticeBlockEntity]).
 * Analogue redstone ([BlockStateProperties.POWER], 0–15) tunes it: 0 = normal (light 15, fluid
 * density 1500), 15 = disabled (light 1, density 0), linear in between. Right-clicking with a book
 * yields an Almanac of Everywhere.
 */
class CrepusculiteLatticeBlock(properties: BlockBehaviour.Properties) : Block(properties), EntityBlock {

    init {
        registerDefaultState(stateDefinition.any().setValue(BlockStateProperties.POWER, 0))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(BlockStateProperties.POWER)
    }

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        CrepusculiteLatticeBlockEntity(pos, state)

    override fun <T : BlockEntity> getTicker(
        level: Level, state: BlockState, type: BlockEntityType<T>
    ): BlockEntityTicker<T>? {
        if (type != EKBlockEntities.CREPUSCULITE_LATTICE.get()) return null
        // Tick on *both* sides — the BE's [commonTick] publishes its catch zone to
        // [LatticeRegistry] each tick so the [EntityMixin] water-fake probe can answer
        // same-tick "in lattice fluid?" without waiting for the (server-only) force pass.
        return BlockEntityTicker { lvl, pos, st, be ->
            (be as? CrepusculiteLatticeBlockEntity)?.commonTick(lvl, pos, st)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRemove(
        state: BlockState, level: Level, pos: BlockPos, newState: BlockState, moved: Boolean
    ) {
        if (!state.`is`(newState.block)) {
            (level.getBlockEntity(pos) as? CrepusculiteLatticeBlockEntity)?.onRemoved(level, pos)
        }
        super.onRemove(state, level, pos, newState, moved)
    }

    override fun use(
        state: BlockState, level: Level, pos: BlockPos,
        player: Player, hand: InteractionHand, hit: BlockHitResult
    ): InteractionResult {
        val held = player.getItemInHand(hand)
        if (held.`is`(Items.ENDER_PEARL)) {
            if (level is ServerLevel) {
                val outcome = assembleConnectedShip(level, pos)
                if (outcome == AssembleOutcome.OK && !player.isCreative) held.shrink(1)
                player.displayClientMessage(Component.translatable(outcome.messageKey), true)
            }
            return InteractionResult.sidedSuccess(level.isClientSide)
        }
        if (held.`is`(Items.BOOK) || held.`is`(Items.WRITABLE_BOOK)) {
            if (!level.isClientSide) {
                if (!player.isCreative) held.shrink(1)
                val almanac = ItemStack(EKItems.ALMANAC_OF_EVERYWHERE.get())
                if (!player.addItem(almanac)) player.drop(almanac, false)
            }
            return InteractionResult.sidedSuccess(level.isClientSide)
        }
        return InteractionResult.PASS
    }

    enum class AssembleOutcome(val messageKey: String) {
        OK("message.enderkinesis.lattice.assembled"),
        ALREADY_SHIP("message.enderkinesis.lattice.already_ship"),
        TOO_LARGE("message.enderkinesis.lattice.too_large"),
    }

    @Deprecated("Deprecated in Java")
    override fun onPlace(state: BlockState, level: Level, pos: BlockPos, oldState: BlockState, moved: Boolean) {
        super.onPlace(state, level, pos, oldState, moved)
        syncSignal(state, level, pos)
    }

    @Deprecated("Deprecated in Java")
    override fun neighborChanged(
        state: BlockState, level: Level, pos: BlockPos,
        block: Block, fromPos: BlockPos, moving: Boolean
    ) {
        if (level.isClientSide) return
        syncSignal(state, level, pos)
    }

    private fun syncSignal(state: BlockState, level: Level, pos: BlockPos) {
        if (level.isClientSide) return
        val signal = level.getBestNeighborSignal(pos)
        if (signal != state.getValue(BlockStateProperties.POWER)) {
            level.setBlock(pos, state.setValue(BlockStateProperties.POWER, signal), 3)
        }
    }

    companion object {
        /** Hard cap on the flood-fill so an ender-pearl assembly can't shipify the world. */
        private const val MAX_ASSEMBLY_BLOCKS = 100_000

        /**
         * Assemble the structure this lattice sits on into a VS2 ship — the same approach other
         * VS2 mods use: breadth-first flood-fill the connected solid blocks from the lattice, then
         * hand that set to [ShipAssembler.assembleToShip] (the canonical public assembly
         * entrypoint, also used by VS2's own Ship Assembler item). Connectivity is 26-neighbour
         * (face + edge + corner): structures whose blocks only touch diagonally (a staircase of
         * single corners, a chequer-pattern hull, a meteorite geode whose ancrite crystals only
         * meet the lattice at a corner) still count as one piece. Air bounds the structure; the
         * size is capped so a lattice that happens to touch terrain can't try to shipify the world.
         *
         * Public-static so the ender-pearl-impact mixin can reuse the exact same flood-fill +
         * bounds logic as the right-click path.
         */
        @JvmStatic
        fun assembleConnectedShip(level: ServerLevel, origin: BlockPos): AssembleOutcome {
            if (level.getLoadedShipManagingPos(origin) != null || level.isBlockInShipyard(origin)) {
                return AssembleOutcome.ALREADY_SHIP
            }
            val blocks = HashSet<BlockPos>()
            val queue = ArrayDeque<BlockPos>()
            blocks.add(origin.immutable())
            queue.add(origin.immutable())
            while (queue.isNotEmpty()) {
                val p = queue.removeFirst()
                for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
                    if (dx == 0 && dy == 0 && dz == 0) continue
                    val n = BlockPos(p.x + dx, p.y + dy, p.z + dz)
                    if (!blocks.add(n)) continue                       // already seen
                    if (level.getBlockState(n).isAir || level.isBlockInShipyard(n)) {
                        blocks.remove(n)                                // air / other ship bounds it
                        continue
                    }
                    if (blocks.size > MAX_ASSEMBLY_BLOCKS) return AssembleOutcome.TOO_LARGE
                    queue.add(n)
                }
            }
            ShipAssembler.assembleToShip(level, blocks, 1.0)
            return AssembleOutcome.OK
        }

        /** Light emitted at analogue redstone [power] (0..15): 0 → 15, 15 → 1, linear. */
        fun lightFor(power: Int): Int =
            Math.round(15.0 - power * (14.0 / 15.0)).toInt().coerceIn(1, 15)

        /** Virtual fluid density at [power] (0..15): 0 → 1500, 15 → 0, linear. */
        fun densityFor(power: Int): Double =
            (CrepusculiteLatticeForceInducer.DEFAULT_FLUID_DENSITY * (1.0 - power / 15.0))
                .coerceAtLeast(0.0)
    }
}
