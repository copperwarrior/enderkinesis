package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import dev.architectury.networking.NetworkManager
import io.netty.buffer.Unpooled
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.Mth
import net.minecraft.world.inventory.InventoryMenu
import org.joml.FrustumIntersection
import org.joml.Matrix3f
import org.joml.Matrix4f
import org.joml.Vector3d
import org.joml.Vector3dc
import org.joml.Vector3f
import org.shipwrights.enderkinesis.vine.JOINED_END
import org.shipwrights.enderkinesis.vine.JOINED_START
import org.shipwrights.enderkinesis.vine.SHIP_END
import org.shipwrights.enderkinesis.vine.SHIP_START
import org.shipwrights.enderkinesis.vine.VINE_HALF_WIDTH
import org.shipwrights.enderkinesis.vine.VINE_KNOT_HALF
import org.shipwrights.enderkinesis.vine.clientVineCollider
import org.shipwrights.enderkinesis.vine.clientVinePushOutbox
import org.shipwrights.enderkinesis.vine.WOGGAK_VINE_FADE_TICKS
import org.shipwrights.enderkinesis.vine.WOGGAK_VINE_FORGET
import org.shipwrights.enderkinesis.vine.WOGGAK_VINE_PUSH
import org.shipwrights.enderkinesis.vine.WOGGAK_VINE_SYNC
import org.valkyrienskies.core.api.chains.ChainId
import org.valkyrienskies.core.api.chains.ClientChain
import org.valkyrienskies.core.api.chains.QueryableChainData
import org.valkyrienskies.core.api.ships.ClientShip
import org.valkyrienskies.mod.common.shipObjectWorld
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

// Render thread only; the sync receiver queues onto it.
object WoggakVineRenderer {

    private val MOSS = ResourceLocation("enderkinesis", "block/wohlon_moss")
    private val MOSS_FILE = ResourceLocation("enderkinesis", "textures/block/wohlon_moss.png")
    private val SOLID: RenderType = RenderType.entityCutout(InventoryMenu.BLOCK_ATLAS)
    private val LEAVES = Array(LEAF_VARIANTS) { ResourceLocation("enderkinesis", "block/woggak_vine_leaf_$it") }
    private val LEAF_FILES = Array(LEAF_VARIANTS) { ResourceLocation("enderkinesis", "textures/block/woggak_vine_leaf_$it.png") }

    /** [point] is in the shipyard of [ship]. */
    private class ShipHold(val ship: Long, val point: Vector3dc)

    /**
     * [key] is the server's vine id, which a chunk reload keeps; [id] is the VS chain, which a reload replaces.
     * [startTarget] and [endTarget] are the vine keys the ends are joined to, at nodes [startNode] and [endNode];
     * [startHold] and [endHold] are the ship blocks that hold the ends. [chunk] is the chunk that sent the vine.
     */
    @Suppress("LongParameterList") // One synced vine: ids, fade, flags and what holds each end.
    private class ClientVine(
        val key: Long, val id: ChainId, val chunk: Long, val fadeStart: Long, val joinedEnds: Int,
        val startTarget: Long?, val startNode: Int, val startHold: ShipHold?,
        val endTarget: Long?, val endNode: Int, val endHold: ShipHold?,
    ) {
        var frames = VineFrames()
        // Per link, then the start and end knots; sampled once per client tick, so light lags by up to one tick.
        var lights = IntArray(0)
        var lightTick = -1L
        var lightCount = 0
        /** The side axis of the first frame of the last draw; zero before the first draw. */
        val seedSide = Vector3d()

        fun keepFrameTiming(old: ClientVine) {
            frames = old.frames
            seedSide.set(old.seedSide)
        }
    }

    private val vines = ArrayList<ClientVine>()
    private val byId = HashMap<ChainId, ClientVine>()
    private val byKey = HashMap<Long, ClientVine>()
    private val byChunk = HashMap<Long, ArrayList<ClientVine>>()
    private var positions = DoubleArray(0)
    private var targetPositions = DoubleArray(0)
    private var copiedTarget: ChainId? = null
    private var copiedAt = 0.0
    private var copiedCount = 0
    // The drawn vine's light cache.
    private var lights = IntArray(0)
    private var clientTicks = 0L
    private var greyType: RenderType? = null

    private val lightPos = BlockPos.MutableBlockPos()
    private val viewProj = Matrix4f()
    private val frustum = FrustumIntersection()
    private var brokenFlags = BooleanArray(0)
    // Per node, the A and B side axes of its frame.
    private var frameAs = DoubleArray(0)
    private var frameBs = DoubleArray(0)
    // Per node, the unit bend direction in frame axes (A, B) and the miter stretch minus one.
    private var miters = DoubleArray(0)
    private var smoothPositions = DoubleArray(0)
    // Per render node, the source link it starts; per source node, its render node.
    private var segLink = IntArray(0)
    private var linkFirst = IntArray(0)
    private val leafSprites = arrayOfNulls<TextureAtlasSprite>(LEAF_VARIANTS)
    private val frameSide = Vector3d()
    private val prevTangent = Vector3d()
    private val reflectedTangent = Vector3d()
    private val tangent = Vector3d()
    private val sideA0 = Vector3d(); private val sideB0 = Vector3d()
    private val sideA1 = Vector3d(); private val sideB1 = Vector3d()
    private val knotT = Vector3d()
    private val shipPoint = Vector3d()
    private val normal = Vector3f()
    // The pose and normal matrices as column-major doubles; the vertex keeps only the xyz rows, so the pose is affine.
    private val pose = DoubleArray(12)
    private val normalPose = DoubleArray(9)
    private val matrixScratch = FloatArray(16)
    private val transformed = DoubleArray(3)
    // Per render node: the view-space centre, the A and B axes through the normal matrix, and the 4 box corner
    // offsets in view space at unit half width.
    private var viewNodes = DoubleArray(0)
    private var normalAs = DoubleArray(0)
    private var normalBs = DoubleArray(0)
    private var viewCorners = DoubleArray(0)
    private val startA = Vector3d(); private val startB = Vector3d()
    private val endA = Vector3d(); private val endB = Vector3d()
    private val point = Vector3d()
    private val leafOut = Vector3d(); private val leafCross = Vector3d()
    private val leafDir = Vector3d(); private val leafWidth = Vector3d()

    // Current draw: buffer, texture rectangle, colour alpha and box half width.
    private lateinit var out: VertexConsumer
    private var u0 = 0f; private var u1 = 1f; private var v0 = 0f; private var v1 = 1f
    private var alpha = 1f
    private var half = VINE_HALF_WIDTH
    private var light = 0
    private var camX = 0.0; private var camY = 0.0; private var camZ = 0.0
    private val clock = VineFrameClock()
    // The last draw's physics tick, so drawnLinkBox samples the chain frames exactly as that draw did.
    private var drawAt = 0.0
    private val boxA = Vector3d(); private val boxB = Vector3d()
    // Cross-section corners in order around the link, as signs on the frame's A and B axes.
    private val BOX_SA = doubleArrayOf(-1.0, 1.0, 1.0, -1.0)
    private val BOX_SB = doubleArrayOf(-1.0, -1.0, 1.0, 1.0)

    fun init() {
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, WOGGAK_VINE_SYNC) { buf, ctx ->
            val chunk = buf.readLong()
            val first = buf.readBoolean()
            val count = buf.readVarInt()
            // Each entry holds at least a vine id, a chain id, a fade start and an end-flag byte.
            require(count >= 0 && count <= buf.readableBytes() / (Long.SIZE_BYTES * 3 + 1)) { "Bad Woggak Vine count $count" }
            val received = List(count) { buf.readVine(chunk) }
            ctx.queue { receivePage(chunk, first, received) }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, WOGGAK_VINE_FORGET) { buf, ctx ->
            val chunk = buf.readLong()
            ctx.queue {
                dropChunk(chunk)
                rebuildVineList()
            }
        }
    }

    /** One page of [chunk]'s vines; the [first] page replaces the vines that the chunk sent before. */
    private fun receivePage(chunk: Long, first: Boolean, received: List<ClientVine>) {
        val old = if (first) byChunk.remove(chunk) else null
        val list = byChunk.getOrPut(chunk) { ArrayList() }
        for (vine in received) {
            val prior = byKey[vine.key]
            if (prior != null) {
                if (prior.id == vine.id) vine.keepFrameTiming(prior)
                // The vine moved here from another chunk.
                if (prior.chunk != chunk) byChunk[prior.chunk]?.remove(prior)
            }
            byKey[vine.key] = vine
            list.add(vine)
        }
        old?.forEach { if (byKey[it.key] === it) byKey.remove(it.key) }
        if (list.isEmpty()) byChunk.remove(chunk)
        rebuildVineList()
    }

    private fun dropChunk(chunk: Long) {
        byChunk.remove(chunk)?.forEach { if (byKey[it.key] === it) byKey.remove(it.key) }
    }

    private fun rebuildVineList() {
        vines.clear()
        byId.clear()
        for (list in byChunk.values) vines.addAll(list)
        for (vine in vines) byId[vine.id] = vine
    }

    private fun FriendlyByteBuf.readVine(chunk: Long): ClientVine {
        val key = readLong()
        val id = ChainId(readLong())
        val fadeStart = readLong()
        val joined = readByte().toInt()
        val startTarget = if ((joined and JOINED_START) != 0) readLong() else null
        val startNode = if (startTarget != null) readVarInt() else -1
        val startHold = if ((joined and SHIP_START) != 0) readShipHold() else null
        val endTarget = if ((joined and JOINED_END) != 0) readLong() else null
        val endNode = if (endTarget != null) readVarInt() else -1
        val endHold = if ((joined and SHIP_END) != 0) readShipHold() else null
        return ClientVine(key, id, chunk, fadeStart, joined, startTarget, startNode, startHold, endTarget, endNode, endHold)
    }

    private fun FriendlyByteBuf.readShipHold(): ShipHold = ShipHold(readLong(), Vector3d(readDouble(), readDouble(), readDouble()))

    fun clear() {
        vines.clear()
        byId.clear()
        byKey.clear()
        byChunk.clear()
        clock.clear()
        clientVineCollider.clear()
    }

    /**
     * Fills the client collider with the drawn vine shape two ticks ahead: the local player ticks on this collider
     * next tick, and vanilla draws that position one tick later still.
     */
    fun tick(level: ClientLevel) {
        clientTicks++
        if (!clientVinePushOutbox.isEmpty) {
            val buf = FriendlyByteBuf(Unpooled.buffer())
            clientVinePushOutbox.drainTo(buf)
            NetworkManager.sendToServer(WOGGAK_VINE_PUSH, buf)
        }
        clientVineCollider.clear()
        if (vines.isEmpty()) return
        val world = level.shipObjectWorld
        val chains = world.allChains
        clientVineCollider.particles = world.allParticles
        copiedTarget = null
        val now = System.nanoTime()
        captureFrames(chains, now)
        clock.advance(now)
        // The stamp is the wall time at which the sample is drawn, so the hang's lead adds nothing before then.
        val shownAt = now + COLLIDER_LEAD_NANOS
        val at = clock.at(shownAt)
        for (vine in vines) {
            val chain = chains.getById(vine.id) ?: continue
            val count = level.shapeNodes(vine, chains, at, COLLIDER_EXTRAPOLATE, drawn = false)
            if (count >= 2) clientVineCollider.add(chain, positions, count, vine.joinedEnds, shownAt)
        }
    }

    /** Keeps each vine's new chain frame at its first sight, so the clock sees true arrival times. */
    private fun captureFrames(chains: QueryableChainData<ClientChain>, now: Long) {
        var newest = -1L
        for (i in vines.indices) {
            val chain = chains.getById(vines[i].id) ?: continue
            newest = maxOf(newest, vines[i].frames.push(chain))
        }
        if (newest >= 0) clock.observe(newest, now)
    }

    /**
     * Fills [positions] with [vine]'s nodes at physics tick [at], with its joined and ship-held ends moved where they
     * are drawn; returns the node count. When [drawn], ships are at their render place.
     */
    private fun ClientLevel.shapeNodes(
        vine: ClientVine, chains: QueryableChainData<ClientChain>, at: Double, extrapolate: Double, drawn: Boolean,
    ): Int {
        val count = vine.sampleNodes(at, extrapolate)
        if (count < 2) return count
        vine.startTarget?.let { byKey[it] }?.let { chains.snapToTarget(it, vine.startNode, 0, at, extrapolate) }
        vine.endTarget?.let { byKey[it] }?.let { chains.snapToTarget(it, vine.endNode, (count - 1) * 3, at, extrapolate) }
        vine.startHold?.let { snapToShip(it, 0, drawn) }
        vine.endHold?.let { snapToShip(it, (count - 1) * 3, drawn) }
        return count
    }

    fun renderAll(
        poseStack: PoseStack, buffers: MultiBufferSource,
        cameraX: Double, cameraY: Double, cameraZ: Double, partialTicks: Float,
    ) {
        if (vines.isEmpty()) return
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val chains = level.shipObjectWorld.allChains
        val atlas = mc.getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
        val sprite = atlas.apply(MOSS)
        for (i in LEAVES.indices) leafSprites[i] = atlas.apply(LEAVES[i])
        val time = level.gameTime + partialTicks.toDouble()
        val now = System.nanoTime()
        captureFrames(chains, now)
        val at = clock.advance(now)
        drawAt = at
        val matrix = poseStack.last().pose()
        loadPose(matrix, poseStack.last().normal())
        camX = cameraX; camY = cameraY; camZ = cameraZ
        frustum.set(viewProj.set(RenderSystem.getProjectionMatrix()).mul(matrix), false)

        for (vine in vines) {
            val chain = chains.getById(vine.id) ?: continue
            val count = level.shapeNodes(vine, chains, at, DRAW_EXTRAPOLATE, drawn = true)
            if (count < 2) continue
            if (!inFrustum(count)) continue
            val hasBroken = chain.status.brokenLinks.copyBroken(count)
            val drawn = smooth(count, hasBroken)
            level.computeFrames(vine, drawn)
            computeMiters(drawn)
            prepareView(drawn)
            out = buffers.getBuffer(SOLID)
            u0 = sprite.u0; u1 = sprite.u1; v0 = sprite.v0; v1 = sprite.v1
            alpha = 1f; half = VINE_HALF_WIDTH
            val sampleLight = vine.lightTick != clientTicks || vine.lightCount != count
            if (sampleLight) {
                if (vine.lights.size < count + 1) vine.lights = IntArray(count + 1)
                vine.lightTick = clientTicks
                vine.lightCount = count
            }
            lights = vine.lights
            drawVine(level, drawn, count, hasBroken, vine.joinedEnds, sampleLight)
            drawLeaves(count, hasBroken, vine.key, grey = false)
            if (vine.fadeStart < 0) continue
            val fade = ((time - vine.fadeStart) / WOGGAK_VINE_FADE_TICKS).coerceIn(0.0, 1.0).toFloat()
            val type = mc.greyType() ?: continue
            // SOLID is vanilla's fixed cutout block sheet, drawn late in the frame. Draw it now, or the grey shell
            // writes depth first and hides the moss under it.
            (buffers as? MultiBufferSource.BufferSource)?.endBatch(SOLID)
            out = buffers.getBuffer(type)
            u0 = 0f; u1 = 1f; v0 = 0f; v1 = 1f / GREY_ROWS
            alpha = fade; half = VINE_HALF_WIDTH + GREY_INFLATE
            drawVine(level, drawn, count, hasBroken, vine.joinedEnds, sampleLight = false)
            half = VINE_HALF_WIDTH
            drawLeaves(count, hasBroken, vine.key, grey = true)
        }
    }

    /** True when vine [chainId] fades out; the server's shear pick skips it. */
    fun isFading(chainId: Long): Boolean {
        for (i in vines.indices) if (vines[i].id.value == chainId) return vines[i].fadeStart >= 0
        return false
    }

    /**
     * The 8 world corners of link [link] of vine [chainId] as the last draw placed it, [halfWidth] out from the axis
     * in the rotation-minimizing frame: 4 around the start node, then 4 around the end node. False when not here.
     */
    fun drawnLinkBox(chainId: Long, link: Int, halfWidth: Double, out: DoubleArray): Boolean {
        require(out.size >= 24) { "A link box needs 24 values" }
        val level = Minecraft.getInstance().level ?: return false
        var vine: ClientVine? = null
        for (i in vines.indices) if (vines[i].id.value == chainId) { vine = vines[i]; break }
        if (vine == null) return false
        val chains = level.shipObjectWorld.allChains
        val chain = chains.getById(vine.id) ?: return false
        val count = level.shapeNodes(vine, chains, drawAt, DRAW_EXTRAPOLATE, drawn = true)
        if (link !in 0 until count - 1) return false
        val drawn = smooth(count, chain.status.brokenLinks.copyBroken(count))
        level.computeFrames(vine, drawn)
        computeMiters(drawn)
        val drawnHalf = half
        half = halfWidth
        for (end in 0..1) {
            val node = linkFirst[link + end]
            loadFrame(node, boxA, boxB)
            for (c in 0 until 4) {
                cornerAt(node * 3, boxA, boxB, BOX_SA[c], BOX_SB[c], point)
                val o = (end * 4 + c) * 3
                out[o] = point.x; out[o + 1] = point.y; out[o + 2] = point.z
            }
        }
        half = drawnHalf
        return true
    }

    /** True when the box around the first [count] nodes in [positions] is in the view; a margin covers the vine's width. */
    private fun inFrustum(count: Int): Boolean {
        var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE; var minZ = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE; var maxZ = -Double.MAX_VALUE
        for (i in 0 until count) {
            val k = i * 3
            minX = minOf(minX, positions[k]); maxX = maxOf(maxX, positions[k])
            minY = minOf(minY, positions[k + 1]); maxY = maxOf(maxY, positions[k + 1])
            minZ = minOf(minZ, positions[k + 2]); maxZ = maxOf(maxZ, positions[k + 2])
        }
        return boxVisible(minX, minY, minZ, maxX, maxY, maxZ, CULL_MARGIN)
    }

    /** True when the render link from node offset [k] can show; [margin] covers all that it draws round its axis. */
    private fun linkVisible(k: Int, margin: Double): Boolean = boxVisible(
        minOf(positions[k], positions[k + 3]), minOf(positions[k + 1], positions[k + 4]), minOf(positions[k + 2], positions[k + 5]),
        maxOf(positions[k], positions[k + 3]), maxOf(positions[k + 1], positions[k + 4]), maxOf(positions[k + 2], positions[k + 5]),
        margin,
    )

    @Suppress("LongParameterList") // A world box and its margin.
    private fun boxVisible(
        minX: Double, minY: Double, minZ: Double, maxX: Double, maxY: Double, maxZ: Double, margin: Double,
    ): Boolean = frustum.testAab(
        (minX - camX - margin).toFloat(), (minY - camY - margin).toFloat(), (minZ - camZ - margin).toFloat(),
        (maxX - camX + margin).toFloat(), (maxY - camY + margin).toFloat(), (maxZ - camZ + margin).toFloat(),
    )

    /** Copies [broken] into [brokenFlags] for the first [count] links; returns false when no link is broken. */
    private fun Set<Int>.copyBroken(count: Int): Boolean {
        if (isEmpty()) return false
        if (brokenFlags.size < count) brokenFlags = BooleanArray(count)
        brokenFlags.fill(false, 0, count)
        for (link in this) if (link in 0 until count) brokenFlags[link] = true
        return true
    }

    /**
     * Moves the joined end at [k] onto the target node's render position. The joint holds the two nodes together
     * only softly, so without this the end shows a gap or passes through the target vine.
     */
    private fun QueryableChainData<ClientChain>.snapToTarget(target: ClientVine, node: Int, k: Int, at: Double, extrapolate: Double) {
        val count = if (target.id == copiedTarget && at == copiedAt) copiedCount else copyTarget(target, at, extrapolate)
        if (node !in 0 until count) return
        val t = node * 3
        positions[k] = targetPositions[t]; positions[k + 1] = targetPositions[t + 1]; positions[k + 2] = targetPositions[t + 2]
    }

    /** Copies [target]'s nodes at tick [at] into [targetPositions] once per sample; returns 0 when it cannot. */
    private fun QueryableChainData<ClientChain>.copyTarget(target: ClientVine, at: Double, extrapolate: Double): Int {
        if (getById(target.id) == null) return 0
        val frames = target.frames
        if (targetPositions.size < frames.nodeCount * 3) targetPositions = DoubleArray(frames.nodeCount * 3)
        val count = frames.sample(at, extrapolate, targetPositions)
        copiedTarget = target.id; copiedAt = at; copiedCount = count
        return count
    }

    /** Moves the end at [k] onto the ship block that holds it, so it does not lag; at the render place when [drawn]. */
    private fun ClientLevel.snapToShip(hold: ShipHold, k: Int, drawn: Boolean) {
        val ship = shipObjectWorld.allShips.getById(hold.ship) as? ClientShip ?: return
        (if (drawn) ship.renderTransform else ship.transform).shipToWorld.transformPosition(hold.point, shipPoint)
        positions[k] = shipPoint.x; positions[k + 1] = shipPoint.y; positions[k + 2] = shipPoint.z
    }

    private fun ClientVine.sampleNodes(at: Double, extrapolate: Double): Int {
        if (positions.size < frames.nodeCount * 3) positions = DoubleArray(frames.nodeCount * 3)
        return frames.sample(at, extrapolate, positions)
    }

    /** Draws the [count] curve nodes; light, broken links and knots go by the [sourceCount] chain nodes. */
    @Suppress("LongParameterList") // Level, both node counts, broken links, joined ends and the light flag.
    private fun drawVine(level: ClientLevel, count: Int, sourceCount: Int, hasBroken: Boolean, joinedEnds: Int, sampleLight: Boolean) {
        var along = 0.0
        loadFrame(0, sideA0, sideB0)
        if ((joinedEnds and JOINED_START) != 0) knot(level, 0, sourceCount - 1, sampleLight, sideA0, sideB0)
        var capStart = true
        for (i in 0 until count - 1) {
            loadFrame(i + 1, sideA1, sideB1)
            val k = i * 3
            val length = sqrt(distSq(k, k + 3))
            val link = segLink[i]
            if (!hasBroken || !brokenFlags[link]) {
                light = lightAt(
                    level, link, sampleLight && linkFirst[link] == i, (positions[k] + positions[k + 3]) * 0.5,
                    (positions[k + 1] + positions[k + 4]) * 0.5, (positions[k + 2] + positions[k + 5]) * 0.5,
                )
                val capEnd = i == count - 2 || (hasBroken && segLink[i + 1] != link && brokenFlags[segLink[i + 1]])
                if (linkVisible(k, CULL_MARGIN)) drawLink(i, along, length, capStart, capEnd)
                capStart = false
            } else {
                capStart = true
            }
            along += length
            sideA0.set(sideA1); sideB0.set(sideB1)
        }
        if ((joinedEnds and JOINED_END) != 0) knot(level, (count - 1) * 3, sourceCount, sampleLight, sideA0, sideB0)
    }

    private fun lightAt(level: ClientLevel, slot: Int, sample: Boolean, x: Double, y: Double, z: Double): Int {
        if (sample) {
            lightPos.set(Mth.floor(x), Mth.floor(y), Mth.floor(z))
            lights[slot] = LevelRenderer.getLightColor(level, lightPos)
        }
        return lights[slot]
    }

    /** A moss cube at node [k] where this vine joins another; same texel scale as the vine. */
    private fun knot(level: ClientLevel, k: Int, lightSlot: Int, sampleLight: Boolean, sideA: Vector3d, sideB: Vector3d) {
        sideA.cross(sideB, knotT)
        light = lightAt(level, lightSlot, sampleLight, positions[k], positions[k + 1], positions[k + 2])
        val h = VINE_KNOT_HALF + (half - VINE_HALF_WIDTH)
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
            // This order faces +n, as n = p x q; the -n face goes the other way round, so culling keeps both outer faces.
            knotCorner(k, n, p, q, sign, -1.0, -1.0, h, ua, ua)
            if (sign > 0) knotCorner(k, n, p, q, sign, 1.0, -1.0, h, ub, ua) else knotCorner(k, n, p, q, sign, -1.0, 1.0, h, ua, ub)
            knotCorner(k, n, p, q, sign, 1.0, 1.0, h, ub, ub)
            if (sign > 0) knotCorner(k, n, p, q, sign, -1.0, 1.0, h, ua, ub) else knotCorner(k, n, p, q, sign, 1.0, -1.0, h, ub, ua)
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

    /** A box from render node [i] to the next node, [along] blocks down the vine; 16 texels to the block along and across. */
    private fun drawLink(i: Int, along: Double, length: Double, capStart: Boolean, capEnd: Boolean) {
        val ua = CENTRE_PX - half * PX
        val ub = CENTRE_PX + half * PX
        val k = i * 3
        val n = k + 3
        val dotA = frameAs[k] * frameAs[n] + frameAs[k + 1] * frameAs[n + 1] + frameAs[k + 2] * frameAs[n + 2]
        val dotB = frameBs[k] * frameBs[n] + frameBs[k + 1] * frameBs[n + 1] + frameBs[k + 2] * frameBs[n + 2]
        // Box corners 0..3 are (-A,-B), (+A,-B), (+A,+B), (-A,+B); the faces go +A, +B, -A, -B.
        side(i, 1, 2, normalAs, dotA, 1.0, ua, ub, along, length)
        side(i, 2, 3, normalBs, dotB, 1.0, ua, ub, along, length)
        side(i, 3, 0, normalAs, dotA, -1.0, ua, ub, along, length)
        side(i, 0, 1, normalBs, dotB, -1.0, ua, ub, along, length)
        if (capStart) cap(k, sideA0, sideB0, -1.0, ua, ub)
        if (capEnd) cap(n, sideA1, sideB1, 1.0, ua, ub)
    }

    /**
     * One long face of link [i] from box corner [first] to [second]. Its outward axis at each node is [axes], already
     * through the normal matrix; [dot] is the dot product of the two world axes. An atlas sprite cannot wrap, so the
     * face is cut where V crosses a texture edge.
     */
    @Suppress("LongParameterList") // Link, corners, face axes, the U range and the V run of one face.
    private fun side(
        i: Int, first: Int, second: Int, axes: DoubleArray, dot: Double, sign: Double,
        ua: Double, ub: Double, along: Double, length: Double,
    ) {
        viewCorner(i, first, startA)
        viewCorner(i, second, startB)
        viewCorner(i + 1, first, endA)
        viewCorner(i + 1, second, endB)
        val vStart = along * PX
        val vEnd = (along + length) * PX
        var v = vStart
        var tile = floor(vStart / PX).toLong()
        var pieces = 0
        while (v < vEnd && pieces < MAX_LINK_PIECES) {
            val vNext = minOf(vEnd, (tile + 1) * PX)
            val t0 = (v - vStart) / (vEnd - vStart)
            val t1 = (vNext - vStart) / (vEnd - vStart)
            val base = tile * PX
            sideNormal(i, axes, dot, sign, t0)
            sideCorner(t0, startA, endA, ua, v - base)
            sideCorner(t0, startB, endB, ub, v - base)
            sideNormal(i, axes, dot, sign, t1)
            sideCorner(t1, startB, endB, ub, vNext - base)
            sideCorner(t1, startA, endA, ua, vNext - base)
            v = vNext
            tile++
            pieces++
        }
    }

    /**
     * The face normal [t] of the way along link [i]: the world axes lerped and normalized, then through the normal
     * matrix. The matrix is linear, so this lerps the transformed [axes] and scales by the world length.
     */
    private fun sideNormal(i: Int, axes: DoubleArray, dot: Double, sign: Double, t: Double) {
        val k = i * 3
        val nx = axes[k] + (axes[k + 3] - axes[k]) * t
        val ny = axes[k + 1] + (axes[k + 4] - axes[k + 1]) * t
        val nz = axes[k + 2] + (axes[k + 5] - axes[k + 2]) * t
        // For unit world axes f0 and f1, |f0 + (f1 - f0) t|^2 = (1 - t)^2 + t^2 + 2 t (1 - t) (f0 . f1).
        val r = 1.0 - t
        val s = sign / sqrt((r * r + t * t + 2.0 * t * r * dot).coerceAtLeast(EPSILON))
        normal.set((nx * s).toFloat(), (ny * s).toFloat(), (nz * s).toFloat())
    }

    /** One vertex [t] of the way between the view-space corners [from] and [to]. */
    private fun sideCorner(t: Double, from: Vector3d, to: Vector3d, uPx: Double, vPx: Double) {
        from.lerp(to, t, point)
        emitView(point.x, point.y, point.z, uPx, vPx)
    }

    /** The view-space position of box corner [c] at render node [i] and the current half width into [out]. */
    private fun viewCorner(i: Int, c: Int, out: Vector3d) {
        val k = i * 3
        val o = i * 12 + c * 3
        out.set(
            viewNodes[k] + viewCorners[o] * half,
            viewNodes[k + 1] + viewCorners[o + 1] * half,
            viewNodes[k + 2] + viewCorners[o + 2] * half,
        )
    }

    private fun setNormal(x: Double, y: Double, z: Double) {
        linear(normalPose, x, y, z, transformed, 0)
        normal.set(transformed[0].toFloat(), transformed[1].toFloat(), transformed[2].toFloat())
    }

    /** The first 9 values of column-major [m] times (x, y, z) into [out] at [o]. */
    @Suppress("LongParameterList") // A matrix, a vector and the output slot.
    private fun linear(m: DoubleArray, x: Double, y: Double, z: Double, out: DoubleArray, o: Int) {
        out[o] = m[0] * x + m[3] * y + m[6] * z
        out[o + 1] = m[1] * x + m[4] * y + m[7] * z
        out[o + 2] = m[2] * x + m[5] * y + m[8] * z
    }

    /** Copies the pose and normal matrices into [pose] and [normalPose]. */
    private fun loadPose(matrix: Matrix4f, normalMatrix: Matrix3f) {
        matrix.get(matrixScratch)
        for (col in 0 until 4) for (row in 0 until 3) pose[col * 3 + row] = matrixScratch[col * 4 + row].toDouble()
        normalMatrix.get(matrixScratch)
        for (j in 0 until 9) normalPose[j] = matrixScratch[j].toDouble()
    }

    /** Fills the per-node view-space data of the first [count] render nodes, after the frames and miters. */
    private fun prepareView(count: Int) {
        if (viewNodes.size < count * 3) {
            viewNodes = DoubleArray(count * 3)
            normalAs = DoubleArray(count * 3)
            normalBs = DoubleArray(count * 3)
            viewCorners = DoubleArray(count * 12)
        }
        for (i in 0 until count) {
            val k = i * 3
            linear(pose, positions[k] - camX, positions[k + 1] - camY, positions[k + 2] - camZ, viewNodes, k)
            viewNodes[k] += pose[9]; viewNodes[k + 1] += pose[10]; viewNodes[k + 2] += pose[11]
            linear(normalPose, frameAs[k], frameAs[k + 1], frameAs[k + 2], normalAs, k)
            linear(normalPose, frameBs[k], frameBs[k + 1], frameBs[k + 2], normalBs, k)
            for (c in 0 until 4) {
                val a = miterA(k, BOX_SA[c], BOX_SB[c])
                val b = miterB(k, BOX_SA[c], BOX_SB[c])
                linear(
                    pose, frameAs[k] * a + frameBs[k] * b, frameAs[k + 1] * a + frameBs[k + 1] * b,
                    frameAs[k + 2] * a + frameBs[k + 2] * b, viewCorners, i * 12 + c * 3,
                )
            }
        }
    }

    private fun cap(k: Int, sideA: Vector3d, sideB: Vector3d, sign: Double, ua: Double, ub: Double) {
        sideA.cross(sideB, tangent)
        setNormal(tangent.x * sign, tangent.y * sign, tangent.z * sign)
        // This order faces along A x B; the start cap faces back, so it goes the other way round for culling.
        corner(k, sideA, sideB, -1.0, -1.0, ua, ua)
        if (sign > 0) corner(k, sideA, sideB, 1.0, -1.0, ub, ua) else corner(k, sideA, sideB, -1.0, 1.0, ua, ub)
        corner(k, sideA, sideB, 1.0, 1.0, ub, ub)
        if (sign > 0) corner(k, sideA, sideB, -1.0, 1.0, ua, ub) else corner(k, sideA, sideB, 1.0, -1.0, ub, ua)
    }

    private fun corner(k: Int, sideA: Vector3d, sideB: Vector3d, sa: Double, sb: Double, uPx: Double, vPx: Double) {
        cornerAt(k, sideA, sideB, sa, sb, point)
        emitWorld(point.x, point.y, point.z, uPx, vPx)
    }

    /** The world position of corner (sa, sb) at node [k] into [out], stretched along the node's bend direction. */
    private fun cornerAt(k: Int, sideA: Vector3d, sideB: Vector3d, sa: Double, sb: Double, out: Vector3d) {
        val a = miterA(k, sa, sb) * half
        val b = miterB(k, sa, sb) * half
        out.set(
            positions[k] + sideA.x * a + sideB.x * b,
            positions[k + 1] + sideA.y * a + sideB.y * b,
            positions[k + 2] + sideA.z * a + sideB.z * b,
        )
    }

    /** Corner (sa, sb)'s offset along the A axis at node offset [k], in half widths, stretched along the bend. */
    private fun miterA(k: Int, sa: Double, sb: Double): Double =
        sa + miters[k + 2] * (sa * miters[k] + sb * miters[k + 1]) * miters[k]

    private fun miterB(k: Int, sa: Double, sb: Double): Double =
        sb + miters[k + 2] * (sa * miters[k] + sb * miters[k + 1]) * miters[k + 1]

    private fun emit(k: Int, ox: Double, oy: Double, oz: Double, uPx: Double, vPx: Double) {
        emitWorld(positions[k] + ox, positions[k + 1] + oy, positions[k + 2] + oz, uPx, vPx)
    }

    /** One vertex at world (x, y, z), with UV in texels of the current sprite rectangle. */
    private fun emitWorld(x: Double, y: Double, z: Double, uPx: Double, vPx: Double) {
        linear(pose, x - camX, y - camY, z - camZ, transformed, 0)
        emitView(transformed[0] + pose[9], transformed[1] + pose[10], transformed[2] + pose[11], uPx, vPx)
    }

    /** One vertex at view-space (x, y, z), with UV in texels of the current sprite rectangle. */
    private fun emitView(x: Double, y: Double, z: Double, uPx: Double, vPx: Double) {
        val u = u0 + (u1 - u0) * (uPx / PX).toFloat()
        val v = v0 + (v1 - v0) * (vPx / PX).toFloat()
        out.vertex(
            x.toFloat(), y.toFloat(), z.toFloat(), WHITE, WHITE, WHITE, alpha,
            u, v, OverlayTexture.NO_OVERLAY, light, normal.x, normal.y, normal.z,
        )
    }

    /**
     * Fills the node frames with rotation-minimizing frames (double reflection, Wang et al. 2008). A frame that each
     * node took from world up alone rolled the square between links and frames, so the vine twisted and pinched.
     * The pass starts at the ship-held end, if there is one, so the vine rolls with the ship.
     */
    private fun ClientLevel.computeFrames(vine: ClientVine, count: Int) {
        if (frameAs.size < count * 3) {
            frameAs = DoubleArray(count * 3)
            frameBs = DoubleArray(count * 3)
        }
        val reverse = vine.startHold == null && vine.endHold != null
        val step = if (reverse) -1 else 1
        var i = if (reverse) count - 1 else 0
        nodeTangent(i, count, tangent)
        seedSide(vine, if (reverse) vine.endHold else vine.startHold)
        storeFrame(i)
        repeat(count - 1) {
            val j = i + step
            prevTangent.set(tangent)
            nodeTangent(j, count, tangent)
            transport(i * 3, j * 3)
            storeFrame(j)
            i = j
        }
    }

    /** The first frame's side axis into [frameSide]: from the holding ship, else the last draw's seed, else world up. */
    private fun ClientLevel.seedSide(vine: ClientVine, hold: ShipHold?) {
        val ship = hold?.let { shipObjectWorld.allShips.getById(it.ship) as? ClientShip }
        if (ship == null) {
            frameSide.set(vine.seedSide)
        } else {
            val rotation = ship.renderTransform.shipToWorldRotation
            rotation.transform(frameSide.set(1.0, 0.0, 0.0))
            if (!frameSide.makeNormalTo(tangent)) rotation.transform(frameSide.set(0.0, 0.0, 1.0))
        }
        if (!frameSide.makeNormalTo(tangent)) worldSide()
        vine.seedSide.set(frameSide)
    }

    /** Carries [frameSide] from node offset [ka] to [kb], from [prevTangent] to [tangent], with the least roll. */
    private fun transport(ka: Int, kb: Int) {
        val vx = positions[kb] - positions[ka]; val vy = positions[kb + 1] - positions[ka + 1]; val vz = positions[kb + 2] - positions[ka + 2]
        val c1 = vx * vx + vy * vy + vz * vz
        reflectedTangent.set(prevTangent)
        if (c1 > EPSILON) {
            frameSide.mirrorIn(vx, vy, vz, c1)
            reflectedTangent.mirrorIn(vx, vy, vz, c1)
        }
        val wx = tangent.x - reflectedTangent.x; val wy = tangent.y - reflectedTangent.y; val wz = tangent.z - reflectedTangent.z
        val c2 = wx * wx + wy * wy + wz * wz
        if (c2 > EPSILON) frameSide.mirrorIn(wx, wy, wz, c2)
        if (!frameSide.makeNormalTo(tangent)) worldSide()
    }

    /** Reflects this vector in the plane normal to (x, y, z), whose squared length is [lengthSq]. */
    private fun Vector3d.mirrorIn(x: Double, y: Double, z: Double, lengthSq: Double) {
        val f = 2.0 * (this.x * x + this.y * y + this.z * z) / lengthSq
        sub(x * f, y * f, z * f)
    }

    /** Removes the part along unit [t] and normalizes; false when too little is left. */
    private fun Vector3d.makeNormalTo(t: Vector3d): Boolean {
        val d = dot(t)
        sub(t.x * d, t.y * d, t.z * d)
        if (lengthSquared() < SEED_EPSILON) return false
        normalize()
        return true
    }

    /** The fallback side axis: across [tangent] and world up, or world X when the tangent is vertical. */
    private fun worldSide() {
        tangent.cross(0.0, 1.0, 0.0, frameSide)
        if (frameSide.lengthSquared() < SEED_EPSILON) tangent.cross(1.0, 0.0, 0.0, frameSide)
        frameSide.normalize()
    }

    /** Stores [frameSide] as node [i]'s A axis and [tangent] x A as its B axis. */
    private fun storeFrame(i: Int) {
        val k = i * 3
        val ax = frameSide.x; val ay = frameSide.y; val az = frameSide.z
        frameAs[k] = ax; frameAs[k + 1] = ay; frameAs[k + 2] = az
        frameBs[k] = tangent.y * az - tangent.z * ay
        frameBs[k + 1] = tangent.z * ax - tangent.x * az
        frameBs[k + 2] = tangent.x * ay - tangent.y * ax
    }

    /** Node [i]'s frame; neighbouring links share it, so the boxes join without gaps. */
    private fun loadFrame(i: Int, sideA: Vector3d, sideB: Vector3d) {
        val k = i * 3
        sideA.set(frameAs[k], frameAs[k + 1], frameAs[k + 2])
        sideB.set(frameBs[k], frameBs[k + 1], frameBs[k + 2])
    }

    /**
     * Resamples the nodes in [positions] as a centripetal Catmull-Rom curve, with more pieces at sharper bends, so a
     * heaped vine bends round, not in kinks. The curve goes through each node. Fills [segLink] and [linkFirst], puts
     * the curve in [positions] and returns its node count.
     */
    private fun smooth(count: Int, hasBroken: Boolean): Int {
        if (linkFirst.size < count) linkFirst = IntArray(count)
        var total = 1
        for (i in 0 until count - 1) {
            linkFirst[i] = total - 1
            total += curvePieces(i, count, hasBroken)
        }
        linkFirst[count - 1] = total - 1
        if (smoothPositions.size < total * 3) smoothPositions = DoubleArray(total * 3)
        if (segLink.size < total) segLink = IntArray(total)
        for (i in 0 until count - 1) {
            val pieces = linkFirst[i + 1] - linkFirst[i]
            val cut = hasBroken && brokenFlags[i]
            val prev = if (i > 0 && !cut && !(hasBroken && brokenFlags[i - 1])) (i - 1) * 3 else -1
            val next = if (i < count - 2 && !cut && !(hasBroken && brokenFlags[i + 1])) (i + 2) * 3 else -1
            val k = i * 3
            val g1 = sqrt(sqrt(distSq(k, k + 3))).coerceAtLeast(MIN_KNOT_GAP)
            val g0 = if (prev >= 0) sqrt(sqrt(distSq(prev, k))).coerceAtLeast(MIN_KNOT_GAP) else g1
            val g2 = if (next >= 0) sqrt(sqrt(distSq(k + 3, next))).coerceAtLeast(MIN_KNOT_GAP) else g1
            for (j in 0 until pieces) {
                val r = linkFirst[i] + j
                segLink[r] = i
                curvePoint(k, prev, next, g0, g1, g2, j.toDouble() / pieces, r * 3)
            }
        }
        val last = (count - 1) * 3
        val end = (total - 1) * 3
        smoothPositions[end] = positions[last]; smoothPositions[end + 1] = positions[last + 1]; smoothPositions[end + 2] = positions[last + 2]
        segLink[total - 1] = count - 2
        val source = positions
        positions = smoothPositions
        smoothPositions = source
        return total
    }

    /** The curve pieces of link [i]: one per [CURVE_STEP] of the sharper bend at its two nodes. */
    private fun curvePieces(i: Int, count: Int, hasBroken: Boolean): Int {
        if (hasBroken && brokenFlags[i]) return 1
        var pieces = 1
        if (i > 0 && !(hasBroken && brokenFlags[i - 1])) pieces = nodePieces(i * 3)
        if (i < count - 2 && !(hasBroken && brokenFlags[i + 1])) pieces = maxOf(pieces, nodePieces((i + 1) * 3))
        return pieces
    }

    /**
     * ceil(bend / [CURVE_STEP]) in 1..[MAX_CURVE_PIECES] at the node at offset [k]; 1 when a link has no length.
     * MAX_CURVE_PIECES * CURVE_STEP is under pi, so cos falls with each step and a cos compare replaces acos.
     */
    private fun nodePieces(k: Int): Int {
        val l0Sq = distSq(k - 3, k)
        val l1Sq = distSq(k, k + 3)
        if (l0Sq < MIN_LINK * MIN_LINK || l1Sq < MIN_LINK * MIN_LINK) return 1
        val dot = (positions[k] - positions[k - 3]) * (positions[k + 3] - positions[k]) +
            (positions[k + 1] - positions[k - 2]) * (positions[k + 4] - positions[k + 1]) +
            (positions[k + 2] - positions[k - 1]) * (positions[k + 5] - positions[k + 2])
        val cosBend = dot / sqrt(l0Sq * l1Sq)
        for (p in 1 until MAX_CURVE_PIECES) if (cosBend >= COS_STEPS[p]) return p
        return MAX_CURVE_PIECES
    }

    /**
     * The curve point [u] of the way along the link from node offset [k] into [smoothPositions] at [out]. A missing
     * [prev] or [next] node (-1) is mirrored from the link, so the curve stays straight there. [g0], [g1] and [g2]
     * are the centripetal knot gaps of the three links.
     */
    @Suppress("LongParameterList") // The link's nodes, its knot gaps, the fraction along and the output slot.
    private fun curvePoint(k: Int, prev: Int, next: Int, g0: Double, g1: Double, g2: Double, u: Double, out: Int) {
        val t1 = g0
        val t2 = t1 + g1
        val t3 = t2 + g2
        val t = t1 + u * g1
        for (a in 0..2) {
            val p1 = positions[k + a]
            val p2 = positions[k + 3 + a]
            val p0 = if (prev >= 0) positions[prev + a] else 2 * p1 - p2
            val p3 = if (next >= 0) positions[next + a] else 2 * p2 - p1
            val a1 = ((t1 - t) * p0 + t * p1) / t1
            val a2 = ((t2 - t) * p1 + (t - t1) * p2) / g1
            val a3 = ((t3 - t) * p2 + (t - t2) * p3) / g2
            val b1 = ((t2 - t) * a1 + t * a2) / t2
            val b2 = ((t3 - t) * a2 + (t - t1) * a3) / (t3 - t1)
            smoothPositions[out + a] = ((t2 - t) * b1 + (t - t1) * b2) / g1
        }
    }

    /**
     * Per interior node, stretches the cross-section in the bend plane by 1 / cos(half bend), so a sharp bend keeps
     * the full thickness. The end nodes and straight runs stay square.
     */
    private fun computeMiters(count: Int) {
        if (miters.size < count * 3) miters = DoubleArray(count * 3)
        miters.fill(0.0, 0, count * 3)
        for (i in 1 until count - 1) {
            val k = i * 3
            val l0 = sqrt(distSq(k - 3, k))
            val l1 = sqrt(distSq(k, k + 3))
            if (l0 < MIN_LINK || l1 < MIN_LINK) continue
            val bx = (positions[k + 3] - positions[k]) / l1 - (positions[k] - positions[k - 3]) / l0
            val by = (positions[k + 4] - positions[k + 1]) / l1 - (positions[k + 1] - positions[k - 2]) / l0
            val bz = (positions[k + 5] - positions[k + 2]) / l1 - (positions[k + 2] - positions[k - 1]) / l0
            // For unit link directions, |d1 - d0|^2 = 2 - 2 cos(bend), so cos^2(half bend) = 1 - |b|^2 / 4.
            val cosHalf = sqrt((1.0 - (bx * bx + by * by + bz * bz) * 0.25).coerceAtLeast(0.0))
            // Full miter up to a 60 deg bend, none at 120 deg: a full miter at a near-fold makes a spike.
            val keep = ((cosHalf - MITER_FADE_END) / (MITER_FADE_START - MITER_FADE_END)).coerceIn(0.0, 1.0)
            val stretch = if (keep == 0.0) 1.0 else (1.0 + (1.0 / cosHalf - 1.0) * keep).coerceAtMost(MITER_MAX)
            val ca = bx * frameAs[k] + by * frameAs[k + 1] + bz * frameAs[k + 2]
            val cb = bx * frameBs[k] + by * frameBs[k + 1] + bz * frameBs[k + 2]
            val c = sqrt(ca * ca + cb * cb)
            if (c < MIN_LINK) continue
            miters[k] = ca / c; miters[k + 1] = cb / c; miters[k + 2] = stretch - 1.0
        }
    }

    /** Leaves seeded from [seed] and the link index, so each stays at one place on the rope from frame to frame. */
    private fun drawLeaves(count: Int, hasBroken: Boolean, seed: Long, grey: Boolean) {
        for (i in 0 until count - 1) {
            if (hasBroken && brokenFlags[i]) continue
            light = lights[i]
            val first = linkFirst[i]
            val pieces = linkFirst[i + 1] - first
            for (slot in 0 until LEAF_SLOTS) {
                val h = mix(seed + (i.toLong() * LEAF_SLOTS + slot + 1) * GOLDEN)
                if (unit(h, 0) >= LEAF_CHANCE) continue
                val along = (0.15 + 0.7 * unit(h, 16)) * pieces
                val piece = minOf(along.toInt(), pieces - 1)
                if (linkVisible((first + piece) * 3, LEAF_CULL_MARGIN)) leaf(first + piece, along - piece, h, grey)
            }
        }
    }

    /**
     * One two-sided leaf on link [i]. Its bottom-left texture corner sits on a face of the mitered rope; it rolls
     * with the RMF frame. The angle limits keep both edges from that corner out of the rope.
     */
    private fun leaf(i: Int, f: Double, h: Long, grey: Boolean) {
        val k = i * 3
        val length = sqrt(distSq(k, k + 3))
        if (length < MIN_LINK) return
        val h2 = mix(h)
        val h3 = mix(h2)
        tangent.set(positions[k + 3] - positions[k], positions[k + 4] - positions[k + 1], positions[k + 5] - positions[k + 2])
            .div(length)
        loadFrame(i, sideA0, sideB0)
        loadFrame(i + 1, sideA1, sideB1)
        // Spin about the rope: one of the four faces (+A, +B, -A, -B), at a place across it.
        val face = ((h ushr 32) and 3L).toInt()
        val faceSign = if (face < 2) 1.0 else -1.0
        val across = (unit(h, 34) * 2 - 1) * LEAF_SPREAD
        val onA = face % 2 == 0
        val sa = if (onA) faceSign * LEAF_SURFACE else across
        val sb = if (onA) across else faceSign * LEAF_SURFACE
        cornerAt(k, sideA0, sideB0, sa, sb, startA)
        cornerAt(k + 3, sideA1, sideB1, sa, sb, endA)
        startA.lerp(endA, f, point)
        sideA0.lerp(sideA1, f, frameSide)
        if (!frameSide.makeNormalTo(tangent)) return
        tangent.cross(frameSide, leafCross)
        val main = if (onA) frameSide else leafCross
        val other = if (onA) leafCross else frameSide
        val jitter = (unit(h2, 0) * 2 - 1) * LEAF_JITTER
        leafOut.set(main).mul(cos(jitter) * faceSign).fma(sin(jitter), other)
        // Lean up or down the rope. The leaf plane holds the rope direction; a small twist turns it about the leaf.
        val lean = (LEAF_LEAN_MIN + LEAF_LEAN_RANGE * unit(h, 48)) * (if ((h3 and 1L) == 0L) 1.0 else -1.0)
        leafDir.set(leafOut).mul(cos(lean)).fma(sin(lean), tangent)
        leafWidth.set(tangent).mul(cos(lean)).fma(-sin(lean), leafOut)
        val twist = (unit(h3, 16) * 2 - 1) * LEAF_TWIST
        leafDir.cross(leafWidth, leafCross)
        leafWidth.mul(cos(twist)).fma(sin(twist), leafCross)
        // Turn about the attach corner: the +U and -V edges sit 45 deg either side of the leaf direction.
        val turn = (unit(h2, 16) * 2 - 1) * LEAF_TURN
        val size = LEAF_MIN + (LEAF_MAX - LEAF_MIN) * unit(h2, 32)
        startB.set(leafDir).mul(cos(QUARTER_TURN + turn) * size).fma(sin(QUARTER_TURN + turn) * size, leafWidth)
        endB.set(leafDir).mul(cos(turn - QUARTER_TURN) * size).fma(sin(turn - QUARTER_TURN) * size, leafWidth)
        startB.cross(endB, leafCross).normalize()
        if (!leafRect(((h2 ushr 48) % LEAF_VARIANTS).toInt(), grey)) return
        val lift = if (grey) GREY_LEAF_LIFT else 0.0
        // Front order faces along U x V; the back face goes the other way round with the normal turned.
        setNormal(leafCross.x, leafCross.y, leafCross.z)
        leafCorner(0.0, 0.0, lift); leafCorner(1.0, 0.0, lift); leafCorner(1.0, 1.0, lift); leafCorner(0.0, 1.0, lift)
        setNormal(-leafCross.x, -leafCross.y, -leafCross.z)
        leafCorner(0.0, 0.0, -lift); leafCorner(0.0, 1.0, -lift); leafCorner(1.0, 1.0, -lift); leafCorner(1.0, 0.0, -lift)
    }

    /** Sets the UV rectangle of leaf [variant]: its atlas sprite, or its row of the grey texture; false when missing. */
    private fun leafRect(variant: Int, grey: Boolean): Boolean {
        if (grey) {
            u0 = 0f; u1 = 1f; v0 = (variant + 1f) / GREY_ROWS; v1 = (variant + 2f) / GREY_ROWS
            return true
        }
        val sprite = leafSprites[variant] ?: return false
        u0 = sprite.u0; u1 = sprite.u1; v0 = sprite.v0; v1 = sprite.v1
        return true
    }

    /** The leaf corner [cu] along the +U edge and [cv] along the -V edge from the attach corner, [lift] off the leaf. */
    private fun leafCorner(cu: Double, cv: Double, lift: Double) {
        emitWorld(
            point.x + startB.x * cu + endB.x * cv + leafCross.x * lift,
            point.y + startB.y * cu + endB.y * cv + leafCross.y * lift,
            point.z + startB.z * cu + endB.z * cv + leafCross.z * lift,
            cu * PX, (1.0 - cv) * PX,
        )
    }

    /** SplitMix64 finalizer. */
    private fun mix(x: Long): Long {
        var z = x
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    /** 16 bits of [h] from bit [shift], as a fraction in [0, 1). */
    private fun unit(h: Long, shift: Int): Double = ((h ushr shift) and 0xFFFFL).toDouble() / 65536.0

    /** The unit central-difference tangent at node [i] into [out]; +X when the neighbours meet. */
    private fun nodeTangent(i: Int, count: Int, out: Vector3d) {
        val next = minOf(i + 1, count - 1) * 3
        val prev = maxOf(i - 1, 0) * 3
        out.set(positions[next] - positions[prev], positions[next + 1] - positions[prev + 1], positions[next + 2] - positions[prev + 2])
        if (out.lengthSquared() < EPSILON) out.set(1.0, 0.0, 0.0) else out.normalize()
    }

    private fun distSq(a: Int, b: Int): Double {
        val dx = positions[b] - positions[a]; val dy = positions[b + 1] - positions[a + 1]; val dz = positions[b + 2] - positions[a + 2]
        return dx * dx + dy * dy + dz * dz
    }

    /**
     * Grey copies of the moss and leaf textures, made in code and stacked in one column (moss, then each leaf), for
     * the fade before a free vine goes. One texture keeps the fade in one buffer.
     */
    private fun Minecraft.greyType(): RenderType? {
        greyType?.let { return it }
        val image = NativeImage(GREY_SIZE, GREY_SIZE * GREY_ROWS, true)
        for (row in 0 until GREY_ROWS) {
            val file = if (row == 0) MOSS_FILE else LEAF_FILES[row - 1]
            val resource = resourceManager.getResource(file).orElse(null)
            if (resource == null) {
                image.close()
                return null
            }
            resource.open().use { NativeImage.read(it) }.use { image.copyGrey(it, row * GREY_SIZE) }
        }
        val location = textureManager.register("enderkinesis_woggak_vine_grey", DynamicTexture(image))
        return RenderType.entityTranslucent(location).also { greyType = it }
    }

    /** Writes a grey copy of [src], scaled to one row, into this image from line [top]. */
    private fun NativeImage.copyGrey(src: NativeImage, top: Int) {
        for (y in 0 until GREY_SIZE) for (x in 0 until GREY_SIZE) {
            val abgr = src.getPixelRGBA(x * src.width / GREY_SIZE, y * src.height / GREY_SIZE)
            val r = abgr and 0xFF; val g = (abgr shr 8) and 0xFF; val b = (abgr shr 16) and 0xFF
            val l = ((r * 299 + g * 587 + b * 114) / 1000) and 0xFF
            setPixelRGBA(x, top + y, (abgr and -0x1000000) or (l shl 16) or (l shl 8) or l)
        }
    }

    private const val COLLIDER_LEAD_NANOS = 100_000_000L
    /** In physics ticks; past the newest frame the drawn rope holds after one game tick, the collider after two. */
    private const val DRAW_EXTRAPOLATE = 3.0
    private const val COLLIDER_EXTRAPOLATE = 6.0
    /** Keeps the grey shell in front of the moss box, so the two do not z-fight. */
    private const val GREY_INFLATE = 0.25 / 16
    private val KNOT_SIGNS = doubleArrayOf(1.0, -1.0)
    private const val PX = 16.0
    private const val CENTRE_PX = 8.0
    private const val CULL_MARGIN = 0.5
    private const val EPSILON = 1e-8
    /** Under this squared length a side axis is too near the tangent to keep. */
    private const val SEED_EPSILON = 1e-6
    private const val WHITE = 1f
    /** Caps the stretch at a near fold, where 1 / cos(half bend) goes to infinity. */
    private const val MITER_MAX = 1.25
    /** cos(half bend) at 60 deg and 120 deg bends. */
    private const val MITER_FADE_START = 0.8660254037844386
    private const val MITER_FADE_END = 0.5
    /** The largest bend that one curve piece takes; a sharper bend gets more pieces. */
    private const val CURVE_STEP = 0.35
    private const val MAX_CURVE_PIECES = 6
    private val COS_STEPS = DoubleArray(MAX_CURVE_PIECES + 1) { cos(it * CURVE_STEP) }
    /** Covers a leaf's far corner: the mitered rope face plus a full leaf diagonal. */
    private const val LEAF_CULL_MARGIN = 0.8
    private const val MIN_KNOT_GAP = 1e-3
    private const val MIN_LINK = 1e-4
    private const val MAX_LINK_PIECES = 32
    private const val LEAF_VARIANTS = 3
    private const val LEAF_SLOTS = 2
    private const val LEAF_CHANCE = 0.6
    private const val LEAF_MIN = 4.0 / 16
    private const val LEAF_MAX = 6.0 / 16
    /** Puts the attach corner a little inside the rope face, so no gap shows. */
    private const val LEAF_SURFACE = 0.95
    private const val LEAF_SPREAD = 0.6
    // Lean + 45 deg + turn stays under 90 deg, so both edges from the attach corner go out of the rope.
    private const val LEAF_JITTER = 0.2
    private const val LEAF_LEAN_MIN = 0.15
    private const val LEAF_LEAN_RANGE = 0.25
    private const val LEAF_TWIST = 0.3
    private const val LEAF_TURN = 0.25
    private const val QUARTER_TURN = Math.PI / 4
    /** Keeps the grey leaf off the green leaf, so the two do not z-fight. */
    private const val GREY_LEAF_LIFT = 0.1 / 16
    private const val GREY_SIZE = 16
    private const val GREY_ROWS = 1 + LEAF_VARIANTS
    private const val GOLDEN = -0x61c8864680b583ebL
}
