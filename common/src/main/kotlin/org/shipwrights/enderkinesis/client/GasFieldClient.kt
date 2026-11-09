// Client gas runs only on the client game and render thread.
@file:OptIn(GameTickOnly::class)

package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.logging.LogUtils
import com.mojang.blaze3d.vertex.PoseStack
import dev.architectury.event.events.client.ClientTickEvent
import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import it.unimi.dsi.fastutil.ints.IntArrayList
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import it.unimi.dsi.fastutil.longs.LongSet
import java.nio.ByteBuffer
import java.nio.ShortBuffer
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.EffectInstance
import net.minecraft.core.BlockPos
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.ClientShip
import org.valkyrienskies.core.api.util.GameTickOnly
import org.valkyrienskies.mod.common.getShipManagingPos
import org.valkyrienskies.mod.common.shipObjectWorld
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL30
import org.lwjgl.system.MemoryUtil
import org.shipwrights.enderkinesis.client.volume.ScreenBounds
import org.shipwrights.enderkinesis.block.GasVentBlock
import org.shipwrights.enderkinesis.client.volume.HalfResVolume
import org.shipwrights.enderkinesis.client.volume.VolumeDepth
import org.shipwrights.enderkinesis.client.volume.ValueNoise3D
import org.shipwrights.enderkinesis.client.volume.VolumeComposite
import org.shipwrights.enderkinesis.client.volume.VolumeEffect
import org.shipwrights.enderkinesis.client.volume.VolumePerf
import org.shipwrights.enderkinesis.client.volume.VolumeProxy
import org.shipwrights.enderkinesis.client.volume.VolumeTextures
import org.shipwrights.enderkinesis.client.volume.VolumeView
import org.shipwrights.enderkinesis.spell.GasField
import org.shipwrights.enderkinesis.spell.GasKind
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

// For each gas kind: one world volume, so gas from many casts merges into one cloud, and one volume for each ship with
// gas. The shader blends the "now" and "was" textures over the tick, so gas that moves between blocks slides.
object GasFieldClient {
    // Blocks across, and blocks up and down, around the camera; also the most that one ship volume holds.
    private const val SPAN = 128
    private const val SPAN_Y = 64
    private const val THIN_REBUILD_TICKS = 20
    private const val NOISE_UNIT = 3
    private const val NOW_UNIT = 4
    private const val WAS_UNIT = 5
    private const val FLOW_UNIT = 6
    private const val FLOW_WAS_UNIT = 12
    private const val OFFSET_A_UNIT = 13
    private const val OFFSET_B_UNIT = 14
    private const val OFFSET_SIZE = 6
    private const val JET_PAGE_UNIT = 7
    private const val JET_SAMPLE_UNIT = 8
    private const val JET_RUN_UNIT = 9
    private const val JET_ATLAS_UNIT = 10
    private const val BRICK_UNIT = 11
    // Empty-space bricks: BRICK blocks a side; a cell marks the bricks within BRICK_REACH blocks, the reach of field().
    private const val BRICK = 4
    private const val BRICK_REACH = 2
    private const val BRICK_FULL: Byte = 1
    // Ticks before a noise phase restarts; the other phase hides the restart.
    private const val FLOW_PERIOD_TICKS = 24L
    private const val FLOW_PERIOD = FLOW_PERIOD_TICKS.toFloat()
    // Ship gas has shipyard coordinates, too large for float noise; any period much larger than a ship works.
    private const val NOISE_PERIOD = 8192.0
    // Share of the gap to the real density that a cell closes each build, so the shown density eases over 2-3 ticks.
    private const val DENSITY_EASE = 0.5f
    private const val DENSITY_SNAP = 0.004f
    // Solid cells beside gas, for the volume clip. Two caches, kept until a block change or a chunk (re)load: block → solid,
    // and gas cell → a 26-bit mask of its solid neighbours. Entries carry the load count of their chunks, so a reload drops them.
    private const val SOLID_ALPHA: Byte = -1
    private const val SOLID_MISS_BUDGET = 20000
    private const val SOLID_CACHE_MAX = 1 shl 20
    private const val CELL_CACHE_MAX = 1 shl 18
    private const val CHUNK_LOADS_MAX = 1 shl 16
    private const val NEIGHBOURS = 26
    private const val MASK_BITS = 0x3FFFFFFL
    private const val NO_ENTRY = Long.MIN_VALUE
    private val solidCache = Long2IntOpenHashMap().also { it.defaultReturnValue(-1) }
    private val cellSolids = Long2LongOpenHashMap().also { it.defaultReturnValue(NO_ENTRY) }
    private val chunkLoads = Long2IntOpenHashMap()
    private val neighbourX = IntArray(NEIGHBOURS); private val neighbourY = IntArray(NEIGHBOURS); private val neighbourZ = IntArray(NEIGHBOURS)
    init {
        var b = 0
        for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
            if (dx == 0 && dy == 0 && dz == 0) continue
            neighbourX[b] = dx; neighbourY[b] = dy; neighbourZ[b] = dz
            b++
        }
    }
    private val solidPos = BlockPos.MutableBlockPos()
    private var solidMisses = 0
    // Raised by every block change, chunk load and cache clear, so a volume knows when its kept solid mask is stale.
    private var solidEpoch = 0
    private var solidLevel: ClientLevel? = null
    private const val SWIRL_PER_TICK = 4
    private const val SWIRL_ONE_IN = 30
    private const val SWIRL_MIN_SPEED = 0.25f
    private const val SWIRL_MIN_AMOUNT = 0.03f
    private const val SWIRL_RANGE_SQ = 48.0 * 48.0
    // Dark specks from cells above 1: chance a tick is STRAIN_CHANCE for each unit above 1.
    private const val STRAIN_PER_TICK = 3
    private const val STRAIN_CHANCE = 0.01f
    private const val STRAIN_DARKEN = 0.5f
    private const val STRAIN_DRIFT = 0.04

    // Render (client) thread only.
    private var ticks = 0
    private val log = LogUtils.getLogger()
    private var preparedCount = 0
    private val fields = GasKind.entries.map { KindField(it) }

    private val view = VolumeView()
    // Half-resolution march with temporal accumulation and the shared depth-aware upsample onto main.
    private val volume = HalfResVolume("gas", temporal = true, volumeDepth = true) { "volumes ${fields.count { !it.isEmpty() }}, jets ${GasJetLines.count}" }
    private val effect = VolumeEffect("spell_gas_field_volume", "DepthSampler" to volume.marchDepth)
    // build holds mask and upload; jetUp is the jet texture and page-table uploads.
    private val cpuPerf = VolumePerf.group("gas cpu")
    private val cpuTick = cpuPerf.cpuTimer("tick")
    private val cpuFrame = cpuPerf.cpuTimer("frame")
    private val cpuLines = cpuPerf.cpuTimer("lines")
    private val cpuTiles = cpuPerf.cpuTimer("tiles")
    private val cpuBuild = cpuPerf.cpuTimer("build")
    private val cpuMask = cpuPerf.cpuTimer("mask")
    private val cpuUpload = cpuPerf.cpuTimer("upload")
    private val cpuJetUpload = cpuPerf.cpuTimer("jetUp")
    private val bounds = ScreenBounds()
    private val localToView = Matrix4f()
    private val fieldRotation = Matrix4f()
    private val shipRotation = Quaternionf()
    private val scratch = Vector3d()
    private val shipPoint = Vector3d()
    private val swirlVelocity = Vector3d()
    private val swirlPos = BlockPos.MutableBlockPos()

    // Texels x first, then y, then z, packed to the size of the volume last built. Shared, because each build uploads at once.
    // 16 bit, because 0 to 2 in a byte steps by 0.008, which shows as steps in thin gas.
    private val nowData: ShortBuffer by lazy(LazyThreadSafetyMode.NONE) { BufferUtils.createShortBuffer(SPAN * SPAN_Y * SPAN) }
    private val wasData: ShortBuffer by lazy(LazyThreadSafetyMode.NONE) { BufferUtils.createShortBuffer(SPAN * SPAN_Y * SPAN) }
    // RGB: the flow as int8 + 128, in the axes of the volume's frame.
    private val flowData: ByteBuffer by lazy(LazyThreadSafetyMode.NONE) { BufferUtils.createByteBuffer(SPAN * SPAN_Y * SPAN * 4) }
    // The flow of the build before, blended like the density: the noise offset is flow x phase, so a flow step jumps it.
    private val flowWasData: ByteBuffer by lazy(LazyThreadSafetyMode.NONE) { BufferUtils.createByteBuffer(SPAN * SPAN_Y * SPAN * 4) }
    // One phase of the noise offsets (RGB half float, blocks) at a time; the upload copies it before the next phase is written.
    // Half floats on the CPU: the texture is RGB16F, and a float upload makes the driver convert every texel.
    private val offsetData: ShortBuffer by lazy(LazyThreadSafetyMode.NONE) { BufferUtils.createShortBuffer(SPAN * SPAN_Y * SPAN * 3) }
    private val offsetPool = ArrayList<FloatArray>()
    // The texels phase A wrote and their offsets, so phase B rewrites only those texels.
    private val offsetTexels = IntArrayList()
    private val offsetCells = ArrayList<FloatArray>()
    // 1 for a brick with gas within reach, packed like nowData at brick size.
    private val brickData: ByteBuffer by lazy(LazyThreadSafetyMode.NONE) {
        BufferUtils.createByteBuffer((SPAN / BRICK) * (SPAN_Y / BRICK) * (SPAN / BRICK))
    }

    /** Least corner in blocks of its frame. */
    private class Box(val minX: Int, val minY: Int, val minZ: Int, val w: Int, val h: Int, val d: Int)

    private class Region(var minX: Int, var minY: Int, var minZ: Int, var maxX: Int, var maxY: Int, var maxZ: Int) {
        fun include(x: Int, y: Int, z: Int) {
            if (x < minX) minX = x; if (y < minY) minY = y; if (z < minZ) minZ = z
            if (x > maxX) maxX = x; if (y > maxY) maxY = y; if (z > maxZ) maxZ = z
        }

        fun reset(): Region {
            minX = Int.MAX_VALUE; minY = Int.MAX_VALUE; minZ = Int.MAX_VALUE
            maxX = Int.MIN_VALUE; maxY = Int.MIN_VALUE; maxZ = Int.MIN_VALUE
            return this
        }
    }

    /** The tick pass visits every cell, so it also sums each chunk for [MiasmaSounds]. */
    class ChunkGas {
        var amount = 0.0
            private set
        /** The cell nearest the listener. */
        var x = 0.0
            private set
        var y = 0.0
            private set
        var z = 0.0
            private set
        var nearestSqr = Double.MAX_VALUE
            private set

        fun clear() {
            amount = 0.0
            nearestSqr = Double.MAX_VALUE
        }

        fun add(cx: Double, cy: Double, cz: Double, a: Float, lx: Double, ly: Double, lz: Double) {
            amount += a
            val dx = cx - lx; val dy = cy - ly; val dz = cz - lz
            val d = dx * dx + dy * dy + dz * dz
            if (d >= nearestSqr) return
            nearestSqr = d
            x = cx; y = cy; z = cz
        }
    }

    fun init() {
        ClientTickEvent.CLIENT_POST.register { mc ->
            val level = mc.level
            if (level == null) reset() else {
                cpuTick.begin()
                ticks++
                GasJetClient.tick(level)
                fields.forEach { it.tick(mc, level) }
                cpuTick.end()
            }
        }
    }

    fun isEmpty(): Boolean = fields.all { it.isEmpty() }

    private val miasma get() = fields[GasKind.MIASMA.ordinal]

    fun chunkKeys(): LongSet = miasma.summaries.keys

    /** Miasma as of the last tick. Null when the chunk held none then. Ship chunks are in the shipyard; VS2 plays their sounds at the ship. */
    fun summary(chunk: Long): ChunkGas? = miasma.summaries.get(chunk)

    fun received(kind: GasKind, chunk: Long, cells: Int2FloatOpenHashMap, flows: Int2IntOpenHashMap) =
        fields[kind.ordinal].received(chunk, cells, flows)

    /** An amount of 0 removes the cell. */
    fun changed(kind: GasKind, chunk: Long, cells: Int2FloatOpenHashMap, flows: Int2IntOpenHashMap) =
        fields[kind.ordinal].changed(chunk, cells, flows)

    fun reset() {
        GasJetClient.reset()
        fields.forEach { it.reset() }
        clearSolids()
    }

    private fun clearSolids() {
        solidEpoch++
        solidCache.clear()
        cellSolids.clear()
        chunkLoads.clear()
        solidLevel = null
    }

    /** Client thread: a block changed, so its solid flag and the masks of the cells around it are stale. */
    fun onBlockChanged(pos: BlockPos) {
        solidEpoch++
        if (solidCache.isEmpty() && cellSolids.isEmpty()) return
        solidCache.remove(pos.asLong())
        for (b in 0 until NEIGHBOURS) cellSolids.remove(BlockPos.asLong(pos.x + neighbourX[b], pos.y + neighbourY[b], pos.z + neighbourZ[b]))
    }

    /** Client thread: a chunk came from the server, so every cached flag in it is stale. */
    fun onChunkLoaded(chunkX: Int, chunkZ: Int) {
        solidEpoch++
        if (solidCache.isEmpty() && cellSolids.isEmpty()) return
        chunkLoads.addTo(ChunkPos.asLong(chunkX, chunkZ), 1)
    }

    // Call two times a frame: in VolumeDepth.behind, then at renderLevel TAIL (the volume writes no depth, so anything
    // drawn after it would paint over it). The first call of a frame updates.
    @JvmStatic
    fun render(poseStack: PoseStack, projection: Matrix4f, camera: Camera) {
        if (isEmpty() || IrisCompat.isShaderPackInUse()) return
        cpuFrame.begin()
        renderVolumes(poseStack, projection, camera)
        cpuFrame.end()
    }

    private fun renderVolumes(poseStack: PoseStack, projection: Matrix4f, camera: Camera) {
        val eff = effect.get()
        if (eff == null) {
            logLoadFailure("spell_gas_field_volume")
            return
        }
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val cam = camera.position
        val main = mc.mainRenderTarget
        view.update(projection, poseStack.last().pose(), cam.x, cam.y, cam.z)
        if (preparedFrame != VolumeDepth.frame) {
            preparedFrame = VolumeDepth.frame
            prepare(level, cam, mc.frameTime)
        } else {
            bindJetTextures()
        }
        if (!bounds.isEmpty) {
            view.pushViewProj(eff)
            view.pushInvViewProj(eff)
            if (!volume.beginMarch(main, eff, bounds)) {
                logLoadFailure("volume_half_depth or volume_upsample")
                return
            }
            eff.safeGetUniform("ScreenSize").set(volume.width.toFloat(), volume.height.toFloat())
            eff.safeGetUniform("SpellTicks").set(SpellClock.time(level, mc.frameTime))
            eff.safeGetUniform("FieldTexSize").set(SPAN.toFloat(), SPAN_Y.toFloat(), SPAN.toFloat())
            VolumeTextures.bind3D(NOISE_UNIT, ValueNoise3D.textureId())
            VolumeProxy.beginBound()
            fields.forEach { it.draw(eff, level, cam) }
            VolumeProxy.endBound(eff)
            volume.composite(main, view, bounds)
            VolumeComposite.restore(main)
        }
        VolumeTextures.bind3D(NOW_UNIT, 0)
        VolumeTextures.bind3D(WAS_UNIT, 0)
        VolumeTextures.bind3D(FLOW_UNIT, 0)
        VolumeTextures.bind3D(FLOW_WAS_UNIT, 0)
        VolumeTextures.bind3D(OFFSET_A_UNIT, 0)
        VolumeTextures.bind3D(OFFSET_B_UNIT, 0)
        VolumeTextures.bind3D(BRICK_UNIT, 0)
        VolumeTextures.bind3D(NOISE_UNIT, 0)
        VolumeTextures.bind2D(JET_SAMPLE_UNIT, 0)
        VolumeTextures.bind2D(JET_RUN_UNIT, 0)
        VolumeTextures.bind3D(JET_PAGE_UNIT, 0)
        VolumeTextures.bind3D(JET_ATLAS_UNIT, 0)
    }

    private var preparedFrame = -1L

    private fun prepare(level: ClientLevel, cam: Vec3, frameTime: Float) {
        preparedCount++
        cpuLines.begin()
        GasJetLines.update(level, cam, frameTime, preparedCount.toLong(), view)
        cpuLines.end()
        fields.forEach { it.refresh(level, cam) }
        cpuTiles.begin()
        GasJetTiles.beginFrame()
        fields.forEach { it.bindJetVolumes() }
        cpuTiles.end()
        cpuJetUpload.begin()
        uploadJetTextures()
        cpuJetUpload.end()
        bounds.reset()
        fields.forEach { it.addBounds(level, cam) }
    }

    private var loadFailureLogged = false

    /** No gas draws without these programs, so say so once, as an error. */
    private fun logLoadFailure(program: String) {
        if (loadFailureLogged) return
        loadFailureLogged = true
        log.error("Gas volumes are not drawn: the shader program {} did not load (see the earlier warning for the compile log)", program)
    }

    private var jetSampleTex = -1
    private var jetRunTex = -1
    private var jetAtlasTex = -1
    private var jetPageTex = -1

    /** The shared jet textures of this frame: samples, runs and the page atlas, each with only the used part uploaded. */
    private fun uploadJetTextures() {
        if (GasJetTiles.volumesInUse == 0) return
        if (jetSampleTex < 0) {
            jetSampleTex = GlStateManager._genTexture()
            VolumeTextures.allocateUint2D(jetSampleTex, GasJetLines.SAMPLE_TEX_W, GasJetLines.SAMPLE_TEX_H, GL30.GL_RGBA32UI, GL30.GL_RGBA_INTEGER)
            jetRunTex = GlStateManager._genTexture()
            VolumeTextures.allocateUint2D(jetRunTex, GasJetLines.RUN_TEX_W, GasJetLines.RUN_TEX_H, GL30.GL_R32UI, GL30.GL_RED_INTEGER)
            jetAtlasTex = GlStateManager._genTexture()
            VolumeTextures.allocateUint3D(
                jetAtlasTex, GasJetLines.ATLAS_W, GasJetLines.ATLAS_W, GasJetLines.ATLAS_D, GL30.GL_R32UI, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT,
            )
            jetPageTex = GlStateManager._genTexture()
            VolumeTextures.allocateUint3D(
                jetPageTex, GasJetLines.TABLE_X, GasJetLines.TABLE_Y, GasJetLines.TABLE_Z, GL30.GL_R16UI, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_SHORT,
            )
        }
        val sampleRows = (GasJetLines.used + GasJetLines.SAMPLE_TEX_W - 1) / GasJetLines.SAMPLE_TEX_W
        VolumeTextures.uploadUint2D(jetSampleTex, GasJetLines.SAMPLE_TEX_W, sampleRows, GL30.GL_RGBA_INTEGER, 4, GasJetTiles.sampleTexels)
        val runRows = (GasJetTiles.runCount + GasJetLines.RUN_TEX_W - 1) / GasJetLines.RUN_TEX_W
        if (runRows > 0) VolumeTextures.uploadUint2D(jetRunTex, GasJetLines.RUN_TEX_W, runRows, GL30.GL_RED_INTEGER, 1, GasJetTiles.runTexels)
        for (p in 0 until GasJetTiles.pagesUsed) {
            val px = p % GasJetLines.ATLAS_PAGES_XY; val py = (p / GasJetLines.ATLAS_PAGES_XY) % GasJetLines.ATLAS_PAGES_XY
            val pz = p / (GasJetLines.ATLAS_PAGES_XY * GasJetLines.ATLAS_PAGES_XY)
            VolumeTextures.uploadUint3D(
                jetAtlasTex, px * GasJetLines.PAGE_SIZE, py * GasJetLines.PAGE_SIZE, pz * GasJetLines.PAGE_SIZE,
                GasJetLines.PAGE_SIZE, GasJetLines.PAGE_SIZE, GasJetLines.PAGE_SIZE, GL30.GL_RED_INTEGER, GasJetTiles.pageContents(p),
            )
        }
        bindJetTextures()
    }

    private fun bindJetTextures() {
        if (GasJetTiles.volumesInUse == 0) return
        VolumeTextures.bind2D(JET_SAMPLE_UNIT, jetSampleTex)
        VolumeTextures.bind2D(JET_RUN_UNIT, jetRunTex)
        VolumeTextures.bind3D(JET_ATLAS_UNIT, jetAtlasTex)
    }

    private fun allocateFlowTexture(): Int = GlStateManager._genTexture().also {
        VolumeTextures.allocateRgba8x3D(it, SPAN, SPAN_Y, SPAN, GL11.GL_LINEAR)
    }

    private fun allocateOffsetTexture(): Int = GlStateManager._genTexture().also {
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, it)
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
        GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL30.GL_RGB16F, SPAN, SPAN_Y, SPAN, 0, GL11.GL_RGB, GL30.GL_HALF_FLOAT, null as ShortBuffer?)
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0)
    }

    private fun uploadOffsets(tex: Int, w: Int, h: Int, d: Int) {
        VolumeTextures.beginUpload3D(tex)
        offsetData.position(0).limit(w * h * d * 3)
        GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, 0, 0, 0, w, h, d, GL11.GL_RGB, GL30.GL_HALF_FLOAT, offsetData)
        offsetData.limit(offsetData.capacity())
        VolumeTextures.endUpload3D()
    }

    private fun allocateBrickTexture(): Int = GlStateManager._genTexture().also {
        VolumeTextures.allocateR8x3D(it, SPAN / BRICK, SPAN_Y / BRICK, SPAN / BRICK, GL11.GL_NEAREST)
    }

    /** Marks the bricks within [BRICK_REACH] blocks of the cell at ([x], [y], [z]) from the box corner. */
    @Suppress("LongParameterList") // Raw ints: called for each gas cell of a build.
    private fun markBricks(x: Int, y: Int, z: Int, bw: Int, bh: Int, bd: Int) {
        for (bz in maxOf(0, (z - BRICK_REACH) / BRICK)..minOf(bd - 1, (z + BRICK_REACH) / BRICK)) {
            for (by in maxOf(0, (y - BRICK_REACH) / BRICK)..minOf(bh - 1, (y + BRICK_REACH) / BRICK)) {
                for (bx in maxOf(0, (x - BRICK_REACH) / BRICK)..minOf(bw - 1, (x + BRICK_REACH) / BRICK)) {
                    brickData.put(bx + bw * (by + bh * bz), BRICK_FULL)
                }
            }
        }
    }

    private fun allocateDensityTexture(): Int = GlStateManager._genTexture().also {
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, it)
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
        GL12.glTexImage3D(
            GL12.GL_TEXTURE_3D, 0, GL30.GL_R16, SPAN, SPAN_Y, SPAN, 0, GL11.GL_RED, GL11.GL_UNSIGNED_SHORT, null as ShortBuffer?,
        )
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0)
    }

    private fun packDensity(a: Float): Short = (a / GasField.MAX_AMOUNT * 65535f + 0.5f).toInt().coerceIn(0, 65535).toShort()

    // IEEE half, rounded to nearest; Float.floatToFloat16 needs Java 20.
    private fun halfOf(f: Float): Short {
        val bits = java.lang.Float.floatToRawIntBits(f)
        val sign = (bits ushr 16) and 0x8000
        val mag = bits and 0x7FFFFFFF
        val v = mag + 0x1000
        val half = when {
            mag >= 0x47800000 -> if (mag > 0x7F800000) 0x7E00 else 0x7C00
            v >= 0x47800000 -> 0x7BFF
            v >= 0x38800000 -> (v - 0x38000000) ushr 13
            mag < 0x33000000 -> 0
            else -> {
                val e = mag ushr 23
                (((mag and 0x7FFFFF) or 0x800000) + (0x800000 ushr (e - 102))) ushr (126 - e)
            }
        }
        return (sign or half).toShort()
    }

    private fun uploadDensity(tex: Int, data: ShortBuffer, w: Int, h: Int, d: Int) {
        VolumeTextures.beginUpload3D(tex)
        data.position(0).limit(w * h * d)
        GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, 0, 0, 0, w, h, d, GL11.GL_RED, GL11.GL_UNSIGNED_SHORT, data)
        data.limit(data.capacity())
        VolumeTextures.endUpload3D()
    }

    private fun upload(tex: Int, data: ByteBuffer, w: Int, h: Int, d: Int) {
        VolumeTextures.beginUpload3D(tex)
        VolumeTextures.uploadR8x3D(0, 0, 0, w, h, d, data)
        VolumeTextures.endUpload3D()
    }

    private class KindField(val kind: GasKind) {
        val chunks = Long2ObjectOpenHashMap<Int2FloatOpenHashMap>()
        // Blocks a tick, in the frame of the chunk; dragged each tick as the server drags them.
        val flows = Long2ObjectOpenHashMap<Int2ObjectOpenHashMap<FloatArray>>()
        val summaries = Long2ObjectOpenHashMap<ChunkGas>()
        // The frames (0: the world, else a ship id) whose cells or flows changed since the last refresh, so only they rebuild.
        private val dirtyFrames = LongOpenHashSet()
        private var allDirty = false

        inner class GasVolume {
            // Keyed by packed block position. The next build blends from it.
            var shown = Long2FloatOpenHashMap()
            // Swapped with [shown], so a build allocates no map.
            var spare = Long2FloatOpenHashMap()
            var nowTex = -1
            var wasTex = -1
            var flowTex = -1
            var flowWasTex = -1
            // Packed flow bytes (r | g << 8 | b << 16) by block position, of this build and the one before.
            var flowNow = Long2IntOpenHashMap()
            var flowWas = Long2IntOpenHashMap()
            // The noise offsets of each cell in blocks: the flow summed since phase A (0-2) and phase B (3-5) last restarted.
            val offsets = Long2ObjectOpenHashMap<FloatArray>()
            var offsetTexA = -1
            var offsetTexB = -1
            // True when the offset textures hold zeros over [box], so still gas needs no offset upload.
            var offsetsZero = false
            var phaseTick = Long.MIN_VALUE
            var brickTex = -1
            // 0 is the world; else the ship whose shipyard this volume covers.
            var frame = 0L
            var jet: GasJetTiles.JetVolume? = null
            var box: Box? = null
            // The map last packed as the now textures; a build whose `was` is that map in the same box swaps the textures in.
            var packedNow: Long2FloatOpenHashMap? = null
            // The solid texels of the last mask (box-relative), reused while the cells, the box and [solidEpoch] stay the same.
            val solidTexels = IntArrayList()
            var maskValid = false
            var maskEpoch = 0
            var keysSame = false
            var version = 0
            var builtVersion = -1
            var builtTick = Int.MIN_VALUE / 2
            var builtAt: Long = Long.MIN_VALUE
            // True when the last build blended between amounts, so one more build must settle the blend.
            var settling = false
            // True while some cell has not reached its real density, so the build repeats.
            var easing = false

            /** At most once a tick, so each blend runs over one tick. Build once more after the last change to settle. */
            fun refresh(minBuildHeight: Int, at: Long, x0: Int, y0: Int, z0: Int) {
                if (at == builtAt && (ticks == builtTick || (version == builtVersion && !settling))) return
                if (ticks == builtTick) {
                    // A new window in the same tick: the same blend in the new box, with no second ease.
                    cpuBuild.begin()
                    keysSame = false
                    pack(this, minBuildHeight, x0, y0, z0, shown, spare, false)
                    cpuBuild.end()
                    builtAt = at
                    return
                }
                settling = version != builtVersion
                cpuBuild.begin()
                build(this, minBuildHeight, x0, y0, z0)
                cpuBuild.end()
                settling = settling || easing
                builtVersion = version
                builtAt = at
                builtTick = ticks
            }

            fun release() {
                if (nowTex >= 0) GlStateManager._deleteTexture(nowTex)
                if (wasTex >= 0) GlStateManager._deleteTexture(wasTex)
                if (flowTex >= 0) GlStateManager._deleteTexture(flowTex)
                if (flowWasTex >= 0) GlStateManager._deleteTexture(flowWasTex)
                if (brickTex >= 0) GlStateManager._deleteTexture(brickTex)
                if (offsetTexA >= 0) GlStateManager._deleteTexture(offsetTexA)
                if (offsetTexB >= 0) GlStateManager._deleteTexture(offsetTexB)
                nowTex = -1; wasTex = -1; flowTex = -1; flowWasTex = -1; brickTex = -1; offsetTexA = -1; offsetTexB = -1
                offsetsZero = false; packedNow = null
                flowNow.clear(); flowWas.clear()
                clearOffsets()
            }

            fun clearOffsets() {
                offsetPool.addAll(offsets.values)
                offsets.clear()
                phaseTick = Long.MIN_VALUE
            }

            /** True while the shown noise still moves, so each tick must build to carry the offsets on. */
            fun moving(): Boolean = offsets.isNotEmpty() || flowNow.isNotEmpty()
        }

        val world = GasVolume()
        val ships = Long2ObjectOpenHashMap<GasVolume>()
        // Rebuilt each tick: the gas bounds of each ship, in its shipyard.
        val shipRegions = Long2ObjectOpenHashMap<Region>()
        private val spareRegions = ArrayList<Region>()

        private fun clearRegions() {
            spareRegions.addAll(shipRegions.values)
            shipRegions.clear()
        }

        fun isEmpty(): Boolean = chunks.isEmpty() && world.shown.isEmpty() && ships.isEmpty() && GasJetClient.isEmpty(kind)

        fun received(chunk: Long, cells: Int2FloatOpenHashMap, flowsIn: Int2IntOpenHashMap) {
            // The periodic resync resends whole chunks that mostly did not change.
            val same = sameCells(chunks.get(chunk), cells)
            if (cells.isEmpty()) forget(chunk) else chunks.put(chunk, cells)
            val hadFlows = flows.remove(chunk) != null
            applyFlows(chunk, flowsIn)
            if (!same || hadFlows || !flowsIn.isEmpty()) markDirty(chunk)
        }

        private fun sameCells(old: Int2FloatOpenHashMap?, new: Int2FloatOpenHashMap): Boolean {
            if (old == null) return new.isEmpty()
            if (old.size != new.size) return false
            val it = new.int2FloatEntrySet().fastIterator()
            while (it.hasNext()) {
                val e = it.next()
                if (!old.containsKey(e.intKey) || old.get(e.intKey) != e.floatValue) return false
            }
            return true
        }

        private fun markDirty(chunk: Long) {
            val level = Minecraft.getInstance().level
            if (level == null) { allDirty = true; return }
            val ship = level.getShipManagingPos(ChunkPos.getX(chunk), ChunkPos.getZ(chunk))
            dirtyFrames.add(ship?.id ?: 0L)
        }

        /** A packed flow of 0 removes it. */
        private fun applyFlows(chunk: Long, flowsIn: Int2IntOpenHashMap) {
            if (flowsIn.isEmpty()) return
            var have = flows.get(chunk)
            val it = flowsIn.int2IntEntrySet().fastIterator()
            while (it.hasNext()) {
                val e = it.next()
                if (e.intValue == 0) { have?.remove(e.intKey); continue }
                val into = (have ?: Int2ObjectOpenHashMap<FloatArray>().also { m -> have = m; flows.put(chunk, m) })
                    .getOrPut(e.intKey) { FloatArray(3) }
                GasField.unpackFlow(e.intValue, into)
            }
            if (have?.isEmpty() == true) flows.remove(chunk)
        }

        fun changed(chunk: Long, cells: Int2FloatOpenHashMap, flowsIn: Int2IntOpenHashMap) {
            applyFlows(chunk, flowsIn)
            var have = chunks.get(chunk)
            // The server sends a cell when its float changes, but the client gets the byte, which mostly stays the same.
            var any = !flowsIn.isEmpty()
            val it = cells.int2FloatEntrySet().fastIterator()
            while (it.hasNext()) {
                val e = it.next()
                if (e.floatValue <= 0f) {
                    if (have?.containsKey(e.intKey) == true) { have?.remove(e.intKey); any = true }
                } else {
                    val into = have ?: Int2FloatOpenHashMap().also { m -> have = m; chunks.put(chunk, m) }
                    if (!into.containsKey(e.intKey) || into.get(e.intKey) != e.floatValue) { into.put(e.intKey, e.floatValue); any = true }
                }
            }
            if (have?.isEmpty() == true) forget(chunk)
            if (any) markDirty(chunk)
        }

        private fun forget(chunk: Long) {
            chunks.remove(chunk)
            flows.remove(chunk)
            summaries.remove(chunk)
        }

        fun reset() {
            if (chunks.isEmpty() && world.box == null && ships.isEmpty()) return
            chunks.clear()
            flows.clear()
            summaries.clear()
            world.shown.clear()
            world.spare.clear()
            world.flowNow.clear()
            world.flowWas.clear()
            world.clearOffsets()
            world.box = null
            ships.values.forEach { it.release() }
            ships.clear()
            clearRegions()
            allDirty = true
        }

        /** A volume whose noise still moves builds each tick. */
        private fun bumpMoving() {
            if (world.moving()) world.version++
            val it = ships.long2ObjectEntrySet().fastIterator()
            while (it.hasNext()) {
                val e = it.next()
                if (e.value.moving()) e.value.version++
            }
        }

        private fun jetsIn(frame: Long): Boolean {
            for (l in 0 until GasJetLines.count) {
                val line = GasJetLines.lines[l]
                if (line.kind == kind && line.frame == frame) return true
            }
            for (l in 0 until GasJetLines.splatCount) {
                val line = GasJetLines.splatLines[l]
                if (line.kind == kind && line.frame == frame) return true
            }
            return false
        }

        // After GasJetLines.update this frame, so a volume with jet lines builds each tick from the lines it will draw.
        private fun applyDirty() {
            if (allDirty || dirtyFrames.contains(0L) || jetsIn(0L)) world.version++
            val it = ships.long2ObjectEntrySet().fastIterator()
            while (it.hasNext()) {
                val e = it.next()
                if (allDirty || dirtyFrames.contains(e.longKey) || jetsIn(e.longKey)) e.value.version++
            }
            allDirty = false
            dirtyFrames.clear()
        }

        private fun dragFlows(level: ClientLevel) {
            var budget = SWIRL_PER_TICK
            val cam = Minecraft.getInstance().gameRenderer.mainCamera.position
            val chunkIt = flows.long2ObjectEntrySet().fastIterator()
            while (chunkIt.hasNext()) {
                val chunkEntry = chunkIt.next()
                val cells = chunkEntry.value
                val it = cells.int2ObjectEntrySet().fastIterator()
                while (it.hasNext()) {
                    val cellEntry = it.next()
                    val v = cellEntry.value
                    for (a in 0..2) v[a] *= kind.flowDrag
                    if (budget > 0 && spawnSwirl(level, cam, chunkEntry.longKey, cellEntry.intKey, v)) budget--
                    if (abs(v[0]) + abs(v[1]) + abs(v[2]) < GasField.STILL) it.remove()
                }
                if (cells.isEmpty()) chunkIt.remove()
            }
        }

        // Particles start with the cell flow and curl on their own. A ship chunk's flow and position go through its render transform.
        private fun spawnSwirl(level: ClientLevel, cam: Vec3, chunk: Long, index: Int, v: FloatArray): Boolean {
            val speed = abs(v[0]) + abs(v[1]) + abs(v[2])
            if (speed < SWIRL_MIN_SPEED || level.random.nextInt(SWIRL_ONE_IN) != 0) return false
            val amount = chunks.get(chunk)?.get(index) ?: 0f
            if (amount < SWIRL_MIN_AMOUNT) return false
            swirlVelocity.set(v[0].toDouble(), v[1].toDouble(), v[2].toDouble())
            val mix = level.random.nextFloat()
            return emitParticle(
                level, cam, chunk, index,
                kind.darkR + (kind.lightR - kind.darkR) * mix, kind.darkG + (kind.lightG - kind.darkG) * mix,
                kind.darkB + (kind.lightB - kind.darkB) * mix,
            )
        }

        // A faint dark speck that drifts out of a cell above 1, in the dark tint of the gas, darker.
        private fun spawnStrain(level: ClientLevel, cam: Vec3, chunk: Long, index: Int): Boolean {
            val r = level.random
            swirlVelocity.set((r.nextDouble() - 0.5) * STRAIN_DRIFT, (r.nextDouble() - 0.5) * STRAIN_DRIFT, (r.nextDouble() - 0.5) * STRAIN_DRIFT)
            return emitParticle(level, cam, chunk, index, kind.darkR * STRAIN_DARKEN, kind.darkG * STRAIN_DARKEN, kind.darkB * STRAIN_DARKEN)
        }

        // The velocity is in [swirlVelocity] in the frame of the chunk; a ship chunk's position and velocity go through its render transform.
        @Suppress("LongParameterList") // A cell and a tint.
        private fun emitParticle(level: ClientLevel, cam: Vec3, chunk: Long, index: Int, red: Float, green: Float, blue: Float): Boolean {
            scratch.set(
                GasField.cellX(ChunkPos.getX(chunk), index) + level.random.nextDouble(),
                GasField.cellY(level.minBuildHeight, index) + level.random.nextDouble(),
                GasField.cellZ(ChunkPos.getZ(chunk), index) + level.random.nextDouble(),
            )
            val ship = level.getShipManagingPos(ChunkPos.getX(chunk), ChunkPos.getZ(chunk)) as? ClientShip
            if (ship != null) {
                if (level.random.nextFloat() < ClientShipCloakingState.getProgress(ship.id)) return false
                ship.renderTransform.shipToWorld.transformPosition(scratch)
                ship.renderTransform.shipToWorldRotation.transform(swirlVelocity)
            }
            val dx = scratch.x - cam.x; val dy = scratch.y - cam.y; val dz = scratch.z - cam.z
            if (dx * dx + dy * dy + dz * dz > SWIRL_RANGE_SQ) return false
            // A world cell can lie beside a solid block at its edge; ship cells are open by the gas rules.
            if (ship == null) {
                swirlPos.set(scratch.x, scratch.y, scratch.z)
                if (!level.getBlockState(swirlPos).getCollisionShape(level, swirlPos).isEmpty) return false
                if (insideShipBlock(level)) return false
            }
            Minecraft.getInstance().particleEngine.add(
                GasFlowParticle(
                    level, scratch.x, scratch.y, scratch.z, swirlVelocity.x, swirlVelocity.y, swirlVelocity.z,
                    red, green, blue, kind == GasKind.DRAKE_BREATH,
                    ship?.id ?: Long.MIN_VALUE,
                ),
            )
            return true
        }

        // World gas lies under and beside hulls; a particle started in a ship wall would show through it.
        private fun insideShipBlock(level: ClientLevel): Boolean {
            for (other in level.shipObjectWorld.loadedShips) {
                if (!other.worldAABB.containsPoint(scratch.x, scratch.y, scratch.z)) continue
                (other as? ClientShip ?: continue).renderTransform.worldToShip.transformPosition(scratch, shipPoint)
                swirlPos.set(shipPoint.x, shipPoint.y, shipPoint.z)
                if (!level.getBlockState(swirlPos).getCollisionShape(level, swirlPos).isEmpty) return true
            }
            return false
        }

        fun tick(mc: Minecraft, level: ClientLevel) {
            dragFlows(level)
            clearRegions()
            bumpMoving()
            if (chunks.isEmpty()) {
                summaries.clear()
                includeJetShips()
                dropIdleShips(level)
                return
            }
            val worldListener = mc.gameRenderer.mainCamera.position
            val minY = level.minBuildHeight
            var strainLeft = STRAIN_PER_TICK
            val it = chunks.long2ObjectEntrySet().fastIterator()
            while (it.hasNext()) {
                val entry = it.next()
                val key = entry.longKey
                val cells = entry.value
                val gas = summaries.get(key) ?: ChunkGas().also { summaries.put(key, it) }
                gas.clear()
                val chunkX = ChunkPos.getX(key); val chunkZ = ChunkPos.getZ(key)
                val ship = level.getShipManagingPos(chunkX, chunkZ) as? ClientShip
                // The listener in the frame of this chunk, so the nearest cell is right on a ship too.
                scratch.set(worldListener.x, worldListener.y, worldListener.z)
                if (ship != null) ship.renderTransform.worldToShip.transformPosition(scratch)
                val lx = scratch.x; val ly = scratch.y; val lz = scratch.z
                val region = ship?.let { s ->
                    shipRegions.get(s.id) ?: (spareRegions.removeLastOrNull()?.reset()
                        ?: Region(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, Int.MIN_VALUE, Int.MIN_VALUE))
                        .also { shipRegions.put(s.id, it) }
                }
                val c = cells.int2FloatEntrySet().fastIterator()
                while (c.hasNext()) {
                    val cell = c.next()
                    val i = cell.intKey
                    val x = GasField.cellX(chunkX, i); val y = GasField.cellY(minY, i); val z = GasField.cellZ(chunkZ, i)
                    gas.add(x + 0.5, y + 0.5, z + 0.5, cell.floatValue, lx, ly, lz)
                    if (cell.floatValue > 1f && strainLeft > 0 && level.random.nextFloat() < (cell.floatValue - 1f) * STRAIN_CHANCE &&
                        spawnStrain(level, worldListener, key, i)
                    ) strainLeft--
                    region?.include(x, y, z)
                }
                if (cells.isEmpty()) {
                    it.remove()
                    summaries.remove(key)
                }
            }
            includeJetShips()
            dropIdleShips(level)
            if (ticks % THIN_REBUILD_TICKS == 0) allDirty = true
        }

        private val jetBounds = IntArray(6)

        /** Draws the coarse jet lines and the tiles that lost runs into the 1-block field, as a floor under the exact capsules. For drawing only. */
        @Suppress("LongParameterList") // The region of the build.
        private fun splatJets(now: Long2FloatOpenHashMap, v: GasVolume, minBuildHeight: Int, rx0: Int, ry0: Int, rz0: Int, rx1: Int, ry1: Int, rz1: Int) {
            if (!GasJetLines.OVERFLOW_SPLAT) return
            for (l in 0 until GasJetLines.splatCount) {
                val line = GasJetLines.splatLines[l]
                if (line.kind != kind || line.frame != v.frame) continue
                for (i in 0 until line.n) {
                    val g = line.first + i
                    val px = line.ox + GasJetLines.sx[g]; val py = line.oy + GasJetLines.sy[g]; val pz = line.oz + GasJetLines.sz[g]
                    val reach = GasJetLines.sr[g] + 0.5f
                    for (x in floor(px - reach).toInt()..floor(px + reach).toInt()) {
                        for (y in floor(py - reach).toInt()..floor(py + reach).toInt()) {
                            for (z in floor(pz - reach).toInt()..floor(pz + reach).toInt()) {
                                if (x < rx0 || x > rx1 || y < ry0 || y > ry1 || z < rz0 || z > rz1) continue
                                val dx = x + 0.5 - px; val dy = y + 0.5 - py; val dz = z + 0.5 - pz
                                val rho = (dx * dx + dy * dy + dz * dz) / (reach * reach)
                                if (rho >= 1.0) continue
                                val value = GasJetLines.sc[g] * ((1.0 - rho) * (1.0 - rho)).toFloat()
                                val key = BlockPos.asLong(x, y, z)
                                if (value > now.get(key)) now.put(key, value)
                            }
                        }
                    }
                }
            }
            for (i in 0 until GasJetTiles.dropCount) {
                if (GasJetTiles.dropKind[i] != kind.ordinal || GasJetTiles.dropFrame[i] != v.frame) continue
                val x = GasJetTiles.dropX[i]; val y = GasJetTiles.dropY[i]; val z = GasJetTiles.dropZ[i]
                if (x < rx0 || x > rx1 || y < ry0 || y > ry1 || z < rz0 || z > rz1) continue
                val key = BlockPos.asLong(x, y, z)
                if (GasJetTiles.dropValue[i] > now.get(key)) now.put(key, GasJetTiles.dropValue[i])
            }
        }

        /** A ship that holds only jet lines still needs a volume, so its region covers them. */
        private fun includeJetShips() {
            GasJetLines.forEachShipFrame(kind) { id ->
                if (!GasJetLines.bounds(kind, id, jetBounds)) return@forEachShipFrame
                val r = shipRegions.get(id) ?: (spareRegions.removeLastOrNull()?.reset()
                    ?: Region(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, Int.MIN_VALUE, Int.MIN_VALUE))
                    .also { shipRegions.put(id, it) }
                r.include(jetBounds[0], jetBounds[1], jetBounds[2])
                r.include(jetBounds[3], jetBounds[4], jetBounds[5])
            }
        }

        /** Adds the screen bounds of the volumes of this kind: the world box, and the world box of each ship. */
        fun addBounds(level: ClientLevel, cam: Vec3) {
            world.box?.let { b ->
                bounds.addBox(
                    view, (b.minX - cam.x).toFloat(), (b.minY - cam.y).toFloat(), (b.minZ - cam.z).toFloat(),
                    (b.minX + b.w - cam.x).toFloat(), (b.minY + b.h - cam.y).toFloat(), (b.minZ + b.d - cam.z).toFloat(),
                )
            }
            val it = ships.long2ObjectEntrySet().fastIterator()
            while (it.hasNext()) {
                val e = it.next()
                if (e.value.box == null) continue
                val ship = level.shipObjectWorld.loadedShips.getById(e.longKey) as? ClientShip ?: continue
                if (ClientShipCloakingState.getProgress(ship.id) >= 1f) continue
                val a = ship.worldAABB
                bounds.addBox(
                    view, (a.minX() - cam.x).toFloat(), (a.minY() - cam.y).toFloat(), (a.minZ() - cam.z).toFloat(),
                    (a.maxX() - cam.x).toFloat(), (a.maxY() - cam.y).toFloat(), (a.maxZ() - cam.z).toFloat(),
                )
            }
        }

        /** Bins the jet lines of every volume of this kind that has a box. */
        fun bindJetVolumes() {
            world.jet = null
            world.box?.let { b -> world.jet = GasJetTiles.bind(kind, 0L, b.minX, b.minY, b.minZ, b.w, b.h, b.d) }
            val it = ships.long2ObjectEntrySet().fastIterator()
            while (it.hasNext()) {
                val e = it.next()
                e.value.jet = null
                e.value.box?.let { b -> e.value.jet = GasJetTiles.bind(kind, e.longKey, b.minX, b.minY, b.minZ, b.w, b.h, b.d) }
            }
        }

        /** Binds the page table of this volume's jets; the shader reads no jet when [JetOn] is 0. */
        private fun bindJets(eff: EffectInstance, v: GasVolume, b: Box) {
            val jet = v.jet
            eff.safeGetUniform("JetOn").set(if (jet != null) 1f else 0f)
            if (jet == null) return
            cpuJetUpload.begin()
            VolumeTextures.uploadUint3D16(
                jetPageTex, GasJetLines.TABLE_X, GasJetLines.TABLE_Y, GasJetLines.TABLE_Z, GL30.GL_RED_INTEGER, jet.table,
            )
            cpuJetUpload.end()
            VolumeTextures.bind3D(JET_PAGE_UNIT, jetPageTex)
            eff.safeGetUniform("JetPageDims").set(
                ceil(b.w / GasJetLines.PAGE_SIZE.toFloat()), ceil(b.h / GasJetLines.PAGE_SIZE.toFloat()), ceil(b.d / GasJetLines.PAGE_SIZE.toFloat()),
            )
        }

        /** A ship volume stays one more build after its gas is gone, so the gas fades out; an unloaded ship does not build. */
        private fun dropIdleShips(level: ClientLevel) {
            val loaded = level.shipObjectWorld.loadedShips
            val it = ships.long2ObjectEntrySet().fastIterator()
            while (it.hasNext()) {
                val e = it.next()
                if (shipRegions.containsKey(e.longKey) || (e.value.shown.isNotEmpty() && loaded.getById(e.longKey) != null)) continue
                e.value.release()
                it.remove()
            }
        }

        fun refresh(level: ClientLevel, cam: Vec3) {
            if (isEmpty()) return
            applyDirty()
            val cx = floor(cam.x).toInt(); val cy = floor(cam.y).toInt(); val cz = floor(cam.z).toInt()
            world.refresh(level.minBuildHeight, BlockPos.asLong(cx shr 2, cy, cz shr 2), cx - SPAN / 2, cy - SPAN_Y / 2, cz - SPAN / 2)
            val ids = shipRegions.keys.iterator()
            while (ids.hasNext()) { val id = ids.nextLong(); ships.getOrPut(id) { GasVolume().also { g -> g.frame = id } } }
            for (e in ships.long2ObjectEntrySet()) {
                // draw() skips a ship the client has not loaded, so its build would be thrown away.
                if (level.shipObjectWorld.loadedShips.getById(e.longKey) == null) continue
                // With no gas left, the last box stays in place so the old gas can fade out.
                val r = shipRegions.get(e.longKey)
                val b = e.value.box
                when {
                    r != null -> e.value.refresh(level.minBuildHeight, 0L, r.minX - 1, r.minY - 1, r.minZ - 1)
                    b != null -> e.value.refresh(level.minBuildHeight, 0L, b.minX, b.minY, b.minZ)
                }
            }
        }

        fun draw(eff: EffectInstance, level: ClientLevel, cam: Vec3) {
            if (world.box == null && ships.isEmpty()) return
            eff.safeGetUniform("DarkColor").set(kind.darkR, kind.darkG, kind.darkB)
            eff.safeGetUniform("FlowPeriod").set(FLOW_PERIOD)
            eff.safeGetUniform("FlowMax").set(GasField.FLOW_MAX)
            eff.safeGetUniform("LightColor").set(kind.lightR, kind.lightG, kind.lightB)
            world.box?.let { b ->
                val ox = (b.minX - cam.x).toFloat(); val oy = (b.minY - cam.y).toFloat(); val oz = (b.minZ - cam.z).toFloat()
                localToView.translation(ox, oy, oz)
                fieldRotation.identity()
                eff.safeGetUniform("Visibility").set(1f)
                drawVolume(eff, world, b, ox, oy, oz, b.minX.toFloat(), b.minY.toFloat(), b.minZ.toFloat())
            }
            val it = ships.long2ObjectEntrySet().fastIterator()
            while (it.hasNext()) {
                val e = it.next()
                val b = e.value.box ?: continue
                val ship = level.shipObjectWorld.loadedShips.getById(e.longKey) as? ClientShip ?: continue
                // The gas of a ship fades with the Staff of Concealment cloak, as its lattice sea does.
                val visibility = 1f - ClientShipCloakingState.getProgress(ship.id).coerceIn(0f, 1f)
                if (visibility <= 0f) continue
                eff.safeGetUniform("Visibility").set(visibility)
                val t = ship.renderTransform
                val corner = t.shipToWorld.transformPosition(scratch.set(b.minX.toDouble(), b.minY.toDouble(), b.minZ.toDouble()))
                shipRotation.set(t.shipToWorldRotation)
                // A scaled ship: the proxy box grows by the scale, and the march works in ship units (FieldRotation is not unit).
                val scale = t.shipToWorldScaling.x().toFloat()
                localToView.translation((corner.x - cam.x).toFloat(), (corner.y - cam.y).toFloat(), (corner.z - cam.z).toFloat()).rotate(shipRotation).scale(scale)
                fieldRotation.scaling(1f / scale).rotate(shipRotation.conjugate())
                val local = t.worldToShip.transformPosition(scratch.set(cam.x, cam.y, cam.z))
                drawVolume(
                    eff, e.value, b, (b.minX - local.x).toFloat(), (b.minY - local.y).toFloat(), (b.minZ - local.z).toFloat(),
                    Math.floorMod(b.minX.toLong(), NOISE_PERIOD.toLong()).toFloat(), b.minY.toFloat(), Math.floorMod(b.minZ.toLong(), NOISE_PERIOD.toLong()).toFloat(),
                )
            }
        }

        /** [ox], [oy] and [oz] place the field corner from the camera, in the axes of the field. */
        @Suppress("LongParameterList") // Raw floats: no vector allocation each frame.
        private fun drawVolume(
            eff: EffectInstance, v: GasVolume, b: Box,
            ox: Float, oy: Float, oz: Float, cornerX: Float, cornerY: Float, cornerZ: Float,
        ) {
            eff.safeGetUniform("LocalToView").set(localToView)
            eff.safeGetUniform("FieldRotation").set(fieldRotation)
            eff.safeGetUniform("BoxMin").set(0f, 0f, 0f)
            eff.safeGetUniform("BoxMax").set(b.w.toFloat(), b.h.toFloat(), b.d.toFloat())
            eff.safeGetUniform("FieldOrigin").set(ox, oy, oz)
            eff.safeGetUniform("FieldCorner").set(cornerX, cornerY, cornerZ)
            eff.safeGetUniform("FieldSize").set(b.w.toFloat(), b.h.toFloat(), b.d.toFloat())
            eff.safeGetUniform("FieldBlend").set(((ticks - v.builtTick) + Minecraft.getInstance().frameTime).coerceIn(0f, 1f))
            VolumeTextures.bind3D(NOW_UNIT, v.nowTex)
            VolumeTextures.bind3D(WAS_UNIT, v.wasTex)
            VolumeTextures.bind3D(FLOW_UNIT, v.flowTex)
            VolumeTextures.bind3D(FLOW_WAS_UNIT, v.flowWasTex)
            VolumeTextures.bind3D(OFFSET_A_UNIT, v.offsetTexA)
            VolumeTextures.bind3D(OFFSET_B_UNIT, v.offsetTexB)
            VolumeTextures.bind3D(BRICK_UNIT, v.brickTex)
            bindJets(eff, v, b)
            VolumeProxy.draw(eff)
        }

        private fun shipsWithGas(): List<ClientShip> {
            val level = Minecraft.getInstance().level ?: return emptyList()
            val out = ArrayList<ClientShip>(shipRegions.size)
            val ids = shipRegions.keys.iterator()
            while (ids.hasNext()) (level.shipObjectWorld.loadedShips.getById(ids.nextLong()) as? ClientShip)?.let { out += it }
            return out
        }

        private fun shipGasAt(ships: List<ClientShip>, minBuildHeight: Int, x: Int, y: Int, z: Int): Float {
            var most = 0f
            val px = x + 0.5; val py = y + 0.5; val pz = z + 0.5
            for (ship in ships) {
                val wa = ship.worldAABB
                if (px < wa.minX() || px > wa.maxX() || py < wa.minY() || py > wa.maxY() || pz < wa.minZ() || pz > wa.maxZ()) continue
                val p = ship.transform.worldToShip.transformPosition(scratch.set(px, py, pz))
                val sx = floor(p.x).toInt(); val sy = floor(p.y).toInt(); val sz = floor(p.z).toInt()
                val cells = chunks.get(ChunkPos.asLong(sx shr 4, sz shr 4)) ?: continue
                most = maxOf(most, cells.get(GasField.index(minBuildHeight, sx, sy, sz)))
            }
            return most
        }

        /** Bounds the textures to the cells with gas, plus one clear block, so the gas fades at the edge. */
        private fun build(v: GasVolume, minBuildHeight: Int, rx0: Int, ry0: Int, rz0: Int) {
            val rx1 = rx0 + SPAN - 1; val ry1 = ry0 + SPAN_Y - 1; val rz1 = rz0 + SPAN - 1
            val now = v.spare
            now.clear()
            val overlaps = if (v === world) shipsWithGas() else emptyList()
            forEachCell(minBuildHeight, rx0, ry0, rz0, rx1, ry1, rz1) { x, y, z, a ->
                // Where a ship cell holds gas at the same point, show only the part above it, so the two never add up.
                val visible = if (overlaps.isEmpty()) a else a - shipGasAt(overlaps, minBuildHeight, x, y, z)
                if (visible > 0f) now.put(BlockPos.asLong(x, y, z), visible)
            }
            splatJets(now, v, minBuildHeight, rx0, ry0, rz0, rx1, ry1, rz1)
            val was = v.shown
            v.easing = false
            // Shown values are all above 0, so a 0 read means the cell is new.
            var kept = 0
            val fresh = now.size
            val nowEase = now.long2FloatEntrySet().fastIterator()
            while (nowEase.hasNext()) {
                val e = nowEase.next()
                val from = was.get(e.longKey)
                if (from > 0f) kept++
                val gap = e.floatValue - from
                if (gap > DENSITY_SNAP || gap < -DENSITY_SNAP) { e.setValue(from + gap * DENSITY_EASE); v.easing = true }
            }
            val fading = was.long2FloatEntrySet().fastIterator()
            while (fading.hasNext()) {
                val e = fading.next()
                if (now.containsKey(e.longKey)) continue
                val eased = e.floatValue * (1f - DENSITY_EASE)
                if (eased > DENSITY_SNAP) { now.put(e.longKey, eased); v.easing = true }
            }
            // Two builds with the same cells: the cells of now and was are the ones the last solid mask was made from.
            val keysSame = kept == fresh && now.size == was.size
            val reuseMask = keysSame && v.keysSame
            v.keysSame = keysSame
            v.shown = now
            v.spare = was
            val oldFlow = v.flowWas
            v.flowWas = v.flowNow
            v.flowNow = oldFlow.also { it.clear() }
            advanceOffsets(v)
            pack(v, minBuildHeight, rx0, ry0, rz0, now, was, reuseMask)
        }

        /** Packs [now] and [was] into the textures, in the box of their cells inside the window at ([rx0], [ry0], [rz0]). */
        @Suppress("LongParameterList", "LongMethod") // The window corner; bounds, pack and upload of one build.
        private fun pack(
            v: GasVolume, minBuildHeight: Int, rx0: Int, ry0: Int, rz0: Int, now: Long2FloatOpenHashMap, was: Long2FloatOpenHashMap, reuseMask: Boolean,
        ) {
            val rx1 = rx0 + SPAN - 1; val ry1 = ry0 + SPAN_Y - 1; val rz1 = rz0 + SPAN - 1
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
            if (GasJetLines.bounds(kind, v.frame, jetBounds)) {
                include(BlockPos.asLong(jetBounds[0], jetBounds[1], jetBounds[2]))
                include(BlockPos.asLong(jetBounds[3], jetBounds[4], jetBounds[5]))
            }
            val wasAll = was.long2FloatEntrySet().fastIterator()
            while (wasAll.hasNext()) include(wasAll.next().longKey)
            if (lx > hx) { v.box = null; v.packedNow = null; return }
            lx = maxOf(rx0, lx - 1); ly = maxOf(ry0, ly - 1); lz = maxOf(rz0, lz - 1)
            hx = minOf(rx1, hx + 1); hy = minOf(ry1, hy + 1); hz = minOf(rz1, hz + 1)
            val w = hx - lx + 1; val h = hy - ly + 1; val d = hz - lz + 1
            val old = v.box
            val sameBox = old != null && old.minX == lx && old.minY == ly && old.minZ == lz && old.w == w && old.h == h && old.d == d
            // The last now textures already hold `was` (density, and flow in RGB) texel for texel.
            val swap = sameBox && was === v.packedNow && v.wasTex >= 0 && v.flowWasTex >= 0
            val used = (w * h * d).toLong()
            MemoryUtil.memSet(MemoryUtil.memAddress0(nowData), 0, used * 2)
            if (!swap) MemoryUtil.memSet(MemoryUtil.memAddress0(wasData), 0, used * 2)
            val bw = (w + BRICK - 1) / BRICK; val bh = (h + BRICK - 1) / BRICK; val bd = (d + BRICK - 1) / BRICK
            MemoryUtil.memSet(MemoryUtil.memAddress0(brickData), 0, (bw * bh * bd).toLong())
            fun put(p: Long, current: Float, before: Float) {
                val x = BlockPos.getX(p); val y = BlockPos.getY(p); val z = BlockPos.getZ(p)
                if (x < lx || x > hx || y < ly || y > hy || z < lz || z > hz) return
                val i = (x - lx) + w * ((y - ly) + h * (z - lz))
                if (current > 0f || before > 0f) markBricks(x - lx, y - ly, z - lz, bw, bh, bd)
                nowData.put(i, packDensity(current))
                if (!swap) wasData.put(i, packDensity(before))
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
            fillFlow(v, minBuildHeight, lx, ly, lz, w, h, d)
            if (!swap) fillFlowWas(v, lx, ly, lz, w, h, d)
            cpuMask.begin()
            if (reuseMask && sameBox && v.maskValid && v.maskEpoch == solidEpoch) {
                val texels = v.solidTexels
                for (n in 0 until texels.size) flowData.put(texels.getInt(n) * 4 + 3, SOLID_ALPHA)
            } else {
                v.solidTexels.clear()
                v.maskValid = clipToSolids(now, was, lx, ly, lz, w, h, d, v.solidTexels)
                v.maskEpoch = solidEpoch
            }
            cpuMask.end()
            cpuUpload.begin()
            if (v.nowTex < 0) v.nowTex = allocateDensityTexture()
            if (v.wasTex < 0) v.wasTex = allocateDensityTexture()
            if (v.flowTex < 0) v.flowTex = allocateFlowTexture()
            if (v.flowWasTex < 0) v.flowWasTex = allocateFlowTexture()
            if (v.brickTex < 0) v.brickTex = allocateBrickTexture()
            if (swap) {
                // The shader reads only RGB of FieldFlowWas, so the solid alpha left in the old flow texture is harmless.
                val nowTex = v.nowTex; v.nowTex = v.wasTex; v.wasTex = nowTex
                val flowTex = v.flowTex; v.flowTex = v.flowWasTex; v.flowWasTex = flowTex
            }
            uploadDensity(v.nowTex, nowData, w, h, d)
            if (!swap) uploadDensity(v.wasTex, wasData, w, h, d)
            VolumeTextures.beginUpload3D(v.flowTex)
            VolumeTextures.uploadRgba8x3D(0, 0, 0, w, h, d, flowData)
            VolumeTextures.endUpload3D()
            if (!swap) {
                VolumeTextures.beginUpload3D(v.flowWasTex)
                VolumeTextures.uploadRgba8x3D(0, 0, 0, w, h, d, flowWasData)
                VolumeTextures.endUpload3D()
            }
            if (v.offsetTexA < 0) { v.offsetTexA = allocateOffsetTexture(); v.offsetsZero = false }
            if (v.offsetTexB < 0) { v.offsetTexB = allocateOffsetTexture(); v.offsetsZero = false }
            if (!(sameBox && v.offsetsZero && v.offsets.isEmpty())) {
                fillOffsetsA(v, lx, ly, lz, w, h, d)
                uploadOffsets(v.offsetTexA, w, h, d)
                fillOffsetsB()
                uploadOffsets(v.offsetTexB, w, h, d)
                v.offsetsZero = v.offsets.isEmpty()
            }
            upload(v.brickTex, brickData, bw, bh, bd)
            cpuUpload.end()
            v.packedNow = now
            if (!sameBox) v.box = Box(lx, ly, lz, w, h, d)
        }

        /**
         * Marks the full-cube solid cells beside gas in the alpha of the flow texture (255). Their density stays 0, so
         * the filter cannot carry gas through a wall; the shader zeroes the gas inside the cell.
         */
        @Suppress("LongParameterList") // Raw box bounds.
        private fun clipToSolids(
            now: Long2FloatOpenHashMap, was: Long2FloatOpenHashMap, lx: Int, ly: Int, lz: Int, w: Int, h: Int, d: Int, texels: IntArrayList,
        ): Boolean {
            val level = Minecraft.getInstance().level ?: return false
            if (level !== solidLevel) { clearSolids(); solidLevel = level }
            solidMisses = SOLID_MISS_BUDGET
            // Dropping the load counts would make old entries look current, so the caches go with them.
            if (chunkLoads.size > CHUNK_LOADS_MAX) { solidCache.clear(); cellSolids.clear(); chunkLoads.clear(); solidEpoch++ }
            if (solidCache.size > SOLID_CACHE_MAX) solidCache.clear()
            if (cellSolids.size > CELL_CACHE_MAX) cellSolids.clear()
            markSolids(level, now, null, lx, ly, lz, w, h, d, texels)
            markSolids(level, was, now, lx, ly, lz, w, h, d, texels)
            // A spent read budget leaves some cells open, so that mask must not be reused.
            return solidMisses > 0
        }

        /** Marks the solid neighbours of the cells of [map], skipping the cells that are also in [skip]. */
        @Suppress("LongParameterList") // Raw box bounds.
        private fun markSolids(
            level: ClientLevel, map: Long2FloatOpenHashMap, skip: Long2FloatOpenHashMap?, lx: Int, ly: Int, lz: Int, w: Int, h: Int, d: Int, texels: IntArrayList,
        ) {
            val cells = map.long2FloatEntrySet().fastIterator()
            while (cells.hasNext()) {
                val p = cells.next().longKey
                if (skip != null && skip.containsKey(p)) continue
                val x = BlockPos.getX(p) - lx; val y = BlockPos.getY(p) - ly; val z = BlockPos.getZ(p) - lz
                if (x < 0 || x >= w || y < 0 || y >= h || z < 0 || z >= d) continue
                var mask = solidMask(level, p, lx + x, ly + y, lz + z)
                while (mask != 0) {
                    val b = Integer.numberOfTrailingZeros(mask)
                    mask = mask and (mask - 1)
                    val nx = x + neighbourX[b]; val ny = y + neighbourY[b]; val nz = z + neighbourZ[b]
                    if (nx < 0 || nx >= w || ny < 0 || ny >= h || nz < 0 || nz >= d) continue
                    val t = nx + w * (ny + h * nz)
                    flowData.put(t * 4 + 3, SOLID_ALPHA)
                    texels.add(t)
                }
            }
        }

        /** The solid-neighbour mask of the cell [key]; a cell whose reads did not fit the budget reads open and is not cached. */
        private fun solidMask(level: ClientLevel, key: Long, x: Int, y: Int, z: Int): Int {
            val loads = loadsAround(x, z)
            val cached = cellSolids.get(key)
            if (cached != NO_ENTRY && (cached ushr 32).toInt() == loads) return (cached and MASK_BITS).toInt()
            var mask = 0
            for (b in 0 until NEIGHBOURS) {
                when (solidAt(level, x + neighbourX[b], y + neighbourY[b], z + neighbourZ[b])) {
                    1 -> mask = mask or (1 shl b)
                    -1 -> return 0
                }
            }
            cellSolids.put(key, (loads.toLong() shl 32) or mask.toLong())
            return mask
        }

        // The load counts of the chunks the 3x3 columns around (x, z) touch: 1, 2 or 4 chunks. Counts only grow, so the sum changes on any reload.
        private fun loadsAround(x: Int, z: Int): Int {
            val cx0 = (x - 1) shr 4; val cx1 = (x + 1) shr 4; val cz0 = (z - 1) shr 4; val cz1 = (z + 1) shr 4
            var sum = chunkLoads.get(ChunkPos.asLong(cx0, cz0))
            if (cx1 != cx0) sum += chunkLoads.get(ChunkPos.asLong(cx1, cz0))
            if (cz1 != cz0) sum += chunkLoads.get(ChunkPos.asLong(cx0, cz1))
            if (cx1 != cx0 && cz1 != cz0) sum += chunkLoads.get(ChunkPos.asLong(cx1, cz1))
            return sum
        }

        // 1 for a full collision cube, 0 for open (slabs, stairs and panes stay open), -1 when the read budget is spent.
        private fun solidAt(level: ClientLevel, x: Int, y: Int, z: Int): Int {
            val key = BlockPos.asLong(x, y, z)
            val loads = chunkLoads.get(ChunkPos.asLong(x shr 4, z shr 4))
            val known = solidCache.get(key)
            if (known >= 0 && known ushr 1 == loads) return known and 1
            if (solidMisses <= 0) return -1
            solidMisses--
            solidPos.set(x, y, z)
            val state = level.getBlockState(solidPos)
            // A vent has a full collision cube, but its jet is drawn through it.
            val solid = if (state.block !is GasVentBlock && state.isCollisionShapeFullBlock(level, solidPos)) 1 else 0
            solidCache.put(key, (loads shl 1) or solid)
            return solid
        }

        /** 128 is no flow, so the buffer starts at 128 everywhere. Records each written flow in [GasVolume.flowNow]. */
        @Suppress("LongParameterList") // Raw box bounds.
        private fun fillFlow(v: GasVolume, minBuildHeight: Int, lx: Int, ly: Int, lz: Int, w: Int, h: Int, d: Int) {
            MemoryUtil.memSet(MemoryUtil.memAddress0(flowData), 0x80, w.toLong() * h * d * 4)
            for (chx in (lx shr 4)..((lx + w - 1) shr 4)) for (chz in (lz shr 4)..((lz + d - 1) shr 4)) {
                val cells = flows.get(ChunkPos.asLong(chx, chz)) ?: continue
                val it = cells.int2ObjectEntrySet().fastIterator()
                while (it.hasNext()) {
                    val e = it.next()
                    val x = GasField.cellX(chx, e.intKey) - lx
                    val y = GasField.cellY(minBuildHeight, e.intKey) - ly
                    val z = GasField.cellZ(chz, e.intKey) - lz
                    if (x !in 0 until w || y !in 0 until h || z !in 0 until d) continue
                    val o = ((x + w * (y + h * z)) * 4)
                    var packed = 0
                    for (a in 0..2) {
                        val b = Math.round(e.value[a] / GasField.FLOW_MAX * 127f).coerceIn(-127, 127) + 128
                        flowData.put(o + a, b.toByte())
                        packed = packed or (b shl (8 * a))
                    }
                    v.flowNow.put(BlockPos.asLong(lx + x, ly + y, lz + z), packed)
                }
            }
        }

        /**
         * Adds the flow the shader showed since the last build (its flow-now texture, which it shows in full at FieldBlend 1)
         * to the offsets, so the GPU and CPU sums stay equal. Each phase restarts at 0 where the shader gives it no weight.
         */
        private fun advanceOffsets(v: GasVolume) {
            val level = Minecraft.getInstance().level ?: return
            val t = SpellClock.now(level)
            val first = v.phaseTick == Long.MIN_VALUE
            val restartA = !first && Math.floorDiv(t, FLOW_PERIOD_TICKS) != Math.floorDiv(v.phaseTick, FLOW_PERIOD_TICKS)
            val half = FLOW_PERIOD_TICKS / 2
            val restartB = !first && Math.floorDiv(t + half, FLOW_PERIOD_TICKS) != Math.floorDiv(v.phaseTick + half, FLOW_PERIOD_TICKS)
            v.phaseTick = t
            val shown = v.flowWas.long2IntEntrySet().fastIterator()
            while (shown.hasNext()) {
                val e = shown.next()
                val o = v.offsets.get(e.longKey)
                    ?: (offsetPool.removeLastOrNull()?.also { it.fill(0f) } ?: FloatArray(OFFSET_SIZE)).also { v.offsets.put(e.longKey, it) }
                for (a in 0..2) {
                    val f = (((e.intValue shr (8 * a)) and 0xFF) - 128) / 127f * GasField.FLOW_MAX
                    o[a] += f
                    o[a + 3] += f
                }
            }
            if (!restartA && !restartB) return
            val cells = v.offsets.long2ObjectEntrySet().fastIterator()
            while (cells.hasNext()) {
                val o = cells.next().value
                if (restartA) { o[0] = 0f; o[1] = 0f; o[2] = 0f }
                if (restartB) { o[3] = 0f; o[4] = 0f; o[5] = 0f }
                if (o.all { c -> c == 0f }) { offsetPool.add(o); cells.remove() }
            }
        }

        /** Phase A of the offsets in the box, into [offsetData]. Records the written texels for [fillOffsetsB]. */
        @Suppress("LongParameterList") // Raw box bounds.
        private fun fillOffsetsA(v: GasVolume, lx: Int, ly: Int, lz: Int, w: Int, h: Int, d: Int) {
            MemoryUtil.memSet(MemoryUtil.memAddress0(offsetData), 0, w.toLong() * h * d * 3 * 2)
            offsetTexels.clear()
            offsetCells.clear()
            val it = v.offsets.long2ObjectEntrySet().fastIterator()
            while (it.hasNext()) {
                val e = it.next()
                val x = BlockPos.getX(e.longKey) - lx; val y = BlockPos.getY(e.longKey) - ly; val z = BlockPos.getZ(e.longKey) - lz
                if (x !in 0 until w || y !in 0 until h || z !in 0 until d) continue
                val o = (x + w * (y + h * z)) * 3
                for (a in 0..2) offsetData.put(o + a, halfOf(e.value[a]))
                offsetTexels.add(o)
                offsetCells.add(e.value)
            }
        }

        /** Phase B over the texels [fillOffsetsA] wrote; all other texels are still 0 after the phase A upload. */
        private fun fillOffsetsB() {
            for (n in 0 until offsetTexels.size) {
                val o = offsetTexels.getInt(n)
                val cell = offsetCells[n]
                for (a in 0..2) offsetData.put(o + a, halfOf(cell[3 + a]))
            }
            offsetCells.clear()
        }

        /** The flow of the build before in the box of this one; cells with no flow then read 128 (still). */
        @Suppress("LongParameterList") // Raw box bounds.
        private fun fillFlowWas(v: GasVolume, lx: Int, ly: Int, lz: Int, w: Int, h: Int, d: Int) {
            MemoryUtil.memSet(MemoryUtil.memAddress0(flowWasData), 0x80, w.toLong() * h * d * 4)
            val it = v.flowWas.long2IntEntrySet().fastIterator()
            while (it.hasNext()) {
                val e = it.next()
                val x = BlockPos.getX(e.longKey) - lx; val y = BlockPos.getY(e.longKey) - ly; val z = BlockPos.getZ(e.longKey) - lz
                if (x !in 0 until w || y !in 0 until h || z !in 0 until d) continue
                val o = ((x + w * (y + h * z)) * 4)
                for (a in 0..2) flowWasData.put(o + a, ((e.intValue shr (8 * a)) and 0xFF).toByte())
            }
        }

        private inline fun forEachCell(minBuildHeight: Int, x0: Int, y0: Int, z0: Int, x1: Int, y1: Int, z1: Int, f: (Int, Int, Int, Float) -> Unit) {
            for (chx in (x0 shr 4)..(x1 shr 4)) for (chz in (z0 shr 4)..(z1 shr 4)) {
                val cells = chunks.get(ChunkPos.asLong(chx, chz)) ?: continue
                val it = cells.int2FloatEntrySet().fastIterator()
                while (it.hasNext()) {
                    val e = it.next()
                    val i = e.intKey
                    val x = GasField.cellX(chx, i); val z = GasField.cellZ(chz, i); val y = GasField.cellY(minBuildHeight, i)
                    if (x in x0..x1 && y in y0..y1 && z in z0..z1) f(x, y, z, e.floatValue)
                }
            }
        }
    }
}
