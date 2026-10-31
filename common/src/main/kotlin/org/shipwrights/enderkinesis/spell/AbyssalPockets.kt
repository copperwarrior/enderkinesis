package org.shipwrights.enderkinesis.spell

import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.event.events.common.TickEvent
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import it.unimi.dsi.fastutil.longs.LongSet
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.block.RuneBlock

/**
 * Void's Abyssal Pocket: full blocks without a block entity in a small core are phased out
 * of the world for a while. A phased block stays in the world data on both sides, so a
 * ship's mass and shape don't change, nothing can be placed in the gap, fluids can't flow in
 * and block updates can't undo it; but it has no collision or outline (entities fall
 * through), and clients don't mesh it, so its neighbours show their faces into the hole.
 * Clients learn the phased positions from [SpellNetwork] and keep them here too.
 *
 * The phased positions are just a timed list per level. Losing it (a restart) only means
 * the blocks are back early. When a pocket closes, anything standing inside a returning
 * block is pushed out to the nearest open spot. Runes on phased blocks phase with them and
 * don't go off.
 */
object AbyssalPockets {

    // Game thread only: position → game time it returns.
    private val phased = HashMap<ResourceKey<Level>, Long2LongOpenHashMap>()

    private const val PUSH_SEARCH = 3

    fun init() {
        TickEvent.SERVER_LEVEL_POST.register(TickEvent.ServerLevelTick(::expire))
        LifecycleEvent.SERVER_STOPPED.register { phased.clear() }
    }

    /** Whether the block at [pos] is phased. Server levels only; clients hide phased blocks
     *  by replacing them locally. Called from the collision mixin, so it's cheap when nothing
     *  is phased. */
    @JvmStatic
    fun isPhased(getter: BlockGetter?, pos: BlockPos?): Boolean {
        // Mods ask for shapes with no level or position (Create builds its shapes that way).
        if (pos == null) return false
        if (getter !is Level) return false
        if (getter.isClientSide) return isPhasedClient(pos)
        if (phased.isEmpty()) return false
        return phased[getter.dimension()]?.containsKey(pos.asLong()) == true
    }

    // Client copy, for the client's level only (render thread).
    private val clientPhased = LongOpenHashSet()

    /** Whether [pos] is phased in the client's level: not meshed, no collision. */
    @JvmStatic
    fun isPhasedClient(pos: BlockPos): Boolean = !clientPhased.isEmpty() && clientPhased.contains(pos.asLong())

    fun clientPhased(): LongSet = clientPhased

    fun setClientPhased(positions: LongArray, isPhased: Boolean) {
        for (key in positions) if (isPhased) clientPhased.add(key) else clientPhased.remove(key)
    }

    fun clearClient() = clientPhased.clear()

    /** Opens a pocket of radius 1.5 + 0.5 × magnitude around [centre] for 3 + magnitude s. */
    fun open(ctx: SpellContext, centre: Vec3, magnitude: Float) =
        open(ctx, centre, 1.5 + 0.5 * magnitude, SpellCurves.ticks(3f, 1f, magnitude))

    /** A pocket of [radius] round [centre], lasting [ticks]. */
    fun open(ctx: SpellContext, centre: Vec3, radius: Double, ticks: Int) {
        val r = Math.ceil(radius).toInt()
        val origin = BlockPos.containing(centre)
        val inside = BlockPos.betweenClosed(origin.offset(-r, -r, -r), origin.offset(r, r, r))
            .filter { Vec3.atCenterOf(it).distanceToSqr(centre) <= radius * radius }
            .map { it.immutable() }
        phase(ctx, inside, ticks)
    }

    /** Whether the block at [pos] can be phased out: a full block, without a block entity,
     *  that can be broken and that [ctx] may change. */
    fun canPhase(ctx: SpellContext, pos: BlockPos): Boolean {
        val level = ctx.level
        val state = level.getBlockState(pos)
        if (state.hasBlockEntity() || !state.isCollisionShapeFullBlock(level, pos)) return false
        return state.getDestroySpeed(level, pos) >= 0f && ctx.mayChange(pos, Blocks.AIR.defaultBlockState())
    }

    /** Phases out those of [positions] that [canPhase], for [ticks]. */
    fun phase(ctx: SpellContext, positions: Iterable<BlockPos>, ticks: Int) {
        val level = ctx.level
        val until = level.gameTime + ticks
        val map = phased.getOrPut(level.dimension()) { Long2LongOpenHashMap() }
        val added = ArrayList<Long>()
        for (pos in positions) {
            if (!canPhase(ctx, pos)) continue
            val key = pos.asLong()
            if (!map.containsKey(key)) added += key
            map.put(key, maxOf(map.get(key), until))
            // Runes written on it phase too.
            for (face in Direction.entries) {
                val rune = pos.relative(face)
                val runeState = level.getBlockState(rune)
                if (runeState.block is RuneBlock && runeState.getValue(RuneBlock.FACING) == face) {
                    if (!map.containsKey(rune.asLong())) added += rune.asLong()
                    map.put(rune.asLong(), maxOf(map.get(rune.asLong()), until))
                }
            }
        }
        if (added.isEmpty()) return
        SpellNetwork.broadcastPhase(level, added, true)
    }

    private fun expire(level: ServerLevel) {
        val map = phased[level.dimension()] ?: return
        val now = level.gameTime
        val returning = ArrayList<Long>()
        val it = map.long2LongEntrySet().iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (entry.longValue <= now) {
                returning += entry.longKey
                it.remove()
            }
        }
        if (map.isEmpty()) phased.remove(level.dimension())
        if (returning.isEmpty()) return
        SpellNetwork.broadcastPhase(level, returning, false)
        for (key in returning) pushOut(level, BlockPos.of(key))
    }

    /** Moves anything inside the returning block at [pos] to the nearest open spot. */
    private fun pushOut(level: ServerLevel, pos: BlockPos) {
        val block = AABB(pos)
        for (entity in level.getEntities(null as Entity?, block) { it.isAlive && !it.isSpectator }) {
            val spot = openSpot(level, entity, pos) ?: continue
            entity.teleportTo(spot.x, spot.y, spot.z)
        }
    }

    private fun openSpot(level: ServerLevel, entity: Entity, pos: BlockPos): Vec3? {
        for (d in 1..PUSH_SEARCH) {
            val candidates = listOf(pos.above(d)) + Direction.Plane.HORIZONTAL.map { pos.relative(it, d) } + pos.below(d)
            for (c in candidates) {
                val at = Vec3(c.x + 0.5, c.y.toDouble(), c.z + 0.5)
                if (level.noCollision(entity, entity.getDimensions(entity.pose).makeBoundingBox(at))) return at
            }
        }
        return null
    }

    /** Every block phased in [level] right now, for a client starting a fresh copy of it
     *  (see [SpellNetwork]). */
    fun phasedIn(level: ServerLevel): LongArray = phased[level.dimension()]?.keys?.toLongArray() ?: LongArray(0)
}
