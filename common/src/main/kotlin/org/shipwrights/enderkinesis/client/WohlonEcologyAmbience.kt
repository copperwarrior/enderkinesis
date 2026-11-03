package org.shipwrights.enderkinesis.client

import dev.architectury.event.events.client.ClientTickEvent
import dev.architectury.registry.registries.RegistrySupplier
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.FluidTags
import net.minecraft.util.RandomSource
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import org.shipwrights.enderkinesis.registry.EKBlocks
import org.shipwrights.enderkinesis.registry.EKSounds

/** Wohlon ecology ambience: three habitat beds that follow the scores, and spot sounds at matching blocks. */
object WohlonEcologyAmbience {

    private const val SAMPLE_TICKS = 20
    private const val RADIUS = 16
    private const val COLUMNS = 48
    private const val DEPTH_BELOW = 6
    private const val HEIGHT_ABOVE = 14
    private const val BOUGH_MIN_RISE = 3
    private const val MAX_SPOTS = 8

    /** Wet columns for a full wetland score. */
    private const val WETLAND_FULL = 1f / 3f
    private const val HOLLOW_RAY_STEPS = 8
    /** Enclosed share of the rays at which hollow starts, and at which it is full. */
    private const val HOLLOW_MIN = 0.5f
    private const val HOLLOW_FULL = 0.85f
    /** Share of the enclosing ray hits that must be wohlon root to count as a root tunnel. */
    private const val ROOT_TUNNEL_SHARE = 0.5f

    private const val BED_FADE_TICKS = 60f
    private const val SPOT_MIN_TICKS = 40
    private const val SPOT_SPREAD_TICKS = 81
    private const val WATER_COOLDOWN_TICKS = 1200L
    private const val FROG_WEIGHT = 3f
    /** Deadwood other than a lying log counts when the ground is this many blocks below it, or fewer. */
    private const val DEADWOOD_GROUND_DEPTH = 2

    private enum class Bed { WETLAND, CANOPY, HOLLOW }

    private enum class Spot(val event: RegistrySupplier<SoundEvent>, val weight: Float = 1f) {
        WATER(EKSounds.WOHLON_SPOT_WATER),
        PAD(EKSounds.WOHLON_SPOT_PAD),
        REED(EKSounds.WOHLON_SPOT_REED),
        FROG(EKSounds.WOHLON_SPOT_FROG, FROG_WEIGHT),
        MUD(EKSounds.WOHLON_SPOT_MUD),
        BOUGH(EKSounds.WOHLON_SPOT_BOUGH),
        ROT(EKSounds.WOHLON_SPOT_ROT),
        ROOTS(EKSounds.WOHLON_SPOT_ROOTS),
        FUNGUS(EKSounds.WOHLON_SPOT_FUNGUS),
        HEARTFIRE(EKSounds.WOHLON_SPOT_HEARTFIRE),
    }

    private val BEDS = Bed.values()
    private val SPOTS = Spot.values()

    // 14 directions: the 6 axes and the 8 diagonals.
    private val RAYS: Array<IntArray> = arrayOf(
        intArrayOf(1, 0, 0), intArrayOf(-1, 0, 0), intArrayOf(0, 1, 0),
        intArrayOf(0, -1, 0), intArrayOf(0, 0, 1), intArrayOf(0, 0, -1),
        intArrayOf(1, 1, 1), intArrayOf(1, 1, -1), intArrayOf(1, -1, 1), intArrayOf(1, -1, -1),
        intArrayOf(-1, 1, 1), intArrayOf(-1, 1, -1), intArrayOf(-1, -1, 1), intArrayOf(-1, -1, -1),
    )

    // Client thread only.
    private val scores = FloatArray(BEDS.size)
    private val beds = arrayOfNulls<BedLoop>(BEDS.size)
    private val spots = Array(SPOTS.size) { LongArray(MAX_SPOTS) }
    private val spotCounts = IntArray(SPOTS.size)
    private val spotSeen = IntArray(SPOTS.size)
    private val weights = FloatArray(SPOTS.size)
    private val pos = BlockPos.MutableBlockPos()
    private val above = BlockPos.MutableBlockPos()
    private val below = BlockPos.MutableBlockPos()
    private var sampleCounter = 0
    private var nextSpotTick = 0L
    private var lastWaterTick = Long.MIN_VALUE / 2
    private var inRootTunnel = false

    fun init() {
        ClientTickEvent.CLIENT_LEVEL_POST.register(::tick)
    }

    fun onDisconnect() {
        val sounds = Minecraft.getInstance().soundManager
        for (i in beds.indices) {
            beds[i]?.let(sounds::stop)
            beds[i] = null
        }
        scores.fill(0f)
        spotCounts.fill(0)
        lastWaterTick = Long.MIN_VALUE / 2
        inRootTunnel = false
    }

    private fun tick(level: ClientLevel) {
        val player = Minecraft.getInstance().player ?: return
        val active = WohlonBiomeFog.currentBlend > 0f
        if (!active) {
            scores.fill(0f)
            spotCounts.fill(0)
            inRootTunnel = false
        } else if (++sampleCounter >= SAMPLE_TICKS) {
            sampleCounter = 0
            sample(level, player.blockPosition())
        }
        startBeds()
        if (active) tickSpots(level)
    }

    private fun sample(level: ClientLevel, centre: BlockPos) {
        spotCounts.fill(0)
        spotSeen.fill(0)
        val random = level.random
        var wetColumns = 0
        repeat(COLUMNS) {
            val x = centre.x + random.nextInt(RADIUS * 2 + 1) - RADIUS
            val z = centre.z + random.nextInt(RADIUS * 2 + 1) - RADIUS
            if (scanColumn(level, x, z, centre.y, random)) wetColumns++
        }
        scores[Bed.WETLAND.ordinal] = (wetColumns.toFloat() / COLUMNS / WETLAND_FULL).coerceIn(0f, 1f)
        scores[Bed.CANOPY.ordinal] = WohlonAtmosphere.cover
        scores[Bed.HOLLOW.ordinal] = hollowScore(level, centre)
    }

    /** Returns true if the column has wetland features. */
    private fun scanColumn(level: ClientLevel, x: Int, z: Int, centreY: Int, random: RandomSource): Boolean {
        var wet = false
        for (y in centreY - DEPTH_BELOW..centreY + HEIGHT_ABOVE) {
            pos.set(x, y, z)
            val state = level.getBlockState(pos)
            if (state.isAir) continue
            if (isWet(level, state)) wet = true
            val spot = classify(level, state, y - centreY) ?: continue
            keep(spot, random)
            if (spot == Spot.WATER || spot == Spot.PAD || spot == Spot.REED) keep(Spot.FROG, random)
        }
        return wet
    }

    private fun classify(level: ClientLevel, state: BlockState, rise: Int): Spot? {
        val block = state.block
        return when {
            isOpenDeepWater(level, state) -> Spot.WATER
            isPad(block) -> Spot.PAD
            block === EKBlocks.WOHLON_REED.get() -> Spot.REED
            block === EKBlocks.WOHLON_MUD.get() && isOpenAbove(level) -> Spot.MUD
            isDeadwood(level, state) -> Spot.ROT
            rise >= BOUGH_MIN_RISE && isBough(state) -> Spot.BOUGH
            block === EKBlocks.WOHLON_HANGING_ROOTS.get() || block === Blocks.VINE -> Spot.ROOTS
            isFungus(block) -> Spot.FUNGUS
            block === EKBlocks.HEARTFIRE.get() -> Spot.HEARTFIRE
            else -> null
        }
    }

    private fun isWet(level: ClientLevel, state: BlockState): Boolean =
        isPad(state.block) || state.block === EKBlocks.WOHLON_REED.get() ||
            state.fluidState.`is`(FluidTags.WATER) && isOpenAbove(level)

    private fun isOpenDeepWater(level: ClientLevel, state: BlockState): Boolean {
        if (!state.fluidState.`is`(FluidTags.WATER) || !state.fluidState.isSource) return false
        if (!level.getFluidState(below.setWithOffset(pos, 0, -1, 0)).`is`(FluidTags.WATER)) return false
        return level.canSeeSky(above.setWithOffset(pos, 0, 1, 0))
    }

    /** A wogor log lying on the ground, or wogor wood, slab or stairs just above it. */
    private fun isDeadwood(level: ClientLevel, state: BlockState): Boolean {
        val block = state.block
        if (block === EKBlocks.WOGOR_LOG.get()) return isHorizontal(state) && isGround(level, 1)
        if (block !== EKBlocks.WOGOR_WOOD.get() && block !== EKBlocks.WOGOR_WOOD_SLAB.get() &&
            block !== EKBlocks.WOGOR_WOOD_STAIRS.get()
        ) return false
        for (depth in 1..DEADWOOD_GROUND_DEPTH) {
            if (isGround(level, depth)) return true
        }
        return false
    }

    /** Terrain, not more of a tree, [depth] blocks under [pos]. */
    private fun isGround(level: ClientLevel, depth: Int): Boolean {
        below.setWithOffset(pos, 0, -depth, 0)
        val state = level.getBlockState(below)
        val block = state.block
        if (block === EKBlocks.WOGOR_LOG.get() || block === EKBlocks.WOGOR_WOOD.get() ||
            block === EKBlocks.WOGOR_LEAVES.get()
        ) return false
        return state.isFaceSturdy(level, below, Direction.UP)
    }

    private fun isHorizontal(state: BlockState): Boolean =
        state.getOptionalValue(BlockStateProperties.AXIS).map { it != Direction.Axis.Y }.orElse(false)

    private fun isOpenAbove(level: ClientLevel): Boolean {
        val top = level.getBlockState(above.setWithOffset(pos, 0, 1, 0))
        if (!top.fluidState.isEmpty) return false
        return top.isAir || isPad(top.block) || top.getCollisionShape(level, above).isEmpty
    }

    private fun isPad(block: Block): Boolean =
        block === EKBlocks.WOHLON_LILY_PAD.get() || block === EKBlocks.WOHLON_DUCKWEED.get()

    private fun isBough(state: BlockState): Boolean =
        state.block === EKBlocks.WOGOR_LEAVES.get() || state.block === EKBlocks.WOGOR_LOG.get() && isHorizontal(state)

    private fun isFungus(block: Block): Boolean =
        block === EKBlocks.WOHLON_LUMPY_MUSHROOM.get() || block === EKBlocks.WOHLON_CUP_MUSHROOM.get() ||
            block === EKBlocks.WOHLON_LUMPY_MUSHROOM_BLOCK.get() ||
            block === EKBlocks.WOHLON_CUP_MUSHROOM_BLOCK.get() ||
            block === EKBlocks.WOHLON_MUSHROOM_STEM.get()

    /** Reservoir sampling, so the kept positions spread over the whole area. */
    private fun keep(spot: Spot, random: RandomSource) {
        val i = spot.ordinal
        val seen = ++spotSeen[i]
        val slot = if (spotCounts[i] < MAX_SPOTS) spotCounts[i]++ else random.nextInt(seen)
        if (slot < MAX_SPOTS) spots[i][slot] = pos.asLong()
    }

    private fun hollowScore(level: ClientLevel, centre: BlockPos): Float {
        inRootTunnel = false
        if (level.canSeeSky(centre.above())) return 0f
        val root = EKBlocks.WOHLON_ROOT.get()
        var enclosed = 0
        var rootHits = 0
        for (ray in RAYS) {
            for (step in 1..HOLLOW_RAY_STEPS) {
                pos.set(centre.x + ray[0] * step, centre.y + 1 + ray[1] * step, centre.z + ray[2] * step)
                val state = level.getBlockState(pos)
                if (state.canOcclude()) {
                    enclosed++
                    if (state.block === root) rootHits++
                    break
                }
            }
        }
        inRootTunnel = rootHits > 0 && rootHits >= enclosed * ROOT_TUNNEL_SHARE
        val share = enclosed.toFloat() / RAYS.size
        return ((share - HOLLOW_MIN) / (HOLLOW_FULL - HOLLOW_MIN)).coerceIn(0f, 1f)
    }

    private fun startBeds() {
        val sounds = Minecraft.getInstance().soundManager
        for (bed in BEDS) {
            val running = beds[bed.ordinal]
            // A level change stops all sounds and does not call stop() on the instance.
            if (running != null && !running.isStopped && sounds.isActive(running)) continue
            beds[bed.ordinal] = null
            if (scores[bed.ordinal] <= 0f) continue
            beds[bed.ordinal] = BedLoop(bed).also(sounds::play)
        }
    }

    private fun tickSpots(level: ClientLevel) {
        val now = level.gameTime
        if (now < nextSpotTick) return
        val random = level.random
        nextSpotTick = now + SPOT_MIN_TICKS + random.nextInt(SPOT_SPREAD_TICKS)
        val dark = WohlonnogondoniaFireflies.isDarkEnough(level)
        var total = 0f
        for (spot in SPOTS) {
            val count = spotCounts[spot.ordinal]
            val allowed = count > 0 &&
                (spot != Spot.FROG || dark && !inRootTunnel) &&
                (spot != Spot.WATER || now - lastWaterTick >= WATER_COOLDOWN_TICKS)
            weights[spot.ordinal] = if (allowed) spot.weight * count / MAX_SPOTS else 0f
            total += weights[spot.ordinal]
        }
        if (total <= 0f) return
        var roll = random.nextFloat() * total
        var spot = SPOTS[0]
        for (candidate in SPOTS) {
            if (weights[candidate.ordinal] <= 0f) continue
            spot = candidate
            roll -= weights[candidate.ordinal]
            if (roll < 0f) break
        }
        if (spot == Spot.WATER) lastWaterTick = now
        val at = BlockPos.of(spots[spot.ordinal][random.nextInt(spotCounts[spot.ordinal])])
        level.playLocalSound(
            at.x + 0.5, at.y + 0.5, at.z + 0.5, spot.event.get(), SoundSource.AMBIENT,
            0.6f + random.nextFloat() * 0.4f, 0.9f + random.nextFloat() * 0.2f, false,
        )
    }

    private class BedLoop(private val bed: Bed) : AbstractTickableSoundInstance(
        when (bed) {
            Bed.WETLAND -> EKSounds.WOHLON_BED_WETLAND.get()
            Bed.CANOPY -> EKSounds.WOHLON_BED_CANOPY.get()
            Bed.HOLLOW -> EKSounds.WOHLON_BED_HOLLOW.get()
        },
        SoundSource.AMBIENT, SoundInstance.createUnseededRandom(),
    ) {

        init {
            looping = true
            delay = 0
            volume = 0f
            relative = true
            attenuation = SoundInstance.Attenuation.NONE
        }

        // The loop starts at volume 0 and fades in; vanilla skips a silent start without this.
        override fun canStartSilent(): Boolean = true

        override fun tick() {
            val target = scores[bed.ordinal]
            val step = 1f / BED_FADE_TICKS
            volume = if (volume < target) minOf(volume + step, target) else maxOf(volume - step, target)
            if (volume <= 0f && target <= 0f) stop()
        }
    }
}
