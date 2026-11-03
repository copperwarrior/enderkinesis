package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.world.level.material.Fluids
import net.minecraft.world.phys.Vec3
import java.util.EnumMap

/**
 * Up to 7 face and crease spikes in one cell, all around one of its corners: the corner spike, the 3 edge
 * spikes and the 3 face spikes. Like candles, the item adds spikes to a cell that already has some.
 */
class GuardianSpikeBlock(props: Properties) : GuardianSpikeBase(props) {

    init {
        var state = stateDefinition.any().setValue(CORNER, SpikeAttachment.DOWN_NORTH_WEST).setValue(WATERLOGGED, false)
        for (slot in SLOTS) state = state.setValue(slot.property, slot.property === FLOOR_SLOT.property)
        registerDefaultState(state)
    }

    override val outer: Boolean get() = false

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(CORNER, WATERLOGGED, *SLOTS.map { it.property }.toTypedArray())
    }

    override fun spikes(state: BlockState): List<SpikeAttachment> {
        val corner = state.getValue(CORNER)
        return SLOTS.filter { state.getValue(it.property) }.map { it.attachment(corner) }
    }

    // Each spike gets its own shift instead, so a cluster's spikes do not all cross at the cell centre.
    override fun offset(state: BlockState, pos: BlockPos): Vec3 = Vec3.ZERO

    // Must draw the same variants, in the same order, as GuardianSpikeModels' cluster model.
    override fun placed(state: BlockState, pos: BlockPos): List<PlacedSpike> {
        var seed = lcgStart(state.getSeed(pos))
        return cachedSpikes(state).map { attach ->
            seed = lcgNext(seed)
            PlacedSpike(attach, JITTER.getValue(attach)[variantOf(seed)])
        }
    }

    /** [existing] plus a spike at [attach], moved to another shared corner if needed; null if it does not fit. */
    fun withSpike(existing: BlockState, attach: SpikeAttachment, waterlogged: Boolean): BlockState? {
        val current = if (existing.block === this) cachedSpikes(existing) else emptyList()
        if (attach in current) return null
        val preferred = if (existing.block === this) existing.getValue(CORNER) else null
        val corner = (listOfNotNull(preferred) + SpikeAttachment.CORNERS).firstOrNull { c ->
            attach.fitsCorner(c) && current.all { it.fitsCorner(c) }
        } ?: return null
        return stateWith(corner, current + attach, waterlogged)
    }

    /** [spikes] must all fit [corner]. */
    fun stateWith(corner: SpikeAttachment, spikes: List<SpikeAttachment>, waterlogged: Boolean): BlockState {
        require(spikes.all { it.fitsCorner(corner) }) { "spikes $spikes do not fit $corner" }
        var state = defaultBlockState().setValue(CORNER, corner).setValue(WATERLOGGED, waterlogged)
        for (slot in SLOTS) state = state.setValue(slot.property, spikes.any { slot.matches(it) })
        return state
    }

    override fun getStateForPlacement(ctx: BlockPlaceContext): BlockState? {
        // Face, crease, outer and merge placement is chosen by GuardianSpikeItem; this is the plain face spike.
        val clicked = ctx.clickedFace.opposite
        val attach = SpikeAttachment.of(clicked.stepX, clicked.stepY, clicked.stepZ) ?: return null
        val water = ctx.level.getFluidState(ctx.clickedPos).type === Fluids.WATER
        val state = withSpike(ctx.level.getBlockState(ctx.clickedPos), attach, water) ?: return null
        return if (state.canSurvive(ctx.level, ctx.clickedPos)) state else null
    }

    override fun canBeReplaced(state: BlockState, ctx: BlockPlaceContext): Boolean =
        !ctx.isSecondaryUseActive && ctx.itemInHand.`is`(asItem()) && cachedSpikes(state).size < SLOTS.size

    override fun canSurvive(state: BlockState, level: LevelReader, pos: BlockPos): Boolean {
        val spikes = cachedSpikes(state)
        return spikes.isNotEmpty() && spikes.all { wallsHold(level, pos, it) }
    }

    // Every wall is a face neighbour, so updateShape sees each support change.
    override fun updateShape(
        state: BlockState, dir: Direction, neighbor: BlockState, level: LevelAccessor, pos: BlockPos, neighborPos: BlockPos,
    ): BlockState {
        scheduleWater(state, level, pos)
        val spikes = cachedSpikes(state)
        val kept = spikes.filter { wallsHold(level, pos, it) }
        if (kept.size == spikes.size) return state
        // Air drops every spike through the loot table; a partial loss drops only the lost ones.
        if (kept.isEmpty()) return Blocks.AIR.defaultBlockState()
        if (level is ServerLevel) popResource(level, pos, ItemStack(asItem(), spikes.size - kept.size))
        return stateWith(state.getValue(CORNER), kept, state.getValue(WATERLOGGED))
    }

    /** One of the 7 spike positions around a corner; [mx], [my], [mz] mark the axes the spike leans on. */
    class Slot(private val mx: Int, private val my: Int, private val mz: Int, name: String) {
        val property: BooleanProperty = BooleanProperty.create(name)
        fun attachment(corner: SpikeAttachment): SpikeAttachment =
            checkNotNull(SpikeAttachment.of(corner.dx * mx, corner.dy * my, corner.dz * mz))
        fun matches(attach: SpikeAttachment): Boolean =
            Math.abs(attach.dx) == mx && Math.abs(attach.dy) == my && Math.abs(attach.dz) == mz
    }

    companion object {
        /** Shift variants per spike; a power of two, so nextInt is one multiply. */
        const val VARIANTS = 16

        /** Per-spike shifts along the axes the spike is free on: a 4 x 4 grid on a face, 16 steps along an edge. */
        val JITTER: Map<SpikeAttachment, Array<Vec3>> = EnumMap<SpikeAttachment, Array<Vec3>>(SpikeAttachment::class.java).apply {
            for (attach in SpikeAttachment.values()) put(attach, Array(VARIANTS) { jitter(attach, it) })
        }

        private fun jitter(attach: SpikeAttachment, k: Int): Vec3 {
            val free = (0 until 3).filter { attach.step(it) == 0 }
            val v = DoubleArray(3)
            when (free.size) {
                2 -> { v[free[0]] = spread(k and 3, 3); v[free[1]] = spread((k shr 2) and 3, 3) }
                1 -> v[free[0]] = spread(k, VARIANTS - 1)
            }
            return Vec3(v[0], v[1], v[2])
        }

        private fun spread(i: Int, last: Int): Double = (i.toDouble() / last - 0.5) * 2.0 * MAX_OFFSET

        // The chunk mesher seeds a java.util.Random-style source with state.getSeed(pos) before getQuads;
        // these copy its LCG so the server picks the same variants without a RandomSource.
        private const val LCG_MULT = 0x5DEECE66DL
        private const val LCG_MASK = (1L shl 48) - 1
        private fun lcgStart(seed: Long): Long = (seed xor LCG_MULT) and LCG_MASK
        private fun lcgNext(s: Long): Long = (s * LCG_MULT + 0xBL) and LCG_MASK
        private fun variantOf(s: Long): Int = ((VARIANTS.toLong() * (s ushr 17).toInt()) shr 31).toInt()

        /** The cell corner every spike in the cell leans toward. */
        val CORNER: EnumProperty<SpikeAttachment> = EnumProperty.create("corner", SpikeAttachment::class.java) { it.order == 3 }

        private val FLOOR_SLOT = Slot(0, 1, 0, "y")
        val SLOTS: List<Slot> = listOf(
            Slot(1, 0, 0, "x"), FLOOR_SLOT, Slot(0, 0, 1, "z"),
            Slot(1, 1, 0, "xy"), Slot(1, 0, 1, "xz"), Slot(0, 1, 1, "yz"), Slot(1, 1, 1, "xyz"),
        )
    }
}
