package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import it.unimi.dsi.fastutil.ints.Int2DoubleOpenHashMap
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.item.BeamSegment
import org.shipwrights.enderkinesis.item.BeamTrace
import org.shipwrights.enderkinesis.item.MysticScrollItem
import org.shipwrights.enderkinesis.item.SunderingBeamTrace
import org.shipwrights.enderkinesis.spell.Element
import org.shipwrights.enderkinesis.spell.Shape
import org.shipwrights.enderkinesis.spell.Spell
import org.shipwrights.enderkinesis.spell.SpellCasting
import org.shipwrights.enderkinesis.spell.SpellMote
import org.shipwrights.enderkinesis.spell.SpellVfx
import org.shipwrights.enderkinesis.registry.EKParticles
import dev.architectury.event.events.client.ClientTickEvent
import kotlin.random.Random

/** Merged beams exist only on the server; the client draws each until the server sends a list without it. */
object ScrollBeamClient {

    /** [elements] and [fused] are bit sets by element ordinal. */
    class MergedWire(val points: List<Pair<Vec3, Vec3>>, val elements: Int, val parentA: Int, val parentB: Int, val fused: Int)

    /** [since] is the game time the merge was first seen, for its knot's build-up. */
    private class Merged(val wire: MergedWire, val segments: List<BeamSegment>, val spell: Spell, val fused: List<Element>, val since: Long) {
        val knot: Vec3? get() = segments.firstOrNull()?.start
    }

    // Render thread only.
    private var merged: List<Merged> = emptyList()
    private val cuts = Int2DoubleOpenHashMap()
    private val lastDrawn = HashMap<Int, Pair<List<BeamSegment>, Spell>>()
    private class Snap(val segments: List<BeamSegment>, val spell: Spell, val start: Double, val seed: Int)
    private val snaps = HashMap<Int, Snap>()

    /** Well past a quick flick of the casters' aim at full reach. */
    private const val MERGE_FOLLOW = 8.0

    private const val KNOT_REACH = 2.0

    fun init() {
        ClientTickEvent.CLIENT_LEVEL_POST.register(ClientTickEvent.ClientLevel(::tick))
    }

    private fun tick(level: ClientLevel) {
        val random = Random(level.random.nextLong())
        for (player in level.players()) {
            val spell = beamSpell(player) ?: continue
            if (player.id in snaps) continue
            val segments = clip(player.id, trace(level, player, 1f, SpellCasting.beamReach(spell.magnitude(spell.shapeElement!!))).segments)
            // Nothing right at the eye, where it would sweep across the view as the camera turns.
            accents(level, spell, segments, random, if (SpellHand.firstPerson(player)) 1.5 else 0.4)
            AegisContacts.touchedByBeam(segments, spell, player.id)
        }
        for (beam in merged) {
            accents(level, beam.spell, beam.segments, random)
            AegisContacts.touchedByBeam(beam.segments, beam.spell)
        }
    }

    /** Each rate passed to `along` is particles per block per tick. */
    private fun accents(level: ClientLevel, spell: Spell, segments: List<BeamSegment>, random: Random, from: Double = 0.4) {
            val total = segments.sumOf { it.length }
            if (total < from + 0.1) return
            fun along(n: Double, spawn: (Vec3, Pair<Vec3, Vec3>) -> Unit) {
                var count = (n * total).toInt() + if (random.nextDouble() < (n * total) % 1.0) 1 else 0
                while (count-- > 0) {
                    var d = from + random.nextDouble() * (total - from)
                    for (seg in segments) {
                        if (d <= seg.length) { spawn(seg.start.add(seg.direction.scale(d)), SpellGeom.basis(seg.direction)); break }
                        d -= seg.length
                    }
                }
            }
            fun out(basis: Pair<Vec3, Vec3>, r: Double) = SpellGeom.around(basis, random.nextDouble() * Math.PI * 2, r)
            fun add(mote: SpellMote, p: Vec3, vx: Double = 0.0, vy: Double = 0.0, vz: Double = 0.0) =
                level.addParticle(EKParticles.mote(mote), p.x, p.y, p.z, vx, vy, vz)
            for (element in spell.magnitudes.keys) when (element) {
                Element.HEAT -> along(0.15) { p, b ->
                    val q = p.add(out(b, 0.22 + 0.25 * random.nextDouble()))
                    add(if (random.nextInt(4) == 0) SpellMote.FLAME else SpellMote.EMBER_FLAME, q, vy = 0.012)
                    if (random.nextInt(6) == 0) add(SpellMote.SPRAY_SMOKE, q.add(0.0, 0.3, 0.0), vy = 0.03)
                }
                Element.COLD -> along(0.06) { p, b -> add(SpellMote.FROST, p.add(out(b, 0.2 + 0.2 * random.nextDouble())), vy = -0.04) }
                Element.GROWTH -> {
                    along(0.024) { p, b -> val q = p.add(out(b, 0.3)); level.addParticle(EKParticles.leafChip(), q.x, q.y, q.z, 0.0, -0.02, 0.0) }
                    along(0.04) { p, b -> add(SpellMote.GROWTH_GLINT, p.add(out(b, 0.35 * Math.sqrt(random.nextDouble()))), vy = 0.006) }
                }
                Element.PURITY -> along(0.1) { p, b -> add(SpellMote.PURITY_GLITTER, p.add(out(b, 0.18 + 0.3 * random.nextDouble()))) }
                Element.CHAOS -> along(0.024) { p, b -> add(SpellMote.CHAOS_GLYPH, p.add(out(b, 0.35 + 0.4 * random.nextDouble()))) }
                Element.BLOOD -> along(0.07) { p, b ->
                    if (random.nextBoolean()) add(SpellMote.BLOOD_DRIP, p.add(0.0, -0.1, 0.0))
                    else add(SpellMote.BLOOD_MIST, p.add(out(b, 0.25)), vy = -0.005)
                }
                Element.POISON -> along(0.02) { p, b -> add(SpellMote.POISON_SWIRL, p.add(out(b, 0.25 + 0.3 * random.nextDouble())), vy = 0.012) }
                Element.VOID -> along(0.06) { p, b ->
                    val q = p.add(out(b, 0.25 + 0.2 * random.nextDouble()))
                    when (random.nextInt(5)) {
                        0 -> add(SpellMote.YGANN_EYE, q)
                        1, 2 -> add(SpellMote.MASS_DUST, q)
                        else -> add(SpellMote.VOID_MOTE, q)
                    }
                }
                Element.SUNFIRE -> {
                    along(0.045) { p, b -> add(SpellMote.SUNFIRE_SPARK, p.add(out(b, 0.15 + 0.15 * random.nextDouble())), vy = 0.025) }
                    // Dust hanging in the light.
                    along(0.2) { p, b -> add(SpellMote.SUNFIRE_DUST, p.add(out(b, 0.25 * Math.sqrt(random.nextDouble()))), (random.nextDouble() - 0.5) * 0.004, 0.004 + random.nextDouble() * 0.006, (random.nextDouble() - 0.5) * 0.004) }
                }
                Element.GRAVITY -> along(0.08) { p, b -> add(SpellMote.GRAVITY_DUST, p.add(out(b, 0.25))) }
                Element.SUNDERING -> {}
            }
    }

    @JvmStatic
    fun renderAll(pose: PoseStack, consumers: MultiBufferSource, camX: Double, camY: Double, camZ: Double, tickDelta: Float) {
        val level = Minecraft.getInstance().level ?: return
        val gameTime = level.gameTime + tickDelta.toDouble()
        lastDrawn.clear()
        for (player in level.players()) {
            val spell = beamSpell(player) ?: continue
            if (player.id in snaps) continue
            val trace = trace(level, player, tickDelta, SpellCasting.beamReach(spell.magnitude(spell.shapeElement!!)))
            val segments = clip(player.id, trace.segments)
            lastDrawn[player.id] = segments to spell
            val flicker = segments.lastOrNull()?.let { BeamClashes.flicker(it.end, SpellClock.time(level, tickDelta)) } ?: 1f
            val buffers = if (flicker < 1f) BeamClashes.Dimmed(consumers, flicker) else consumers
            // No fadeBack: from a first-person hand, the fade behind the start goes past the eye.
            if (prism(spell)) SunderingClient.renderColouredBeam(pose, buffers, camX, camY, camZ, segments, spell.markColour(), gameTime, fadeBack = false)
            ScrollBeamDetail.render(pose, buffers, Vec3(camX, camY, camZ), segments, spell, gameTime, spell.markColour())
            aegisContacts(pose, buffers, Vec3(camX, camY, camZ), segments, spell.markColour(), gameTime)
        }
        renderSnaps(pose, consumers, Vec3(camX, camY, camZ), gameTime)
        for (beam in merged) {
            if (beam.segments.isEmpty()) continue
            val colour = if (Element.SUNDERING in beam.spell.magnitudes) Element.SUNDERING.color else beam.spell.markColour()
            if (prism(beam.spell)) SunderingClient.renderColouredBeam(pose, consumers, camX, camY, camZ, beam.segments, colour, gameTime)
            ScrollBeamDetail.render(pose, consumers, Vec3(camX, camY, camZ), beam.segments, beam.spell, gameTime, colour)
            aegisContacts(pose, consumers, Vec3(camX, camY, camZ), beam.segments, colour, gameTime)
            ScrollBeamDetail.knot(pose, consumers, Vec3(camX, camY, camZ), beam.segments.first().start, beam.wire.parentA, beam.wire.parentB, colour, beam.fused, (gameTime - beam.since).toFloat(), gameTime)
        }
    }

    fun snap(at: Vec3) {
        val level = Minecraft.getInstance().level ?: return
        for ((id, drawn) in lastDrawn) {
            val (segments, spell) = drawn
            val end = segments.lastOrNull()?.end ?: continue
            if (end.distanceToSqr(at) < 1.5 * 1.5) snaps[id] = Snap(segments, spell, level.gameTime.toDouble(), id * 31 + level.gameTime.toInt())
        }
    }

    private fun renderSnaps(pose: PoseStack, consumers: MultiBufferSource, cam: Vec3, gameTime: Double) {
        if (snaps.isEmpty()) return
        val camera = Minecraft.getInstance().gameRenderer.mainCamera
        val view = SpellView(cam, Vec3(camera.leftVector), Vec3(camera.upVector))
        val it = snaps.values.iterator()
        while (it.hasNext()) {
            val snap = it.next()
            val e = ((gameTime - snap.start) / 20.0).toFloat()
            val cut = SpellGeom.easeOut(minOf(1.0, e / BeamClashes.SNAP_SECONDS.toDouble())).toFloat()
            if (cut >= 1f) { it.remove(); continue }
            val total = snap.segments.sumOf { s -> s.length }
            val segments = truncate(snap.segments, total * (1 - cut))
            if (segments.isEmpty()) continue
            val rgb = snap.spell.markColour()
            if (prism(snap.spell)) SunderingClient.renderColouredBeam(pose, consumers, cam.x, cam.y, cam.z, segments, rgb, gameTime, fadeBack = false)
            ScrollBeamDetail.render(pose, consumers, cam, segments, snap.spell, gameTime, rgb)
            if (cut > 0f && SpellVfxRenderTypes.isReady()) {
                pose.pushPose()
                pose.translate(-cam.x, -cam.y, -cam.z)
                BeamClashes.snapSparks(consumers.getBuffer(SpellVfxRenderTypes.glow(PARTICLE_ATLAS)), pose.last().pose(), view, segments.last().end, cut, snap.seed)
                pose.popPose()
            }
        }
    }

    private fun truncate(segments: List<BeamSegment>, length: Double): List<BeamSegment> {
        val out = ArrayList<BeamSegment>()
        var travelled = 0.0
        for (seg in segments) {
            if (travelled + seg.length >= length) {
                val keep = (length - travelled).coerceAtLeast(0.0)
                out += BeamSegment(seg.start, seg.direction, seg.start.add(seg.direction.scale(keep)), keep)
                break
            }
            out += seg
            travelled += seg.length
        }
        return out
    }

    private fun aegisContacts(pose: PoseStack, consumers: MultiBufferSource, cam: Vec3, segments: List<BeamSegment>, rgb: Int, gameTime: Double) {
        if (segments.size < 2) return
        val camera = Minecraft.getInstance().gameRenderer.mainCamera
        val level = Minecraft.getInstance().level ?: return
        pose.pushPose()
        pose.translate(-cam.x, -cam.y, -cam.z)
        val time = SpellClock.time(level, (gameTime - level.gameTime).toFloat())
        AegisContacts.renderBeam(pose, consumers, SpellView(cam, Vec3(camera.leftVector), Vec3(camera.upVector)), segments, rgb, time)
        pose.popPose()
    }

    fun prism(spell: Spell): Boolean = Element.PURITY in spell.magnitudes || Element.SUNDERING in spell.magnitudes

    private fun beamSpell(player: Player) =
        if (!player.isUsingItem) null
        else MysticScrollItem.spellOf(player.useItem)?.takeIf { it.shape == Shape.BEAM && it.signature == null }

    private fun trace(level: ClientLevel, player: Player, tickDelta: Float, reach: Double): BeamTrace {
        val shields = SunderingBeamTrace.collectWieldedShields(level, player)
        // The first-person hand has its own field of view, so use its drawn position.
        val origin = if (SpellHand.firstPerson(player)) {
            SpellHand.palmInWorld(if (player.usedItemHand == InteractionHand.MAIN_HAND) player.mainArm else player.mainArm.opposite)
        } else SpellCasting.handOrigin(player, player.usedItemHand, tickDelta)
        return SunderingBeamTrace.trace(level, player, origin, SpellCasting.handAim(player, origin, tickDelta), reach, shields)
    }

    /**
     * The server cut length starts at the third-person hand, so a first-person beam ends off the crossing.
     * Thus cut at a nearby crossing first, and use the length only when no crossing is near.
     */
    @JvmStatic
    fun clip(entityId: Int, segments: List<BeamSegment>): List<BeamSegment> {
        if (!cuts.containsKey(entityId)) return segments
        val cut = cuts.get(entityId)
        var at = -1.0
        for (beam in merged) {
            val knot = beam.knot ?: continue
            var travelled = 0.0
            for (seg in segments) {
                val t = knot.subtract(seg.start).dot(seg.direction).coerceIn(0.0, seg.length)
                val near = seg.start.add(seg.direction.scale(t)).distanceToSqr(knot) < KNOT_REACH * KNOT_REACH
                if (near && (at < 0.0 || Math.abs(travelled + t - cut) < Math.abs(at - cut))) at = travelled + t
                travelled += seg.length
            }
        }
        return truncate(segments, if (at >= 0.0) at else cut)
    }

    /**
     * [beams] is the full live list. A merge has no id on the wire, so match each to last tick's by
     * [sameMerge] and [MERGE_FOLLOW]; only an unmatched merge is new and flashes its fusions.
     */
    fun onMerged(beams: List<MergedWire>, parentCuts: Map<Int, Double>) {
        val level = Minecraft.getInstance().level ?: return
        val tick = level.gameTime
        val previous = merged.toMutableList()
        merged = beams.map { wire ->
            val segments = wire.points.map { (from, to) -> BeamSegment(from, to.subtract(from).normalize(), to, from.distanceTo(to)) }
            val at = wire.points.firstOrNull()?.first ?: Vec3.ZERO
            val same = previous
                .filter { sameMerge(it.wire, wire) && (it.knot?.distanceToSqr(at) ?: Double.MAX_VALUE) < MERGE_FOLLOW * MERGE_FOLLOW }
                .minByOrNull { it.knot!!.distanceToSqr(at) }
            val since = if (same != null) {
                previous.remove(same)
                same.since
            } else {
                for (fusion in SpellVfx.elementsOf(wire.fused)) fusionFlash(level, at, fusion)
                tick
            }
            Merged(wire, segments, Spell(SpellVfx.elementsOf(wire.elements)), SpellVfx.elementsOf(wire.fused), since)
        }
        cuts.clear()
        for ((id, cut) in parentCuts) cuts.put(id, cut)
    }

    private fun sameMerge(a: MergedWire, b: MergedWire): Boolean =
        a.elements == b.elements && a.fused == b.fused &&
            ((a.parentA == b.parentA && a.parentB == b.parentB) || (a.parentA == b.parentB && a.parentB == b.parentA))

    private fun fusionFlash(level: ClientLevel, at: Vec3, fusion: Element) {
        val mote = when (fusion) {
            Element.SUNFIRE -> SpellMote.SUNFIRE_DUST
            Element.POISON -> SpellMote.POISON_SWIRL
            else -> SpellMote.VOID_MOTE
        }
        val random = Random(level.random.nextLong())
        repeat(14) {
            val v = SpellGeom.spread(random).scale(0.1)
            level.addParticle(EKParticles.mote(mote), at.x, at.y, at.z, v.x, v.y, v.z)
        }
    }

    fun reset() {
        lastDrawn.clear()
        snaps.clear()
        merged = emptyList()
        cuts.clear()
    }
}
