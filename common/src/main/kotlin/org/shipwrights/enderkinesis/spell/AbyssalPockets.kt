package org.shipwrights.enderkinesis.spell

import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.event.events.common.TickEvent
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap
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
import net.minecraft.sounds.SoundSource
import org.shipwrights.enderkinesis.registry.EKSounds

/**
 * Void's Abyssal Pocket: full blocks without a block entity in a small core are phased out
 * of the world for a while. A phased block stays in the world data, so a ship's mass and
 * shape don't change, nothing can be placed in the gap and fluids can't flow in; but it has
 * no collision (entities fall through) and clients stop drawing it.
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
    fun isPhased(getter: BlockGetter, pos: BlockPos): Boolean {
        if (phased.isEmpty() || getter !is Level || getter.isClientSide) return false
        return phased[getter.dimension()]?.containsKey(pos.asLong()) == true
    }

    /** Opens a pocket of radius 1.5 + 0.5 × magnitude around [centre] for 3 + magnitude s. */
    fun open(ctx: SpellContext, centre: Vec3, magnitude: Float) {
        val level = ctx.level
        val radius = 1.5 + 0.5 * magnitude
        val until = level.gameTime + SpellCurves.ticks(3f, 1f, magnitude)
        val map = phased.getOrPut(level.dimension()) { Long2LongOpenHashMap() }
        val added = ArrayList<Long>()
        val r = Math.ceil(radius).toInt()
        val origin = BlockPos.containing(centre)
        for (pos in BlockPos.betweenClosed(origin.offset(-r, -r, -r), origin.offset(r, r, r))) {
            if (Vec3.atCenterOf(pos).distanceToSqr(centre) > radius * radius) continue
            val state = level.getBlockState(pos)
            if (state.hasBlockEntity() || !state.isCollisionShapeFullBlock(level, pos)) continue
            if (state.getDestroySpeed(level, pos) < 0f || !ctx.mayChange(pos, Blocks.AIR.defaultBlockState())) continue
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
        level.playSound(null, centre.x, centre.y, centre.z, EKSounds.POCKET_PHASE.get(), SoundSource.BLOCKS, 1f, 0.7f)
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
}
