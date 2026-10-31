package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.texture.TextureAtlas
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.ItemParticleOption
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.block.RuneBlock
import org.shipwrights.enderkinesis.blockentity.RuneBlockEntity
import org.shipwrights.enderkinesis.registry.EKItems
import org.shipwrights.enderkinesis.spell.SpellCasting
import org.shipwrights.enderkinesis.spell.SpellGlyphOptions
import org.shipwrights.enderkinesis.spell.SpellVfx
import org.shipwrights.enderkinesis.client.SpellGeom.easeOut
import org.shipwrights.enderkinesis.client.SpellGeom.whiten
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * How runes look. A rune waits glowing slowly, its glyphs drifting off the face now and then,
 * and every so often an unstable flicker with a spark of its elements: they're volatile, and
 * they look it. Written, it appears as a white flash, a little larger than the face, settling
 * onto it, and the scroll breaks. Set off, it flares and its pixels burst off the face (its spell
 * goes off as Area waves from the rune; see [AreaWaves]).
 *
 * You see your own first-person arm scoop up as you write one ([SpellHand]), so yours appears at
 * the top of the scoop, timed from your click; anyone else's, and yours in third person, appears
 * as word of it arrives, as there's no motion to wait for.
 */
object RuneVfx {
    private val RUNE: ResourceLocation = EnderkinesisMod.id("block/mystic_rune")

    /** A rune being written: it appears [delay] ticks after word of it arrived at [start]. */
    private class Writing(val pos: BlockPos, val entityId: Int, val hand: InteractionHand, val rgb: Int, val start: Long, val delay: Float) {
        var crumbled = false
    }
    private class Burst(val pos: BlockPos, val facing: Direction, val rgb: Int, val start: Long)

    // Render thread only.
    private val writing = HashMap<BlockPos, Writing>()
    private val bursts = ArrayList<Burst>()

    /** How long its flash takes to settle. */
    private const val POP_TICKS = 6
    private const val BURST_TICKS = 18
    /** How long the flare lasts before the pixels fly. */
    private const val FLARE_TICKS = 2.4f

    fun init() {
        RuneBlockEntity.clientTick = ::tickRune
    }

    fun add(level: ClientLevel, kind: SpellVfx.Kind, at: Vec3, size: Float, rgb: Int, seed: Int) {
        val pos = BlockPos.containing(at)
        when (kind) {
            SpellVfx.Kind.INSCRIBE -> {
                val delay = level.getEntity(seed)?.let { SpellHand.momentDelay(it, SpellHand.Use.RUNE) } ?: 0f
                writing[pos] = Writing(pos, seed, InteractionHand.entries.getOrElse(size.toInt()) { InteractionHand.MAIN_HAND }, rgb, SpellClock.now(level), delay)
            }
            SpellVfx.Kind.RUNE_BURST -> bursts += Burst(pos, Direction.entries.getOrElse(size.toInt()) { Direction.UP }, rgb, SpellClock.now(level))
            else -> {}
        }
    }

    fun isEmpty(): Boolean = writing.isEmpty() && bursts.isEmpty()

    fun reset() {
        writing.clear()
        bursts.clear()
    }

    fun tick(level: ClientLevel) {
        val now = SpellClock.now(level)
        for (w in writing.values) if (!w.crumbled && now - w.start >= w.delay) {
            // The scroll breaks into crumbs of itself, from the hand that held it: in first
            // person, where your fist shows.
            w.crumbled = true
            val writer = level.getEntity(w.entityId) as? LivingEntity ?: continue
            val arm = if (w.hand == InteractionHand.MAIN_HAND) writer.mainArm else writer.mainArm.opposite
            val at = (if (writer is Player && SpellHand.firstPerson(writer)) SpellHand.fistInWorld(arm) else null) ?: SpellCasting.handOrigin(writer, w.hand)
            val crumbs = ItemParticleOption(ParticleTypes.ITEM, ItemStack(EKItems.MYSTIC_SCROLL.get()))
            val r = Random(w.pos.hashCode())
            repeat(14) {
                val d = Vec3(SpellGeom.gauss(r), Math.abs(SpellGeom.gauss(r)) + 0.4, SpellGeom.gauss(r)).normalize().scale(0.08 + 0.05 * r.nextDouble())
                level.addParticle(crumbs, at.x, at.y, at.z, d.x, d.y, d.z)
            }
        }
        writing.values.removeIf { now - it.start > it.delay + POP_TICKS }
        bursts.removeIf { now - it.start > BURST_TICKS }
    }

    // ---- The face -------------------------------------------------------------------------------

    /** A rune's face: its centre (relative to its block's corner), the way out of it, and its
     *  own across and up. */
    private class Face(val centre: Vec3, val n: Vec3, val u: Vec3, val v: Vec3) {
        /** The middle of pixel [px], [py] of the rune (0..15, top left first). */
        fun pixel(px: Int, py: Int): Vec3 = centre.add(u.scale((px + 0.5) / 16 - 0.5)).add(v.scale(0.5 - (py + 0.5) / 16))
    }

    /** Each facing's face, made once. */
    private val FACES = Direction.entries.map { facing ->
        val n = Vec3.atLowerCornerOf(facing.normal)
        val centre = Vec3(0.5, 0.5, 0.5).subtract(n.scale(0.5 - 0.006))
        when (facing) {
            Direction.UP -> Face(centre, n, Vec3(1.0, 0.0, 0.0), Vec3(0.0, 0.0, -1.0))
            Direction.DOWN -> Face(centre, n, Vec3(1.0, 0.0, 0.0), Vec3(0.0, 0.0, 1.0))
            else -> Face(centre, n, n.cross(SpellGeom.UP), SpellGeom.UP)
        }
    }

    private fun face(facing: Direction): Face = FACES[facing.ordinal]

    /** How each pixel of a bursting rune flies: a spread about the face's way out (three
     *  numbers a pixel) and a speed. The same for every burst, so worked out once. */
    private val SPRAY = DoubleArray(256 * 3)
    private val SPEED = DoubleArray(256)

    init {
        for (py in 0 until 16) for (px in 0 until 16) {
            val r = Random(px * 31 + py * 7 + 2)
            val i = py * 16 + px
            for (k in 0 until 3) SPRAY[i * 3 + k] = SpellGeom.gauss(r) * 0.6
            SPEED[i] = 2 + 3 * r.nextDouble()
        }
    }

    private fun sprite(): Sprite {
        val s = Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(RUNE)
        return Sprite(TextureAtlas.LOCATION_BLOCKS, s.u0, s.v0, s.u1, s.v1)
    }

    /** The slow glow, and a quick unstable flare every couple of seconds, at its own time. */
    private fun pulse(time: Float): Float = 0.25f + 0.12f * sin(time * 0.08f)

    private fun flicker(time: Float): Float {
        val k = ((time % 37f) / 37f - 0.82f) / 0.06f
        return if (k > 0f && k < 1f) sin(Math.PI * k).toFloat() * (0.6f + 0.4f * sin(time * 3f)) else 0f
    }

    private fun phase(pos: BlockPos): Float = (Math.floorMod(pos.hashCode(), 997)).toFloat()

    // ---- Waiting: glyphs drifting off, sparks on the flicker ----------------------------------

    private fun tickRune(level: Level, pos: BlockPos, state: BlockState, rune: RuneBlockEntity) {
        val clientLevel = level as? ClientLevel ?: return
        if (rune.spell.glyphs.isEmpty() || writing.containsKey(pos)) return
        val random = Random(level.random.nextLong())
        val f = face(RuneBlock.facing(state))
        val base = Vec3.atLowerCornerOf(pos)
        if (random.nextFloat() < 0.12f) {
            val p = base.add(f.pixel(random.nextInt(16), random.nextInt(16)))
            val v = f.n.scale(0.01).add((random.nextDouble() - 0.5) * 0.004, 0.002, (random.nextDouble() - 0.5) * 0.004)
            level.addParticle(SpellGlyphOptions(whiten(rune.spell.markColour(), 0.3f)), p.x, p.y, p.z, v.x, v.y, v.z)
        }
        if (flicker(SpellClock.now(level) + phase(pos)) > 0.5f) {
            val at = base.add(f.centre).add(f.n.scale(0.05))
            for (element in rune.spell.magnitudes.keys) if (random.nextFloat() < 0.3f) SpellTrails.emit(clientLevel, element, at, random)
        }
    }

    // ---- Drawing --------------------------------------------------------------------------------

    /** One pixel of the rune with its middle at [x], [y], [z], in [rgb] at [alpha], lit by
     *  [light]. Written straight out: a burst draws hundreds a frame. */
    private fun pixel(c: VertexConsumer, m: Matrix4f, f: Face, x: Double, y: Double, z: Double, px: Int, py: Int, sprite: Sprite, rgb: Int, alpha: Float, light: Int) {
        if (alpha <= 0.004f) return
        val h = 0.5 / 16
        val r = (rgb shr 16) and 0xFF; val g = (rgb shr 8) and 0xFF; val b = rgb and 0xFF
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        val u0 = sprite.u(px / 16f); val u1 = sprite.u((px + 1) / 16f)
        val v0 = sprite.v(py / 16f); val v1 = sprite.v((py + 1) / 16f)
        fun put(su: Double, sv: Double, u: Float, v: Float) {
            c.vertex(
                m, (x + (f.u.x * su + f.v.x * sv) * h).toFloat(), (y + (f.u.y * su + f.v.y * sv) * h).toFloat(), (z + (f.u.z * su + f.v.z * sv) * h).toFloat(),
            ).uv(u, v).color(r, g, b, a).uv2(light).endVertex()
        }
        put(-1.0, -1.0, u0, v1); put(1.0, -1.0, u1, v1); put(1.0, 1.0, u1, v0); put(-1.0, 1.0, u0, v0)
    }

    /** The whole rune as one quad, [size] times the face across. */
    private fun decal(c: VertexConsumer, m: Matrix4f, f: Face, sprite: Sprite, rgb: Int, alpha: Float, light: Int, size: Double = 1.0) {
        val a = f.centre; val h = 0.5 * size
        SpellShapes.tquad(
            c, m, a.add(f.u.scale(-h)).add(f.v.scale(-h)), a.add(f.u.scale(h)).add(f.v.scale(-h)), a.add(f.u.scale(h)).add(f.v.scale(h)), a.add(f.u.scale(-h)).add(f.v.scale(h)),
            sprite, floatArrayOf(0f, 1f, 1f, 1f, 1f, 0f, 0f, 0f), rgb, alpha, light,
        )
    }

    /** Draws a standing rune, in its block's own space. */
    class Renderer(@Suppress("UNUSED_PARAMETER") ctx: BlockEntityRendererProvider.Context) : BlockEntityRenderer<RuneBlockEntity> {
        override fun render(rune: RuneBlockEntity, partialTick: Float, pose: PoseStack, buffers: MultiBufferSource, light: Int, overlay: Int) {
            if (!SpellVfxRenderTypes.isReady() || rune.spell.glyphs.isEmpty()) return
            val level = rune.level ?: return
            val time = SpellClock.time(level, partialTick)
            val f = face(RuneBlock.facing(rune.blockState))
            val tint = rune.spell.markColour()
            val sprite = sprite()
            val m = pose.last().pose()
            val pulse = pulse(time)
            val write = writing[rune.blockPos]
            if (write != null) {
                // Not there until the writer's scoop tops out; then a flash, settling onto the face.
                val e = time - write.start - write.delay
                if (e < 0) return
                val flash = exp(-e / 2.5f)
                val size = 1.0 + 0.25 * flash
                decal(buffers.getBuffer(SpellVfxRenderTypes.sprite(TextureAtlas.LOCATION_BLOCKS)), m, f, sprite, whiten(tint, flash), 1f, light, size)
                decal(buffers.getBuffer(SpellVfxRenderTypes.glow(TextureAtlas.LOCATION_BLOCKS)), m, f, sprite, tint, (pulse + flash).coerceAtMost(1f), SpellShapes.FULL_BRIGHT, size)
                return
            }
            val flick = flicker(time + phase(rune.blockPos))
            val colour = whiten(tint, 0.3f * flick)
            decal(buffers.getBuffer(SpellVfxRenderTypes.sprite(TextureAtlas.LOCATION_BLOCKS)), m, f, sprite, colour, 1f, light)
            decal(buffers.getBuffer(SpellVfxRenderTypes.glow(TextureAtlas.LOCATION_BLOCKS)), m, f, sprite, colour, (pulse + 0.7f * flick).coerceAtMost(1f), SpellShapes.FULL_BRIGHT)
        }

        /** Runes are always there to see: as far as the world itself is drawn, rather than
         *  vanilla's 64 blocks for block entities. */
        override fun getViewDistance(): Int = Minecraft.getInstance().options.effectiveRenderDistance * 16
    }

    /** Runes bursting. [pose] is already moved to the world origin. */
    fun render(pose: PoseStack, buffers: MultiBufferSource, level: ClientLevel, view: SpellView, time: Float, partialTick: Float) {
        if (bursts.isEmpty() || !SpellVfxRenderTypes.isReady()) return
        val m = pose.last().pose()
        val sprite = sprite()
        val body = buffers.getBuffer(SpellVfxRenderTypes.sprite(TextureAtlas.LOCATION_BLOCKS))
        for (b in bursts) burst(body, m, b, sprite, time, glow = false, light = SpellShapes.lightAt(level, Vec3.atCenterOf(b.pos)))
        val glow = buffers.getBuffer(SpellVfxRenderTypes.glow(TextureAtlas.LOCATION_BLOCKS))
        for (b in bursts) burst(glow, m, b, sprite, time, glow = true, light = SpellShapes.FULL_BRIGHT)
    }

    /** A rune that's gone off: a flare, then its pixels bursting off the face and falling. */
    private fun burst(c: VertexConsumer, m: Matrix4f, b: Burst, sprite: Sprite, time: Float, glow: Boolean, light: Int) {
        val e = time - b.start
        val f = face(b.facing)
        val flare = e < FLARE_TICKS
        val k = (e - FLARE_TICKS) / 20.0
        if (!flare && k > 0.7) return
        val rgb = whiten(b.rgb, if (flare) 0.7f else 0.4f)
        val alpha = if (flare) 1f else (if (glow) 0.8f else 1f) * (1f - k.toFloat() / 0.7f)
        val bx = b.pos.x.toDouble(); val by = b.pos.y.toDouble(); val bz = b.pos.z.toDouble()
        val n = f.n
        for (py in 0 until 16) for (px in 0 until 16) {
            // The pixel's middle on the face, from the block's corner.
            val su = (px + 0.5) / 16 - 0.5; val sv = 0.5 - (py + 0.5) / 16
            var x = f.centre.x + f.u.x * su + f.v.x * sv
            var y = f.centre.y + f.u.y * su + f.v.y * sv
            var z = f.centre.z + f.u.z * su + f.v.z * sv
            if (!flare) {
                val i = py * 16 + px
                val dx = n.x + SPRAY[i * 3]; val dy = n.y + SPRAY[i * 3 + 1]; val dz = n.z + SPRAY[i * 3 + 2]
                val out = SPEED[i] * k * 0.6 / sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1.0e-6)
                x += dx * out; y += dy * out - 3.0 * k * k; z += dz * out
            }
            pixel(c, m, f, bx + x, by + y, bz + z, px, py, sprite, rgb, alpha, light)
        }
    }
}
