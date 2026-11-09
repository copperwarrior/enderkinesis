package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.vertex.PoseStack
import dev.architectury.networking.NetworkManager
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import java.nio.ByteBuffer
import net.minecraft.Util
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.EffectInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.core.BlockPos
import org.joml.Matrix4f
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.shipwrights.enderkinesis.block.WohlonRitualNetwork
import org.shipwrights.enderkinesis.block.WohlonnogondoniaTreeGrower
import org.shipwrights.enderkinesis.client.volume.VolumeComposite
import org.shipwrights.enderkinesis.client.volume.VolumeEffect
import org.shipwrights.enderkinesis.client.volume.VolumeProxy
import org.shipwrights.enderkinesis.client.volume.VolumeTextures
import org.shipwrights.enderkinesis.client.volume.VolumeView
import org.shipwrights.enderkinesis.util.warmUpClasses

/**
 * A full-bodied ghost of a ritual tree, shaded like the ritual tendrils. When its shape arrives it
 * disperses in through noise over [IN_SEC], holds for [HOLD_SEC], then disperses out, from the Heart
 * first, over [OUT_SEC]. The skeleton is voxelized into a 3D texture of the signed margin to the
 * surface, one channel each for roots, wood and leaves; it is smooth, so the filtered shape is
 * smooth too. Client thread only.
 */
object WohlonTreeGhost {

    // Margin bytes: 128 is the surface, 16 for each block, inside above.
    private const val MARGIN_ZERO = 128
    private const val MARGIN_PER_BLOCK = 16f
    // The fields reach this far past the surface, so the filtered edge has a gradient to follow.
    private const val SURFACE_PAD = 2f
    // Margin bytes below this read as empty: the shader's 128 - 1.9 * 16 test.
    private const val EMPTY_BELOW = 97.6f
    private const val PAD = 3
    private const val MAX_DIM = 160
    private const val LEAF_RXZ = 7f
    private const val LEAF_RY = 4f
    // Soft-union width of the leaf blobs, in blocks: larger merges neighbouring blobs into one canopy.
    private const val LEAF_BLEND = 1.5f

    // Seconds.
    private const val IN_SEC = 6.0
    private const val HOLD_SEC = 4.0
    private const val OUT_SEC = 9.0
    private const val FADE_OUT = 3.0
    private const val STALE = 5.0
    private const val TIME_PERIOD_SEC = 4000.0
    private const val PULL_ROOM = 11f

    private const val TREE_OUT_FROM_HEART = 0.45f
    private const val TREE_OUT_SOFT = 0.25f
    // A root ghost clears behind the growing wood: the order is nearly all distance, with a narrow edge.
    private const val ROOT_OUT_FROM_HEART = 0.97f
    private const val ROOT_OUT_SOFT = 0.03f
    // Share of the gap to the reported front that the root ghost clears each second.
    private const val ROOT_FRONT_EASE = 1.5

    // The Margin and Warp uniforms in wohlon_tree_ghost.json hold these units.
    private const val MARGIN_UNIT = 4
    private const val WARP_UNIT = 5

    private class Grid(
        val minX: Int, val minY: Int, val minZ: Int, val dx: Int, val dy: Int, val dz: Int, val margin: ByteBuffer, val edgeEmpty: Boolean,
    )

    /** A [rootGrowth] ghost has no timed out-dispersal: it clears up to the root's reported front. */
    private class Ghost(val heart: BlockPos, val grid: Grid, val startSec: Double, val rootGrowth: Boolean = false) {
        var texture = -1
        var front = 0f
        var frontTarget = 0f
        var lastFrameSec = startSec
        var sound: SoundInstance? = null
        var lastSyncSec = startSec
        /** Start of the even fade, when the syncs stopped. */
        var fadeOutSec = Double.NaN
        /** Distance from the Heart to the farthest grid corner; the dispersal out goes by the share of it. */
        val reach: Float = run {
            val hx = heart.x + 0.5f - grid.minX; val hy = heart.y + 0.5f - grid.minY; val hz = heart.z + 0.5f - grid.minZ
            val fx = maxOf(hx, grid.dx - hx); val fy = maxOf(hy, grid.dy - hy); val fz = maxOf(hz, grid.dz - hz)
            Math.sqrt((fx * fx + fy * fy + fz * fz).toDouble()).toFloat()
        }
    }

    private val ghosts = Long2ObjectOpenHashMap<Ghost>()
    private val view = VolumeView()
    private val effect = VolumeEffect("wohlon_tree_ghost")
    private val localToView = Matrix4f()

    fun init() {
        // The first ritual of a session loaded these on its frame.
        warmUpClasses(Grid::class.java, Ghost::class.java, Fields::class.java)
        WohlonNoiseTexture.prepare()
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, WohlonRitualNetwork.S2C_GHOST) { buf, _ ->
            val portal = BlockPos.of(buf.readLong())
            val candle = BlockPos.of(buf.readLong())
            val stride = WohlonnogondoniaTreeGrower.GHOST_SEGMENT_FLOATS
            val segments = FloatArray(buf.readVarInt().coerceIn(0, (1 shl 20) / stride) * stride) {
                if (it % stride == stride - 1) (if (buf.readBoolean()) 1f else 0f) else buf.readFloat()
            }
            val tips = IntArray(buf.readVarInt().coerceIn(0, 1 shl 16)) { buf.readShort().toInt() }
            Util.backgroundExecutor().execute {
                val grid = voxelize(candle, segments, tips) ?: return@execute
                Minecraft.getInstance().execute { onGrid(portal, grid) }
            }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, WohlonRitualNetwork.S2C_ROOT_GHOST) { buf, _ ->
            val key = buf.readLong()
            val anchor = BlockPos.of(buf.readLong())
            val segments = FloatArray(buf.readVarInt().coerceIn(0, 1 shl 16)) { buf.readFloat() }
            Util.backgroundExecutor().execute {
                val grid = voxelize(anchor, segments, IntArray(0)) ?: return@execute
                Minecraft.getInstance().execute { onRootGrid(key, anchor, grid) }
            }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, WohlonRitualNetwork.S2C_ROOT_FRONT) { buf, _ ->
            val key = buf.readLong()
            val front = buf.readFloat()
            Minecraft.getInstance().execute { onRootFront(key, front) }
        }
    }

    fun onDisconnect() {
        for (g in ghosts.values) free(g)
        ghosts.clear()
    }

    private fun nowSec(): Double = System.nanoTime() * 1e-9

    private fun onGrid(portal: BlockPos, grid: Grid) {
        ghosts.remove(portal.asLong())?.let {
            it.sound?.let(WohlonRitualSounds::stop)
            free(it)
        }
        val ghost = Ghost(portal, grid, nowSec())
        ghost.sound = WohlonRitualSounds.playGhost(portal)
        ghosts.put(portal.asLong(), ghost)
    }

    private fun onRootGrid(key: Long, anchor: BlockPos, grid: Grid) {
        ghosts.remove(key)?.let(::free)
        ghosts.put(key, Ghost(anchor, grid, nowSec(), rootGrowth = true))
    }

    private fun onRootFront(key: Long, front: Float) {
        val g = ghosts.get(key) ?: return
        val now = nowSec()
        g.lastSyncSec = now
        if (front == WohlonRitualNetwork.ROOT_DONE) {
            if (g.fadeOutSec.isNaN()) g.fadeOutSec = now
        } else {
            g.frontTarget = front
        }
    }

    /** The per-second tree sync: a ghost whose tree stops syncing (out of range, or gone) fades out. */
    @Suppress("UNUSED_PARAMETER") // The progress is for the sync's other readers.
    fun onProgress(trunks: LongArray, progress: FloatArray) {
        val now = nowSec()
        for (trunk in trunks) ghosts.get(trunk)?.lastSyncSec = now
    }

    private fun free(g: Ghost) {
        if (g.texture != -1) GlStateManager._deleteTexture(g.texture)
        g.texture = -1
    }

    // Called with the other world-space volumes, before the fog passes.
    @JvmStatic
    fun render(poseStack: PoseStack, projection: Matrix4f, camera: Camera) {
        if (ghosts.isEmpty() || IrisCompat.isShaderPackInUse()) return
        val now = nowSec()
        val eff = effect.get() ?: return
        val main = Minecraft.getInstance().mainRenderTarget
        val cam = camera.position
        view.update(projection, poseStack.last().pose(), cam.x, cam.y, cam.z)
        view.pushViewProj(eff)
        view.pushInvViewProj(eff)
        eff.safeGetUniform("ScreenSize").set(main.width.toFloat(), main.height.toFloat())
        VolumeTextures.bind3D(WARP_UNIT, WohlonNoiseTexture.id3D())
        var drawn = 0
        val iter = ghosts.long2ObjectEntrySet().fastIterator()
        while (iter.hasNext()) {
            val g = iter.next().value
            if (now - g.lastSyncSec > STALE && g.fadeOutSec.isNaN()) {
                g.fadeOutSec = now
                g.sound?.let(WohlonRitualSounds::stop)
            }
            val fadeOut = if (g.fadeOutSec.isNaN()) 1.0 else 1.0 - (now - g.fadeOutSec) / FADE_OUT
            val age = now - g.startSec
            if (fadeOut <= 0.0 || (!g.rootGrowth && age >= IN_SEC + HOLD_SEC + OUT_SEC)) {
                free(g)
                iter.remove()
                continue
            }
            val dispersedIn = smooth((age / IN_SEC).coerceIn(0.0, 1.0).toFloat())
            val dispersedOut = if (g.rootGrowth) rootDispersal(g, now) else smooth(((age - IN_SEC - HOLD_SEC) / OUT_SEC).coerceIn(0.0, 1.0).toFloat())
            if (g.texture == -1) g.texture = upload(g.grid, g.grid.margin)
            drawGhost(eff, g, fadeOut.toFloat(), dispersedIn, dispersedOut, drawn == 0, main)
            drawn++
        }
        if (drawn > 0) VolumeProxy.end(main, eff)
        VolumeTextures.bind3D(MARGIN_UNIT, 0)
        VolumeTextures.bind3D(WARP_UNIT, 0)
    }

    private fun smooth(t: Float) = t * t * (3f - 2f * t)

    // The shader clears a point once Dispersal.y passes its key; this centres that edge on the front, at mid noise.
    private fun rootDispersal(g: Ghost, now: Double): Float {
        val dt = (now - g.lastFrameSec).coerceIn(0.0, 1.0)
        g.lastFrameSec = now
        g.front += ((g.frontTarget - g.front) * minOf(1.0, dt * ROOT_FRONT_EASE)).toFloat()
        val share = (g.front / g.reach).coerceIn(0f, 1f)
        val key = ROOT_OUT_FROM_HEART * share + (1f - ROOT_OUT_FROM_HEART) * 0.5f + ROOT_OUT_SOFT * 0.5f
        return key / (1f + ROOT_OUT_SOFT)
    }

    @Suppress("LongParameterList") // One ghost's uniforms.
    private fun drawGhost(
        eff: EffectInstance, g: Ghost, strength: Float, dispersedIn: Float, dispersedOut: Float, first: Boolean, main: RenderTarget,
    ) {
        val grid = g.grid
        val sx = (grid.minX - view.camX).toFloat()
        val sy = (grid.minY - view.camY).toFloat()
        val sz = (grid.minZ - view.camZ).toFloat()
        // Room round the grid for the fog that comes in from the air (the shader's PULL_DIST plus its curl).
        eff.safeGetUniform("BoxMin").set(-PULL_ROOM, -PULL_ROOM, -PULL_ROOM)
        eff.safeGetUniform("BoxMax").set(grid.dx + PULL_ROOM, grid.dy + PULL_ROOM, grid.dz + PULL_ROOM)
        eff.safeGetUniform("GridSize").set(grid.dx.toFloat(), grid.dy.toFloat(), grid.dz.toFloat())
        localToView.translation(sx, sy, sz)
        eff.safeGetUniform("LocalToView").set(localToView)
        eff.safeGetUniform("CamLocal").set(-sx, -sy, -sz)
        eff.safeGetUniform("Strength").set(strength)
        eff.safeGetUniform("Heart").set(
            (g.heart.x + 0.5 - grid.minX).toFloat(), (g.heart.y + 0.5 - grid.minY).toFloat(), (g.heart.z + 0.5 - grid.minZ).toFloat(),
        )
        eff.safeGetUniform("Reach").set(g.reach)
        eff.safeGetUniform("EdgeEmpty").set(if (grid.edgeEmpty) 1f else 0f)
        eff.safeGetUniform("Dispersal").set(dispersedIn, dispersedOut)
        if (g.rootGrowth) {
            eff.safeGetUniform("OutShape").set(ROOT_OUT_FROM_HEART, ROOT_OUT_SOFT)
        } else {
            eff.safeGetUniform("OutShape").set(TREE_OUT_FROM_HEART, TREE_OUT_SOFT)
        }
        val phase = (System.nanoTime() * 1e-9) % (2.0 * TIME_PERIOD_SEC)
        eff.safeGetUniform("Time").set((if (phase <= TIME_PERIOD_SEC) phase else 2.0 * TIME_PERIOD_SEC - phase).toFloat())
        // Each ghost has its own texture, so bind it and apply the program again for each one.
        VolumeTextures.bind3D(MARGIN_UNIT, g.texture)
        if (first) VolumeProxy.begin(main, VolumeComposite.Blend.PREMULTIPLIED)
        VolumeProxy.draw(eff)
    }

    private fun upload(grid: Grid, data: ByteBuffer): Int {
        val tex = GlStateManager._genTexture()
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, tex)
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL12.GL_CLAMP_TO_EDGE)
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 1)
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0)
        GlStateManager._pixelStore(GL12.GL_UNPACK_IMAGE_HEIGHT, 0)
        data.rewind()
        GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL11.GL_RGB8, grid.dx, grid.dy, grid.dz, 0, GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, data)
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0)
        return tex
    }

    /** Scratch for one voxelization; one for each call, so worker threads share nothing. */
    private class Fields(val dx: Int, val dy: Int, val dz: Int) {
        // Signed margin in blocks for roots and wood; the leaf channel uses leafSum.
        val margin = FloatArray(dx * dy * dz * 2) { -SURFACE_PAD }
        // Soft union of the leaf blobs: the sum of exp(margin / LEAF_BLEND).
        val leafSum = FloatArray(dx * dy * dz)

        fun index(x: Int, y: Int, z: Int): Int =
            if (x !in 0 until dx || y !in 0 until dy || z !in 0 until dz) -1 else (z * dy + y) * dx + x
    }

    // Worker thread: no level access, only the packet data.
    private fun voxelize(candle: BlockPos, segments: FloatArray, tips: IntArray): Grid? {
        val stride = WohlonnogondoniaTreeGrower.GHOST_SEGMENT_FLOATS
        val segCount = segments.size / stride
        if (segCount == 0) return null
        val bounds = bounds(segments, segCount, stride, tips)
        val ox = bounds[0]; val oy = bounds[1]; val oz = bounds[2]
        val f = Fields(bounds[3], bounds[4], bounds[5])
        for (i in 0 until segCount) paintSegment(f, segments, i * stride, ox, oy, oz)
        for (i in 0 until tips.size / 3) paintLeafBlob(f, tips[i * 3] - ox, tips[i * 3 + 1] - oy, tips[i * 3 + 2] - oz)
        return pack(candle, ox, oy, oz, f)
    }

    private fun bounds(segments: FloatArray, segCount: Int, stride: Int, tips: IntArray): IntArray {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        for (i in 0 until segCount) {
            val b = i * stride
            val r = maxOf(segments[b + 6], segments[b + 7])
            minX = minOf(minX, segments[b] - r, segments[b + 3] - r); maxX = maxOf(maxX, segments[b] + r, segments[b + 3] + r)
            minY = minOf(minY, segments[b + 1] - r, segments[b + 4] - r); maxY = maxOf(maxY, segments[b + 1] + r, segments[b + 4] + r)
            minZ = minOf(minZ, segments[b + 2] - r, segments[b + 5] - r); maxZ = maxOf(maxZ, segments[b + 2] + r, segments[b + 5] + r)
        }
        for (i in 0 until tips.size / 3) {
            minX = minOf(minX, tips[i * 3] - LEAF_RXZ); maxX = maxOf(maxX, tips[i * 3] + LEAF_RXZ)
            minY = minOf(minY, tips[i * 3 + 1] - LEAF_RY); maxY = maxOf(maxY, tips[i * 3 + 1] + LEAF_RY)
            minZ = minOf(minZ, tips[i * 3 + 2] - LEAF_RXZ); maxZ = maxOf(maxZ, tips[i * 3 + 2] + LEAF_RXZ)
        }
        val ox = Math.floor(minX.toDouble()).toInt() - PAD
        val oy = Math.floor(minY.toDouble()).toInt() - PAD
        val oz = Math.floor(minZ.toDouble()).toInt() - PAD
        return intArrayOf(
            ox, oy, oz,
            (Math.ceil(maxX.toDouble()).toInt() + PAD - ox).coerceIn(1, MAX_DIM),
            (Math.ceil(maxY.toDouble()).toInt() + PAD - oy).coerceIn(1, MAX_DIM),
            (Math.ceil(maxZ.toDouble()).toInt() + PAD - oz).coerceIn(1, MAX_DIM),
        )
    }

    private fun paintSegment(f: Fields, s: FloatArray, b: Int, ox: Int, oy: Int, oz: Int) {
        val ax = s[b] - ox; val ay = s[b + 1] - oy; val az = s[b + 2] - oz
        val ex = s[b + 3] - s[b]; val ey = s[b + 4] - s[b + 1]; val ez = s[b + 5] - s[b + 2]
        val r0 = s[b + 6]; val r1 = s[b + 7]
        val root = s[b + 8] > 0.5f
        val channel = if (root) 0 else 1
        val len2 = maxOf(ex * ex + ey * ey + ez * ez, 1.0e-6f)
        val reach = maxOf(r0, r1) + SURFACE_PAD
        val x0 = Math.floor((minOf(ax, ax + ex) - reach).toDouble()).toInt()
        val x1 = Math.ceil((maxOf(ax, ax + ex) + reach).toDouble()).toInt()
        val y0 = Math.floor((minOf(ay, ay + ey) - reach).toDouble()).toInt()
        val y1 = Math.ceil((maxOf(ay, ay + ey) + reach).toDouble()).toInt()
        val z0 = Math.floor((minOf(az, az + ez) - reach).toDouble()).toInt()
        val z1 = Math.ceil((maxOf(az, az + ez) + reach).toDouble()).toInt()
        for (z in z0..z1) for (y in y0..y1) for (x in x0..x1) {
            val j = f.index(x, y, z)
            if (j < 0) continue
            val px = x + 0.5f - ax; val py = y + 0.5f - ay; val pz = z + 0.5f - az
            val t = ((px * ex + py * ey + pz * ez) / len2).coerceIn(0f, 1f)
            val cx = px - ex * t; val cy = py - ey * t; val cz = pz - ez * t
            val d = Math.sqrt((cx * cx + cy * cy + cz * cz).toDouble()).toFloat()
            val m = r0 + (r1 - r0) * t - d
            if (m < -SURFACE_PAD) continue
            val mi = j * 2 + channel
            if (m > f.margin[mi]) f.margin[mi] = m
        }
    }

    private fun paintLeafBlob(f: Fields, tx: Int, ty: Int, tz: Int) {
        val rx = (LEAF_RXZ + SURFACE_PAD).toInt()
        val ry = (LEAF_RY + SURFACE_PAD).toInt()
        for (z in -rx..rx) for (y in -ry..ry) for (x in -rx..rx) {
            val j = f.index(tx + x, ty + y, tz + z)
            if (j < 0) continue
            val e = Math.sqrt((x * x / (LEAF_RXZ * LEAF_RXZ) + y * y / (LEAF_RY * LEAF_RY) + z * z / (LEAF_RXZ * LEAF_RXZ)).toDouble())
            // Margin in blocks, scaled by the short axis so the soft union blends evenly.
            val m = ((1.0 - e) * LEAF_RY).toFloat()
            if (m < -SURFACE_PAD) continue
            f.leafSum[j] += Math.exp((m / LEAF_BLEND).toDouble()).toFloat()
        }
    }

    private fun pack(candle: BlockPos, ox: Int, oy: Int, oz: Int, f: Fields): Grid {
        val n = f.dx * f.dy * f.dz
        val margin = BufferUtils.createByteBuffer(n * 3)
        for (j in 0 until n) {
            val leaf = if (f.leafSum[j] > 0f) LEAF_BLEND * Math.log(f.leafSum[j].toDouble()).toFloat() else -SURFACE_PAD
            for (k in 0 until 3) {
                val m = if (k == 2) leaf else f.margin[j * 2 + k]
                margin.put(j * 3 + k, (MARGIN_ZERO + m * MARGIN_PER_BLOCK).toInt().coerceIn(0, 255).toByte())
            }
        }
        return Grid(candle.x + ox, candle.y + oy, candle.z + oz, f.dx, f.dy, f.dz, margin, bordersEmpty(f, margin))
    }

    // Leaf unions and grids clamped at MAX_DIM can reach the border, and clamped reads repeat it outside the grid.
    private fun bordersEmpty(f: Fields, margin: ByteBuffer): Boolean {
        for (z in 0 until f.dz) {
            for (y in 0 until f.dy) {
                val face = z == 0 || y == 0 || z == f.dz - 1 || y == f.dy - 1
                val step = if (face) 1 else maxOf(f.dx - 1, 1)
                var x = 0
                while (x < f.dx) {
                    val j = ((z * f.dy + y) * f.dx + x) * 3
                    for (k in 0 until 3) if ((margin.get(j + k).toInt() and 0xFF) >= EMPTY_BELOW) return false
                    x += step
                }
            }
        }
        return true
    }
}
