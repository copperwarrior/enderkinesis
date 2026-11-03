package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.item.AegisBox
import org.shipwrights.enderkinesis.item.BeamSegment
import org.shipwrights.enderkinesis.item.UlderScrollItem
import org.shipwrights.enderkinesis.registry.EKItems
import org.shipwrights.enderkinesis.registry.EKParticles
import org.shipwrights.enderkinesis.spell.Element
import org.shipwrights.enderkinesis.spell.Shape
import org.shipwrights.enderkinesis.spell.Spell
import org.shipwrights.enderkinesis.spell.SpellCasting
import org.shipwrights.enderkinesis.spell.SpellMote
import org.shipwrights.enderkinesis.spell.SpellVfx
import org.shipwrights.enderkinesis.client.SpellGeom.whiten
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

object AegisContacts {
    private class Frame(val wielderId: Int, val frame: AegisBox.Frame)
    private class Contact(val at: Vec3, val normal: Vec3, val colours: List<Int>, val elements: Collection<Element>, val spray: Boolean)
    private class Strike(val at: Vec3, val normal: Vec3, val start: Long)
    private class Streak(val at: Vec3, val along: Vec3, val start: Long)

    // Render thread only.
    private var frames: List<Frame> = emptyList()
    private var contacts: List<Contact> = emptyList()
    private val strikes = ArrayList<Strike>()
    private val streaks = ArrayList<Streak>()

    private const val RIPPLE_EVERY = 7L
    private const val SPRAY_RIPPLE_EVERY = 5L
    private const val STRIKE_TICKS = 9
    private const val STREAK_TICKS = 4

    fun reset() {
        frames = emptyList()
        contacts = emptyList()
        strikes.clear()
        streaks.clear()
    }

    fun isEmpty(): Boolean = contacts.isEmpty() && strikes.isEmpty() && streaks.isEmpty()

    fun anyFrames(): Boolean = frames.isNotEmpty()

    fun add(level: ClientLevel, kind: SpellVfx.Kind, at: Vec3, b: Vec3, rgb: Int, seed: Int) {
        val r = Random(seed)
        when (kind) {
            SpellVfx.Kind.AEGIS_DEFLECT -> {
                val along = b.normalize()
                repeat(14) {
                    val v = along.add(SpellGeom.gauss(r) * 0.6, SpellGeom.gauss(r) * 0.6, SpellGeom.gauss(r) * 0.6).normalize().scale(0.08 * r.nextDouble())
                    level.addParticle(EKParticles.mote(SpellMote.PURITY_GLITTER), at.x, at.y, at.z, v.x, v.y, v.z)
                }
                for (element in SpellVfx.elementsOf(rgb)) repeat(10) {
                    SpellTrails.emit(level, element, at, r, along.add(SpellGeom.gauss(r) - 0.8, SpellGeom.gauss(r), SpellGeom.gauss(r)).normalize().scale(0.08))
                }
                streaks += Streak(at, along, SpellClock.now(level))
            }
            SpellVfx.Kind.AEGIS_STRIKE -> {
                strikes += Strike(at, b.normalize(), SpellClock.now(level))
                ripple(at, 0.6f)
            }
            else -> {}
        }
    }

    private fun ripple(at: Vec3, halfSize: Float) {
        val wielder = frames.filter { it.wielderId >= 0 }.minByOrNull { it.frame.center.distanceToSqr(at) } ?: return
        if (wielder.frame.center.distanceTo(at) < 5.0) AegisRipples.add(wielder.wielderId, at, halfSize)
    }

    fun tick(level: ClientLevel) {
        val now = SpellClock.now(level)
        frames = level.players().filter { it.isUsingItem && it.useItem.item == EKItems.STAFF_OF_AEGIS.get() }.map { Frame(it.id, AegisBox.forPlayer(it, 1f)) }
        strikes.removeIf { now - it.start > STRIKE_TICKS }
        streaks.removeIf { now - it.start > STREAK_TICKS }
        contacts = if (frames.isEmpty()) emptyList() else sprayContacts(level)
        val random = Random(level.random.nextLong())
        for (c in contacts) if (now % SPRAY_RIPPLE_EVERY == 0L) ripple(c.at, 0.5f)
        // [touchedByBeam] fills beamContacts during render.
        if (now % RIPPLE_EVERY == 0L) for (c in beamContacts.values) ripple(c.at, 0.6f)
        if (now % 2 == 0L) for (c in beamContacts.values) for (element in c.elements) {
            SpellTrails.emit(level, element, c.at, random, c.normal.add(SpellGeom.gauss(random) * 0.5, SpellGeom.gauss(random) * 0.5, SpellGeom.gauss(random) * 0.5).normalize().scale(0.06))
        }
        beamContacts.clear()
    }

    /** Sprays from players that land on an Aegis face. */
    private fun sprayContacts(level: ClientLevel): List<Contact> {
        val out = ArrayList<Contact>()
        for (player in level.players()) {
            if (!player.isUsingItem) continue
            val spell = UlderScrollItem.spellOf(player.useItem)?.takeIf { UlderScrollItem.heldShape(player.useItem) == Shape.SPRAY } ?: continue
            val origin = SpellCasting.handOrigin(player, player.usedItemHand)
            val aim = SpellCasting.handAim(player, origin)
            val reach = SpellCasting.sprayLength(spell.magnitude(spell.shapeElement!!))
            val hit = frames.mapNotNull { f -> AegisBox.rayIntersect(origin, aim, f.frame) }.filter { it.distance < reach }.minByOrNull { it.distance } ?: continue
            out += Contact(origin.add(aim.scale(hit.distance)), hit.normal, spell.magnitudes.keys.map { it.color }, spell.magnitudes.keys, spray = true)
        }
        return out
    }

    // Render thread only. One contact for each beam and turn per tick, so the count does not grow with the frame rate.
    private val beamContacts = Long2ObjectLinkedOpenHashMap<Contact>()
    // Far above the turns of all beams in view; it only stops runaway growth.
    private const val MAX_BEAM_CONTACTS = 256

    /** Each turn point of the beam and the outward normal of the Aegis face there. */
    private fun turns(segments: List<BeamSegment>): List<Pair<Vec3, Vec3>> {
        val out = ArrayList<Pair<Vec3, Vec3>>()
        for (i in 0 until segments.size - 1) {
            val turn = segments[i + 1].direction.subtract(segments[i].direction)
            if (turn.lengthSqr() < 1.0e-6) continue
            out += segments[i].end to turn.normalize()
        }
        return out
    }

    /** Keys the beam by its spell and its start block. */
    fun touchedByBeam(segments: List<BeamSegment>, spell: Spell) {
        val from = segments.firstOrNull()?.start ?: return
        var beam = spell.glyphs.hashCode()
        beam = 31 * beam + floor(from.x).toInt()
        beam = 31 * beam + floor(from.y).toInt()
        beam = 31 * beam + floor(from.z).toInt()
        touchedByBeam(segments, spell, beam)
    }

    /** Only the first frame's contacts of each tick are kept for each [beam] key. */
    fun touchedByBeam(segments: List<BeamSegment>, spell: Spell, beam: Int) {
        for (i in 0 until segments.size - 1) {
            if (beamContacts.size >= MAX_BEAM_CONTACTS) return
            val key = (beam.toLong() shl 32) or i.toLong()
            if (beamContacts.containsKey(key)) continue
            val turn = segments[i + 1].direction.subtract(segments[i].direction)
            if (turn.lengthSqr() < 1.0e-6) continue
            beamContacts.put(key, Contact(segments[i].end, turn.normalize(), listOf(spell.markColour()), spell.magnitudes.keys, spray = false))
        }
    }

    /** If [p] is inside an Aegis, returns the point on the face on the side of [from], and the outward normal. */
    fun stopAt(p: Vec3, from: Vec3): Pair<Vec3, Vec3>? {
        for (f in frames) {
            val box = f.frame
            if (!box.containsVisual(p)) continue
            val side = if (from.subtract(box.center).dot(box.axisZ) >= 0) 1.0 else -1.0
            val n = box.axisZ.scale(side)
            val depth = p.subtract(box.center).dot(box.axisZ) * side
            return p.add(n.scale(AegisBox.VISUAL_DEPTH / 2 - depth + 0.02)) to n
        }
        return null
    }

    /** [pose] is at the world origin. */
    fun renderBeam(pose: PoseStack, buffers: MultiBufferSource, view: SpellView, segments: List<BeamSegment>, rgb: Int, time: Float) {
        val contacts = turns(segments)
        if (contacts.isEmpty()) return
        val m = pose.last().pose()
        val glow = buffers.getBuffer(AegisRenderType.FACE_GLOW)
        val pulse = 0.85 + 0.15 * sin(time * 0.5)
        for ((at, n) in contacts) {
            val (u, v) = SpellGeom.basis(n)
            val face = SpellView(view.cam, u, v)
            val p = at.add(n.scale(0.015))
            SpellShapes.disc(glow, m, face, p, 1.7, rgb, 0.22f)
            SpellShapes.disc(glow, m, face, p, 0.55 * pulse, rgb, 0.7f)
            SpellShapes.disc(glow, m, face, p, 0.2 * pulse, 0xFFFFFF, 0.95f)
        }
    }

    /** [pose] is at the world origin. */
    fun render(pose: PoseStack, buffers: MultiBufferSource, view: SpellView, time: Float) {
        if (isEmpty()) return
        val m = pose.last().pose()
        val glow = buffers.getBuffer(AegisRenderType.FACE_GLOW)
        for (c in contacts) {
            val (u, v) = SpellGeom.basis(c.normal)
            val face = SpellView(view.cam, u, v)
            for (rgb in c.colours) SpellShapes.disc(glow, m, face, c.at.add(c.normal.scale(0.015)), 1.4, rgb, 0.18f)
        }
        for (s in strikes) {
            val e = (time - s.start) / STRIKE_TICKS
            if (e < 0f || e > 1f) continue
            val (u, v) = SpellGeom.basis(s.normal)
            val strike = (time / 2).toInt()
            for (k in 0 until 5) {
                val r = Random(k * 37 + strike * 13 + s.start.toInt())
                val an = r.nextDouble() * Math.PI * 2
                val reach = 0.7 + 0.9 * r.nextDouble()
                val start = s.at.add(s.normal.scale(0.02))
                val pts = SpellGeom.jagged(r, start, start.add(u.scale(Math.cos(an) * reach)).add(v.scale(Math.sin(an) * reach)), 0.12)
                val w = { i: Int -> 1 - 0.85 * (i.toDouble() / (pts.size - 1)).pow(1.4) }
                SpellShapes.strip(glow, m, view, pts, { 0.11 * w(it) }, Element.CHAOS.color, 0.6f * (1f - e), true)
                SpellShapes.strip(glow, m, view, pts, { 0.024 * w(it) }, whiten(Element.CHAOS.color, 0.75f), 1f - e, false)
            }
        }
        for (s in streaks) {
            val e = (time - s.start) / STREAK_TICKS
            if (e < 0f || e > 1f) continue
            SpellShapes.strip(glow, m, view, listOf(s.at, s.at.add(s.along.scale(0.8)), s.at.add(s.along.scale(1.8))), { 0.1 }, 0xFFFFFF, 0.9f * (1f - e), true, floatArrayOf(1f, 0.7f, 0f))
        }
    }
}
