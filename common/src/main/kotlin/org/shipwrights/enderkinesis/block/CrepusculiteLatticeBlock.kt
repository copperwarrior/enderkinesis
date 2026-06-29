package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.material.FluidState
import net.minecraft.world.level.material.Fluids
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
                val pState = level.getBlockState(p)
                for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
                    if (dx == 0 && dy == 0 && dz == 0) continue
                    val n = BlockPos(p.x + dx, p.y + dy, p.z + dz)
                    if (!blocks.add(n)) continue                       // already seen
                    val nState = level.getBlockState(n)
                    if (nState.isAir || level.isBlockInShipyard(n)) {
                        blocks.remove(n)                                // air / other ship bounds it
                        continue
                    }
                    // Sturdy-face structural gate: the connection from `p` to `n` only
                    // counts when both blocks have a full sturdy face touching the
                    // cardinal axes of the move (see [isStructuralLink]). Filters out
                    // leaves / vines / panes / gates that aren't really hull material
                    // from being scooped into the assembly via diagonal corners.
                    if (!isStructuralLink(level, pState, p, nState, n, dx, dy, dz)) {
                        blocks.remove(n)
                        continue
                    }
                    if (blocks.size > MAX_ASSEMBLY_BLOCKS) return AssembleOutcome.TOO_LARGE
                    queue.add(n)
                }
            }
            val withAttached = expandWithAttachments(level, blocks)
            ShipAssembler.assembleToShip(level, withAttached, 1.0)
            return AssembleOutcome.OK
        }

        /** Structural-link gate. For each non-zero axis component of the move from
         *  `fromPos` to `toPos`, both blocks must present a sturdy face along that axis
         *  — `fromState`'s face in the move direction *and* `toState`'s opposite face.
         *
         *  Uses the 3-arg `BlockState.isFaceSturdy` (defaults to `SupportType.FULL`),
         *  matching the gate vanilla uses to decide whether things like rails, scaffolds,
         *  and torches can attach to a face. Net effect: leaves, vines, panes, fences,
         *  most "decorative" blocks fail at least one of the directional sturdy checks
         *  and don't get pulled into the assembly. Full blocks (stone, dirt, planks,
         *  ores) and the rigid hull blocks the player is likely to be building with all
         *  pass.
         *
         *  For diagonal / corner moves (≥2 non-zero components), every applicable axis
         *  must pass — both blocks need sturdy faces in *each* direction we're crossing.
         *  That preserves the 26-neighbour intent (a diagonal staircase of solid cubes
         *  still connects) while rejecting diagonals through non-structural blocks. */
        @JvmStatic
        fun isStructuralLink(
            level: net.minecraft.world.level.LevelReader,
            fromState: BlockState, fromPos: BlockPos,
            toState: BlockState, toPos: BlockPos,
            dx: Int, dy: Int, dz: Int,
        ): Boolean {
            if (dx != 0) {
                val face = if (dx > 0) Direction.EAST else Direction.WEST
                if (!fromState.isFaceSturdy(level, fromPos, face)) return false
                if (!toState.isFaceSturdy(level, toPos, face.opposite)) return false
            }
            if (dy != 0) {
                val face = if (dy > 0) Direction.UP else Direction.DOWN
                if (!fromState.isFaceSturdy(level, fromPos, face)) return false
                if (!toState.isFaceSturdy(level, toPos, face.opposite)) return false
            }
            if (dz != 0) {
                val face = if (dz > 0) Direction.SOUTH else Direction.NORTH
                if (!fromState.isFaceSturdy(level, fromPos, face)) return false
                if (!toState.isFaceSturdy(level, toPos, face.opposite)) return false
            }
            return true
        }

        /** Expand an assembly block-set with anything that *depends* on a block already
         *  in the set — tall grass on the grass block we're scooping, the torch on the
         *  stone wall, the head of the bed whose foot is in the set, the upper half of
         *  a double-tall plant, snow layers, carpets, vines, signs, etc.
         *
         *  Method: for each block already in the set, look at its 6 cardinal neighbours;
         *  for any neighbour that isn't in the set, check whether removing the in-set
         *  block (by handing the neighbour a [LevelReader] that returns AIR at the in-set
         *  position) makes the neighbour's [BlockState.canSurvive] fail. If it does, the
         *  neighbour is structurally attached to the in-set block and joins the assembly.
         *
         *  Iterates until quiescent so chained attachments (LOWER double-plant → UPPER
         *  double-plant → ...) are picked up in subsequent passes.
         *
         *  Result is a [LinkedHashSet] ordered by Y *descending*. That order is preserved
         *  through VS2's `moveBlocksFromTo` clear loop (`for (pos in blocks)` over a
         *  `LinkedHashSet`), and it sidesteps a cascade in VS2's pass-1 BARRIER
         *  substitution: pass 1 uses `Block.UPDATE_CLIENTS`, which fires `updateShape`
         *  on neighbours without `UPDATE_SUPPRESS_DROPS`. A supported plant
         *  (`BushBlock.updateShape` → `canSurvive`) sees its supporter go BARRIER, fails
         *  the dirt/farmland check, and pops out as an item before VS2 can sweep its
         *  own position. Top-down order means the plant is BARRIER first — by the time
         *  the supporter switches, the UP neighbour is already a (non-BushBlock)
         *  BARRIER, `updateShape` no-ops, and nothing drops. Helps every single-block
         *  vertical attachment (grass, saplings, flowers, snow, carpets). True mutually-
         *  dependent multi-block structures (doors, beds, double-tall plants) still
         *  cascade — that one needs a VS2-side fix to add `UPDATE_SUPPRESS_DROPS` or
         *  `UPDATE_KNOWN_SHAPE` to the pass-1 flag. */
        @JvmStatic
        fun expandWithAttachments(level: ServerLevel, blocks: Set<BlockPos>): Set<BlockPos> {
            val expanded = HashSet(blocks)
            var changed = true
            while (changed) {
                changed = false
                // Snapshot to avoid mutating the set during iteration.
                val frontier = expanded.toList()
                for (p in frontier) {
                    for (dir in Direction.values()) {
                        val n = p.relative(dir)
                        if (n in expanded) continue
                        val ns = level.getBlockState(n)
                        if (ns.isAir) continue
                        // Don't steal blocks that belong to another ship's claim or are
                        // unbreakable — same gates the flood-fills already use.
                        if (level.getLoadedShipManagingPos(n) != null) continue
                        if (level.isBlockInShipyard(n)) continue
                        if (ns.getDestroySpeed(level, n) < 0f) continue
                        // Hide p; if n still survives, it didn't depend on p. The mock
                        // reader returns AIR at p only — everything else is the real world,
                        // so canSurvive's neighbour checks see the rest of the structure.
                        val mock = AttachmentMockReader(level, p)
                        if (!ns.canSurvive(mock, n)) {
                            expanded.add(n.immutable())
                            changed = true
                        }
                    }
                }
            }
            return expanded
                .sortedByDescending { it.y }
                .toCollection(LinkedHashSet())
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

/** [LevelReader] facade that pretends a single position is air. Used by
 *  [CrepusculiteLatticeBlock.expandWithAttachments] to ask each candidate neighbour
 *  "would you still survive if THIS block were gone?" without actually mutating the world.
 *  Kotlin's `by` delegation forwards every other [LevelReader] method to the real level. */
private class AttachmentMockReader(
    private val delegate: LevelReader,
    private val hidden: BlockPos,
) : LevelReader by delegate {
    override fun getBlockState(pos: BlockPos): BlockState =
        if (sameCoords(pos, hidden)) Blocks.AIR.defaultBlockState() else delegate.getBlockState(pos)

    override fun getFluidState(pos: BlockPos): FluidState =
        if (sameCoords(pos, hidden)) Fluids.EMPTY.defaultFluidState() else delegate.getFluidState(pos)

    private fun sameCoords(a: BlockPos, b: BlockPos): Boolean =
        a.x == b.x && a.y == b.y && a.z == b.z
}
