package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import dev.architectury.networking.NetworkManager
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.Mth
import net.minecraft.world.inventory.InventoryMenu
import org.joml.Matrix3f
import org.joml.Matrix4f
import org.joml.Vector3d
import org.joml.Vector3f
import org.joml.Vector4f
import org.shipwrights.enderkinesis.vine.JOINED_END
import org.shipwrights.enderkinesis.vine.JOINED_START
import org.shipwrights.enderkinesis.vine.MAX_WOGGAK_VINES
import org.shipwrights.enderkinesis.vine.VINE_HALF_WIDTH
import org.shipwrights.enderkinesis.vine.clientVineCollider
import org.shipwrights.enderkinesis.vine.WOGGAK_VINE_FADE_TICKS
import org.shipwrights.enderkinesis.vine.WOGGAK_VINE_SYNC
import org.valkyrienskies.core.api.chains.ChainId
import org.valkyrienskies.core.api.chains.ClientChain
import org.valkyrienskies.core.api.chains.QueryableChainData
import org.valkyrienskies.mod.common.shipObjectWorld
import kotlin.math.sqrt

// Render thread only; the sync receiver queues onto it.
object WoggakVineRenderer {

    private val MOSS = ResourceLocation("enderkinesis", "block/wohlon_moss")
    private val MOSS_FILE = ResourceLocation("enderkinesis", "textures/block/wohlon_moss.png")
    private val SOLID: RenderType = RenderType.entityCutoutNoCull(InventoryMenu.BLOCK_ATLAS)

    /** [startTarget] and [endTarget] are the chains the ends are joined to, at nodes [startNode] and [endNode]. */
    private class ClientVine(
        val id: ChainId, val fadeStart: Long, val joinedEnds: Int,
        val startTarget: ChainId?, val startNode: Int, val endTarget: ChainId?, val endNode: Int,
    ) {
        var frameTick = -1L
        var frameArrival = 0L
        var frameInterval = DEFAULT_FRAME_NANOS
    }

    private val vines = ArrayList<ClientVine>()
    private val byId = HashMap<ChainId, ClientVine>()
    private var positions = DoubleArray(0)
    private var targetPositions = DoubleArray(0)
    private var greyType: RenderType? = null

    private val lightPos = BlockPos.MutableBlockPos()
    private val tangent = Vector3d()
    private val sideA0 = Vector3d(); private val sideB0 = Vector3d()
    private val sideA1 = Vector3d(); private val sideB1 = Vector3d()
    private val knotT = Vector3d()
    private val corner = Vector4f()
    private val normal = Vector3f()

    // Current draw: buffer, texture rectangle, colour alpha and box half width.
    private lateinit var out: VertexConsumer
    private lateinit var matrix: Matrix4f
    private lateinit var normalMatrix: Matrix3f
    private var u0 = 0f; private var u1 = 1f; private var v0 = 0f; private var v1 = 1f
    private var alpha = 1f
    private var half = VINE_HALF_WIDTH
    private var light = 0
    private var camX = 0.0; private var camY = 0.0; private var camZ = 0.0

    fun init() {
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, WOGGAK_VINE_SYNC) { buf, ctx ->
            val count = buf.readVarInt()
            require(count in 0..MAX_WOGGAK_VINES) { "Bad Woggak Vine count $count" }
            val received = List(count) { readVine(buf) }
            ctx.queue {
                vines.clear()
                vines.addAll(received)
                byId.clear()
                for (vine in received) byId[vine.id] = vine
            }
        }
    }

    private fun readVine(buf: FriendlyByteBuf): ClientVine {
        val id = ChainId(buf.readLong())
        val fadeStart = buf.readLong()
        val joined = buf.readByte().toInt()
        val startTarget = if ((joined and JOINED_START) != 0) ChainId(buf.readLong()) else null
        val startNode = if (startTarget != null) buf.readVarInt() else -1
        val endTarget = if ((joined and JOINED_END) != 0) ChainId(buf.readLong()) else null
        val endNode = if (endTarget != null) buf.readVarInt() else -1
        return ClientVine(id, fadeStart, joined, startTarget, startNode, endTarget, endNode)
    }

    fun clear() {
        vines.clear()
        byId.clear()
        clientVineCollider.clear()
    }

    /** Fills the client collider from the newest chain positions, so movement prediction matches the server. */
    fun tick(level: ClientLevel) {
        clientVineCollider.clear()
        if (vines.isEmpty()) return
        val chains = level.shipObjectWorld.allChains
        for (vine in vines) chains.getById(vine.id)?.let { clientVineCollider.add(it) }
    }

    /**
     * VS applies chain frames off the client tick, so the vanilla partial tick does not match the time since the
     * last frame. Blend from the frame's arrival over the measured gap between frames instead.
     */
    private fun frameAlpha(vine: ClientVine, physicsTick: Long, now: Long): Double {
        if (physicsTick != vine.frameTick) {
            if (vine.frameTick >= 0) {
                val gap = (now - vine.frameArrival).coerceIn(MIN_FRAME_NANOS, MAX_FRAME_NANOS)
                vine.frameInterval = (vine.frameInterval * 3 + gap) / 4
            }
            vine.frameTick = physicsTick
            vine.frameArrival = now
        }
        return ((now - vine.frameArrival).toDouble() / vine.frameInterval).coerceIn(0.0, 1.0)
    }

    fun renderAll(
        poseStack: PoseStack, buffers: MultiBufferSource,
        cameraX: Double, cameraY: Double, cameraZ: Double, partialTicks: Float,
    ) {
        if (vines.isEmpty()) return
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val chains = level.shipObjectWorld.allChains
        val sprite = mc.getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(MOSS)
        val time = level.gameTime + partialTicks.toDouble()
        val now = System.nanoTime()
        matrix = poseStack.last().pose()
        normalMatrix = poseStack.last().normal()
        camX = cameraX; camY = cameraY; camZ = cameraZ

        for (vine in vines) {
            val chain = chains.getById(vine.id) ?: continue
            val count = copyNodes(chain, frameAlpha(vine, chain.status.physicsTick, now))
            if (count < 2) continue
            vine.startTarget?.let { snapToTarget(chains, it, vine.startNode, 0, now) }
            vine.endTarget?.let { snapToTarget(chains, it, vine.endNode, (count - 1) * 3, now) }
            val broken = chain.status.brokenLinks
            out = buffers.getBuffer(SOLID)
            u0 = sprite.u0; u1 = sprite.u1; v0 = sprite.v0; v1 = sprite.v1
            alpha = 1f; half = VINE_HALF_WIDTH
            drawVine(level, count, broken, vine.joinedEnds)
            if (vine.fadeStart < 0) continue
            val fade = ((time - vine.fadeStart) / WOGGAK_VINE_FADE_TICKS).coerceIn(0.0, 1.0).toFloat()
            val type = greyType(mc) ?: continue
            out = buffers.getBuffer(type)
            u0 = 0f; u1 = 1f; v0 = 0f; v1 = 1f
            alpha = fade; half = VINE_HALF_WIDTH + GREY_INFLATE
            drawVine(level, count, broken, vine.joinedEnds)
        }
    }

    /**
     * Moves the joined end at [k] onto the target node's render position. The joint holds the two nodes together
     * only softly, so without this the end shows a gap or passes through the target vine.
     */
    private fun snapToTarget(
        chains: QueryableChainData<ClientChain>, target: ChainId, node: Int, k: Int, now: Long,
    ) {
        val chain = chains.getById(target) ?: return
        val state = byId[target]
        val partial = if (state != null) frameAlpha(state, chain.status.physicsTick, now) else 1.0
        val count = chain.copyInterpolatedNodePositions(partial, targetPositions)
        if (count > MAX_NODES || node !in 0 until count) return
        if (targetPositions.size < count * 3) {
            targetPositions = DoubleArray(count * 3)
            if (chain.copyInterpolatedNodePositions(partial, targetPositions) != count) return
        }
        val t = node * 3
        positions[k] = targetPositions[t]; positions[k + 1] = targetPositions[t + 1]; positions[k + 2] = targetPositions[t + 2]
    }

    private fun copyNodes(chain: ClientChain, partial: Double): Int {
        var count = chain.copyInterpolatedNodePositions(partial, positions)
        if (count > MAX_NODES) return 0
        // The copy writes nothing when the array is too small.
        if (positions.size < count * 3) {
            positions = DoubleArray(count * 3)
            count = chain.copyInterpolatedNodePositions(partial, positions)
            if (positions.size < count * 3) return 0
        }
        return count
    }

    private fun drawVine(level: ClientLevel, count: Int, broken: Set<Int>, joinedEnds: Int) {
        var along = 0.0
        nodeFrame(0, count, sideA0, sideB0)
        if ((joinedEnds and JOINED_START) != 0) knot(level, 0, sideA0, sideB0)
        var capStart = true
        for (i in 0 until count - 1) {
            nodeFrame(i + 1, count, sideA1, sideB1)
            val k = i * 3
            val length = sqrt(distSq(k, k + 3))
            if (broken.isEmpty() || i !in broken) {
                lightPos.set(
                    Mth.floor((positions[k] + positions[k + 3]) * 0.5),
                    Mth.floor((positions[k + 1] + positions[k + 4]) * 0.5),
                    Mth.floor((positions[k + 2] + positions[k + 5]) * 0.5),
                )
                light = LevelRenderer.getLightColor(level, lightPos)
                val capEnd = i == count - 2 || (broken.isNotEmpty() && (i + 1) in broken)
                drawLink(k, along, length, capStart, capEnd)
                capStart = false
            } else {
                capStart = true
            }
            along += length
            sideA0.set(sideA1); sideB0.set(sideB1)
        }
        if ((joinedEnds and JOINED_END) != 0) knot(level, (count - 1) * 3, sideA0, sideB0)
    }

    /** A moss cube at node [k] where this vine joins another; same texel scale as the vine. */
    private fun knot(level: ClientLevel, k: Int, sideA: Vector3d, sideB: Vector3d) {
        sideA.cross(sideB, knotT)
        lightPos.set(Mth.floor(positions[k]), Mth.floor(positions[k + 1]), Mth.floor(positions[k + 2]))
        light = LevelRenderer.getLightColor(level, lightPos)
        val h = KNOT_HALF + (half - VINE_HALF_WIDTH)
        knotFace(k, sideA, sideB, knotT, h)
        knotFace(k, sideB, knotT, sideA, h)
        knotFace(k, knotT, sideA, sideB, h)
    }

    /** The two faces of the cube with normals along +/-[n]; [p] and [q] span them. */
    private fun knotFace(k: Int, n: Vector3d, p: Vector3d, q: Vector3d, h: Double) {
        val ua = CENTRE_PX - h * PX
        val ub = CENTRE_PX + h * PX
        for (sign in KNOT_SIGNS) {
            setNormal(n.x * sign, n.y * sign, n.z * sign)
            knotCorner(k, n, p, q, sign, -1.0, -1.0, h, ua, ua)
            knotCorner(k, n, p, q, sign, 1.0, -1.0, h, ub, ua)
            knotCorner(k, n, p, q, sign, 1.0, 1.0, h, ub, ub)
            knotCorner(k, n, p, q, sign, -1.0, 1.0, h, ua, ub)
        }
    }

    @Suppress("LongParameterList") // One cube corner: three axes, three signs, the half size and the UV.
    private fun knotCorner(
        k: Int, n: Vector3d, p: Vector3d, q: Vector3d, sn: Double, sp: Double, sq: Double, h: Double,
        uPx: Double, vPx: Double,
    ) {
        emit(
            k,
            (n.x * sn + p.x * sp + q.x * sq) * h,
            (n.y * sn + p.y * sp + q.y * sq) * h,
            (n.z * sn + p.z * sp + q.z * sq) * h,
            uPx, vPx,
        )
    }

    /** A box from node [k] to the next node; the texture keeps 16 texels to the block along and across. */
    private fun drawLink(k: Int, along: Double, length: Double, capStart: Boolean, capEnd: Boolean) {
        val texLength = (length * PX).coerceAtMost(PX)
        var va = (along * PX) % PX
        if (va + texLength > PX) va = PX - texLength
        val vb = va + texLength
        val ua = CENTRE_PX - half * PX
        val ub = CENTRE_PX + half * PX
        val n = k + 3
        side(k, n, 1.0, -1.0, 1.0, 1.0, sideA0, 1.0, ua, ub, va, vb)
        side(k, n, 1.0, 1.0, -1.0, 1.0, sideB0, 1.0, ua, ub, va, vb)
        side(k, n, -1.0, 1.0, -1.0, -1.0, sideA0, -1.0, ua, ub, va, vb)
        side(k, n, -1.0, -1.0, 1.0, -1.0, sideB0, -1.0, ua, ub, va, vb)
        if (capStart) cap(k, sideA0, sideB0, -1.0, ua, ub)
        if (capEnd) cap(n, sideA1, sideB1, 1.0, ua, ub)
    }

    /** One long face between corners (sa, sb) and (sa2, sb2), in units of the half width along the A and B axes. */
    @Suppress("LongParameterList") // Corner signs and the UV rectangle of one face.
    private fun side(
        k: Int, n: Int, sa: Double, sb: Double, sa2: Double, sb2: Double,
        face: Vector3d, sign: Double, ua: Double, ub: Double, va: Double, vb: Double,
    ) {
        setNormal(face.x * sign, face.y * sign, face.z * sign)
        corner(k, sideA0, sideB0, sa, sb, ua, va)
        corner(k, sideA0, sideB0, sa2, sb2, ub, va)
        corner(n, sideA1, sideB1, sa2, sb2, ub, vb)
        corner(n, sideA1, sideB1, sa, sb, ua, vb)
    }

    private fun setNormal(x: Double, y: Double, z: Double) {
        normal.set(x.toFloat(), y.toFloat(), z.toFloat())
        normalMatrix.transform(normal)
    }

    private fun cap(k: Int, sideA: Vector3d, sideB: Vector3d, sign: Double, ua: Double, ub: Double) {
        sideA.cross(sideB, tangent)
        setNormal(tangent.x * sign, tangent.y * sign, tangent.z * sign)
        corner(k, sideA, sideB, -1.0, -1.0, ua, ua)
        corner(k, sideA, sideB, 1.0, -1.0, ub, ua)
        corner(k, sideA, sideB, 1.0, 1.0, ub, ub)
        corner(k, sideA, sideB, -1.0, 1.0, ua, ub)
    }

    private fun corner(k: Int, sideA: Vector3d, sideB: Vector3d, sa: Double, sb: Double, uPx: Double, vPx: Double) {
        emit(
            k, (sideA.x * sa + sideB.x * sb) * half, (sideA.y * sa + sideB.y * sb) * half,
            (sideA.z * sa + sideB.z * sb) * half, uPx, vPx,
        )
    }

    private fun emit(k: Int, ox: Double, oy: Double, oz: Double, uPx: Double, vPx: Double) {
        corner.set((positions[k] + ox - camX).toFloat(), (positions[k + 1] + oy - camY).toFloat(), (positions[k + 2] + oz - camZ).toFloat(), 1f)
        matrix.transform(corner)
        val u = u0 + (u1 - u0) * (uPx / PX).toFloat()
        val v = v0 + (v1 - v0) * (vPx / PX).toFloat()
        out.vertex(
            corner.x, corner.y, corner.z, WHITE, WHITE, WHITE, alpha,
            u, v, OverlayTexture.NO_OVERLAY, light, normal.x, normal.y, normal.z,
        )
    }

    /** Two unit axes across the vine at node [i]; neighbouring links share them, so the boxes join without gaps. */
    private fun nodeFrame(i: Int, count: Int, sideA: Vector3d, sideB: Vector3d) {
        val next = minOf(i + 1, count - 1) * 3
        val prev = maxOf(i - 1, 0) * 3
        tangent.set(positions[next] - positions[prev], positions[next + 1] - positions[prev + 1], positions[next + 2] - positions[prev + 2])
        if (tangent.lengthSquared() < EPSILON) tangent.set(1.0, 0.0, 0.0) else tangent.normalize()
        tangent.cross(0.0, 1.0, 0.0, sideA)
        if (sideA.lengthSquared() < EPSILON) tangent.cross(1.0, 0.0, 0.0, sideA)
        sideA.normalize()
        tangent.cross(sideA, sideB)
    }

    private fun distSq(a: Int, b: Int): Double {
        val dx = positions[b] - positions[a]; val dy = positions[b + 1] - positions[a + 1]; val dz = positions[b + 2] - positions[a + 2]
        return dx * dx + dy * dy + dz * dz
    }

    /** A grey copy of the moss texture, made in code from the moss file, for the fade before a free vine goes. */
    private fun greyType(mc: Minecraft): RenderType? {
        greyType?.let { return it }
        val resource = mc.resourceManager.getResource(MOSS_FILE).orElse(null) ?: return null
        val image = resource.open().use { NativeImage.read(it) }
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val abgr = image.getPixelRGBA(x, y)
            val r = abgr and 0xFF; val g = (abgr shr 8) and 0xFF; val b = (abgr shr 16) and 0xFF
            val l = ((r * 299 + g * 587 + b * 114) / 1000) and 0xFF
            image.setPixelRGBA(x, y, (abgr and -0x1000000) or (l shl 16) or (l shl 8) or l)
        }
        val location = mc.textureManager.register("enderkinesis_woggak_vine_grey", DynamicTexture(image))
        return RenderType.entityTranslucent(location).also { greyType = it }
    }

    private const val DEFAULT_FRAME_NANOS = 50_000_000L
    private const val MIN_FRAME_NANOS = 10_000_000L
    private const val MAX_FRAME_NANOS = 250_000_000L
    /** Keeps the grey shell in front of the moss box, so the two do not z-fight. */
    private const val GREY_INFLATE = 0.25 / 16
    private const val KNOT_HALF = 3.0 / 16
    private val KNOT_SIGNS = doubleArrayOf(1.0, -1.0)
    private const val PX = 16.0
    private const val CENTRE_PX = 8.0
    private const val MAX_NODES = 4096
    private const val EPSILON = 1e-8
    private const val WHITE = 1f
}
