package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.core.particles.ItemParticleOption
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.nbt.Tag
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.shipwrights.enderkinesis.registry.EKItems
import org.shipwrights.enderkinesis.registry.EKParticles
import org.shipwrights.enderkinesis.spell.Element
import org.shipwrights.enderkinesis.spell.Imbues
import org.shipwrights.enderkinesis.spell.Spell
import org.shipwrights.enderkinesis.spell.SpellCasting
import org.shipwrights.enderkinesis.spell.SpellMote
import org.shipwrights.enderkinesis.spell.SpellVfx
import org.shipwrights.enderkinesis.client.SpellGeom.easeOut
import org.shipwrights.enderkinesis.client.SpellGeom.smooth
import org.shipwrights.enderkinesis.client.SpellGeom.whiten
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

// The local first-person burst waits for the blade animation. All other bursts start when the packet arrives.
object ImbueVfx {
    /** [delay] is in ticks after [start]. */
    private class Meeting(val entityId: Int, val rgb: Int, val start: Long, val delay: Float) {
        var met = false
    }
    private class Burst(val at: Vec3, val rgb: Int, val start: Long, val spread: Double = 0.9)
    private class Shot(val elements: List<Element>, val since: Long) {
        var seen = false
        var last: Vec3? = null
        var lastStep: Vec3? = null
        // The first impact sets off the spell, so the shot trails nothing after it.
        var spent = false
        val path = ArrayList<Pair<Vec3, Long>>()
    }

    // Render thread only.
    private val meetings = ArrayList<Meeting>()
    private val bursts = ArrayList<Burst>()
    private val shots = HashMap<Int, Shot>()

    // Drop a meeting after this many ticks if its holder does not show.
    private const val MEETING_TIMEOUT = 40
    private const val BURST_LIFE = 12
    private const val TRAIL_EVERY = 3
    // Drop a shot after this many ticks if it is not seen.
    private const val SHOT_TIMEOUT = 100
    private const val VINE_FADE_START = 5
    private const val VINE_FADE = 12

    fun add(level: ClientLevel, kind: SpellVfx.Kind, at: Vec3, rgb: Int, seed: Int) {
        val now = SpellClock.now(level)
        when (kind) {
            SpellVfx.Kind.IMBUE -> {
                val delay = level.getEntity(seed)?.let { SpellHand.momentDelay(it, SpellHand.Use.IMBUE) } ?: 0f
                meetings += Meeting(seed, rgb, now, delay)
            }
            SpellVfx.Kind.GLYPH_BURST -> bursts += Burst(at, rgb, now)
            SpellVfx.Kind.IMBUED_SHOT -> shots[seed] = Shot(SpellVfx.elementsOf(rgb), now)
            SpellVfx.Kind.FIZZLE -> {
                val r = Random(seed)
                repeat(6) { level.addParticle(EKParticles.mote(SpellMote.SPRAY_SMOKE), at.x + (r.nextDouble() - 0.5) * 0.3, at.y, at.z + (r.nextDouble() - 0.5) * 0.3, 0.0, 0.03, 0.0) }
                repeat(12) { level.addParticle(EKParticles.mote(SpellMote.FIZZLE_GLYPH), at.x + (r.nextDouble() - 0.5) * 0.4, at.y + 0.1, at.z + (r.nextDouble() - 0.5) * 0.4, (r.nextDouble() - 0.5) * 0.04, 0.02, (r.nextDouble() - 0.5) * 0.04) }
            }
            else -> {}
        }
    }

    fun isEmpty(): Boolean = meetings.isEmpty() && bursts.isEmpty() && shots.isEmpty()

    fun reset() {
        meetings.clear()
        bursts.clear()
        shots.clear()
    }

    fun tick(level: ClientLevel) {
        val now = SpellClock.now(level)
        val random = Random(level.random.nextLong())
        for (s in meetings) if (!s.met && now - s.start >= s.delay) {
            val holder = level.getEntity(s.entityId) as? LivingEntity ?: continue
            s.met = true
            val off = SpellCasting.handOrigin(holder, InteractionHand.OFF_HAND)
            val at = if (holder is Player && SpellHand.firstPerson(holder)) SpellHand.fistInWorld(holder.mainArm.opposite) ?: off
                else off.lerp(SpellCasting.handOrigin(holder, InteractionHand.MAIN_HAND), 0.5)
            bursts += Burst(at, s.rgb, now, 0.45)
            val crumbs = ItemParticleOption(ParticleTypes.ITEM, ItemStack(EKItems.MYSTIC_SCROLL.get()))
            repeat(14) {
                val d = Vec3(SpellGeom.gauss(random), Math.abs(SpellGeom.gauss(random)) + 0.4, SpellGeom.gauss(random)).normalize().scale(0.08 + 0.05 * random.nextDouble())
                level.addParticle(crumbs, at.x, at.y, at.z, d.x, d.y, d.z)
            }
        }
        meetings.removeIf { it.met || now - it.start > it.delay + MEETING_TIMEOUT }
        bursts.removeIf { now - it.start > BURST_LIFE }
        heldTrails(level, now, random)
        shotTrails(level, now, random)
    }

    private fun heldTrails(level: ClientLevel, now: Long, random: Random) {
        if (now % TRAIL_EVERY != 0L) return
        for (entity in level.entitiesForRendering()) {
            if (entity !is LivingEntity) continue
            val stack = entity.mainHandItem
            if (stack.tag?.contains(Imbues.NBT, Tag.TAG_COMPOUND.toInt()) != true) continue
            val elements = Imbues.of(stack)?.spell?.magnitudes?.keys?.toList() ?: continue
            if (elements.isEmpty()) continue
            val element = elements[((now / TRAIL_EVERY) % elements.size).toInt()]
            val hand = SpellCasting.handOrigin(entity, InteractionHand.MAIN_HAND)
            SpellTrails.emit(level, element, hand.add((random.nextDouble() - 0.5) * 0.2, 0.1 + random.nextDouble() * 0.3, (random.nextDouble() - 0.5) * 0.2), random)
        }
    }

    private fun shotTrails(level: ClientLevel, now: Long, random: Random) {
        val gone = ArrayList<Int>()
        for ((id, shot) in shots) {
            val entity = level.getEntity(id)
            if (entity == null || entity.isRemoved) {
                // The spawn can arrive after the packet. Keep a Growth vine until it fades.
                val lastPass = shot.path.lastOrNull()?.second ?: shot.since
                if (shot.seen && now - lastPass > VINE_FADE_START + VINE_FADE || !shot.seen && now - shot.since > SHOT_TIMEOUT) gone += id
                continue
            }
            shot.seen = true
            val p = entity.position()
            val step = shot.last?.let { p.subtract(it) }
            shot.last = p
            // A stop or a reversal means the first impact, which sets off the spell.
            val moving = step == null || step.lengthSqr() > 1.0e-4
            val turned = step != null && shot.lastStep?.let { it.dot(step) < 0 } == true
            if (moving) shot.lastStep = step
            if (!moving || turned) shot.spent = true
            if (shot.spent) continue
            if (Element.GROWTH in shot.elements) {
                shot.path += p to now
                if (shot.path.size > 80) shot.path.removeAt(0)
            }
            if (now % 2 == 0L) for (element in shot.elements) {
                if (element != Element.GROWTH) SpellTrails.emit(level, element, p, random)
            }
        }
        for (id in gone) shots.remove(id)
    }

    /** Null when the shot carries no spell or has struck. */
    fun shotGlint(entityId: Int): Int? {
        val shot = shots[entityId] ?: return null
        if (shot.spent || shot.elements.isEmpty()) return null
        return Spell(shot.elements).markColour()
    }

    fun render(pose: PoseStack, buffers: MultiBufferSource, level: ClientLevel, view: SpellView, time: Float, partialTick: Float) {
        if (isEmpty()) return
        val m = pose.last().pose()
        if (SpellVfxRenderTypes.isReady() && shots.values.any { it.path.size > 1 }) {
            val blocks = buffers.getBuffer(SpellVfxRenderTypes.sprite(BLOCK_ATLAS))
            for (shot in shots.values) if (shot.path.size > 1) vineTrail(blocks, m, level, shot, time)
        }
        if (SpellVfxRenderTypes.isReady() && bursts.isNotEmpty()) {
            val glow = buffers.getBuffer(SpellVfxRenderTypes.glow(PARTICLE_ATLAS))
            for (b in bursts) burst(glow, m, view, b, time)
        }
    }

    private fun glyph(i: Int): Sprite = SpellArt.glyph(i)

    private fun burst(c: VertexConsumer, m: Matrix4f, view: SpellView, b: Burst, time: Float) {
        val e = (time - b.start) / BURST_LIFE
        if (e < 0f || e > 1f) return
        val rgb = whiten(b.rgb, 0.35f)
        for (i in 0 until 18) {
            val r = Random(i * 3 + 11)
            val d = SpellGeom.spread(r)
            val p = b.at.add(d.scale(b.spread * easeOut(e.toDouble()) * (0.5 + 0.5 * r.nextDouble())))
            SpellShapes.sprite(c, m, view, p, 0.15, glyph(i * 7), rgb, 1f - e, SpellShapes.FULL_BRIGHT)
        }
    }

    private fun vineTrail(c: VertexConsumer, m: Matrix4f, level: ClientLevel, shot: Shot, time: Float) {
        val pts = shot.path.map { it.first }
        val light = SpellShapes.lightAt(level, pts.last())
        for ((name, width, phase, rgb) in arrayOf(Strand("vine", 0.2, 0.0, 0x48B518), Strand("mangrove_roots_side", 0.14, Math.PI / 2, 0xFFFFFF))) {
            val alphas = FloatArray(pts.size) { 1f - smooth((time - shot.path[it].second - VINE_FADE_START) / VINE_FADE.toFloat()) }
            if (alphas.all { it <= 0.01f }) continue
            val edges = List(pts.size) { k ->
                val d = pts[minOf(pts.size - 1, k + 1)].subtract(pts[maxOf(0, k - 1)]).normalize()
                val (s1, s2) = SpellGeom.basis(d)
                val an = k * 0.45 + phase
                s1.scale(cos(an)).add(s2.scale(sin(an))).scale(width / 2)
            }
            SpellShapes.stripPx(c, m, pts, edges, SpellArt.block(name), rgb, alphas, if (phase > 0) 0.3 else 0.6, light)
        }
    }

    private data class Strand(val texture: String, val width: Double, val phase: Double, val rgb: Int)
}
