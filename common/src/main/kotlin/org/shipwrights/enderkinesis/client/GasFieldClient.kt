package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.vertex.PoseStack
import dev.architectury.event.events.client.ClientTickEvent
import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import java.nio.ByteBuffer
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.level.ChunkPos
import org.joml.Matrix4f
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11
import org.lwjgl.system.MemoryUtil
import org.shipwrights.enderkinesis.client.volume.VolumeComposite
import org.shipwrights.enderkinesis.client.volume.VolumeEffect
import org.shipwrights.enderkinesis.client.volume.VolumeProxy
import org.shipwrights.enderkinesis.client.volume.VolumeTextures
import org.shipwrights.enderkinesis.client.volume.VolumeView
import org.shipwrights.enderkinesis.spell.GasField
import kotlin.math.floor

// One volume, so gas from many casts merges into one cloud. The shader blends the "now" and
// "was" textures over the tick, so gas that moves between blocks slides.
object GasFieldClient {
    // Blocks across, and blocks up and down, around the camera.
    private const val SPAN = 128
    private const val SPAN_Y = 64
    private const val THIN_REBUILD_TICKS = 20
    private const val NOW_UNIT = 4
    private const val WAS_UNIT = 5

    // Render (client) thread only.
    private val chunks = Long2ObjectOpenHashMap<Int2FloatOpenHashMap>()
    // Keyed by packed block position. The next build blends from it.
    private var shown = Long2FloatOpenHashMap()
    // Swapped with [shown], so a build allocates no map.
    private var spare = Long2FloatOpenHashMap()
    private var version = 0
    private var builtVersion = -1
    private var builtAt: Long = Long.MIN_VALUE
    private var ticks = 0
    private var builtTick = Int.MIN_VALUE / 2
    // True when the last build blended between amounts, so one more build must settle the blend.
    private var settling = false

    private val view = VolumeView()
    private val effect = VolumeEffect("spell_gas_field_volume")
    private val localToView = Matrix4f()

    // Texels x first, then y, then z, packed to the size of the volume last built.
    private val nowData: ByteBuffer by lazy(LazyThreadSafetyMode.NONE) { BufferUtils.createByteBuffer(SPAN * SPAN_Y * SPAN) }
    private val wasData: ByteBuffer by lazy(LazyThreadSafetyMode.NONE) { BufferUtils.createByteBuffer(SPAN * SPAN_Y * SPAN) }
    private var nowTex = -1
    private var wasTex = -1

    /** Least corner in world blocks. */
    private class Volume(val minX: Int, val minY: Int, val minZ: Int, val w: Int, val h: Int, val d: Int)
    private var volume: Volume? = null

    fun init() {
        ClientTickEvent.CLIENT_POST.register { mc ->
            if (mc.level == null) reset() else tick()
        }
    }

    fun isEmpty(): Boolean = chunks.isEmpty() && shown.isEmpty()

    fun received(chunk: Long, cells: Int2FloatOpenHashMap) {
        if (cells.isEmpty()) chunks.remove(chunk) else chunks.put(chunk, cells)
        version++
    }

    /** An amount of 0 removes the cell. */
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
        spare.clear()
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

    // Called at renderLevel TAIL: the volume writes no depth, so anything drawn after it would paint over it.
    @JvmStatic
    fun render(poseStack: PoseStack, projection: Matrix4f, camera: Camera) {
        if (isEmpty() || IrisCompat.isShaderPackInUse()) return
        val eff = effect.get() ?: return
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val cam = camera.position
        val cx = floor(cam.x).toInt(); val cy = floor(cam.y).toInt(); val cz = floor(cam.z).toInt()
        val at = BlockPos.asLong(cx shr 2, cy, cz shr 2)
        // At most once a tick, so each blend runs over one tick. Build once more after the last change to settle.
        if (at != builtAt || (ticks != builtTick && (version != builtVersion || settling))) {
            settling = version != builtVersion
            build(level.minBuildHeight, cx, cy, cz)
            builtVersion = version
            builtAt = at
            builtTick = ticks
        }
        val v = volume ?: return
        val main = mc.mainRenderTarget
        view.update(projection, poseStack.last().pose(), cam.x, cam.y, cam.z)
        view.pushViewProj(eff)
        view.pushInvViewProj(eff)
        val ox = (v.minX - cam.x).toFloat(); val oy = (v.minY - cam.y).toFloat(); val oz = (v.minZ - cam.z).toFloat()
        eff.safeGetUniform("LocalToView").set(localToView.translation(ox, oy, oz))
        eff.safeGetUniform("BoxMin").set(0f, 0f, 0f)
        eff.safeGetUniform("BoxMax").set(v.w.toFloat(), v.h.toFloat(), v.d.toFloat())
        eff.safeGetUniform("ScreenSize").set(main.width.toFloat(), main.height.toFloat())
        eff.safeGetUniform("SpellTicks").set(SpellClock.time(level, mc.frameTime))
        eff.safeGetUniform("CameraPos").set(cam.x.toFloat(), cam.y.toFloat(), cam.z.toFloat())
        eff.safeGetUniform("FieldOrigin").set(ox, oy, oz)
        eff.safeGetUniform("FieldSize").set(v.w.toFloat(), v.h.toFloat(), v.d.toFloat())
        eff.safeGetUniform("FieldTexSize").set(SPAN.toFloat(), SPAN_Y.toFloat(), SPAN.toFloat())
        eff.safeGetUniform("FieldBlend").set(((ticks - builtTick) + mc.frameTime).coerceIn(0f, 1f))
        VolumeTextures.bind3D(NOW_UNIT, nowTex)
        VolumeTextures.bind3D(WAS_UNIT, wasTex)
        VolumeProxy.begin(main, VolumeComposite.Blend.ALPHA)
        VolumeProxy.draw(eff)
        VolumeProxy.end(main, eff)
        VolumeTextures.bind3D(NOW_UNIT, 0)
        VolumeTextures.bind3D(WAS_UNIT, 0)
    }

    /** Bounds the textures to the cells with gas, plus one clear block, so the gas fades at the edge. */
    private fun build(minBuildHeight: Int, cx: Int, cy: Int, cz: Int) {
        val rx0 = cx - SPAN / 2; val ry0 = cy - SPAN_Y / 2; val rz0 = cz - SPAN / 2
        val rx1 = rx0 + SPAN - 1; val ry1 = ry0 + SPAN_Y - 1; val rz1 = rz0 + SPAN - 1
        val now = spare
        now.clear()
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
        val nowAll = now.long2FloatEntrySet().fastIterator()
        while (nowAll.hasNext()) include(nowAll.next().longKey)
        val wasAll = was.long2FloatEntrySet().fastIterator()
        while (wasAll.hasNext()) include(wasAll.next().longKey)
        shown = now
        spare = was
        if (lx > hx) { volume = null; return }
        lx = maxOf(rx0, lx - 1); ly = maxOf(ry0, ly - 1); lz = maxOf(rz0, lz - 1)
        hx = minOf(rx1, hx + 1); hy = minOf(ry1, hy + 1); hz = minOf(rz1, hz + 1)
        val w = hx - lx + 1; val h = hy - ly + 1; val d = hz - lz + 1
        val used = (w * h * d).toLong()
        MemoryUtil.memSet(MemoryUtil.memAddress0(nowData), 0, used)
        MemoryUtil.memSet(MemoryUtil.memAddress0(wasData), 0, used)
        fun put(p: Long, current: Float, before: Float) {
            val x = BlockPos.getX(p); val y = BlockPos.getY(p); val z = BlockPos.getZ(p)
            if (x < lx || x > hx || y < ly || y > hy || z < lz || z > hz) return
            val i = (x - lx) + w * ((y - ly) + h * (z - lz))
            nowData.put(i, (current * 255f).toInt().coerceIn(0, 255).toByte())
            wasData.put(i, (before * 255f).toInt().coerceIn(0, 255).toByte())
        }
        val nowCells = now.long2FloatEntrySet().fastIterator()
        while (nowCells.hasNext()) {
            val e = nowCells.next()
            put(e.longKey, e.floatValue, was.get(e.longKey))
        }
        // Gas gone since the last build fades out over the tick.
        val wasCells = was.long2FloatEntrySet().fastIterator()
        while (wasCells.hasNext()) {
            val e = wasCells.next()
            if (!now.containsKey(e.longKey)) put(e.longKey, 0f, e.floatValue)
        }
        if (nowTex < 0) nowTex = allocateTexture()
        if (wasTex < 0) wasTex = allocateTexture()
        upload(nowTex, nowData, w, h, d)
        upload(wasTex, wasData, w, h, d)
        volume = Volume(lx, ly, lz, w, h, d)
    }

    private fun allocateTexture(): Int = GlStateManager._genTexture().also {
        VolumeTextures.allocateR8x3D(it, SPAN, SPAN_Y, SPAN, GL11.GL_LINEAR)
    }

    private fun upload(tex: Int, data: ByteBuffer, w: Int, h: Int, d: Int) {
        VolumeTextures.beginUpload3D(tex)
        VolumeTextures.uploadR8x3D(0, 0, 0, w, h, d, data)
        VolumeTextures.endUpload3D()
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
