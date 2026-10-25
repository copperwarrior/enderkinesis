package org.shipwrights.enderkinesis.spell

import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.event.events.common.TickEvent
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundSource
import net.minecraft.world.damagesource.DamageTypes
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.command.AegisDebugCommands
import org.shipwrights.enderkinesis.item.AegisBox
import org.shipwrights.enderkinesis.item.BeamSegment
import org.shipwrights.enderkinesis.item.BeamTrace
import org.shipwrights.enderkinesis.item.SunderingBeamTrace
import java.util.UUID
import org.shipwrights.enderkinesis.registry.EKSounds
import org.shipwrights.enderkinesis.registry.EKParticles

/**
 * Every live beam, scroll and Staff of Sundering alike, registers here each tick. At the
 * end of the server tick, once every caster has ticked, beams that meet are resolved:
 *
 * 1. **Loop:** a beam that met itself (see [SunderingBeamTrace]), or two beams meeting
 *    head-on (directions within 10° of opposite), set off a light shockwave there, and
 *    the casts involved are cancelled.
 * 2. **Explode:** beams carrying an opposite pair between them, counting components inside
 *    fusions, explode once at the crossing (see [explode]); both casts are cancelled.
 * 3. **Merge:** otherwise a merged beam starts at the crossing, along the bisector of the
 *    two, reaching as far as the further parent had left, carrying both beams' elements.
 *    It holds no state: it's rebuilt every tick from its parents and is gone as soon as
 *    either ends. It can't merge again. Each parent's own effects stop at the crossing
 *    (from the next tick, see [clip]).
 *
 * Two beams cross where they come closer than the sum of their half-widths. Sundering
 * never cancels another beam: a Staff of Sundering beam only ever merges, and two of them
 * merge without adding their magnitudes.
 */
object BeamManager {

    /** One beam this tick. [glyphs] are a scroll's glyphs (empty for a staff beam);
     *  [sundering] is the Staff of Sundering's ramp stage (0 without). [trace] is already
     *  reflected off Aegis boxes and cut at any self-loop. [cancel] ends the cast. */
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
    /** From the last resolution: how far along its path each merged parent still acts. */
    private var cuts: Map<UUID, Double> = emptyMap()

    fun init() {
        TickEvent.SERVER_POST.register(TickEvent.Server(::resolve))
        LifecycleEvent.SERVER_STOPPED.register {
            beams.clear()
            cuts = emptyMap()
        }
    }

    fun register(beam: Beam) {
        beams += beam
    }

    /** Aegis boxes a beam from [caster] reflects off: everyone else's, plus debug boxes. */
    fun mirrors(level: Level, caster: Entity?): List<AegisBox.Frame> =
        SunderingBeamTrace.collectWieldedShields(level, caster) + AegisDebugCommands.allDebugBoxes()

    /** [trace]'s segments, ending where beam [id] fed a merged beam last tick. */
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

    /** Whether beam [id] fed a merged beam last tick, so its far end is cut short. */
    fun isCut(id: UUID): Boolean = id in cuts

    // -----------------------------------------------------------------------
    // Resolution
    // -----------------------------------------------------------------------

    private class Crossing(val pathA: Double, val pathB: Double, val dirA: Vec3, val dirB: Vec3, val point: Vec3)

    private fun resolve(server: MinecraftServer) {
        if (beams.isEmpty()) {
            cuts = emptyMap()
            return
        }
        val live = beams.toList()
        beams.clear()
        val cancelled = LinkedHashSet<Beam>()
        val merged = HashSet<Beam>()
        val nextCuts = HashMap<UUID, Double>()
        val synced = HashMap<ServerLevel, MergedSync>()

        for (beam in live) {
            val loop = beam.trace.loopPoint ?: continue
            shockwave(beam.level, loop)
            cancelled += beam
        }
        for (i in live.indices) for (j in i + 1 until live.size) {
            val a = live[i]
            val b = live[j]
            if (a.level !== b.level || a in cancelled || b in cancelled) continue
            val crossing = crossing(a, b) ?: continue
            val staff = a.fromStaff || b.fromStaff
            when {
                !staff && BeamGeometry.headOn(crossing.dirA, crossing.dirB) -> {
                    shockwave(a.level, crossing.point)
                    cancelled += a; cancelled += b
                }
                !staff && opposed(a.glyphs, b.glyphs) -> {
                    explode(a, b, crossing.point)
                    cancelled += a; cancelled += b
                }
                a !in merged && b !in merged -> {
                    merged += a; merged += b
                    nextCuts[a.id] = crossing.pathA
                    nextCuts[b.id] = crossing.pathB
                    val sync = synced.getOrPut(a.level) { MergedSync() }
                    a.owner?.let { sync.cuts[it.id] = crossing.pathA }
                    b.owner?.let { sync.cuts[it.id] = crossing.pathB }
                    if (!runMerged(a, b, crossing, sync)) {
                        cancelled += a; cancelled += b
                    }
                }
            }
        }
        cuts = nextCuts
        for ((level, sync) in synced) SpellNetwork.broadcastMerged(level, sync.beams, sync.cuts)
        for (beam in cancelled) beam.cancel()
    }

    /** One level's merged beams this tick, for clients to draw: each one's path and colour,
     *  and how far each parent (by entity id) still reaches. */
    class MergedSync {
        val beams = ArrayList<Pair<List<BeamSegment>, Int>>()
        val cuts = HashMap<Int, Double>()
    }

    /** The first place [a] and [b] come within their half-widths, in [a]'s path order. */
    private fun crossing(a: Beam, b: Beam): Crossing? {
        var pathA = 0.0
        for (sa in a.trace.segments) {
            var pathB = 0.0
            for (sb in b.trace.segments) {
                val c = BeamGeometry.closest(sa.start, sa.end, sb.start, sb.end)
                if (c.distance < a.halfWidth + b.halfWidth) {
                    return Crossing(pathA + sa.length * c.s, pathB + sb.length * c.t, sa.direction, sb.direction, c.midpoint)
                }
                pathB += sb.length
            }
            pathA += sa.length
        }
        return null
    }

    private fun components(glyphs: List<Element>): Set<Element> = glyphs.flatMapTo(HashSet()) { it.components }

    /** Whether the two beams carry an opposite pair between them. */
    fun opposed(a: List<Element>, b: List<Element>): Boolean {
        val inB = components(b)
        return components(a).any { it.opposite in inB }
    }

    /** [b]'s glyphs added onto [a]'s as if inscribed at the altar, one component at a time,
     *  with no glyph limit. Returns the glyphs left and how many glyphs annihilated. */
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

    // -----------------------------------------------------------------------
    // Merged beams
    // -----------------------------------------------------------------------

    /** Traces and applies the beam merged from [a] and [b] at [crossing]. False when it loops
     *  on itself, which cancels both casts. */
    private fun runMerged(a: Beam, b: Beam, crossing: Crossing, sync: MergedSync): Boolean {
        val level = a.level
        val strong = if (a.magnitude >= b.magnitude) a else b
        val bisector = crossing.dirA.add(crossing.dirB)
        val direction = if (bisector.lengthSqr() < 1.0e-6) crossing.dirA else bisector.normalize()
        val reach = maxOf(a.reach - crossing.pathA, b.reach - crossing.pathB)
        if (reach <= 0.0) return true
        val halfWidth = maxOf(a.halfWidth, b.halfWidth)
        val trace = SunderingBeamTrace.trace(level, strong.owner, crossing.point, direction, reach, mirrors(level, strong.owner), halfWidth)
        trace.loopPoint?.let {
            shockwave(level, it)
            return false
        }
        val glyphs = combine(a.glyphs, b.glyphs).first
        val sundering = maxOf(a.sundering, b.sundering)
        val ctx = SpellContext(level, strong.owner, crossing.point, strong.owner?.uuid, pulsed = true)
        val tint = Spell(glyphs + if (sundering > 0f) listOf(Element.SUNDERING) else emptyList())
        if (level.gameTime % SpellCasting.PULSE_TICKS == 0L) {
            val magnitudes = Spell(glyphs).magnitudes.toMutableMap()
            if (sundering > 0f) magnitudes[Element.SUNDERING] = sundering
            for (line in SpellCasting.beamLines(trace)) {
                for ((element, magnitude) in magnitudes) ElementEffects.of(element).apply(ctx, line, magnitude)
            }
        }
        sync.beams += trace.segments to tint.tint()
        if (level.gameTime % MERGE_SOUND_TICKS == 0L) {
            val p = crossing.point
            level.playSound(null, p.x, p.y, p.z, EKSounds.BEAM_MERGE.get(), SoundSource.PLAYERS, 1f, 1f)
        }
        return true
    }

    // -----------------------------------------------------------------------
    // Shockwave and explosion
    // -----------------------------------------------------------------------

    private const val MERGE_SOUND_TICKS = 40L
    private const val SHOCKWAVE_RADIUS = 3.0
    private const val SHOCKWAVE_PUSH = 0.9

    /** A light shockwave: pushes everything within [SHOCKWAVE_RADIUS] away. No damage, no
     *  element effects. */
    fun shockwave(level: ServerLevel, point: Vec3) {
        push(level, point, SHOCKWAVE_RADIUS, SHOCKWAVE_PUSH)
        level.sendParticles(EKParticles.shockwaveRing(), point.x, point.y, point.z, 1, 0.0, 0.0, 0.0, 0.0)
        level.sendParticles(ParticleTypes.END_ROD, point.x, point.y, point.z, 40, 0.2, 0.2, 0.2, 0.35)
        level.playSound(null, point.x, point.y, point.z, EKSounds.BEAM_SHOCKWAVE.get(), SoundSource.PLAYERS, 1.5f, 0.5f)
    }

    private const val DAMAGE_PER_ANNIHILATED = 1.5f
    private const val MAX_EXPLOSION_DAMAGE = 15f
    private const val MAX_EXPLOSION_RADIUS = 6.0
    private const val EXPLOSION_PUSH = 1.2

    /**
     * Two crossing beams with opposites between them explode once. Their glyphs resolve as if
     * inscribed at the altar, the stronger beam's first: opposites annihilate one for one, and
     * a fusion hit on one component leaves the other. Each annihilated glyph adds 1.5 damage
     * (15 at most) and the radius is 2 + 1 per 3 annihilated (6 at most), with knockback and
     * no block damage. Every glyph that survives then bursts over the same radius at its
     * magnitude. A Sunfire beam crossing a Growth beam annihilates Purity with Growth, and the
     * Heat left over makes it a fiery blast.
     */
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
        for ((element, magnitude) in magnitudes) {
            ElementEffects.of(element).apply(ctx, Delivery.Sphere(point, radius), magnitude)
        }
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, point.x, point.y, point.z, 1, 0.0, 0.0, 0.0, 0.0)
        level.playSound(null, point.x, point.y, point.z, EKSounds.BEAM_EXPLODE.get(), SoundSource.PLAYERS, 2f, 1.2f)
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
