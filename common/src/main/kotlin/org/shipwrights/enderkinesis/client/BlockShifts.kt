package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.BiomeColors
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.client.renderer.texture.TextureAtlas
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.BlockParticleOption
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.tags.BlockTags
import net.minecraft.tags.FluidTags
import net.minecraft.util.RandomSource
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.registry.EKParticles
import org.shipwrights.enderkinesis.spell.Element
import org.shipwrights.enderkinesis.spell.SpellMote
import org.shipwrights.enderkinesis.client.SpellGeom.smooth
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * A block a spell changes into another doesn't just swap: the old block's own textures stay
 * over the new one in 4x4-pixel cells, which wipe away from where the spell came from. Moss
 * cleans off and copper restores behind a glitter edge (Purity); grass scorches to dirt behind a
 * line of sparks, and leaves char black then crumble to ash (Sunfire); ice and snow melt off in
 * steam (Heat), the ice dripping as it goes; water frosts over and lava darkens to obsidian
 * (Cold). Moss flakes off as it's cleaned away.
 *
 * Some changes are whole blocks moving instead:
 *
 * - **Snow melting** (Heat) sinks away into steam.
 * - **Chaos swapping two blocks:** both shrink away to points and grow back in each other's
 *   places. While they're drawn here the world's own blocks there aren't ([isHidden]).
 * - **A block Gravity drops** puffs a ring of dust where it lands.
 */
object BlockShifts {
    /** One 4x4-pixel piece of the old block's face: its corners, its texture coordinates in the
     *  block atlas, its colour (tint and face shade), and the light just outside the face. */
    private class Cell(val corners: Array<Vec3>, val uv: FloatArray, val rgb: Int, val light: Int, val normal: Vec3) {
        val mid: Vec3 = corners[0].add(corners[1]).add(corners[2]).add(corners[3]).scale(0.25)
        var gone = false
    }

    /** What comes off at the edge as the change passes. */
    private enum class Edge { NONE, GLITTER, SPARKS, STEAM, ASH }

    private class Shift(val edge: Edge, val from: Vec3, val start: Long, val cells: List<Cell>, val life: Int, val pos: BlockPos, val drips: Boolean, val flakes: Boolean) {
        /** Burning leaves char first, then crumble. */
        val leaves get() = edge == Edge.ASH
    }

    private class Sink(val pos: BlockPos, val state: BlockState, val start: Long)
    private class Swap(val a: BlockPos, val b: BlockPos, val oldA: BlockState, val oldB: BlockState, val start: Long)
    private class Fall(val id: Int, val since: Long) {
        var last: Vec3? = null
    }

    // Render thread only.
    private val shifts = ArrayList<Shift>()
    private val sinks = ArrayList<Sink>()
    private val swaps = ArrayList<Swap>()
    private val falls = ArrayList<Fall>()
    /** Blocks drawn here for now instead of by the world. Read by the chunk mesher's threads. */
    private val hidden: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    /** Snow sinks away over this many ticks, and steams for longer. */
    private const val SINK_TICKS = 7f
    private const val STEAM_TICKS = 18
    /** A swap shrinks to points by [SHRINK_TICKS], and grows back from [REGROW_AT] to [SWAP_TICKS]. */
    private const val SHRINK_TICKS = 2.4f
    private const val REGROW_AT = 3.6f
    private const val SWAP_TICKS = 6
    /** Ice drips this long as it melts. */
    private const val DRIP_TICKS = 16
    /** A dropped block not seen falling in this long is given up on. */
    private const val FALL_WAIT = 100

    /** How far the change spreads each tick, in blocks. */
    private const val RATE = 0.096
    /** Burning leaves crumble this long after they char, and more slowly. */
    private const val CRUMBLE_DELAY = 5
    private const val CRUMBLE_RATE = 0.8
    private const val CHARRED = 0x231C17
    private val ATLAS = Sprite(TextureAtlas.LOCATION_BLOCKS, 0f, 0f, 1f, 1f)

    fun add(level: ClientLevel, from: Vec3, at: Vec3, element: Element?, oldId: Int) {
        val old = Block.stateById(oldId)
        if (old.isAir) return
        val pos = BlockPos.containing(at)
        if (element == Element.HEAT && (old.`is`(Blocks.SNOW) || old.`is`(Blocks.SNOW_BLOCK) || old.`is`(Blocks.POWDER_SNOW))) {
            sinks += Sink(pos, old, SpellClock.now(level))
            return
        }
        val cells = cells(level, pos, old)
        if (cells.isEmpty()) return
        // It spreads from the point of the block nearest where the spell came from.
        val origin = Vec3(from.x.coerceIn(pos.x.toDouble(), pos.x + 1.0), from.y.coerceIn(pos.y.toDouble(), pos.y + 1.0), from.z.coerceIn(pos.z.toDouble(), pos.z + 1.0))
        val edge = when (element) {
            Element.SUNFIRE -> if (old.`is`(BlockTags.LEAVES)) Edge.ASH else Edge.SPARKS
            Element.PURITY -> Edge.GLITTER
            Element.HEAT -> Edge.STEAM
            Element.COLD -> if (old.fluidState.`is`(FluidTags.LAVA)) Edge.STEAM else Edge.GLITTER
            else -> Edge.NONE
        }
        val far = cells.maxOf { it.mid.distanceTo(origin) }
        val life = (if (edge == Edge.ASH) CRUMBLE_DELAY + far / (RATE * CRUMBLE_RATE) else far / RATE).toInt() + 2
        val drips = element == Element.HEAT && (old.`is`(Blocks.ICE) || old.`is`(Blocks.FROSTED_ICE))
        val flakes = element == Element.PURITY && BuiltInRegistries.BLOCK.getKey(old.block).path.contains("mossy")
        shifts += Shift(edge, origin, SpellClock.now(level), cells, life, pos, drips, flakes)
    }

    /** Chaos swapped the blocks at [a] and [b]; [oldA] and [oldB] are what they were. */
    fun swap(level: ClientLevel, a: Vec3, b: Vec3, oldA: Int, oldB: Int) {
        val s = Swap(BlockPos.containing(a), BlockPos.containing(b), Block.stateById(oldA), Block.stateById(oldB), SpellClock.now(level))
        swaps += s
        hide(s.a, true)
        hide(s.b, true)
    }

    /** The falling block with entity id [id] puffs dust where it lands. */
    fun trackFall(id: Int, now: Long) {
        falls += Fall(id, now)
    }

    @JvmStatic
    fun isHidden(pos: BlockPos): Boolean = hidden.isNotEmpty() && pos.asLong() in hidden

    private fun hide(pos: BlockPos, hide: Boolean) {
        if (hide) hidden += pos.asLong() else hidden -= pos.asLong()
        Minecraft.getInstance().levelRenderer.setBlocksDirty(pos.x - 1, pos.y - 1, pos.z - 1, pos.x + 1, pos.y + 1, pos.z + 1)
    }

    fun isEmpty(): Boolean = shifts.isEmpty() && sinks.isEmpty() && swaps.isEmpty()

    fun reset() {
        shifts.clear()
        sinks.clear()
        swaps.clear()
        falls.clear()
        hidden.clear()
    }

    /** The old block's faces, cut into 4x4-pixel cells. A fluid's are its surface and sides. */
    private fun cells(level: ClientLevel, pos: BlockPos, old: BlockState): List<Cell> {
        val out = ArrayList<Cell>()
        val base = Vec3.atLowerCornerOf(pos)
        if (!old.fluidState.isEmpty && old.renderShape == net.minecraft.world.level.block.RenderShape.INVISIBLE) {
            val lava = old.fluidState.`is`(FluidTags.LAVA)
            val s = SpellArt.block(if (lava) "lava_still" else "water_still")
            val rgb = if (lava) 0xFFFFFF else BiomeColors.getAverageWaterColor(level, pos)
            val h = 14.0 / 16
            for (dir in Direction.entries) {
                if (dir == Direction.DOWN) continue
                face(out, level, pos, base, dir, h, s, rgb)
            }
            return out
        }
        val model = Minecraft.getInstance().blockRenderer.getBlockModel(old)
        val random = RandomSource.create(42)
        val colours = Minecraft.getInstance().blockColors
        for (dir in Direction.entries + listOf<Direction?>(null)) {
            random.setSeed(42)
            for (quad in model.getQuads(old, dir, random)) {
                val v = quad.vertices
                val stride = v.size / 4
                val corners = Array(4) { i -> base.add(Float.fromBits(v[i * stride]).toDouble(), Float.fromBits(v[i * stride + 1]).toDouble(), Float.fromBits(v[i * stride + 2]).toDouble()) }
                val uvs = FloatArray(8) { k -> Float.fromBits(v[(k / 2) * stride + 4 + (k % 2)]) }
                val n = Vec3.atLowerCornerOf(quad.direction.normal)
                val tint = if (quad.isTinted) colours.getColor(old, level, pos, quad.tintIndex) else 0xFFFFFF
                val rgb = SpellGeom.darken(tint, level.getShade(quad.direction, quad.isShade))
                val light = LevelRenderer.getLightColor(level, pos.relative(quad.direction))
                split(out, corners.map { it.add(n.scale(0.002)) }.toTypedArray(), uvs, rgb, light, n)
            }
        }
        return out
    }

    /** A fluid's face toward [dir], [h] high. */
    private fun face(out: MutableList<Cell>, level: ClientLevel, pos: BlockPos, base: Vec3, dir: Direction, h: Double, s: Sprite, tint: Int) {
        val n = Vec3.atLowerCornerOf(dir.normal)
        val c = base.add(0.5, h / 2, 0.5).add(n.x * 0.502, if (dir == Direction.UP) h / 2 + 0.002 else 0.0, n.z * 0.502)
        val u = if (dir.axis == Direction.Axis.Y) Vec3(1.0, 0.0, 0.0) else Vec3(-n.z, 0.0, n.x)
        val up = if (dir.axis == Direction.Axis.Y) Vec3(0.0, 0.0, -1.0) else Vec3(0.0, h, 0.0)
        val hu = u.scale(0.5); val hv = up.scale(0.5)
        val corners = arrayOf(c.subtract(hu).subtract(hv), c.add(hu).subtract(hv), c.add(hu).add(hv), c.subtract(hu).add(hv))
        val uvs = floatArrayOf(s.u0, s.v1, s.u1, s.v1, s.u1, s.v0, s.u0, s.v0)
        val rgb = SpellGeom.darken(tint, level.getShade(dir, true))
        split(out, corners, uvs, rgb, LevelRenderer.getLightColor(level, pos.relative(dir)), n)
    }

    /** A quad cut 4 x 4, positions and texture coordinates alike. */
    private fun split(out: MutableList<Cell>, p: Array<Vec3>, uv: FloatArray, rgb: Int, light: Int, normal: Vec3) {
        fun at(a: Double, b: Double): Vec3 = p[0].lerp(p[1], a).lerp(p[3].lerp(p[2], a), b)
        fun tex(a: Float, b: Float, k: Int): Float {
            val bottom = uv[k] + (uv[2 + k] - uv[k]) * a
            val top = uv[6 + k] + (uv[4 + k] - uv[6 + k]) * a
            return bottom + (top - bottom) * b
        }
        for (i in 0 until 4) for (j in 0 until 4) {
            val a0 = i / 4.0; val a1 = (i + 1) / 4.0; val b0 = j / 4.0; val b1 = (j + 1) / 4.0
            val corners = arrayOf(at(a0, b0), at(a1, b0), at(a1, b1), at(a0, b1))
            val f = floatArrayOf(a0.toFloat(), b0.toFloat(), a1.toFloat(), b0.toFloat(), a1.toFloat(), b1.toFloat(), a0.toFloat(), b1.toFloat())
            out += Cell(corners, FloatArray(8) { k -> tex(f[k - k % 2], f[k - k % 2 + 1], k % 2) }, rgb, light, normal)
        }
    }

    private fun reached(s: Shift, cell: Cell, age: Double): Boolean = cell.mid.distanceTo(s.from) < age * RATE
    private fun crumbled(s: Shift, cell: Cell, age: Double): Boolean = cell.mid.distanceTo(s.from) < (age - CRUMBLE_DELAY) * RATE * CRUMBLE_RATE

    // ---- Per tick: what comes off at the edge -----------------------------------------------------

    fun tick(level: ClientLevel) {
        val now = SpellClock.now(level)
        shifts.removeIf { now - it.start > maxOf(it.life, if (it.drips) DRIP_TICKS else 0) }
        sinks.removeIf { now - it.start > STEAM_TICKS }
        swaps.removeIf { s ->
            (now - s.start >= SWAP_TICKS).also { done -> if (done) { hide(s.a, false); hide(s.b, false) } }
        }
        val random = Random(level.random.nextLong())
        fun mote(m: SpellMote, p: Vec3, vy: Double = 0.0) = level.addParticle(EKParticles.mote(m), p.x, p.y, p.z, (random.nextDouble() - 0.5) * 0.02, vy, (random.nextDouble() - 0.5) * 0.02)
        // Snow sinking away into steam off what's left of it.
        for (s in sinks) {
            val top = 1 - smooth((now - s.start) / SINK_TICKS)
            val h = s.state.getShape(level, s.pos).max(Direction.Axis.Y).coerceAtLeast(0.125) * top
            if (random.nextFloat() < 0.6f) mote(SpellMote.STEAM, Vec3(s.pos.x + random.nextDouble(), s.pos.y + h, s.pos.z + random.nextDouble()), 0.04)
        }
        // Where a dropped block lands, a ring of dust.
        falls.removeIf { f ->
            val entity = level.getEntity(f.id)
            if (entity != null) { f.last = entity.position(); return@removeIf false }
            val at = f.last ?: return@removeIf now - f.since > FALL_WAIT
            for (i in 0 until 16) {
                val an = i / 16.0 * Math.PI * 2
                val p = at.add(Math.cos(an) * 0.6, 0.1, Math.sin(an) * 0.6)
                level.addParticle(EKParticles.mote(SpellMote.LAND_DUST), p.x, p.y, p.z, Math.cos(an) * 0.12, 0.03, Math.sin(an) * 0.12)
            }
            true
        }
        for (s in shifts) {
            val age = (now - s.start).toDouble()
            // Melting ice drips down its sides.
            if (s.drips && age < DRIP_TICKS) {
                val side = Direction.Plane.HORIZONTAL.getRandomDirection(level.random)
                val n = Vec3.atLowerCornerOf(side.normal)
                val along = random.nextDouble() - 0.5
                val p = Vec3.atCenterOf(s.pos).add(n.scale(0.52)).add(n.z * along, (random.nextDouble() - 0.5) * 0.8, n.x * along)
                level.addParticle(ParticleTypes.FALLING_WATER, p.x, p.y, p.z, 0.0, 0.0, 0.0)
            }
            for (cell in s.cells) {
                if (cell.gone) continue
                val off = if (s.leaves) crumbled(s, cell, age) else reached(s, cell, age)
                if (!off) continue
                cell.gone = true
                // Moss flakes off and falls away as it's cleaned.
                if (s.flakes && random.nextFloat() < 0.12f) {
                    val p = cell.mid.add(cell.normal.scale(0.03))
                    val v = cell.normal.scale(0.03)
                    level.addParticle(BlockParticleOption(ParticleTypes.BLOCK, Blocks.MOSS_BLOCK.defaultBlockState()), p.x, p.y, p.z, v.x, v.y, v.z)
                }
                if (s.edge == Edge.ASH) repeat(2) { mote(SpellMote.ASH, cell.mid) }
                else if (random.nextFloat() < 0.35f) when (s.edge) {
                    Edge.GLITTER -> mote(SpellMote.PURITY_GLITTER, cell.mid)
                    Edge.SPARKS -> mote(SpellMote.SUNFIRE_SPARK, cell.mid, 0.05)
                    Edge.STEAM -> mote(SpellMote.STEAM, cell.mid, 0.04)
                    else -> {}
                }
            }
        }
    }

    // ---- Drawing --------------------------------------------------------------------------------

    /** [CHARRED] shaded for a face facing [normal], as vanilla shades a block's faces: tops
     *  full, bottoms half, north and south 0.8, east and west 0.6. */
    private fun charred(normal: Vec3): Int {
        val shade = when {
            normal.y > 0.5 -> 1f
            normal.y < -0.5 -> 0.5f
            Math.abs(normal.z) > Math.abs(normal.x) -> 0.8f
            else -> 0.6f
        }
        return SpellGeom.darken(CHARRED, shade)
    }

    /** [pose] is at the world origin. */
    fun render(pose: PoseStack, buffers: MultiBufferSource, time: Float) {
        val level = Minecraft.getInstance().level ?: return
        // Snow sinking: the block squashed down onto its base.
        for (s in sinks) {
            val sink = smooth((time - s.start) / SINK_TICKS)
            if (sink < 1f) block(pose, buffers, level, s.state, s.pos, 1f, 1f - sink, 1f, s.pos.above())
        }
        // Swapped blocks: shrinking to points, then growing in each other's places.
        for (s in swaps) {
            val k = time - s.start
            val scale = when {
                k < SHRINK_TICKS -> 1f - smooth(k / SHRINK_TICKS)
                k < REGROW_AT -> 0f
                else -> smooth((k - REGROW_AT) / (SWAP_TICKS - REGROW_AT))
            }
            if (scale <= 0.001f) continue
            val atA = if (k < REGROW_AT) s.oldA else s.oldB
            val atB = if (k < REGROW_AT) s.oldB else s.oldA
            block(pose, buffers, level, atA, s.a, scale, scale, scale, s.a)
            block(pose, buffers, level, atB, s.b, scale, scale, scale, s.b)
        }
        if (shifts.isEmpty() || !SpellVfxRenderTypes.isReady()) return
        val m = pose.last().pose()
        // Opaque, writing depth as the block did: a translucent face here would paint over any
        // glow (a beam, a burst) drawn in front of it before it.
        val c = buffers.getBuffer(SpellVfxRenderTypes.solid(TextureAtlas.LOCATION_BLOCKS))
        for (s in shifts) {
            val age = (time - s.start).toDouble()
            for (cell in s.cells) {
                if (cell.gone) continue
                val rgb = if (s.leaves && reached(s, cell, age)) charred(cell.normal) else if (!s.leaves && reached(s, cell, age)) continue else cell.rgb
                val p = cell.corners
                SpellShapes.tquad(c, m, p[0], p[1], p[2], p[3], ATLAS, cell.uv, rgb, 1f, cell.light)
            }
        }
    }

    /** [state]'s model at [pos], scaled by [sx], [sy], [sz]: about its centre, but in height
     *  from its base when only the height changes. Lit as the block at [lightAt] is. */
    private fun block(pose: PoseStack, buffers: MultiBufferSource, level: ClientLevel, state: BlockState, pos: BlockPos, sx: Float, sy: Float, sz: Float, lightAt: BlockPos) {
        if (state.isAir) return
        val light = maxOf(LevelRenderer.getLightColor(level, lightAt), LevelRenderer.getLightColor(level, pos.above()))
        pose.pushPose()
        val pivotY = if (sx == 1f && sz == 1f) 0.0 else 0.5
        pose.translate(pos.x + 0.5, pos.y + pivotY, pos.z + 0.5)
        pose.scale(sx, sy, sz)
        pose.translate(-0.5, -pivotY, -0.5)
        Minecraft.getInstance().blockRenderer.renderSingleBlock(state, pose, buffers, light, OverlayTexture.NO_OVERLAY)
        pose.popPose()
    }
}
