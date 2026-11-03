package org.shipwrights.enderkinesis.spell

import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.event.events.common.TickEvent
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.damagesource.DamageTypes
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.item.AegisBox
import org.shipwrights.enderkinesis.item.BeamSegment
import org.shipwrights.enderkinesis.item.BeamTrace
import org.shipwrights.enderkinesis.item.SunderingBeamTrace
import java.util.UUID

/** Resolve at SERVER_POST, after all casters tick. Test for opposites before head-on, so that head-on opposites still explode. */
object BeamManager {

    /** [trace] already reflects off Aegis boxes and stops at a self-loop. */
    class Beam(
        val id: UUID,
        val level: ServerLevel,
        val owner: LivingEntity?,
        val glyphs: List<Element>,
        val sundering: Float,
        val trace: BeamTrace,
        val reach: Double,
        val halfWidth: Double,
        val fromStaff: Boolean,
        val cancel: () -> Unit,
    ) {
        val magnitude: Float get() = glyphs.size + sundering
    }

    // Game thread only.
    private val beams = ArrayList<Beam>()
    private var clashes: Map<Pair<UUID, UUID>, Clash> = emptyMap()

    private class Clash(val start: Long, val ticks: Int)
    private var cuts: Map<UUID, Double> = emptyMap()

    fun init() {
        TickEvent.SERVER_POST.register(TickEvent.Server(::resolve))
        LifecycleEvent.SERVER_STOPPED.register {
            beams.clear()
            cuts = emptyMap()
            clashes = emptyMap()
            mining = emptyMap()
        }
    }

    fun register(beam: Beam) {
        beams += beam
    }

    fun mirrors(level: Level, caster: Entity?): List<AegisBox.Frame> =
        SunderingBeamTrace.collectWieldedShields(level, caster)

    /** Uses the cut from the last tick, so a parent stops at the crossing one tick late. */
    fun clip(id: UUID, trace: BeamTrace): List<BeamSegment> {
        val cut = cuts[id] ?: return trace.segments
        val out = ArrayList<BeamSegment>()
        var travelled = 0.0
        for (seg in trace.segments) {
            if (travelled + seg.length >= cut) {
                val keep = (cut - travelled).coerceAtLeast(0.0)
                out += BeamSegment(seg.start, seg.direction, seg.start.add(seg.direction.scale(keep)), keep)
                break
            }
            out += seg
            travelled += seg.length
        }
        return out
    }

    fun isCut(id: UUID): Boolean = id in cuts

    private class Crossing(val pathA: Double, val pathB: Double, val dirA: Vec3, val dirB: Vec3, val point: Vec3)

    private class Resolution {
        val cancelled = LinkedHashSet<Beam>()
        val merged = HashSet<Beam>()
        val nextCuts = HashMap<UUID, Double>()
        val synced = HashMap<ServerLevel, MergedSync>()
        val nextClashes = HashMap<Pair<UUID, UUID>, Clash>()
        val reach = HashMap<Beam, Double>()
        /** Each merged beam with all the beams that fed it. */
        val lineage = HashMap<Beam, Set<Beam>>()
        val spawned = ArrayList<Beam>()

        fun related(a: Beam, b: Beam): Boolean = lineage[a]?.contains(b) == true || lineage[b]?.contains(a) == true

        fun stopAt(a: Beam, b: Beam, crossing: Crossing): MergedSync {
            reach[a] = crossing.pathA
            reach[b] = crossing.pathB
            nextCuts[a.id] = crossing.pathA
            nextCuts[b.id] = crossing.pathB
            val sync = synced.getOrPut(a.level) { MergedSync() }
            // The client cuts by owner entity id, which a merged beam shares with its stronger parent.
            if (a !in lineage) a.owner?.let { sync.cuts[it.id] = crossing.pathA }
            if (b !in lineage) b.owner?.let { sync.cuts[it.id] = crossing.pathB }
            return sync
        }

        /** True when the clash goes now. Until then, both beams stop at the crossing. */
        fun clash(a: Beam, b: Beam, crossing: Crossing, ticks: Int, begin: () -> Unit): Boolean {
            val key = if (a.id < b.id) a.id to b.id else b.id to a.id
            val now = a.level.gameTime
            val clash = clashes[key] ?: Clash(now, ticks).also { begin() }
            if (now - clash.start >= clash.ticks) return true
            nextClashes[key] = clash
            stopAt(a, b, crossing)
            return false
        }
    }

    private fun resolve(server: MinecraftServer) {
        if (beams.isEmpty()) {
            cuts = emptyMap()
            clashes = emptyMap()
            mining = emptyMap()
            return
        }
        val live = beams.toList()
        beams.clear()
        val r = Resolution()
        for (beam in live) {
            val loop = beam.trace.loopPoint ?: continue
            shockwave(beam.level, loop)
            r.cancelled += beam
        }
        for ((a, b, crossing) in crossingsNearestFirst(r, live, emptyList())) resolveCrossing(r, a, b, crossing)
        var older: List<Beam> = live
        var depth = 0
        while (r.spawned.isNotEmpty() && depth < MAX_MERGE_DEPTH) {
            val fresh = r.spawned.toList()
            r.spawned.clear()
            for ((a, b, crossing) in crossingsNearestFirst(r, fresh, older)) resolveCrossing(r, a, b, crossing)
            older = older + fresh
            depth++
        }
        cuts = r.nextCuts
        clashes = r.nextClashes
        mining = HashMap(nextMining)
        nextMining.clear()
        for ((level, sync) in r.synced) SpellNetwork.broadcastMerged(level, sync.beams, sync.cuts)
        for (beam in r.cancelled) beam.cancel()
    }

    // Resolve nearest the casters first and cut each beam then, so that the unseen tail past a cut meets nothing.
    /** Pairs each [fresh] beam with the other fresh beams and with each [older] beam. */
    private fun crossingsNearestFirst(r: Resolution, fresh: List<Beam>, older: List<Beam>): List<Triple<Beam, Beam, Crossing>> {
        val crossings = ArrayList<Triple<Beam, Beam, Crossing>>()
        fun check(a: Beam, b: Beam) {
            if (a.level !== b.level || r.related(a, b)) return
            // A signature splits off its lance, and these beams must not meet.
            if (a.owner != null && a.owner === b.owner) return
            crossing(a, b)?.let { crossings += Triple(a, b, it) }
        }
        for (i in fresh.indices) {
            for (j in i + 1 until fresh.size) check(fresh[i], fresh[j])
            for (b in older) check(fresh[i], b)
        }
        crossings.sortBy { (_, _, c) -> c.pathA + c.pathB }
        return crossings
    }

    private fun resolveCrossing(r: Resolution, a: Beam, b: Beam, crossing: Crossing) {
        if (a in r.cancelled || b in r.cancelled) return
        if (crossing.pathA > (r.reach[a] ?: Double.MAX_VALUE) || crossing.pathB > (r.reach[b] ?: Double.MAX_VALUE)) return
        val staff = a.fromStaff || b.fromStaff
        when {
            !staff && opposed(a.glyphs, b.glyphs) -> {
                if (r.clash(a, b, crossing, EXPLODE_CHARGE_TICKS) { charge(a, b, crossing) }) {
                    explode(a, b, crossing.point)
                    r.cancelled += a; r.cancelled += b
                }
            }
            !staff && BeamGeometry.headOn(crossing.dirA, crossing.dirB) -> {
                if (r.clash(a, b, crossing, PRESS_TICKS) { SpellVfx.beamPress(a.level, crossing.point, crossing.dirA, PRESS_TICKS) }) {
                    shockwave(a.level, crossing.point)
                    r.cancelled += a; r.cancelled += b
                }
            }
            a !in r.merged && b !in r.merged -> {
                r.merged += a; r.merged += b
                val sync = r.stopAt(a, b, crossing)
                if (!runMerged(r, a, b, crossing, sync)) {
                    r.cancelled += a; r.cancelled += b
                }
            }
        }
    }

    /** [elements] and [fused] are bitsets by Element ordinal. */
    class MergedBeam(val segments: List<BeamSegment>, val elements: Int, val parentA: Int, val parentB: Int, val fused: Int)

    /** [cuts] uses entity ids as keys, not beam UUIDs. */
    class MergedSync {
        val beams = ArrayList<MergedBeam>()
        val cuts = HashMap<Int, Double>()
    }

    private fun crossing(a: Beam, b: Beam): Crossing? {
        var pathA = 0.0
        for (sa in a.trace.segments) {
            var pathB = 0.0
            for (sb in b.trace.segments) {
                val c = BeamGeometry.closest(sa.start, sa.end, sb.start, sb.end)
                if (c.distance < MERGE_REACH * (a.halfWidth + b.halfWidth)) {
                    return Crossing(pathA + sa.length * c.s, pathB + sb.length * c.t, sa.direction, sb.direction, c.midpoint)
                }
                pathB += sb.length
            }
            pathA += sa.length
        }
        return null
    }

    private fun components(glyphs: List<Element>): Set<Element> = glyphs.flatMapTo(HashSet()) { it.components }

    fun opposed(a: List<Element>, b: List<Element>): Boolean {
        val inB = components(b)
        return components(a).any { it.opposite in inB }
    }

    /** Resolves as the altar does, but with no glyph limit. */
    fun combine(a: List<Element>, b: List<Element>): Pair<List<Element>, Int> {
        var glyphs = a
        var annihilated = 0
        for (glyph in b) for (component in glyph.components) {
            val result = Inscription.resolve(glyphs, component)
            if (result.outcome is Inscription.Outcome.Annihilated) annihilated += 2
            glyphs = result.glyphs
        }
        return glyphs to annihilated
    }

    /** False when the merged beam loops on itself. */
    private fun runMerged(r: Resolution, a: Beam, b: Beam, crossing: Crossing, sync: MergedSync): Boolean {
        val level = a.level
        val strong = if (a.magnitude >= b.magnitude) a else b
        val pull = crossing.dirA.scale(a.magnitude.toDouble()).add(crossing.dirB.scale(b.magnitude.toDouble()))
        val direction = if (pull.lengthSqr() < 1.0e-6) (if (strong === a) crossing.dirA else crossing.dirB) else pull.normalize()
        val reach = maxOf(a.reach - crossing.pathA, b.reach - crossing.pathB)
        if (reach <= 0.0) return true
        val halfWidth = maxOf(a.halfWidth, b.halfWidth)
        val trace = SunderingBeamTrace.trace(level, strong.owner, crossing.point, direction, reach, mirrors(level, strong.owner), halfWidth)
        trace.loopPoint?.let {
            shockwave(level, it)
            return false
        }
        val id = mergedId(a, b)
        val acting = BeamTrace(clip(id, trace), if (isCut(id)) null else trace.finalBlockPos)
        val glyphs = combine(a.glyphs, b.glyphs).first
        val sundering = maxOf(a.sundering, b.sundering)
        val ctx = SpellContext(level, strong.owner, crossing.point, strong.owner?.uuid, pulsed = true)
        val tint = Spell(glyphs + if (sundering > 0f) listOf(Element.SUNDERING) else emptyList())
        if (level.gameTime % SpellCasting.PULSE_TICKS == 0L) {
            val magnitudes = Spell(glyphs).magnitudes.toMutableMap()
            if (sundering > 0f) magnitudes[Element.SUNDERING] = sundering
            ctx.elements = magnitudes.keys
            for (line in SpellCasting.beamLines(acting)) {
                for ((element, magnitude) in magnitudes) ElementEffects.of(element).apply(ctx, line, magnitude)
            }
        }
        if (sundering > 0f) mine(level, strong, ctx, acting.finalBlockPos, sundering)
        val fused = glyphs.filter { it.isFusion && it !in a.glyphs && it !in b.glyphs }
        sync.beams += MergedBeam(acting.segments, SpellVfx.elementBits(tint.magnitudes.keys), parentColour(a), parentColour(b), SpellVfx.elementBits(fused))
        val child = Beam(id, level, strong.owner, glyphs, sundering, trace, reach, halfWidth, a.fromStaff || b.fromStaff) {
            a.cancel()
            b.cancel()
        }
        r.lineage[child] = setOf(a, b) + r.lineage[a].orEmpty() + r.lineage[b].orEmpty()
        r.spawned += child
        return true
    }

    /** Stays the same from tick to tick, so that the cut and clash of a merged beam carry over. */
    private fun mergedId(a: Beam, b: Beam): UUID =
        UUID(a.id.mostSignificantBits xor b.id.mostSignificantBits, a.id.leastSignificantBits xor b.id.leastSignificantBits)

    /** Mining speed goes from a bare hand at stage 1 to Efficiency V at stage 4, as the staff beam does. */
    private fun mine(level: ServerLevel, strong: Beam, ctx: SpellContext, pos: net.minecraft.core.BlockPos?, stage: Float) {
        if (pos == null) return
        val state = level.getBlockState(pos)
        val hardness = state.getDestroySpeed(level, pos)
        if (state.isAir || hardness < 0f || !state.fluidState.isEmpty) return
        val last = mining[strong.id]?.takeIf { it.first == pos }?.second ?: 0f
        val progress = last + 1f + 26f * ((stage - 1f) / 3f).coerceIn(0f, 1f)
        if (progress < hardness * MINING_TICKS_PER_HARDNESS) {
            nextMining[strong.id] = pos to progress
            return
        }
        if (ctx.mayChange(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState())) level.destroyBlock(pos, true, strong.owner)
    }

    /** Keys are the UUID of the stronger parent. */
    private var mining: Map<UUID, Pair<net.minecraft.core.BlockPos, Float>> = emptyMap()
    private val nextMining = HashMap<UUID, Pair<net.minecraft.core.BlockPos, Float>>()
    /** halfWidth is the wide hit width for creatures; beams draw much thinner, so merge closer than that. */
    private const val MERGE_REACH = 0.4
    private const val MAX_MERGE_DEPTH = 4
    private const val MINING_TICKS_PER_HARDNESS = 30f

    private fun parentColour(beam: Beam): Int =
        if (beam.fromStaff) Element.SUNDERING.color else Spell(beam.glyphs).markColour()

    private const val SHOCKWAVE_RADIUS = 3.0
    private const val SHOCKWAVE_PUSH = 0.9

    fun shockwave(level: ServerLevel, point: Vec3) {
        push(level, point, SHOCKWAVE_RADIUS, SHOCKWAVE_PUSH)
        SpellVfx.shockwave(level, point, SHOCKWAVE_RADIUS)
    }

    private const val EXPLODE_CHARGE_TICKS = 10
    private const val PRESS_TICKS = 7

    private fun charge(a: Beam, b: Beam, crossing: Crossing) {
        val (strong, weak) = if (a.magnitude >= b.magnitude) a to b else b to a
        val pairs = annihilating(strong.glyphs, weak.glyphs)
        val merged = crossing.dirA.add(crossing.dirB).let { if (it.lengthSqr() < 1.0e-6) crossing.dirA else it.normalize() }
        val side = merged.cross(if (Math.abs(merged.y) > 0.9) Vec3(1.0, 0.0, 0.0) else Vec3(0.0, 1.0, 0.0)).normalize()
        SpellVfx.beamCharge(a.level, crossing.point, side, parentColour(a), parentColour(b), pairs, EXPLODE_CHARGE_TICKS)
    }

    /** Must resolve in the same order as [combine]. */
    private fun annihilating(a: List<Element>, b: List<Element>): List<Element> {
        val out = ArrayList<Element>()
        var glyphs = a
        for (glyph in b) for (component in glyph.components) {
            val result = Inscription.resolve(glyphs, component)
            if (result.outcome is Inscription.Outcome.Annihilated) component.opposite?.let { out += it }
            glyphs = result.glyphs
        }
        return out
    }

    private const val DAMAGE_PER_ANNIHILATED = 1.5f
    private const val MAX_EXPLOSION_DAMAGE = 15f
    private const val MAX_EXPLOSION_RADIUS = 6.0
    private const val EXPLOSION_PUSH = 1.2

    private fun explode(a: Beam, b: Beam, point: Vec3) {
        val level = a.level
        val (strong, weak) = if (a.magnitude >= b.magnitude) a to b else b to a
        val (survivors, annihilated) = combine(strong.glyphs, weak.glyphs)
        val damage = (DAMAGE_PER_ANNIHILATED * annihilated).coerceAtMost(MAX_EXPLOSION_DAMAGE)
        val radius = (2.0 + annihilated / 3).coerceAtMost(MAX_EXPLOSION_RADIUS)
        val ctx = SpellContext(level, strong.owner, point, strong.owner?.uuid)
        for (entity in level.getEntities(null as Entity?, AABB(point, point).inflate(radius)) { it.isAlive && !it.isSpectator }) {
            if (entity.position().distanceToSqr(point) > radius * radius) continue
            entity.hurt(ctx.damage(DamageTypes.EXPLOSION), damage)
        }
        push(level, point, radius, EXPLOSION_PUSH)
        val magnitudes = Spell(survivors).magnitudes.toMutableMap()
        val sundering = maxOf(a.sundering, b.sundering)
        if (sundering > 0f) magnitudes[Element.SUNDERING] = sundering
        SpellVfx.beamExplosion(level, point, radius)
        ctx.elements = magnitudes.keys
        for ((element, magnitude) in magnitudes) {
            ElementEffects.of(element).apply(ctx, Delivery.Sphere(point, radius, waves = true), magnitude)
        }
    }

    private fun push(level: ServerLevel, point: Vec3, radius: Double, strength: Double) {
        for (entity in level.getEntities(null as Entity?, AABB(point, point).inflate(radius)) { it.isAlive && !it.isSpectator }) {
            val away = entity.position().add(0.0, entity.bbHeight * 0.5, 0.0).subtract(point)
            val dist = away.length()
            if (dist > radius) continue
            val dir = if (dist < 1.0e-3) Vec3(0.0, 1.0, 0.0) else away.scale(1.0 / dist)
            entity.deltaMovement = entity.deltaMovement.add(dir.scale(strength * (1.0 - dist / radius) + 0.2))
            entity.hurtMarked = true
        }
    }
}
