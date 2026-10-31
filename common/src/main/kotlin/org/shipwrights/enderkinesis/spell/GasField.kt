package org.shipwrights.enderkinesis.spell

import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.event.events.common.TickEvent
import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.ceil
import org.shipwrights.enderkinesis.registry.EKEffects

/**
 * Poison's gas: a light fluid over the world, kept per chunk, holding an amount from 0 to 1 in
 * each open block (nothing to collide with, no fluid), and how fast that gas is moving. Gas is
 * never in solid blocks or water.
 *
 * - A Spray adds [PULSE_ADD] a pulse to the open blocks its cone reaches and blows the gas there
 *   forward along it, hardest down its middle ([spray]); a Beam adds to a dome at its end; an
 *   Area cast, a Bolt's impact or a rune leaves a small dome, thick in the middle and thin at
 *   the edge, that rolls outward ([addBurst]); anything else Poison casts fills a still dome
 *   at once ([add], [addDome]).
 * - Moving gas carries itself along: each tick some of each moving block's gas goes on into the
 *   open blocks it's heading for, taking its speed with it, and slows as it goes ([flow]). A
 *   wall stops it that way.
 * - Gas is heavy: each tick, [SINK_PER_CHUNK] blocks of gas in every chunk let [SINK_SHARE]
 *   of theirs down into an open block below ([sink]), so a cloud settles and pools in hollows.
 * - Gas drifts: each tick, [SPREAD_PER_CHUNK] blocks of gas in every chunk move [SPREAD_SHARE]
 *   of the way to evening out with the open blocks on their six faces ([spread]), so a cloud
 *   creeps slowly out into the air round it, thinning as it goes.
 * - Every block's gas thins by [DECAY_PER_TICK] on its own (a full block, shut in, lasts five
 *   minutes), and goes when it runs out or its block fills in or floods. Gas in a chunk that
 *   unloads is lost.
 * - Every [POISON_EVERY] ticks, whatever lives in gas is poisoned, longer and then harder the
 *   thicker the gas.
 *
 * Clients are sent every chunk's gas every [RESYNC_EVERY]
 * ticks (so a player arriving sees it), and thin it themselves in between (`GasFieldClient`).
 * What's added, and what moving, sinking and drifting change, goes to them as it happens,
 * block by block.
 */
object GasField {
    const val DECAY_PER_TICK = 1f / 6000f
    const val PULSE_ADD = 0.05f
    /** A one-off cast's Miasma: this much gas a tick of the time it would once have lasted. */
    const val ONCE_FILL_PER_TICK = 1f / 1200f
    private const val POISON_EVERY = 10
    /** How often blocks are looked at again for having filled in or flooded. */
    private const val CHECK_EVERY = 20
    private const val RESYNC_EVERY = 40
    /** Less gas than this does nothing to those in it. */
    private const val POISON_MIN = 0.05f
    private const val POISON_TICKS = 40
    private const val POISON_TICKS_PER_GAS = 80
    /** From this much gas, Poison II. */
    private const val POISON_STRONG = 0.7f
    /** A dome is squatter than it is wide. */
    private const val DOME_HEIGHT = 0.7
    /** Blocks of gas in each chunk that share theirs out each tick. */
    private const val SPREAD_PER_CHUNK = 6
    /** How far a drift moves the blocks toward their even share: a slow creep, not a jump. */
    private const val SPREAD_SHARE = 0.5f
    /** Blocks of gas in each chunk that sink each tick, and how much of theirs goes down. */
    private const val SINK_PER_CHUNK = 8
    private const val SINK_SHARE = 0.5f
    /** Gas thinner than this, moved into a block, is lost to the air. */
    private const val SPREAD_MIN = 0.004f
    /** A Spray's blow down its middle, in blocks a tick, and how much of the way the gas it
     *  blows is brought to that each time. */
    private const val SPRAY_BLOW = 0.6f
    private const val SPRAY_BLOW_SHARE = 0.5f
    /** An Area cast's burst outward, in blocks a tick, near its middle (none at its edge). */
    const val AREA_BLOW = 0.5f
    /** The very middle of a burst, too near to say which way is out, isn't pushed. */
    private const val BURST_STILL = 0.3
    /** Moving gas keeps this much of its speed each tick, and stops below [STILL]. */
    private const val FLOW_DRAG = 0.88f
    private const val STILL = 0.02f
    /** At most this much of a block's gas moves on in a tick, however fast. */
    private const val FLOW_MAX = 0.6f
    private val FACES = Direction.values()

    private class LevelGas {
        val chunks = Long2ObjectOpenHashMap<Int2FloatOpenHashMap>()
        /** The gas that's moving: its velocity (x, y, z, blocks a tick), by chunk and block. */
        val flows = Long2ObjectOpenHashMap<Int2ObjectOpenHashMap<FloatArray>>()
        val dirty = LongOpenHashSet()
        /** Blocks changed this tick other than by adding, by chunk: their new amounts (0 for
         *  none left). */
        val changed = Long2ObjectOpenHashMap<Int2FloatOpenHashMap>()
    }

    // Game thread only.
    private val levels = WeakHashMap<ServerLevel, LevelGas>()

    fun init() {
        TickEvent.SERVER_LEVEL_POST.register { level -> tick(level) }
        LifecycleEvent.SERVER_STOPPING.register { levels.clear() }
    }

    /** Whether [pos] can hold gas. */
    fun open(level: Level, pos: BlockPos): Boolean =
        level.getBlockState(pos).getCollisionShape(level, pos).isEmpty && level.getFluidState(pos).isEmpty

    /** Where [pos] is kept in its chunk's gas: x and z within the chunk, and the height from the
     *  level's floor. [posOf] turns it back. */
    fun index(level: Level, pos: BlockPos): Int = ((pos.y - level.minBuildHeight) shl 8) or ((pos.z and 15) shl 4) or (pos.x and 15)

    fun posOf(level: Level, chunk: Long, index: Int): BlockPos = BlockPos(
        ChunkPos.getX(chunk) * 16 + (index and 15), (index shr 8) + level.minBuildHeight, ChunkPos.getZ(chunk) * 16 + ((index shr 4) and 15),
    )

    private fun key(pos: BlockPos): Long = ChunkPos.asLong(pos.x shr 4, pos.z shr 4)

    /** Adds [amount] of gas at [pos], if it's open and loaded (never past 1). */
    fun add(level: ServerLevel, pos: BlockPos, amount: Float) {
        if (amount <= 0f || !level.isLoaded(pos) || !open(level, pos)) return
        val gas = levels.getOrPut(level) { LevelGas() }
        val key = key(pos)
        val cells = gas.chunks.getOrPut(key) { Int2FloatOpenHashMap() }
        val i = index(level, pos)
        val value = minOf(1f, cells.get(i) + amount)
        cells.put(i, value)
        gas.changed.getOrPut(key) { Int2FloatOpenHashMap() }.put(i, value)
    }

    /**
     * A Spray from [origin] along the unit [direction], [length] long and [halfAngle] wide: adds
     * [amount] to every open block of the cone the gas can reach from the hand (spreading out
     * from it through open blocks, so a wall stops it) and blows the gas there forward along the
     * cone, hardest down its middle and not at all at its edge. Cheaper than looking for each
     * block of the cone in sight, and it fills the whole cone.
     */
    fun spray(level: ServerLevel, origin: Vec3, direction: Vec3, length: Double, halfAngle: Double, amount: Float) {
        val gas = levels.getOrPut(level) { LevelGas() }
        val start = BlockPos.containing(origin)
        if (!level.isLoaded(start) || !open(level, start)) return
        val seen = HashSet<BlockPos>()
        val queue = ArrayDeque<BlockPos>()
        seen += start
        queue += start
        val next = BlockPos.MutableBlockPos()
        while (queue.isNotEmpty()) {
            val at = queue.removeFirst()
            add(level, at, amount)
            val to = Vec3.atCenterOf(at).subtract(origin)
            val dist = to.length()
            val off = if (dist < 1.0e-3) 0.0 else Math.acos((to.dot(direction) / dist).coerceIn(-1.0, 1.0)) / halfAngle
            val blow = (SPRAY_BLOW * (1.0 - off * off).coerceAtLeast(0.0)).toFloat()
            if (blow > 0f && amountAt(gas, level, at) > 0f) {
                val flow = gas.flows.getOrPut(key(at)) { Int2ObjectOpenHashMap() }.getOrPut(index(level, at)) { FloatArray(3) }
                flow[0] += (direction.x.toFloat() * blow - flow[0]) * SPRAY_BLOW_SHARE
                flow[1] += (direction.y.toFloat() * blow - flow[1]) * SPRAY_BLOW_SHARE
                flow[2] += (direction.z.toFloat() * blow - flow[2]) * SPRAY_BLOW_SHARE
            }
            for (face in FACES) {
                next.setWithOffset(at, face)
                if (next in seen || !inCone(next, origin, direction, length, halfAngle)) continue
                val pos = next.immutable()
                seen += pos
                if (level.isLoaded(pos) && open(level, pos)) queue += pos
            }
        }
    }

    /** Whether [pos]'s centre is in the cone, or so near its tip that the hand's block counts. */
    private fun inCone(pos: BlockPos, origin: Vec3, direction: Vec3, length: Double, halfAngle: Double): Boolean {
        val to = Vec3.atCenterOf(pos).subtract(origin)
        val dist = to.length()
        if (dist > length) return false
        if (dist < 1.0) return true
        return to.dot(direction) / dist >= Math.cos(halfAngle)
    }

    /** Adds [amount] to every open block of a dome [radius] wide at [centre]. */
    fun addDome(level: ServerLevel, centre: Vec3, radius: Double, amount: Float) =
        forDome(centre, radius) { at, _ -> add(level, at, amount) }

    /**
     * An Area cast's, Bolt impact's or rune's burst: a dome [radius] wide at [centre], [amount] thick at its middle and
     * thinning to nothing at its edge, the gas pushed outward hardest near the middle (up to
     * [AREA_BLOW] blocks a tick), so the thick of it rolls out as a wave.
     */
    fun addBurst(level: ServerLevel, centre: Vec3, radius: Double, amount: Float) {
        forDome(centre, radius) { at, reach ->
            add(level, at, amount * (1f - reach * reach))
            val gas = levels[level] ?: return@forDome
            if (amountAt(gas, level, at) <= 0f) return@forDome
            val away = Vec3.atCenterOf(at).subtract(centre)
            val dist = away.length()
            if (dist < BURST_STILL) return@forDome
            val speed = (AREA_BLOW * (1f - reach) / dist).toFloat()
            gas.flows.getOrPut(key(at)) { Int2ObjectOpenHashMap() }.put(index(level, at), floatArrayOf(away.x.toFloat() * speed, away.y.toFloat() * speed, away.z.toFloat() * speed))
        }
    }

    /** Each block of a dome [radius] wide at [centre], with how far out it is (0 at the middle,
     *  1 at the edge). */
    private inline fun forDome(centre: Vec3, radius: Double, f: (BlockPos, Float) -> Unit) {
        val h = radius * DOME_HEIGHT
        val r = ceil(radius).toInt()
        val c = BlockPos.containing(centre)
        for (dx in -r..r) for (dy in -r..r) for (dz in -r..r) {
            val at = BlockPos(c.x + dx, c.y + dy, c.z + dz)
            val x = (at.x + 0.5 - centre.x) / radius
            val y = (at.y + 0.5 - centre.y) / h
            val z = (at.z + 0.5 - centre.z) / radius
            val reach = x * x + y * y + z * z
            if (reach <= 1.0) f(at, Math.sqrt(reach).toFloat())
        }
    }

    /** The gas at [pos]. */
    fun amount(level: ServerLevel, pos: BlockPos): Float {
        val gas = levels[level] ?: return 0f
        return amountAt(gas, level, pos)
    }

    private fun tick(level: ServerLevel) {
        val gas = levels[level] ?: return
        val time = level.gameTime
        val check = time % CHECK_EVERY == 0L
        val chunks = gas.chunks.long2ObjectEntrySet().fastIterator()
        while (chunks.hasNext()) {
            val entry = chunks.next()
            val key = entry.longKey
            if (!level.hasChunk(ChunkPos.getX(key), ChunkPos.getZ(key))) {
                chunks.remove()
                gas.flows.remove(key)
                gas.dirty.remove(key)
                continue
            }
            val flows = gas.flows.get(key)
            val cells = entry.value.int2FloatEntrySet().fastIterator()
            while (cells.hasNext()) {
                val cell = cells.next()
                val left = cell.floatValue - DECAY_PER_TICK
                if (left <= 0f || (check && !open(level, posOf(level, key, cell.intKey)))) {
                    cells.remove()
                    flows?.remove(cell.intKey)
                } else cell.setValue(left)
            }
            if (entry.value.isEmpty()) {
                chunks.remove()
                gas.flows.remove(key)
                // Clients thin it away too; this only makes sure it's gone.
                gas.dirty.add(key)
            }
        }
        flow(level, gas)
        sink(level, gas)
        spread(level, gas)
        if (time % POISON_EVERY == 0L) poison(level, gas)
        val send = if (time % RESYNC_EVERY == 0L) LongOpenHashSet(gas.chunks.keys).apply { addAll(gas.dirty) } else gas.dirty
        val it = send.iterator()
        while (it.hasNext()) {
            val key = it.nextLong()
            SpellNetwork.sendGas(level, key, gas.chunks.get(key))
        }
        gas.dirty.clear()
        // What moving, sinking and drifting changed, in the chunks not just sent whole.
        for (entry in gas.changed.long2ObjectEntrySet()) {
            if (!send.contains(entry.longKey)) SpellNetwork.sendGasCells(level, entry.longKey, entry.value)
        }
        gas.changed.clear()
        if (gas.chunks.isEmpty()) levels.remove(level)
    }

    /**
     * Moving gas: each moving block sends on as much of its gas along each axis as its speed
     * that way (in blocks a tick, [FLOW_MAX] of it at most), into the open block it's heading
     * for, as much as that has room for. What moves takes its velocity with it, mixed by amount
     * into what's there. A way that's shut stops the gas going that way. Then it slows.
     */
    private fun flow(level: ServerLevel, gas: LevelGas) {
        if (gas.flows.isEmpty()) return
        // Each moving block once, as it was at the start of the tick.
        val moving = ArrayList<Pair<BlockPos, FloatArray>>()
        for (entry in gas.flows.long2ObjectEntrySet()) {
            for (f in entry.value.int2ObjectEntrySet()) moving += posOf(level, entry.longKey, f.intKey) to f.value
        }
        val to = BlockPos.MutableBlockPos()
        for ((from, v) in moving) {
            var here = amountAt(gas, level, from)
            if (here <= 0f) {
                removeFlow(gas, level, from)
                continue
            }
            val total = abs(v[0]) + abs(v[1]) + abs(v[2])
            val scale = if (total > FLOW_MAX) FLOW_MAX / total else 1f
            for (axis in 0..2) {
                val speed = v[axis]
                if (speed == 0f) continue
                val step = if (speed > 0f) 1 else -1
                to.set(from.x + if (axis == 0) step else 0, from.y + if (axis == 1) step else 0, from.z + if (axis == 2) step else 0)
                if (!level.isLoaded(to) || !open(level, to)) {
                    v[axis] = 0f
                    continue
                }
                val there = amountAt(gas, level, to)
                val moved = minOf(here * abs(speed) * scale, 1f - there)
                if (moved <= 0f) continue
                here -= moved
                val dest = to.immutable()
                set(gas, level, dest, there + moved)
                if (amountAt(gas, level, dest) > 0f) {
                    val w = moved / (there + moved)
                    val dv = gas.flows.getOrPut(key(dest)) { Int2ObjectOpenHashMap() }.getOrPut(index(level, dest)) { FloatArray(3) }
                    for (a in 0..2) dv[a] += (v[a] - dv[a]) * w
                }
            }
            set(gas, level, from, here)
            for (a in 0..2) v[a] *= FLOW_DRAG
            if (abs(v[0]) + abs(v[1]) + abs(v[2]) < STILL || amountAt(gas, level, from) <= 0f) removeFlow(gas, level, from)
        }
    }

    private fun removeFlow(gas: LevelGas, level: ServerLevel, pos: BlockPos) {
        val key = key(pos)
        val flows = gas.flows.get(key) ?: return
        flows.remove(index(level, pos))
        if (flows.isEmpty()) gas.flows.remove(key)
    }

    /** Drifting: in every chunk, [SPREAD_PER_CHUNK] blocks of gas picked at random move
     *  toward evening out with the open blocks on their faces, across chunk edges too. */
    private fun spread(level: ServerLevel, gas: LevelGas) {
        val random = level.random
        val pos = BlockPos.MutableBlockPos()
        val open = ArrayList<BlockPos>(6)
        for (key in gas.chunks.keys.toLongArray()) {
            val cells = gas.chunks.get(key) ?: continue
            val indices = cells.keys.toIntArray()
            // A partial shuffle: the first few picks, all different.
            for (n in 0 until minOf(SPREAD_PER_CHUNK, indices.size)) {
                val j = n + random.nextInt(indices.size - n)
                val i = indices[j]; indices[j] = indices[n]; indices[n] = i
                val from = posOf(level, key, i)
                open.clear()
                for (face in FACES) {
                    pos.setWithOffset(from, face)
                    if (level.isLoaded(pos) && open(level, pos)) open += pos.immutable()
                }
                if (open.isEmpty()) continue
                var total = amountAt(gas, level, from)
                for (p in open) total += amountAt(gas, level, p)
                val even = total / (open.size + 1)
                set(gas, level, from, mix(amountAt(gas, level, from), even))
                for (p in open) set(gas, level, p, mix(amountAt(gas, level, p), even))
            }
        }
    }

    /** [amount] moved [SPREAD_SHARE] of the way to [even]. Every block moves the same share of
     *  its difference, and the differences add to nothing, so no gas is made or lost. */
    private fun mix(amount: Float, even: Float): Float = amount + (even - amount) * SPREAD_SHARE

    /** Gas settling: in every chunk, [SINK_PER_CHUNK] blocks of gas picked at random over an
     *  open block let [SINK_SHARE] of theirs down into it, as much as it has room for. */
    private fun sink(level: ServerLevel, gas: LevelGas) {
        val random = level.random
        for (key in gas.chunks.keys.toLongArray()) {
            val cells = gas.chunks.get(key) ?: continue
            val indices = cells.keys.toIntArray()
            for (n in 0 until minOf(SINK_PER_CHUNK, indices.size)) {
                val j = n + random.nextInt(indices.size - n)
                val i = indices[j]; indices[j] = indices[n]; indices[n] = i
                val from = posOf(level, key, i)
                val below = from.below()
                if (!level.isLoaded(below) || !open(level, below)) continue
                val here = amountAt(gas, level, from)
                val under = amountAt(gas, level, below)
                val moved = minOf(here * SINK_SHARE, 1f - under)
                if (moved <= 0f) continue
                set(gas, level, from, here - moved)
                set(gas, level, below, under + moved)
            }
        }
    }

    private fun amountAt(gas: LevelGas, level: ServerLevel, pos: BlockPos): Float =
        gas.chunks.get(key(pos))?.get(index(level, pos)) ?: 0f

    /** The [amount] moving, sinking or drifting leaves at [pos], noted for clients. A block
     *  left with none stops moving. */
    private fun set(gas: LevelGas, level: ServerLevel, pos: BlockPos, amount: Float) {
        val key = key(pos)
        val i = index(level, pos)
        val value = if (amount < SPREAD_MIN) 0f else minOf(1f, amount)
        if (value == 0f) {
            gas.chunks.get(key)?.remove(i)
            gas.flows.get(key)?.remove(i)
        } else gas.chunks.getOrPut(key) { Int2FloatOpenHashMap() }.put(i, value)
        gas.changed.getOrPut(key) { Int2FloatOpenHashMap() }.put(i, value)
    }

    private fun poison(level: ServerLevel, gas: LevelGas) {
        for (entry in gas.chunks.long2ObjectEntrySet()) {
            val key = entry.longKey
            var lo = Int.MAX_VALUE; var hi = Int.MIN_VALUE
            for (i in entry.value.keys) { lo = minOf(lo, i shr 8); hi = maxOf(hi, i shr 8) }
            if (lo > hi) continue
            val x = ChunkPos.getX(key) * 16.0; val z = ChunkPos.getZ(key) * 16.0
            val box = AABB(x, lo + level.minBuildHeight - 2.0, z, x + 16, hi + level.minBuildHeight + 1.0, z + 16)
            // A Poison Cloak's wearer breathes the gas unharmed.
            for (living in level.getEntitiesOfClass(LivingEntity::class.java, box) { it.isAlive && it.isAffectedByPotions && !it.hasEffect(EKEffects.POISON_CLOAK.get()) }) {
                val feet = BlockPos.containing(living.position().add(0.0, 0.1, 0.0))
                val eyes = BlockPos.containing(living.eyePosition)
                val a = maxOf(amount(level, feet), amount(level, eyes))
                if (a < POISON_MIN) continue
                val ticks = POISON_TICKS + Mth.floor(POISON_TICKS_PER_GAS * a)
                living.addEffect(MobEffectInstance(MobEffects.POISON, ticks, if (a >= POISON_STRONG) 1 else 0))
            }
        }
    }
}
