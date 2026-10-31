package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.platform.TextureUtil
import com.mojang.blaze3d.systems.RenderSystem
import dev.architectury.event.events.client.ClientTickEvent
import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.core.BlockPos
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.shipwrights.enderkinesis.spell.GasField
import kotlin.math.floor

/**
 * The client's copy of Poison's gas field ([GasField]): each chunk's gas as last sent, thinned
 * here every tick as the server thins it. Drawn as one volume (`rendertype_spell_gas_field`):
 * the gas round the camera goes into a texture, a layer of blocks per tile, and each pixel
 * marches through it, so gas from many casts runs together into one cloud wherever it lies.
 *
 * Each block's texel holds its gas now (red) and as it was when the texture was last built
 * (green), and the shader blends from one to the other over the tick, so gas passing from
 * block to block slides across rather than jumping.
 *
 * The texture is rebuilt whenever the gas has changed (at most once a tick); thinning alone
 * (slow) changes it every [THIN_REBUILD_TICKS].
 */
object GasFieldClient {
    /** How far round the camera gas is drawn: blocks across, and up and down. */
    private const val SPAN = 128
    private const val SPAN_Y = 64
    /** Room for the whole span: 8 layers of up to 128 blocks to a row, 8 rows. */
    private const val TEX = 1024
    private const val THIN_REBUILD_TICKS = 20

    // Render (client) thread only.
    private val chunks = Long2ObjectOpenHashMap<Int2FloatOpenHashMap>()
    /** The gas each block was last built with, by packed block position: what the next build
     *  blends from. */
    private var shown = Long2FloatOpenHashMap()
    /** Bumped whenever the gas changes, so the texture is only rebuilt then. */
    private var version = 0
    private var builtVersion = -1
    private var builtAt: Long = Long.MIN_VALUE
    private var ticks = 0
    private var builtTick = Int.MIN_VALUE / 2
    /** The last build blended from one amount to another, so the next (a tick on) settles. */
    private var settling = false

    private var image: NativeImage? = null
    private var textureId = -1

    /** The volume last built: its least corner (world blocks), size, and tiles per row; null
     *  when there's no gas near. */
    class Volume(val minX: Int, val minY: Int, val minZ: Int, val w: Int, val h: Int, val d: Int, val cols: Int)
    private var volume: Volume? = null

    fun init() {
        ClientTickEvent.CLIENT_POST.register { mc ->
            if (mc.level == null) reset() else tick()
        }
    }

    fun isEmpty(): Boolean = chunks.isEmpty() && shown.isEmpty()

    /** All the gas in [chunk], replacing what was there. */
    fun received(chunk: Long, cells: Int2FloatOpenHashMap) {
        if (cells.isEmpty()) chunks.remove(chunk) else chunks.put(chunk, cells)
        version++
    }

    /** Some blocks of [chunk]'s gas changed to [cells]' amounts (0 for none). */
    fun changed(chunk: Long, cells: Int2FloatOpenHashMap) {
        var have = chunks.get(chunk)
        val it = cells.int2FloatEntrySet().fastIterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.floatValue <= 0f) have?.remove(e.intKey)
            else (have ?: Int2FloatOpenHashMap().also { m -> have = m; chunks.put(chunk, m) }).put(e.intKey, e.floatValue)
        }
        if (have?.isEmpty() == true) chunks.remove(chunk)
        version++
    }

    fun reset() {
        if (chunks.isEmpty() && volume == null) return
        chunks.clear()
        shown.clear()
        volume = null
        version++
    }

    private fun tick() {
        ticks++
        if (chunks.isEmpty()) return
        val it = chunks.values.iterator()
        while (it.hasNext()) {
            val cells = it.next()
            val c = cells.int2FloatEntrySet().fastIterator()
            while (c.hasNext()) {
                val cell = c.next()
                val left = cell.floatValue - GasField.DECAY_PER_TICK
                if (left <= 0f) c.remove() else cell.setValue(left)
            }
            if (cells.isEmpty()) it.remove()
        }
        if (ticks % THIN_REBUILD_TICKS == 0) version++
    }

    /** Draws the gas round [cam] through [m] (world space), in the spell visuals' pass. */
    fun render(buffers: MultiBufferSource.BufferSource, m: Matrix4f, cam: Vec3) {
        if (isEmpty() || !SpellVfxRenderTypes.isGasFieldReady()) return
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val cx = floor(cam.x).toInt(); val cy = floor(cam.y).toInt(); val cz = floor(cam.z).toInt()
        val at = ChunkPos.asLong(cx shr 2, cz shr 2) xor (cy.toLong() shl 40)
        // Once a tick at most, so the blend from one build to the next runs over that tick; a
        // tick after the last change, once more, so the blend settles on it.
        if (at != builtAt || (ticks != builtTick && (version != builtVersion || settling))) {
            settling = version != builtVersion
            build(level.minBuildHeight, cx, cy, cz)
            builtVersion = version
            builtAt = at
            builtTick = ticks
        }
        val v = volume ?: return
        val blend = ((ticks - builtTick) + mc.frameTime).coerceIn(0f, 1f)
        SpellVfxRenderTypes.gasField = SpellVfxRenderTypes.GasFieldDraw(
            Vec3(v.minX - cam.x, v.minY - cam.y, v.minZ - cam.z), v.w, v.h, v.d, v.cols, TEX, textureId, cam, blend,
        )
        val c = buffers.getBuffer(SpellVfxRenderTypes.GAS_FIELD)
        box(c, m, v, cam)
        buffers.endBatch(SpellVfxRenderTypes.GAS_FIELD)
        SpellVfxRenderTypes.gasField = null
    }

    /** The volume's faces the camera looks out through from inside their planes: each ray
     *  leaves a box through exactly one, so each pixel marches once. */
    private fun box(c: com.mojang.blaze3d.vertex.VertexConsumer, m: Matrix4f, v: Volume, cam: Vec3) {
        val x0 = v.minX.toFloat(); val y0 = v.minY.toFloat(); val z0 = v.minZ.toFloat()
        val x1 = x0 + v.w; val y1 = y0 + v.h; val z1 = z0 + v.d
        fun quad(a: FloatArray, b: FloatArray, cc: FloatArray, d: FloatArray) {
            for (p in arrayOf(a, b, cc, d)) c.vertex(m, p[0], p[1], p[2]).endVertex()
        }
        fun p(x: Float, y: Float, z: Float) = floatArrayOf(x, y, z)
        if (cam.x > x0) quad(p(x0, y0, z0), p(x0, y1, z0), p(x0, y1, z1), p(x0, y0, z1))
        if (cam.x < x1) quad(p(x1, y0, z0), p(x1, y0, z1), p(x1, y1, z1), p(x1, y1, z0))
        if (cam.y > y0) quad(p(x0, y0, z0), p(x0, y0, z1), p(x1, y0, z1), p(x1, y0, z0))
        if (cam.y < y1) quad(p(x0, y1, z0), p(x1, y1, z0), p(x1, y1, z1), p(x0, y1, z1))
        if (cam.z > z0) quad(p(x0, y0, z0), p(x1, y0, z0), p(x1, y1, z0), p(x0, y1, z0))
        if (cam.z < z1) quad(p(x0, y0, z1), p(x0, y1, z1), p(x1, y1, z1), p(x1, y0, z1))
    }

    /** Puts the gas within [SPAN] of the camera block into the texture, now and as last built,
     *  round just the part that has either (and a clear block past it, so it fades out at the
     *  edge). */
    private fun build(minBuildHeight: Int, cx: Int, cy: Int, cz: Int) {
        val rx0 = cx - SPAN / 2; val ry0 = cy - SPAN_Y / 2; val rz0 = cz - SPAN / 2
        val rx1 = rx0 + SPAN - 1; val ry1 = ry0 + SPAN_Y - 1; val rz1 = rz0 + SPAN - 1
        // Every block with gas now or last time, with both amounts.
        val now = Long2FloatOpenHashMap()
        forEachCell(minBuildHeight, rx0, ry0, rz0, rx1, ry1, rz1) { x, y, z, a -> now.put(BlockPos.asLong(x, y, z), a) }
        val was = shown
        var lx = Int.MAX_VALUE; var ly = Int.MAX_VALUE; var lz = Int.MAX_VALUE
        var hx = Int.MIN_VALUE; var hy = Int.MIN_VALUE; var hz = Int.MIN_VALUE
        fun include(p: Long) {
            val x = BlockPos.getX(p); val y = BlockPos.getY(p); val z = BlockPos.getZ(p)
            if (x < rx0 || x > rx1 || y < ry0 || y > ry1 || z < rz0 || z > rz1) return
            if (x < lx) lx = x; if (y < ly) ly = y; if (z < lz) lz = z
            if (x > hx) hx = x; if (y > hy) hy = y; if (z > hz) hz = z
        }
        for (p in now.keys) include(p)
        for (p in was.keys) include(p)
        shown = now
        if (lx > hx) { volume = null; return }
        lx = maxOf(rx0, lx - 1); ly = maxOf(ry0, ly - 1); lz = maxOf(rz0, lz - 1)
        hx = minOf(rx1, hx + 1); hy = minOf(ry1, hy + 1); hz = minOf(rz1, hz + 1)
        val w = hx - lx + 1; val h = hy - ly + 1; val d = hz - lz + 1
        val cols = TEX / w
        val img = image ?: NativeImage(NativeImage.Format.RGBA, TEX, TEX, true).also { image = it }
        if (textureId < 0) {
            textureId = TextureUtil.generateTextureId()
            TextureUtil.prepareImage(textureId, TEX, TEX)
        }
        val usedW = minOf(cols, h) * w
        val usedH = ((h + cols - 1) / cols) * d
        img.fillRect(0, 0, usedW, usedH, 0)
        fun put(p: Long, current: Float, before: Float) {
            val x = BlockPos.getX(p); val y = BlockPos.getY(p); val z = BlockPos.getZ(p)
            if (x < lx || x > hx || y < ly || y > hy || z < lz || z > hz) return
            val layer = y - ly
            val u = (layer % cols) * w + (x - lx)
            val v = (layer / cols) * d + (z - lz)
            val r = (current * 255f).toInt().coerceIn(0, 255)
            val g = (before * 255f).toInt().coerceIn(0, 255)
            img.setPixelRGBA(u, v, (0xFF shl 24) or (g shl 8) or r)
        }
        for (e in now.long2FloatEntrySet()) put(e.longKey, e.floatValue, was.get(e.longKey))
        // Gone since last time: it fades out over the tick.
        for (e in was.long2FloatEntrySet()) if (!now.containsKey(e.longKey)) put(e.longKey, 0f, e.floatValue)
        RenderSystem.bindTexture(textureId)
        img.upload(0, 0, 0, 0, 0, usedW, usedH, true, true, false, false)
        volume = Volume(lx, ly, lz, w, h, d, cols)
    }

    private inline fun forEachCell(minBuildHeight: Int, x0: Int, y0: Int, z0: Int, x1: Int, y1: Int, z1: Int, f: (Int, Int, Int, Float) -> Unit) {
        for (chx in (x0 shr 4)..(x1 shr 4)) for (chz in (z0 shr 4)..(z1 shr 4)) {
            val cells = chunks.get(ChunkPos.asLong(chx, chz)) ?: continue
            val it = cells.int2FloatEntrySet().fastIterator()
            while (it.hasNext()) {
                val e = it.next()
                val i = e.intKey
                val x = chx * 16 + (i and 15); val z = chz * 16 + ((i shr 4) and 15); val y = (i shr 8) + minBuildHeight
                if (x in x0..x1 && y in y0..y1 && z in z0..z1) f(x, y, z, e.floatValue)
            }
        }
    }
}
