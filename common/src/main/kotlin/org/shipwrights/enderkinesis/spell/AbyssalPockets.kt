package org.shipwrights.enderkinesis.spell

import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.event.events.common.TickEvent
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import it.unimi.dsi.fastutil.longs.LongSet
import it.unimi.dsi.fastutil.longs.LongSets
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

/** A phased block stays in the world data, so ship mass does not change and nothing fills the gap.
 *  Only collision, outline and client meshing ignore it. A restart only brings the blocks back early. */
object AbyssalPockets {

    // Game thread only. Maps a position to the game time that its block comes back.
    private val phased = HashMap<ResourceKey<Level>, Long2LongOpenHashMap>()

    private const val PUSH_SEARCH = 3

    fun init() {
        TickEvent.SERVER_LEVEL_POST.register(TickEvent.ServerLevelTick(::expire))
        LifecycleEvent.SERVER_STOPPED.register { phased.clear() }
    }

    // The collision mixin calls this, so keep it cheap when nothing is phased.
    @JvmStatic
    fun isPhased(getter: BlockGetter?, pos: BlockPos?): Boolean {
        // Some mods (Create) ask for shapes with no level or position.
        if (pos == null) return false
        if (getter !is Level) return false
        if (getter.isClientSide) return isPhasedClient(pos)
        if (phased.isEmpty()) return false
        return phased[getter.dimension()]?.containsKey(pos.asLong()) == true
    }

    // Chunk-build workers read this while the client thread writes, so publish a new read-only copy at each change.
    @Volatile
    private var clientPhased: LongSet = LongSets.EMPTY_SET

    @JvmStatic
    fun isPhasedClient(pos: BlockPos): Boolean = isPhasedClient(pos.asLong())

    @JvmStatic
    fun isPhasedClient(key: Long): Boolean {
        val snapshot = clientPhased
        return !snapshot.isEmpty() && snapshot.contains(key)
    }

    fun clientPhased(): LongSet = clientPhased

    fun setClientPhased(positions: LongArray, isPhased: Boolean) {
        val next = LongOpenHashSet(clientPhased)
        for (key in positions) if (isPhased) next.add(key) else next.remove(key)
        clientPhased = if (next.isEmpty()) LongSets.EMPTY_SET else LongSets.unmodifiable(next)
    }

    fun clearClient() {
        clientPhased = LongSets.EMPTY_SET
    }

    fun open(ctx: SpellContext, centre: Vec3, magnitude: Float) =
        open(ctx, centre, 1.5 + 0.5 * magnitude, SpellCurves.ticks(3f, 1f, magnitude))

    fun open(ctx: SpellContext, centre: Vec3, radius: Double, ticks: Int) {
        val r = Math.ceil(radius).toInt()
        val origin = BlockPos.containing(centre)
        val inside = BlockPos.betweenClosed(origin.offset(-r, -r, -r), origin.offset(r, r, r))
            .filter { Vec3.atCenterOf(it).distanceToSqr(centre) <= radius * radius }
            .map { it.immutable() }
        phase(ctx, inside, ticks)
    }

    fun canPhase(ctx: SpellContext, pos: BlockPos): Boolean {
        val level = ctx.level
        val state = level.getBlockState(pos)
        if (state.hasBlockEntity() || !state.isCollisionShapeFullBlock(level, pos)) return false
        return state.getDestroySpeed(level, pos) >= 0f && ctx.mayChange(pos, Blocks.AIR.defaultBlockState())
    }

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

    fun phasedIn(level: ServerLevel): LongArray = phased[level.dimension()]?.keys?.toLongArray() ?: LongArray(0)
}
