package org.shipwrights.enderkinesis.spell

import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.event.events.common.TickEvent
import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import it.unimi.dsi.fastutil.ints.IntArrayList
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.LongArrayList
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

/** Gas is never in solid blocks or water. Clients thin the gas between full syncs themselves
 *  (`GasFieldClient`). */
object GasField {
    const val DECAY_PER_TICK = 1f / 6000f
    const val PULSE_ADD = 0.05f
    const val ONCE_FILL_PER_TICK = 1f / 1200f
    private const val POISON_EVERY = 10
    private const val CHECK_EVERY = 20
    private const val RESYNC_EVERY = 40
    private const val POISON_MIN = 0.05f
    private const val POISON_TICKS = 40
    private const val POISON_TICKS_PER_GAS = 80
    private const val POISON_STRONG = 0.7f
    private const val DOME_HEIGHT = 0.7
    private const val SPREAD_PER_CHUNK = 6
    private const val SPREAD_SHARE = 0.5f
    private const val SINK_PER_CHUNK = 8
    private const val SINK_SHARE = 0.5f
    private const val SPREAD_MIN = 0.004f
    private const val SPRAY_BLOW = 0.6f
    private const val SPRAY_BLOW_SHARE = 0.5f
    const val AREA_BLOW = 0.5f
    // Too near the middle of a burst to know which way is out.
    private const val BURST_STILL = 0.3
    private const val FLOW_DRAG = 0.88f
    private const val STILL = 0.02f
    private const val FLOW_MAX = 0.6f
    // When full, gas moves only into blocks that hold some. Many Sprays held for minutes stay far under it.
    private const val MAX_CELLS = 1 shl 17
    private val FACES = Direction.values()

    private class LevelGas {
        val chunks = Long2ObjectOpenHashMap<Int2FloatOpenHashMap>()
        // Velocity in blocks a tick, by chunk and block.
        val flows = Long2ObjectOpenHashMap<Int2ObjectOpenHashMap<FloatArray>>()
        val dirty = LongOpenHashSet()
        val changed = Long2ObjectOpenHashMap<Int2FloatOpenHashMap>()
        val spareChanged = ArrayList<Int2FloatOpenHashMap>()
        var count = 0

        val chunkKeys = LongArrayList()
        val indices = IntArrayList()
        val movingKeys = LongArrayList()
        val movingCells = IntArrayList()
        val movingFlows = ArrayList<FloatArray>()
        val openFaces = IntArray(FACES.size)
        val at = BlockPos.MutableBlockPos()
        val near = BlockPos.MutableBlockPos()

        fun changedIn(key: Long): Int2FloatOpenHashMap =
            changed.get(key) ?: (spareChanged.removeLastOrNull() ?: Int2FloatOpenHashMap()).also { changed.put(key, it) }

        fun hasRoom(there: Float): Boolean = there > 0f || count < MAX_CELLS
    }

    // Game thread only.
    private val levels = WeakHashMap<ServerLevel, LevelGas>()

    fun init() {
        TickEvent.SERVER_LEVEL_POST.register { level -> tick(level) }
        LifecycleEvent.SERVER_STOPPING.register { levels.clear() }
    }

    fun open(level: Level, pos: BlockPos): Boolean =
        level.getBlockState(pos).getCollisionShape(level, pos).isEmpty && level.getFluidState(pos).isEmpty

    fun index(level: Level, pos: BlockPos): Int = ((pos.y - level.minBuildHeight) shl 8) or ((pos.z and 15) shl 4) or (pos.x and 15)

    fun posOf(level: Level, chunk: Long, index: Int, out: BlockPos.MutableBlockPos): BlockPos.MutableBlockPos = out.set(
        ChunkPos.getX(chunk) * 16 + (index and 15), (index shr 8) + level.minBuildHeight, ChunkPos.getZ(chunk) * 16 + ((index shr 4) and 15),
    )

    private fun key(pos: BlockPos): Long = ChunkPos.asLong(pos.x shr 4, pos.z shr 4)

    fun add(level: ServerLevel, pos: BlockPos, amount: Float) {
        if (amount <= 0f || !level.isLoaded(pos) || !open(level, pos)) return
        val gas = levels.getOrPut(level) { LevelGas() }
        val key = key(pos)
        val i = index(level, pos)
        val had = gas.chunks.get(key)?.get(i) ?: 0f
        if (!gas.hasRoom(had)) return
        val value = minOf(1f, had + amount)
        gas.chunks.getOrPut(key) { Int2FloatOpenHashMap() }.put(i, value)
        if (had == 0f) gas.count++
        gas.changedIn(key).put(i, value)
    }

    // A flood fill from the hand costs less than a line-of-sight test for each block, and a wall still stops it.
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

    // Within one block of the tip, the block of the hand counts.
    private fun inCone(pos: BlockPos, origin: Vec3, direction: Vec3, length: Double, halfAngle: Double): Boolean {
        val to = Vec3.atCenterOf(pos).subtract(origin)
        val dist = to.length()
        if (dist > length) return false
        if (dist < 1.0) return true
        return to.dot(direction) / dist >= Math.cos(halfAngle)
    }

    fun addDome(level: ServerLevel, centre: Vec3, radius: Double, amount: Float) =
        forDome(centre, radius) { at, _ -> add(level, at, amount) }

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

    /** [f] gets each block and its reach: 0 at the middle, 1 at the edge. */
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
                gas.count -= entry.value.size
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
                if (left <= 0f || (check && !open(level, posOf(level, key, cell.intKey, gas.at)))) {
                    cells.remove()
                    gas.count--
                    flows?.remove(cell.intKey)
                } else cell.setValue(left)
            }
            if (entry.value.isEmpty()) {
                chunks.remove()
                gas.flows.remove(key)
                // Clients thin the gas too. This send only makes sure that it is gone.
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
        // Chunks sent whole this tick already carry their changes.
        val changed = gas.changed.long2ObjectEntrySet().fastIterator()
        while (changed.hasNext()) {
            val entry = changed.next()
            if (!send.contains(entry.longKey)) SpellNetwork.sendGasCells(level, entry.longKey, entry.value)
            entry.value.clear()
            gas.spareChanged += entry.value
        }
        gas.changed.clear()
        if (gas.chunks.isEmpty()) levels.remove(level)
    }

    private fun flow(level: ServerLevel, gas: LevelGas) {
        if (gas.flows.isEmpty()) return
        // Snapshot first, so each block moves once, as it was at the start of the tick.
        val keys = gas.movingKeys; val cells = gas.movingCells; val moving = gas.movingFlows
        val chunks = gas.flows.long2ObjectEntrySet().fastIterator()
        while (chunks.hasNext()) {
            val entry = chunks.next()
            val flows = entry.value.int2ObjectEntrySet().fastIterator()
            while (flows.hasNext()) {
                val f = flows.next()
                keys.add(entry.longKey); cells.add(f.intKey); moving += f.value
            }
        }
        val from = gas.at
        val to = gas.near
        for (n in moving.indices) {
            posOf(level, keys.getLong(n), cells.getInt(n), from)
            flowFrom(level, gas, from, to, moving[n])
        }
        keys.clear(); cells.clear(); moving.clear()
    }

    private fun flowFrom(level: ServerLevel, gas: LevelGas, from: BlockPos, to: BlockPos.MutableBlockPos, v: FloatArray) {
        var here = amountAt(gas, level, from)
        if (here <= 0f) {
            removeFlow(gas, level, from)
            return
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
            if (moved <= 0f || !gas.hasRoom(there)) continue
            here -= moved
            set(gas, level, to, there + moved)
            if (amountAt(gas, level, to) > 0f) {
                val w = moved / (there + moved)
                val dv = gas.flows.getOrPut(key(to)) { Int2ObjectOpenHashMap() }.getOrPut(index(level, to)) { FloatArray(3) }
                for (a in 0..2) dv[a] += (v[a] - dv[a]) * w
            }
        }
        set(gas, level, from, here)
        for (a in 0..2) v[a] *= FLOW_DRAG
        if (abs(v[0]) + abs(v[1]) + abs(v[2]) < STILL || amountAt(gas, level, from) <= 0f) removeFlow(gas, level, from)
    }

    private fun removeFlow(gas: LevelGas, level: ServerLevel, pos: BlockPos) {
        val key = key(pos)
        val flows = gas.flows.get(key) ?: return
        flows.remove(index(level, pos))
        if (flows.isEmpty()) gas.flows.remove(key)
    }

    private fun spread(level: ServerLevel, gas: LevelGas) {
        val random = level.random
        val from = gas.at
        val pos = gas.near
        val openFaces = gas.openFaces
        val keys = gas.chunkKeys
        keys.clear(); keys.addAll(gas.chunks.keys)
        for (k in 0 until keys.size) {
            val key = keys.getLong(k)
            val cells = gas.chunks.get(key) ?: continue
            val indices = gas.indices
            indices.clear(); indices.addAll(cells.keys)
            // Partial shuffle, so the picks are all different.
            for (n in 0 until minOf(SPREAD_PER_CHUNK, indices.size)) {
                val i = pick(indices, n, random.nextInt(indices.size - n))
                posOf(level, key, i, from)
                var faces = 0
                for (f in FACES.indices) {
                    pos.setWithOffset(from, FACES[f])
                    if (level.isLoaded(pos) && open(level, pos) && gas.hasRoom(amountAt(gas, level, pos))) openFaces[faces++] = f
                }
                if (faces == 0) continue
                var total = amountAt(gas, level, from)
                for (f in 0 until faces) total += amountAt(gas, level, pos.setWithOffset(from, FACES[openFaces[f]]))
                val even = total / (faces + 1)
                set(gas, level, from, mix(amountAt(gas, level, from), even))
                for (f in 0 until faces) {
                    pos.setWithOffset(from, FACES[openFaces[f]])
                    set(gas, level, pos, mix(amountAt(gas, level, pos), even))
                }
            }
        }
    }

    private fun pick(indices: IntArrayList, n: Int, offset: Int): Int {
        val j = n + offset
        val i = indices.getInt(j)
        indices.set(j, indices.getInt(n))
        indices.set(n, i)
        return i
    }

    // Each block moves the same share of its difference, and the differences add to zero, so no gas is made or lost.
    private fun mix(amount: Float, even: Float): Float = amount + (even - amount) * SPREAD_SHARE

    private fun sink(level: ServerLevel, gas: LevelGas) {
        val random = level.random
        val from = gas.at
        val below = gas.near
        val keys = gas.chunkKeys
        keys.clear(); keys.addAll(gas.chunks.keys)
        for (k in 0 until keys.size) {
            val key = keys.getLong(k)
            val cells = gas.chunks.get(key) ?: continue
            val indices = gas.indices
            indices.clear(); indices.addAll(cells.keys)
            for (n in 0 until minOf(SINK_PER_CHUNK, indices.size)) {
                val i = pick(indices, n, random.nextInt(indices.size - n))
                posOf(level, key, i, from)
                below.setWithOffset(from, Direction.DOWN)
                if (!level.isLoaded(below) || !open(level, below)) continue
                val here = amountAt(gas, level, from)
                val under = amountAt(gas, level, below)
                val moved = minOf(here * SINK_SHARE, 1f - under)
                if (moved <= 0f || !gas.hasRoom(under)) continue
                set(gas, level, from, here - moved)
                set(gas, level, below, under + moved)
            }
        }
    }

    private fun amountAt(gas: LevelGas, level: ServerLevel, pos: BlockPos): Float =
        gas.chunks.get(key(pos))?.get(index(level, pos)) ?: 0f

    private fun set(gas: LevelGas, level: ServerLevel, pos: BlockPos, amount: Float) {
        val key = key(pos)
        val i = index(level, pos)
        val value = if (amount < SPREAD_MIN) 0f else minOf(1f, amount)
        if (value == 0f) {
            if ((gas.chunks.get(key)?.remove(i) ?: 0f) != 0f) gas.count--
            gas.flows.get(key)?.remove(i)
        } else if (gas.chunks.getOrPut(key) { Int2FloatOpenHashMap() }.put(i, value) == 0f) gas.count++
        gas.changedIn(key).put(i, value)
    }

    private fun poison(level: ServerLevel, gas: LevelGas) {
        val chunks = gas.chunks.long2ObjectEntrySet().fastIterator()
        while (chunks.hasNext()) {
            val entry = chunks.next()
            val key = entry.longKey
            var lo = Int.MAX_VALUE; var hi = Int.MIN_VALUE
            val cells = entry.value.int2FloatEntrySet().fastIterator()
            while (cells.hasNext()) {
                val y = cells.next().intKey shr 8
                lo = minOf(lo, y); hi = maxOf(hi, y)
            }
            if (lo > hi) continue
            val x = ChunkPos.getX(key) * 16.0; val z = ChunkPos.getZ(key) * 16.0
            val box = AABB(x, lo + level.minBuildHeight - 2.0, z, x + 16, hi + level.minBuildHeight + 1.0, z + 16)
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
