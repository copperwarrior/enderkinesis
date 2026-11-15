package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import dev.architectury.networking.NetworkManager
import io.netty.buffer.Unpooled
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.SimpleParticleType
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.Mth
import net.minecraft.world.inventory.InventoryMenu
import net.minecraft.world.phys.shapes.VoxelShape
import org.joml.FrustumIntersection
import org.joml.Matrix3f
import org.joml.Matrix4f
import org.joml.Vector3d
import org.joml.Vector3dc
import org.joml.Vector3f
import org.shipwrights.enderkinesis.registry.EKParticles
import org.shipwrights.enderkinesis.vine.BLOCK_END
import org.shipwrights.enderkinesis.vine.BLOCK_START
import org.shipwrights.enderkinesis.vine.JOINED_END
import org.shipwrights.enderkinesis.vine.JOINED_START
import org.shipwrights.enderkinesis.vine.MAX_VINE_BURN_FRONTS
import org.shipwrights.enderkinesis.vine.SHIP_END
import org.shipwrights.enderkinesis.vine.SHIP_START
import org.shipwrights.enderkinesis.vine.VINE_HALF_WIDTH
import org.shipwrights.enderkinesis.vine.VINE_KNOT_HALF
import org.shipwrights.enderkinesis.vine.VINE_WIDTH
import org.shipwrights.enderkinesis.vine.validVineWidth
import org.shipwrights.enderkinesis.vine.VINE_SHAPE_NONE
import org.shipwrights.enderkinesis.vine.VINE_SHAPE_PARKED
import org.shipwrights.enderkinesis.vine.VINE_SHAPE_SEED
import org.shipwrights.enderkinesis.vine.VINE_TARGET_SPACING
import org.shipwrights.enderkinesis.vine.VineSplit
import org.shipwrights.enderkinesis.vine.clientVineCollider
import org.shipwrights.enderkinesis.vine.rayHitsSegment
import org.shipwrights.enderkinesis.vine.clientVinePushOutbox
import org.shipwrights.enderkinesis.vine.WOGGAK_VINE_FADE_TICKS
import org.shipwrights.enderkinesis.vine.WOGGAK_VINE_FORGET
import org.shipwrights.enderkinesis.vine.WOGGAK_VINE_PUSH
import org.shipwrights.enderkinesis.vine.WOGGAK_VINE_SYNC
import org.valkyrienskies.core.api.chains.ChainId
import org.valkyrienskies.core.api.chains.ClientChain
import org.valkyrienskies.core.api.chains.QueryableChainData
import org.valkyrienskies.core.api.particles.ClientVsParticle
import org.valkyrienskies.core.api.particles.QueryableParticleData
import org.valkyrienskies.core.api.ships.ClientShip
import org.valkyrienskies.mod.common.shipObjectWorld
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// Render thread only; the sync receiver queues onto it.
object WoggakVineRenderer {

    private val ROPE = ResourceLocation("enderkinesis", "block/woggak_vine_rope")
    private val KNOT = ResourceLocation("enderkinesis", "block/woggak_knot")
    private val KNOT_FILE = ResourceLocation("enderkinesis", "textures/block/woggak_knot.png")
    private val ROPE_FILE = ResourceLocation("enderkinesis", "textures/block/woggak_vine_rope.png")
    private val BURN = ResourceLocation("enderkinesis", "block/woggak_vine_burn")
    private val NO_BURNING = IntArray(0)
    private val SOLID: RenderType = RenderType.entityCutout(InventoryMenu.BLOCK_ATLAS)
    private val LEAVES = Array(LEAF_VARIANTS) { ResourceLocation("enderkinesis", "block/woggak_vine_leaf_$it") }
    private val LEAF_FILES = Array(LEAF_VARIANTS) { ResourceLocation("enderkinesis", "textures/block/woggak_vine_leaf_$it.png") }
    private val MOSS_PAD = ResourceLocation("enderkinesis", "block/woggak_vine_moss_pad")
    // atlases/blocks.json keeps these out of the block atlas: a sprite under 16 px lowers the mip level of the whole atlas.
    private val BEARD_TYPES = Array(BEARD_VARIANTS) {
        RenderType.entityCutoutNoCull(ResourceLocation("enderkinesis", "textures/block/woggak_vine_beard_$it.png"))
    }
    private val beardStashes = Array(BEARD_VARIANTS) { VertexStash(BEARD_TYPES[it]) }
    /** When set, vertices go here, not to [out]. */
    private var stash: VertexStash? = null

    /** [point] and [block] are in the shipyard of [ship] when it is set, else in the world. */
    private class Hold(val ship: Long?, val block: BlockPos, val point: Vector3dc)

    /** The face of a held end's block shape in the hold's frame; refreshed with the light, once per client tick. */
    private class EndFace {
        var valid = false
        var axis = 0
        var sign = 1.0
        /** How far the rope goes into the block; 0 when the block is too thin or narrow. */
        var depth = 0.0
        var padHalf = 0.0
        /** The shape's thickness along [axis]. */
        var thick = 0.0
        val centre = Vector3d()
        /** Hold-frame offset from the anchor point to the buried end, toward the block centre. */
        val inset = Vector3d()
        /** The face's edges in the hold frame: low u, high u, low v, high v. */
        val edge = DoubleArray(4)
        /** How far the pad runs past each edge and wraps onto the neighbouring face; 0 when none or hidden. */
        val over = DoubleArray(4)
    }

    /**
     * [key] is the server's vine id, which a chunk reload keeps; [id] is the VS chain, which a reload replaces.
     * [startTarget] and [endTarget] are the vine keys the ends are joined to, at nodes [startNode] and [endNode];
     * [startAnchor] and [endAnchor] are the world or ship blocks that hold the ends. [chunk] is the chunk that sent
     * the vine.
     */
    @Suppress("LongParameterList") // One synced vine: ids, fade, flags and what holds each end.
    private class ClientVine(
        val key: Long, val id: ChainId, val chunk: Long, val fadeStart: Long, val joinedEnds: Int,
        val startTarget: Long?, val startNode: Int, val startAnchor: Hold?,
        val endTarget: Long?, val endNode: Int, val endAnchor: Hold?,
        val spacing: Double = VINE_TARGET_SPACING,
        /** Over zero on a vine whose chain the server took out of physics; it draws frozen at this rest length. */
        val parkedRest: Double = 0.0,
        val width: Double = VINE_WIDTH,
        /** The chain links that burn now; the server sends the vine again at each burn step. */
        val burning: IntArray = NO_BURNING,
    ) {
        val halfWidth: Double get() = width * 0.5
        val parked: Boolean get() = parkedRest > 0.0
        val startHold: Hold? get() = startAnchor?.takeIf { it.ship != null }
        val endHold: Hold? get() = endAnchor?.takeIf { it.ship != null }
        val startFace = EndFace()
        val endFace = EndFace()
        val startFree: Boolean get() = (joinedEnds and FIXED_START) == 0
        val endFree: Boolean get() = (joinedEnds and FIXED_END) == 0
        val bothFixed: Boolean get() = !startFree && !endFree
        var frames = VineFrames()
        // Per link, then the start and end knots; sampled once per client tick, so light lags by up to one tick.
        var lights = IntArray(0)
        var lightTick = -1L
        var lightCount = 0
        /** True once the extras of this vine have fallen off as particles. */
        var shed = false
        /** The side axis of the first frame of the last draw; zero before the first draw. */
        val seedSide = Vector3d()

        fun keepFrameTiming(old: ClientVine) {
            frames = old.frames
            seedSide.set(old.seedSide)
            shed = old.shed
        }
    }

    /**
     * A replaced vine, drawn in place until every vine key in [pieces] shows: VS makes the new chains on the physics
     * thread, so they reach the client some ticks after our sync. [rest] and [broken] are of its last chain state.
     */
    private class Retiring(val vine: ClientVine, var pieces: LongArray, val rest: Double, val broken: Set<Int>) {
        var ticks = 0
    }

    private val vines = ArrayList<ClientVine>()
    private val retiring = ArrayList<Retiring>()
    /** The keys of [retiring]'s pieces, which stay hidden until their old vine goes. */
    private val heldBack = LongOpenHashSet()
    private val byId = HashMap<ChainId, ClientVine>()
    private val byKey = HashMap<Long, ClientVine>()
    private val byChunk = HashMap<Long, ArrayList<ClientVine>>()
    /** Stand-in vines of the thrown tips in flight, by flight id. */
    private val flightVines = Long2ObjectOpenHashMap<ClientVine>()
    private var positions = DoubleArray(0)
    private var targetPositions = DoubleArray(0)
    private var copiedTarget: ChainId? = null
    private var copiedAt = 0.0
    private var copiedCount = 0
    private var copiedDrawn = false
    // The pinned nodes of one vine, sorted by node, and the world move of each.
    private val pinNodes = IntArray(MAX_PINS)
    private val pinMoves = DoubleArray(MAX_PINS * 3)
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
    // Per render node, the rope width factor of a tight span.
    private var widthScale = DoubleArray(0)
    // Render nodes added inside the anchor block at the start and the end (0 or 1), and the start inset depth.
    private var insetStart = 0
    private var insetEnd = 0
    private var startInset = 0.0
    private val insetOffset = Vector3d()
    private val padPos = BlockPos.MutableBlockPos()
    // Scratch for a beard strand: the top-edge direction, the hang direction and the swing axis.
    private val ropeDir = Vector3d()
    private val hangDir = Vector3d()
    private val sideDir = Vector3d()
    private var padSprite: TextureAtlasSprite? = null
    private var fireSprite: TextureAtlasSprite? = null
    // The burning links of the vine that drawRope draws.
    private var burning = NO_BURNING
    private var knotSprite: TextureAtlasSprite? = null
    private var greyPass = false
    // Game time in seconds for the sway.
    private var seconds = 0.0
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
    private val padNormal = Vector3d()

    // Current draw: buffer, texture rectangle, colour alpha and box half width.
    private lateinit var out: VertexConsumer
    private var u0 = 0f; private var u1 = 1f; private var v0 = 0f; private var v1 = 1f
    private var alpha = 1f
    private var half = VINE_HALF_WIDTH
    // The drawn vine's own half width, without the grey shell's inflate.
    private var ropeHalf = VINE_HALF_WIDTH
    private var light = 0
    private var camX = 0.0; private var camY = 0.0; private var camZ = 0.0
    private val clock = VineFrameClock()
    // The last draw's physics tick, so drawnLinkBox samples the chain frames exactly as that draw did.
    private var drawAt = 0.0
    // Game time in ticks of this draw, for the sway of sleeping vines.
    private var swayTicks = 0.0
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
            val splitCount = buf.readVarInt()
            require(splitCount >= 0 && splitCount <= buf.readableBytes() / (Long.SIZE_BYTES + 1)) { "Bad Woggak Vine split count $splitCount" }
            val splits = List(splitCount) { VineSplit(buf.readLong(), buf.readLongArray()) }
            ctx.queue { receivePage(chunk, first, received, splits) }
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
    private fun receivePage(chunk: Long, first: Boolean, received: List<ClientVine>, splits: List<VineSplit>) {
        val chains = Minecraft.getInstance().level?.shipObjectWorld?.allChains
        for (split in splits) {
            val prior = byKey.remove(split.old) ?: continue
            byChunk[prior.chunk]?.remove(prior)
            retire(prior, split.pieces, chains)
        }
        val old = if (first) byChunk.remove(chunk) else null
        val list = byChunk.getOrPut(chunk) { ArrayList() }
        for (vine in received) {
            val prior = byKey[vine.key]
            if (prior != null) {
                // A parked vine draws at once from the frozen shape it came with, so its prior needs no hand-over.
                if (!vine.parked) {
                    if (prior.id == vine.id) {
                        vine.keepFrameTiming(prior)
                    } else {
                        // A rebuild on reel or pay-out keeps the vine id but makes a new chain.
                        retire(prior, longArrayOf(vine.key), chains)
                    }
                }
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

    private fun retire(prior: ClientVine, pieces: LongArray, chains: QueryableChainData<ClientChain>?) {
        // A piece still held back never showed, so its own pieces take its place behind the older vine.
        for (i in retiring.indices) {
            val r = retiring[i]
            val at = r.pieces.indexOf(prior.key)
            if (at < 0) continue
            r.pieces = (r.pieces.filterIndexed { k, _ -> k != at } + pieces.asList()).distinct().toLongArray()
            rebuildHeldBack()
            return
        }
        if (prior.frames.nodeCount < 2) return
        if (retiring.size >= MAX_RETIRING) retiring.removeAt(0)
        val status = chains?.getById(prior.id)?.status
        retiring.add(Retiring(prior, pieces, status?.currentRestLength ?: prior.parkedRest, status?.brokenLinks?.toSet() ?: emptySet()))
        rebuildHeldBack()
    }

    private fun rebuildHeldBack() {
        heldBack.clear()
        for (r in retiring) for (key in r.pieces) heldBack.add(key)
    }

    /** True when vine [key] is synced, its VS chain is here, and it has a frame to draw. */
    private fun isShown(key: Long, chains: QueryableChainData<ClientChain>): Boolean {
        val vine = byKey[key] ?: return false
        return vine.frames.nodeCount >= 2 && (vine.parked || chains.getById(vine.id) != null)
    }

    /** Ends each replaced vine whose pieces all show, so the pieces take over in this frame. */
    private fun settleRetiring(chains: QueryableChainData<ClientChain>) {
        var changed = false
        var i = 0
        while (i < retiring.size) {
            if (retiring[i].pieces.all { isShown(it, chains) }) {
                retiring.removeAt(i)
                changed = true
            } else {
                i++
            }
        }
        if (changed) rebuildHeldBack()
    }

    /** Rule 2 cap: a piece that never reaches this client (an untracked chunk) does not hold the old vine forever. */
    private fun expireRetiring() {
        var changed = false
        var i = 0
        while (i < retiring.size) {
            if (++retiring[i].ticks > MAX_RETIRE_TICKS) {
                retiring.removeAt(i)
                changed = true
            } else {
                i++
            }
        }
        if (changed) rebuildHeldBack()
    }

    private fun dropChunk(chunk: Long) {
        byChunk.remove(chunk)?.forEach { if (byKey[it.key] === it) byKey.remove(it.key) }
        if (retiring.removeIf { it.vine.chunk == chunk }) rebuildHeldBack()
    }

    private fun rebuildVineList() {
        vines.clear()
        byId.clear()
        for (list in byChunk.values) vines.addAll(list)
        for (vine in vines) if (!vine.parked) byId[vine.id] = vine
    }

    private fun FriendlyByteBuf.readVine(chunk: Long): ClientVine {
        val key = readLong()
        val id = ChainId(readLong())
        val fadeStart = readLong()
        val joined = readByte().toInt()
        val startTarget = if ((joined and JOINED_START) != 0) readLong() else null
        val startNode = if (startTarget != null) readVarInt() else -1
        val startAnchor = readHold(joined, SHIP_START, BLOCK_START)
        val endTarget = if ((joined and JOINED_END) != 0) readLong() else null
        val endNode = if (endTarget != null) readVarInt() else -1
        val endAnchor = readHold(joined, SHIP_END, BLOCK_END)
        val spacing = readDouble()
        require(spacing.isFinite() && spacing > 0) { "Bad Woggak Vine spacing $spacing" }
        val width = readDouble()
        require(validVineWidth(width)) { "Bad Woggak Vine width $width" }
        val burnCount = readVarInt()
        require(burnCount in 0..MAX_VINE_BURN_FRONTS) { "Bad Woggak Vine burn count $burnCount" }
        val burning = if (burnCount == 0) NO_BURNING else IntArray(burnCount) { readVarInt() }
        return when (val shape = readByte().toInt()) {
            VINE_SHAPE_NONE ->
                ClientVine(
                    key, id, chunk, fadeStart, joined, startTarget, startNode, startAnchor, endTarget, endNode, endAnchor, spacing,
                    width = width, burning = burning,
                )
            VINE_SHAPE_SEED -> ClientVine(
                key, id, chunk, fadeStart, joined, startTarget, startNode, startAnchor, endTarget, endNode, endAnchor, spacing,
                width = width, burning = burning,
            ).also { readFrozenNodes(it.frames) }
            VINE_SHAPE_PARKED -> {
                val rest = readDouble()
                require(rest.isFinite() && rest > 0) { "Bad parked Woggak Vine rest length $rest" }
                ClientVine(
                    key, id, chunk, fadeStart, joined, startTarget, startNode, startAnchor, endTarget, endNode, endAnchor, spacing, rest, width,
                    burning,
                ).also { readFrozenNodes(it.frames) }
            }
            else -> throw IllegalArgumentException("Bad Woggak Vine shape $shape")
        }
    }

    /** Reads the first node as three doubles and each other node as three float offsets from it into one frozen frame. */
    private fun FriendlyByteBuf.readFrozenNodes(frames: VineFrames) {
        val nodes = readVarInt()
        require(nodes >= 2 && nodes - 1 <= (readableBytes() - Double.SIZE_BYTES * 3) / (Float.SIZE_BYTES * 3)) {
            "Bad Woggak Vine node count $nodes"
        }
        val shape = DoubleArray(nodes * 3)
        shape[0] = readDouble(); shape[1] = readDouble(); shape[2] = readDouble()
        for (i in 3 until nodes * 3) shape[i] = shape[i % 3] + readFloat()
        require(shape.all { it.isFinite() }) { "Bad Woggak Vine nodes" }
        frames.freeze(shape, nodes)
    }

    private fun FriendlyByteBuf.readHold(flags: Int, shipBit: Int, blockBit: Int): Hold? {
        if ((flags and (shipBit or blockBit)) == 0) return null
        val ship = if ((flags and shipBit) != 0) readLong() else null
        val block = BlockPos.of(readLong())
        return Hold(ship, block, Vector3d(readDouble(), readDouble(), readDouble()))
    }

    fun clear() {
        vines.clear()
        byId.clear()
        byKey.clear()
        byChunk.clear()
        retiring.clear()
        heldBack.clear()
        clock.clear()
        clientVineCollider.clear()
        flightVines.clear()
    }

    /** Forgets the stand-in vine of a flight that [VineThrowClient] dropped. */
    fun forgetFlight(id: Long) {
        flightVines.remove(id)
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
        expireRetiring()
        if (vines.isEmpty()) return
        val world = level.shipObjectWorld
        val chains = world.allChains
        clientVineCollider.particles = world.allParticles
        copiedTarget = null
        val now = System.nanoTime()
        captureFrames(chains, world.allParticles, now)
        clock.advance(now)
        // The stamp is the wall time at which the sample is drawn, so the hang's lead adds nothing before then.
        val shownAt = now + COLLIDER_LEAD_NANOS
        val at = clock.at(shownAt)
        VineBurnClient.beginTick()
        for (vine in vines) {
            val chain = chains.getById(vine.id) ?: continue
            val count = level.shapeNodes(vine, chains, at, clock.horizon, drawn = false)
            if (count >= 2) clientVineCollider.add(chain, positions, count, vine.joinedEnds, shownAt, vine.halfWidth)
            if (count >= 3) level.maybeDrip(vine, chain, count)
            if (count >= 2 && vine.burning.isNotEmpty()) VineBurnClient.effects(level, positions, count, vine.burning)
        }
    }

    /** Now and then a drop falls from the lowest node of a span that hangs between two held ends. */
    private fun ClientLevel.maybeDrip(vine: ClientVine, chain: ClientChain, count: Int) {
        if (!vine.bothFixed || random.nextInt(DRIP_TICKS) != 0 || chain.status.brokenLinks.isNotEmpty()) return
        var low = 1
        for (i in 2 until count - 1) if (positions[i * 3 + 1] < positions[low * 3 + 1]) low = i
        val k = low * 3
        if (positions[k + 1] >= minOf(positions[1], positions[(count - 1) * 3 + 1])) return
        val camera = Minecraft.getInstance().gameRenderer.mainCamera.position
        val dx = positions[k] - camera.x; val dy = positions[k + 1] - camera.y; val dz = positions[k + 2] - camera.z
        if (dx * dx + dy * dy + dz * dz > DRIP_DISTANCE * DRIP_DISTANCE) return
        addParticle(EKParticles.woggakVineDrip(), positions[k], positions[k + 1] - vine.halfWidth - DRIP_GAP, positions[k + 2], 0.0, 0.0, 0.0)
    }

    /** Keeps each vine's new chain frame at its first sight, so the clock sees true arrival times. */
    private fun captureFrames(
        chains: QueryableChainData<ClientChain>, particles: QueryableParticleData<ClientVsParticle>, now: Long,
    ) {
        var newest = -1L
        for (i in vines.indices) {
            val chain = chains.getById(vines[i].id) ?: continue
            newest = maxOf(newest, vines[i].frames.push(chain, particles))
        }
        for (i in retiring.indices) {
            val vine = retiring[i].vine
            val chain = chains.getById(vine.id) ?: continue
            newest = maxOf(newest, vine.frames.push(chain, particles))
        }
        if (newest >= 0) clock.observe(newest, now)
    }

    /**
     * Fills [positions] with [vine]'s nodes at physics tick [at], with its joined ends and ship-held ends moved
     * where they are drawn; returns the node count. When [drawn], ships are at their render place.
     */
    private fun ClientLevel.shapeNodes(
        vine: ClientVine, chains: QueryableChainData<ClientChain>, at: Double, extrapolate: Double, drawn: Boolean,
    ): Int {
        val count = vine.sampleNodes(at, extrapolate)
        if (count < 2) return count
        vine.startTarget?.let { byKey[it] }?.let { snapToTarget(chains, it, vine.startNode, 0, at, extrapolate, drawn) }
        vine.endTarget?.let { byKey[it] }?.let { snapToTarget(chains, it, vine.endNode, (count - 1) * 3, at, extrapolate, drawn) }
        pinToShips(vine, positions, count, drawn)
        return count
    }

    fun renderAll(
        poseStack: PoseStack, buffers: MultiBufferSource,
        cameraX: Double, cameraY: Double, cameraZ: Double, partialTicks: Float,
    ) {
        if (vines.isEmpty() && retiring.isEmpty() && VineThrowClient.flights.isEmpty()) return
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val chains = level.shipObjectWorld.allChains
        val atlas = mc.getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
        val rope = atlas.apply(ROPE)
        padSprite = atlas.apply(MOSS_PAD)
        knotSprite = atlas.apply(KNOT)
        fireSprite = atlas.apply(BURN)
        for (i in LEAVES.indices) leafSprites[i] = atlas.apply(LEAVES[i])
        val time = level.gameTime + partialTicks.toDouble()
        seconds = time / TICKS_PER_SECOND
        swayTicks = time
        for (s in beardStashes) s.clear()
        val now = System.nanoTime()
        captureFrames(chains, level.shipObjectWorld.allParticles, now)
        settleRetiring(chains)
        val at = clock.advance(now)
        drawAt = at
        val matrix = poseStack.last().pose()
        loadPose(matrix, poseStack.last().normal())
        camX = cameraX; camY = cameraY; camZ = cameraZ
        frustum.set(viewProj.set(RenderSystem.getProjectionMatrix()).mul(matrix), false)

        for (vine in vines) {
            if (heldBack.contains(vine.key) || VineThrowClient.hides(vine.id.value)) continue
            val status = chains.getById(vine.id)?.status
            // A new vine draws its seed path until VS sends its chain.
            if (status == null && !vine.frames.frozen) continue
            val count = level.shapeNodes(vine, chains, at, clock.horizon, drawn = true)
            if (count < 2) continue
            VineThrowClient.blendInto(vine.id.value, positions, count)
            if (!inFrustum(count)) continue
            level.drawOne(mc, buffers, vine, status?.brokenLinks ?: emptySet(), status?.currentRestLength ?: vine.parkedRest, count, rope, time)
        }
        for (i in retiring.indices) {
            val r = retiring[i]
            // VS can drop the old chain first; its kept frames still draw it.
            val status = chains.getById(r.vine.id)?.status
            val count = level.shapeNodes(r.vine, chains, at, clock.horizon, drawn = true)
            if (count < 2 || !inFrustum(count)) continue
            level.drawOne(mc, buffers, r.vine, status?.brokenLinks ?: r.broken, status?.currentRestLength ?: r.rest, count, rope, time)
        }
        level.drawFlights(buffers, rope, partialTicks)
        for (s in beardStashes) s.flush(buffers)
    }

    /** Draws [vine], whose [count] chain nodes are in [positions]: rope, leaves, decor and the grey fade. */
    @Suppress("LongParameterList") // Minecraft, buffers, the vine, its broken links and rest length, the node count, rope sprite and time.
    private fun ClientLevel.drawOne(
        mc: Minecraft, buffers: MultiBufferSource, vine: ClientVine, broken: Set<Int>, rest: Double, count: Int,
        rope: TextureAtlasSprite, time: Double,
    ) {
        val hasBroken = broken.copyBroken(count)
        val sampleLight = takeLights(vine, count)
        val tight = if (hasBroken) 0.0 else tightness(vine, rest, count)
        val drawn = drawRope(buffers, vine, count, rope, hasBroken, tight, sampleLight)
        if (vine.fadeStart < 0) {
            drawPads(vine, count)
            drawBeard(vine, count, hasBroken, shed = false)
            return
        }
        if (!vine.shed) {
            vine.shed = true
            shedPads(vine)
            drawBeard(vine, count, hasBroken, shed = true)
        }
        val fade = ((time - vine.fadeStart) / WOGGAK_VINE_FADE_TICKS).coerceIn(0.0, 1.0).toFloat()
        val type = mc.greyType() ?: return
        // SOLID is vanilla's fixed cutout block sheet, drawn late in the frame. Draw it now, or the grey shell
        // writes depth first and hides the rope under it.
        (buffers as? MultiBufferSource.BufferSource)?.endBatch(SOLID)
        out = buffers.getBuffer(type)
        u0 = 0f; u1 = 1f; v0 = 0f; v1 = 1f / GREY_ROWS
        alpha = fade; half = vine.halfWidth + GREY_INFLATE
        greyPass = true
        drawVine(this, drawn, count, hasBroken, vine.joinedEnds, sampleLight = false)
        greyPass = false
        half = vine.halfWidth
        drawLeaves(vine, drawn, count, hasBroken, grey = true)
    }

    /** Points [lights] at [vine]'s light cache; true when this draw samples it again. */
    private fun ClientLevel.takeLights(vine: ClientVine, count: Int): Boolean {
        val due = if (vine.parked) clientTicks - vine.lightTick >= PARKED_LIGHT_TICKS else vine.lightTick != clientTicks
        val sampleLight = due || vine.lightCount != count
        if (sampleLight) {
            if (vine.lights.size < count + 1) vine.lights = IntArray(count + 1)
            vine.lightTick = clientTicks
            vine.lightCount = count
            refreshFace(vine.startAnchor, vine.startFace, vine.width)
            refreshFace(vine.endAnchor, vine.endFace, vine.width)
        }
        lights = vine.lights
        return sampleLight
    }

    /** Draws the rope and leaves of [vine] from its [count] nodes in [positions]; returns the drawn render node count. */
    @Suppress("LongParameterList") // Buffers, the vine, its node count, rope sprite, and the broken, tight and light flags.
    private fun ClientLevel.drawRope(
        buffers: MultiBufferSource, vine: ClientVine, count: Int, rope: TextureAtlasSprite, hasBroken: Boolean, tight: Double,
        sampleLight: Boolean,
    ): Int {
        val drawn = insetEnds(vine, smooth(count, hasBroken), count)
        computeFrames(vine, drawn)
        computeMiters(drawn)
        computeWidths(drawn, tight)
        prepareView(drawn)
        out = buffers.getBuffer(SOLID)
        u0 = rope.u0; u1 = rope.u1; v0 = rope.v0; v1 = rope.v1
        alpha = 1f; half = vine.halfWidth; ropeHalf = vine.halfWidth
        burning = vine.burning
        drawVine(this, drawn, count, hasBroken, vine.joinedEnds, sampleLight)
        burning = NO_BURNING
        drawLeaves(vine, drawn, count, hasBroken, grey = false)
        return drawn
    }

    /** The rope of each thrown tip in flight, from the thrower's hand along the flight path to the tip. */
    private fun ClientLevel.drawFlights(buffers: MultiBufferSource, rope: TextureAtlasSprite, partialTicks: Float) {
        val flights = VineThrowClient.flights
        for (i in flights.indices) {
            val flight = flights[i]
            val count = VineThrowClient.sampleRope(this, flight, partialTicks)
            if (count < 2) continue
            if (positions.size < count * 3) positions = DoubleArray(count * 3)
            System.arraycopy(VineThrowClient.rope, 0, positions, 0, count * 3)
            if (!inFrustum(count)) continue
            val vine = flightVines.get(flight.id) ?: flightVine(flight.id).also { flightVines.put(flight.id, it) }
            drawRope(buffers, vine, count, rope, hasBroken = false, tight = 0.0, sampleLight = takeLights(vine, count))
        }
    }

    /** A stand-in vine with two free ends, which keeps a flight's frame seed and light cache between draws. */
    private fun flightVine(key: Long) = ClientVine(key, ChainId(0L), 0L, -1L, 0, null, 0, null, null, 0, null)

    /** True once the vine of [chain] is synced and its VS chain is here, so a landed flight can stop drawing. */
    fun isDrawable(level: ClientLevel, chain: Long): Boolean {
        val id = ChainId(chain)
        return byId.containsKey(id) && level.shipObjectWorld.allChains.getById(id) != null
    }

    /** True once [chain]'s vine has a frame at [tick] or later and the frame clock draws it. */
    fun showsTick(chain: Long, tick: Long): Boolean {
        val frames = byId[ChainId(chain)]?.frames ?: return false
        return frames.newestTick >= tick && clock.at(System.nanoTime()) >= tick
    }

    /** 0 on a slack span, up to 1 as the straight end-to-end distance reaches the chain's rest length. */
    private fun tightness(vine: ClientVine, rest: Double, count: Int): Double {
        if (!vine.bothFixed) return 0.0
        if (rest < MIN_LINK) return 0.0
        val x = ((sqrt(distSq(0, (count - 1) * 3)) / rest - TAUT_SLACK) / (1.0 - TAUT_SLACK)).coerceIn(0.0, 1.0)
        return x * x * (3.0 - 2.0 * x)
    }

    /** Fills [widthScale]: a tight span gets thinner towards its middle, on a sine from anchor to anchor. */
    private fun computeWidths(count: Int, tight: Double) {
        if (widthScale.size < count) widthScale = DoubleArray(count)
        widthScale.fill(1.0, 0, count)
        if (tight <= 0.0) return
        val last = count - 1 - insetEnd
        var total = 0.0
        for (i in insetStart until last) total += sqrt(distSq(i * 3, i * 3 + 3))
        if (total < MIN_LINK) return
        var along = 0.0
        for (i in insetStart + 1..last) {
            along += sqrt(distSq(i * 3 - 3, i * 3))
            widthScale[i] = 1.0 - TAUT_THIN * tight * sin(PI * (along / total).coerceIn(0.0, 1.0))
        }
    }

    /**
     * Adds a render node [EndFace.depth] into the block at each held end, so the rope joins the block; returns the
     * new render node count. [sourceCount] is the chain node count.
     */
    private fun ClientLevel.insetEnds(vine: ClientVine, count: Int, sourceCount: Int): Int {
        var n = count
        insetStart = 0; insetEnd = 0; startInset = 0.0
        val start = vine.startAnchor
        if (start != null && vine.startFace.depth > 0.0 && worldInset(start, vine.startFace, insetOffset)) {
            growRender(n + 1)
            System.arraycopy(positions, 0, positions, 3, n * 3)
            System.arraycopy(segLink, 0, segLink, 1, n)
            for (j in 0 until sourceCount) linkFirst[j]++
            setInset(0, 3)
            startInset = vine.startFace.depth
            insetStart = 1
            n++
        }
        val end = vine.endAnchor
        if (end != null && vine.endFace.depth > 0.0 && worldInset(end, vine.endFace, insetOffset)) {
            growRender(n + 1)
            setInset(n * 3, (n - 1) * 3)
            segLink[n] = segLink[n - 1]
            insetEnd = 1
            n++
        }
        return n
    }

    private fun growRender(count: Int) {
        if (positions.size < count * 3) positions = positions.copyOf(count * 3)
        if (segLink.size < count) segLink = segLink.copyOf(count)
    }

    /** Puts node offset [k] at node offset [from] plus the buried offset in [insetOffset]. */
    private fun setInset(k: Int, from: Int) {
        positions[k] = positions[from] + insetOffset.x
        positions[k + 1] = positions[from + 1] + insetOffset.y
        positions[k + 2] = positions[from + 2] + insetOffset.z
    }

    /** The world offset from the anchor point to the buried end of [face] into [out]; false when the face or the holding ship is gone. */
    private fun ClientLevel.worldInset(hold: Hold, face: EndFace, out: Vector3d): Boolean {
        if (!face.valid) return false
        out.set(face.inset)
        val shipId = hold.ship ?: return true
        val ship = shipObjectWorld.allShips.getById(shipId) as? ClientShip ?: return false
        ship.renderTransform.shipToWorldRotation.transform(out)
        return true
    }

    /**
     * Finds the face of [hold]'s block shape that the hold point is on. The inset stops [INSET_MARGIN] short of the
     * far side, and a face narrower than the rope gets none; the pad stays on the face.
     */
    private fun ClientLevel.refreshFace(hold: Hold?, face: EndFace, width: Double) {
        face.valid = false
        if (hold == null) return
        val shape = getBlockState(hold.block).getShape(this, hold.block)
        if (shape.isEmpty) return
        var best = FACE_SNAP
        for (a in 0..2) {
            val p = hold.point.get(a) - hold.block.get(AXES[a])
            val lo = abs(p - shape.min(AXES[a]))
            val hi = abs(p - shape.max(AXES[a]))
            if (lo < best) { best = lo; face.axis = a; face.sign = -1.0; face.valid = true }
            if (hi < best) { best = hi; face.axis = a; face.sign = 1.0; face.valid = true }
        }
        if (!face.valid) return
        val a = face.axis; val u = (a + 1) % 3; val v = (a + 2) % 3
        val uLo = shape.min(AXES[u]); val uHi = shape.max(AXES[u])
        val vLo = shape.min(AXES[v]); val vHi = shape.max(AXES[v])
        val fits = uHi - uLo >= width && vHi - vLo >= width
        val thickness = shape.max(AXES[a]) - shape.min(AXES[a])
        val origin = hold.block
        val plane = origin.get(AXES[a]) + if (face.sign > 0) shape.max(AXES[a]) else shape.min(AXES[a])
        face.thick = thickness
        face.padHalf = PAD_SIZE * 0.5
        face.centre.setComponent(a, plane)
        face.centre.setComponent(u, origin.get(AXES[u]) + clamp(hold.point.get(u) - origin.get(AXES[u]), uLo, uHi))
        face.centre.setComponent(v, origin.get(AXES[v]) + clamp(hold.point.get(v) - origin.get(AXES[v]), vLo, vHi))
        face.depth = if (fits) buryTowardCentre(hold, face, shape, plane) else 0.0
        if (face.depth <= 0.0) face.inset.set(0.0, 0.0, 0.0)
        refreshOverhang(hold, face, shape, uLo, uHi, vLo, vHi)
    }

    /** Sets [EndFace.inset] from the anchor toward the block centre, short of every side by [INSET_MARGIN]; returns its length. */
    private fun ClientLevel.buryTowardCentre(hold: Hold, face: EndFace, shape: VoxelShape, plane: Double): Double {
        val origin = hold.block
        var len = 0.0
        for (i in 0..2) {
            val pos = if (i == face.axis) plane - origin.get(AXES[i]) else hold.point.get(i) - origin.get(AXES[i])
            val d = (shape.min(AXES[i]) + shape.max(AXES[i])) * 0.5 - pos
            face.inset.setComponent(i, d)
            len += d * d
        }
        if (len < EPSILON) face.inset.set(0.0, 0.0, 0.0).setComponent(face.axis, -face.sign) else face.inset.div(sqrt(len))
        var t = INSET_DEPTH
        for (i in 0..2) {
            val d = face.inset.get(i)
            if (abs(d) < EPSILON) continue
            val pos = if (i == face.axis) plane - origin.get(AXES[i]) else hold.point.get(i) - origin.get(AXES[i])
            val room = if (d > 0) shape.max(AXES[i]) - INSET_MARGIN - pos else pos - shape.min(AXES[i]) - INSET_MARGIN
            t = minOf(t, room / abs(d))
        }
        t = maxOf(t, 0.0)
        face.inset.mul(t)
        return t
    }

    /** Fills [EndFace.edge] and [EndFace.over]; a wrap onto a neighbour that a full block covers is dropped. */
    private fun ClientLevel.refreshOverhang(hold: Hold, face: EndFace, shape: VoxelShape, uLo: Double, uHi: Double, vLo: Double, vHi: Double) {
        val u = (face.axis + 1) % 3
        val v = (face.axis + 2) % 3
        val ou = hold.block.get(AXES[u]).toDouble(); val ov = hold.block.get(AXES[v]).toDouble()
        val cu = face.centre.get(u); val cv = face.centre.get(v)
        val h = face.padHalf
        face.edge[0] = ou + uLo; face.edge[1] = ou + uHi; face.edge[2] = ov + vLo; face.edge[3] = ov + vHi
        face.over[0] = face.edge[0] - (cu - h)
        face.over[1] = cu + h - face.edge[1]
        face.over[2] = face.edge[2] - (cv - h)
        face.over[3] = cv + h - face.edge[3]
        for (i in 0..3) {
            face.over[i] = minOf(face.over[i], face.thick)
            if (face.over[i] <= 0.0) { face.over[i] = 0.0; continue }
            val axis = if (i < 2) u else v
            val hi = i % 2 == 1
            val atBlockEdge = if (hi) shape.max(AXES[axis]) >= 1.0 - EPSILON else shape.min(AXES[axis]) <= EPSILON
            if (!atBlockEdge) continue
            val step = if (hi) 1 else -1
            val b = hold.block
            padPos.set(b.x + if (axis == 0) step else 0, b.y + if (axis == 1) step else 0, b.z + if (axis == 2) step else 0)
            if (getBlockState(padPos).isSolidRender(this, padPos)) face.over[i] = 0.0
        }
    }

    /** Never throws, unlike coerceIn, when rounding puts [lo] a hair over [hi]. */
    private fun clamp(x: Double, lo: Double, hi: Double): Double = maxOf(lo, minOf(hi, x))

    /**
     * The 8 world corners of link [link] of vine [chainId] as the last draw placed it, [margin] out from the rope
     * faces in the rotation-minimizing frame: 4 around the start node, then 4 around the end node. False when not here.
     */
    fun drawnLinkBox(chainId: Long, link: Int, margin: Double, out: DoubleArray): Boolean {
        require(out.size >= 24) { "A link box needs 24 values" }
        val level = Minecraft.getInstance().level ?: return false
        var vine: ClientVine? = null
        for (i in vines.indices) if (vines[i].id.value == chainId) { vine = vines[i]; break }
        if (vine == null) return false
        val chains = level.shipObjectWorld.allChains
        val chain = chains.getById(vine.id) ?: return false
        val count = level.shapeNodes(vine, chains, drawAt, clock.horizon, drawn = true)
        if (link !in 0 until count - 1) return false
        val drawn = smooth(count, chain.status.brokenLinks.copyBroken(count))
        level.computeFrames(vine, drawn)
        computeMiters(drawn)
        if (widthScale.size < drawn) widthScale = DoubleArray(drawn)
        widthScale.fill(1.0, 0, drawn)
        val drawnHalf = half
        half = vine.halfWidth + margin
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

    /**
     * The nearest vine along the ray, closer than [maxT], as the last frame drew it: the smoothed rope curve at its drawn
     * half width. Skips fading, retiring and hidden vines, as the server pick does. False on a miss.
     */
    fun pickDrawn(origin: Vector3d, dir: Vector3d, maxT: Double, out: DrawnVinePick): Boolean {
        val level = Minecraft.getInstance().level ?: return false
        val chains = level.shipObjectWorld.allChains
        var best = maxT
        var found = false
        for (i in vines.indices) {
            val vine = vines[i]
            if (vine.fadeStart >= 0 || heldBack.contains(vine.key) || VineThrowClient.hides(vine.id.value)) continue
            // The collider holds the tick sample; the margin covers the move from it to the drawn frame.
            if (!clientVineCollider.rayNearVine(vine.id.value, origin, dir, best, vine.halfWidth + PICK_MOVE_MARGIN)) continue
            val chain = chains.getById(vine.id) ?: continue
            val count = level.shapeNodes(vine, chains, drawAt, clock.horizon, drawn = true)
            if (count < 2) continue
            VineThrowClient.blendInto(vine.id.value, positions, count)
            val hasBroken = chain.status.brokenLinks.copyBroken(count)
            val drawn = smooth(count, hasBroken)
            for (r in 0 until drawn - 1) {
                val link = segLink[r]
                if (hasBroken && brokenFlags[link]) continue
                val t = positions.rayHitsSegment(origin, dir, r * 3, best, vine.halfWidth)
                if (t < 0) continue
                best = t
                val pieces = linkFirst[link + 1] - linkFirst[link]
                out.chainId = vine.id.value
                out.link = link
                out.along = ((r - linkFirst[link]) + pieceShare(r * 3, origin, dir, t)) / pieces
                out.distance = t
                found = true
            }
        }
        return found
    }

    /** The share along the curve piece at [k] of the point nearest to the ray point at [t]. */
    private fun pieceShare(k: Int, origin: Vector3d, dir: Vector3d, t: Double): Double {
        val ux = positions[k + 3] - positions[k]; val uy = positions[k + 4] - positions[k + 1]; val uz = positions[k + 5] - positions[k + 2]
        val lengthSq = ux * ux + uy * uy + uz * uz
        if (lengthSq < MIN_LINK * MIN_LINK) return 0.0
        val wx = origin.x + dir.x * t - positions[k]; val wy = origin.y + dir.y * t - positions[k + 1]; val wz = origin.z + dir.z * t - positions[k + 2]
        return ((wx * ux + wy * uy + wz * uz) / lengthSq).coerceIn(0.0, 1.0)
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
    @Suppress("LongParameterList") // The chains, the target and its node, the output offset, the sample time and frame.
    private fun ClientLevel.snapToTarget(
        chains: QueryableChainData<ClientChain>, target: ClientVine, node: Int, k: Int, at: Double, extrapolate: Double, drawn: Boolean,
    ) {
        val cached = target.id == copiedTarget && at == copiedAt && drawn == copiedDrawn
        val count = if (cached) copiedCount else copyTarget(chains, target, at, extrapolate, drawn)
        if (node !in 0 until count) return
        val t = node * 3
        positions[k] = targetPositions[t]; positions[k + 1] = targetPositions[t + 1]; positions[k + 2] = targetPositions[t + 2]
    }

    /** Copies [target]'s nodes at tick [at], pinned to their ships, into [targetPositions] once per sample; returns 0 when it cannot. */
    private fun ClientLevel.copyTarget(
        chains: QueryableChainData<ClientChain>, target: ClientVine, at: Double, extrapolate: Double, drawn: Boolean,
    ): Int {
        if (chains.getById(target.id) == null) return 0
        val frames = target.frames
        if (targetPositions.size < frames.nodeCount * 3) targetPositions = DoubleArray(frames.nodeCount * 3)
        val count = frames.sample(at, extrapolate, targetPositions)
        if (count >= 2) pinToShips(target, targetPositions, count, drawn)
        copiedTarget = target.id; copiedAt = at; copiedCount = count; copiedDrawn = drawn
        return count
    }

    /**
     * Puts the ship-held ends of [p] on their ships, and moves the nodes between by a blend of those
     * moves. The chain frames play later than the ship's render place, so without this the vine slides on the hull.
     */
    private fun ClientLevel.pinToShips(vine: ClientVine, p: DoubleArray, count: Int, drawn: Boolean) {
        var pins = 0
        var onShip = false
        val start = vine.startHold
        if (start != null && shipMove(start.ship!!, start.point.x(), start.point.y(), start.point.z(), p, 0, drawn, pins)) {
            pins++; onShip = true
        } else if (!vine.startFree) {
            pins = zeroPin(0, pins)
        }
        val end = vine.endHold
        if (end != null && shipMove(end.ship!!, end.point.x(), end.point.y(), end.point.z(), p, count - 1, drawn, pins)) {
            pins++; onShip = true
        } else if (!vine.endFree) {
            pins = zeroPin(count - 1, pins)
        }
        if (!onShip) return
        sortPins(pins)
        blendPinMoves(p, count, pins)
    }

    /** Writes pin [pin]: node [node] of [p] onto the shipyard point on ship [shipId]; false when the ship is not here. */
    @Suppress("LongParameterList") // The ship, its point, the nodes, the node, the frame and the pin slot.
    private fun ClientLevel.shipMove(
        shipId: Long, x: Double, y: Double, z: Double, p: DoubleArray, node: Int, drawn: Boolean, pin: Int,
    ): Boolean {
        val ship = shipObjectWorld.allShips.getById(shipId) as? ClientShip ?: return false
        (if (drawn) ship.renderTransform else ship.transform).shipToWorld.transformPosition(shipPoint.set(x, y, z))
        val k = node * 3
        pinNodes[pin] = node
        pinMoves[pin * 3] = shipPoint.x - p[k]; pinMoves[pin * 3 + 1] = shipPoint.y - p[k + 1]; pinMoves[pin * 3 + 2] = shipPoint.z - p[k + 2]
        return true
    }

    /** A fixed end that no ship holds keeps its place; returns the new pin count. */
    private fun zeroPin(node: Int, pin: Int): Int {
        pinNodes[pin] = node
        pinMoves[pin * 3] = 0.0; pinMoves[pin * 3 + 1] = 0.0; pinMoves[pin * 3 + 2] = 0.0
        return pin + 1
    }

    private fun sortPins(pins: Int) {
        for (i in 1 until pins) {
            var j = i
            while (j > 0 && pinNodes[j - 1] > pinNodes[j]) {
                val n = pinNodes[j]; pinNodes[j] = pinNodes[j - 1]; pinNodes[j - 1] = n
                for (c in 0 until 3) {
                    val m = pinMoves[j * 3 + c]; pinMoves[j * 3 + c] = pinMoves[(j - 1) * 3 + c]; pinMoves[(j - 1) * 3 + c] = m
                }
                j--
            }
        }
    }

    /** Moves each node by its pins' moves, blended by node order; a free end beyond the last pin takes that pin's move. */
    private fun blendPinMoves(p: DoubleArray, count: Int, pins: Int) {
        var seg = 0
        for (j in 0 until count) {
            while (seg < pins && pinNodes[seg] < j) seg++
            val lo = (if (seg < pins && pinNodes[seg] == j) seg else seg - 1).coerceIn(0, pins - 1)
            val hi = seg.coerceAtMost(pins - 1)
            val span = pinNodes[hi] - pinNodes[lo]
            val f = if (span > 0) (j - pinNodes[lo]).toDouble() / span else 0.0
            for (c in 0 until 3) {
                val a = pinMoves[lo * 3 + c]
                p[j * 3 + c] += a + (pinMoves[hi * 3 + c] - a) * f
            }
        }
    }

    private fun ClientVine.sampleNodes(at: Double, extrapolate: Double): Int {
        if (positions.size < frames.nodeCount * 3) positions = DoubleArray(frames.nodeCount * 3)
        val count = frames.sample(at, extrapolate, positions)
        if (parked && PARKED_SWAY_AMPLITUDE > 0.0) swayParked(key, count)
        return count
    }

    /** A sleeping vine has no physics, so its middle sways a little on the client; the ends stay. */
    private fun swayParked(key: Long, count: Int) {
        val phase = swayTicks * PARKED_SWAY_RATE + (key and PARKED_SWAY_KEY_MASK) * PARKED_SWAY_KEY_PHASE
        for (i in 1 until count - 1) {
            val sway = PARKED_SWAY_AMPLITUDE * sin(PI * i / (count - 1))
            positions[i * 3] += sway * sin(phase + i * PARKED_SWAY_NODE_PHASE)
            positions[i * 3 + 2] += sway * cos(phase * PARKED_SWAY_Z_RATE + i * PARKED_SWAY_NODE_PHASE)
        }
    }

    /** Draws the [count] curve nodes; light, broken links and knots go by the [sourceCount] chain nodes. */
    @Suppress("LongParameterList") // Level, both node counts, broken links, joined ends and the light flag.
    private fun drawVine(level: ClientLevel, count: Int, sourceCount: Int, hasBroken: Boolean, joinedEnds: Int, sampleLight: Boolean) {
        // V starts at the block face, not at the inset node, so the texture does not move with the inset depth.
        var along = -startInset
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
                if (linkVisible(k, CULL_MARGIN)) {
                    drawLink(i, along, length, capStart, capEnd)
                    if (!greyPass && link in burning) drawFireBand(i, along, length)
                }
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

    /** A cube at node [k] where this vine joins another; every face shows the painted 6 x 6 px of the knot texture. */
    private fun knot(level: ClientLevel, k: Int, lightSlot: Int, sampleLight: Boolean, sideA: Vector3d, sideB: Vector3d) {
        sideA.cross(sideB, knotT)
        light = lightAt(level, lightSlot, sampleLight, positions[k], positions[k + 1], positions[k + 2])
        val h = ropeHalf * (VINE_KNOT_HALF / VINE_HALF_WIDTH) + (half - ropeHalf)
        val ropeU0 = u0; val ropeU1 = u1; val ropeV0 = v0; val ropeV1 = v1
        val sprite = knotSprite
        if (greyPass || sprite == null) {
            u0 = 0f; u1 = 1f; v0 = 1f / GREY_ROWS; v1 = 2f / GREY_ROWS
        } else {
            u0 = sprite.u0; u1 = sprite.u1; v0 = sprite.v0; v1 = sprite.v1
        }
        knotSide(k, sideA, 1.0, sideB, -1.0, h)
        knotSide(k, sideB, -1.0, sideA, -1.0, h)
        knotSide(k, sideA, -1.0, sideB, 1.0, h)
        knotSide(k, sideB, 1.0, sideA, 1.0, h)
        knotFace(k, knotT, sideA, sideB, h)
        u0 = ropeU0; u1 = ropeU1; v0 = ropeV0; v1 = ropeV1
    }

    /** The knot side facing [n] x [sn], with the knot texture's painted square on it and U along [u] x [su]. */
    @Suppress("LongParameterList") // Node, normal, U axis and the half size.
    private fun knotSide(k: Int, n: Vector3d, sn: Double, u: Vector3d, su: Double, h: Double) {
        setNormal(n.x * sn, n.y * sn, n.z * sn)
        val uLeft = KNOT_MIN_PX
        val uRight = KNOT_MIN_PX + KNOT_SIZE_PX
        val vTop = KNOT_MIN_PX
        val vBottom = KNOT_MIN_PX + KNOT_SIZE_PX
        // The +T then +U order faces out, as T x U = n.
        knotCorner(k, n, u, knotT, sn, -su, -1.0, h, uLeft, vTop)
        knotCorner(k, n, u, knotT, sn, -su, 1.0, h, uLeft, vBottom)
        knotCorner(k, n, u, knotT, sn, su, 1.0, h, uRight, vBottom)
        knotCorner(k, n, u, knotT, sn, su, -1.0, h, uRight, vTop)
    }

    /** The two faces of the cube with normals along +/-[n]; [p] and [q] span them. */
    private fun knotFace(k: Int, n: Vector3d, p: Vector3d, q: Vector3d, h: Double) {
        for (sign in KNOT_SIGNS) {
            setNormal(n.x * sign, n.y * sign, n.z * sign)
            // This order faces +n, as n = p x q; the -n face goes the other way round, so culling keeps both outer faces.
            knotEnd(k, n, p, q, sign, -1.0, -1.0, h)
            if (sign > 0) knotEnd(k, n, p, q, sign, 1.0, -1.0, h) else knotEnd(k, n, p, q, sign, -1.0, 1.0, h)
            knotEnd(k, n, p, q, sign, 1.0, 1.0, h)
            if (sign > 0) knotEnd(k, n, p, q, sign, -1.0, 1.0, h) else knotEnd(k, n, p, q, sign, 1.0, -1.0, h)
        }
    }

    @Suppress("LongParameterList") // One end-face corner: three axes, three signs and the half size.
    private fun knotEnd(k: Int, n: Vector3d, p: Vector3d, q: Vector3d, sn: Double, sp: Double, sq: Double, h: Double) {
        knotCorner(k, n, p, q, sn, sp, sq, h, KNOT_MIN_PX + capU(sp, sn) * KNOT_CAP_SCALE, KNOT_MIN_PX + capV(sq) * KNOT_CAP_SCALE)
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
        drawSides(i, along, length)
        if (capStart) cap(i * 3, sideA0, sideB0, -1.0)
        if (capEnd) cap(i * 3 + 3, sideA1, sideB1, 1.0)
    }

    /** A full-bright band of the burn texture just outside the rope box of render node [i]. */
    private fun drawFireBand(i: Int, along: Double, length: Double) {
        val sprite = fireSprite ?: return
        val ropeU0 = u0; val ropeU1 = u1; val ropeV0 = v0; val ropeV1 = v1
        val ropeLight = light
        val ropeBoxHalf = half
        u0 = sprite.u0; u1 = sprite.u1; v0 = sprite.v0; v1 = sprite.v1
        light = LightTexture.FULL_BRIGHT
        half = ropeHalf + FIRE_BAND_GAP
        drawSides(i, along, length)
        u0 = ropeU0; u1 = ropeU1; v0 = ropeV0; v1 = ropeV1
        light = ropeLight
        half = ropeBoxHalf
    }

    /** The four long faces of render link [i]. */
    private fun drawSides(i: Int, along: Double, length: Double) {
        val k = i * 3
        val n = k + 3
        val dotA = frameAs[k] * frameAs[n] + frameAs[k + 1] * frameAs[n + 1] + frameAs[k + 2] * frameAs[n + 2]
        val dotB = frameBs[k] * frameBs[n] + frameBs[k + 1] * frameBs[n + 1] + frameBs[k + 2] * frameBs[n + 2]
        // Box corners 0..3 are (-A,-B), (+A,-B), (+A,+B), (-A,+B). Columns 0..3 go +A, -B, -A, +B, so they wrap
        // counter-clockwise when seen from the start, and U x V points into each face, so no face shows mirrored.
        side(i, 1, 2, normalAs, dotA, 1.0, 0, along, length)
        side(i, 2, 3, normalBs, dotB, 1.0, 3, along, length)
        side(i, 3, 0, normalAs, dotA, -1.0, 2, along, length)
        side(i, 0, 1, normalBs, dotB, -1.0, 1, along, length)
    }

    /**
     * One long face of link [i] from box corner [first] to [second], with texture [column] from [second] to [first].
     * Its outward axis at each node is [axes], already through the normal matrix; [dot] is the dot product of the two
     * world axes. An atlas sprite cannot wrap, so the face is cut where V crosses a texture edge.
     */
    @Suppress("LongParameterList") // Link, corners, face axes, the texture column and the V run of one face.
    private fun side(
        i: Int, first: Int, second: Int, axes: DoubleArray, dot: Double, sign: Double,
        column: Int, along: Double, length: Double,
    ) {
        val uSecond = column * COLUMN_PX
        val uFirst = uSecond + COLUMN_PX
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
            sideCorner(t0, startA, endA, uFirst, v - base)
            sideCorner(t0, startB, endB, uSecond, v - base)
            sideNormal(i, axes, dot, sign, t1)
            sideCorner(t1, startB, endB, uSecond, vNext - base)
            sideCorner(t1, startA, endA, uFirst, vNext - base)
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
        val h = half * widthScale[i]
        out.set(
            viewNodes[k] + viewCorners[o] * h,
            viewNodes[k + 1] + viewCorners[o + 1] * h,
            viewNodes[k + 2] + viewCorners[o + 2] * h,
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

    private fun cap(k: Int, sideA: Vector3d, sideB: Vector3d, sign: Double) {
        sideA.cross(sideB, tangent)
        setNormal(tangent.x * sign, tangent.y * sign, tangent.z * sign)
        // This order faces along A x B; the start cap faces back, so it goes the other way round for culling.
        capCorner(k, sideA, sideB, -1.0, -1.0, sign)
        if (sign > 0) capCorner(k, sideA, sideB, 1.0, -1.0, sign) else capCorner(k, sideA, sideB, -1.0, 1.0, sign)
        capCorner(k, sideA, sideB, 1.0, 1.0, sign)
        if (sign > 0) capCorner(k, sideA, sideB, -1.0, 1.0, sign) else capCorner(k, sideA, sideB, 1.0, -1.0, sign)
    }

    private fun capCorner(k: Int, sideA: Vector3d, sideB: Vector3d, sa: Double, sb: Double, sign: Double) {
        corner(k, sideA, sideB, sa, sb, capU(sa, sign), capV(sb))
    }

    /** Cap texels: the top-left 4 x 4 of column 0, with A running right on the end cap and left on the start cap. */
    private fun capU(sa: Double, sign: Double): Double = (1.0 + sa * sign) * CAP_HALF_PX

    private fun capV(sb: Double): Double = (1.0 - sb) * CAP_HALF_PX

    private fun corner(k: Int, sideA: Vector3d, sideB: Vector3d, sa: Double, sb: Double, uPx: Double, vPx: Double) {
        cornerAt(k, sideA, sideB, sa, sb, point)
        emitWorld(point.x, point.y, point.z, uPx, vPx)
    }

    /** The world position of corner (sa, sb) at node [k] into [out], stretched along the node's bend direction. */
    private fun cornerAt(k: Int, sideA: Vector3d, sideB: Vector3d, sa: Double, sb: Double, out: Vector3d) {
        val h = half * widthScale[k / 3]
        val a = miterA(k, sa, sb) * h
        val b = miterB(k, sa, sb) * h
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
        val target = stash
        if (target != null) {
            target.add(x.toFloat(), y.toFloat(), z.toFloat(), u, v, alpha, light, normal)
            return
        }
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
    private fun ClientLevel.seedSide(vine: ClientVine, hold: Hold?) {
        val ship = hold?.ship?.let { shipObjectWorld.allShips.getById(it) as? ClientShip }
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

    /**
     * Leaves in clusters, seeded from the vine key and the link index, so each stays at one place on the rope from
     * frame to frame; a closed bud on each free end. [drawn] is the render node count, [count] the chain node count.
     */
    private fun drawLeaves(vine: ClientVine, drawn: Int, count: Int, hasBroken: Boolean, grey: Boolean) {
        val seed = vine.key
        val phases = mix(seed xor CLUSTER_SALT)
        val p1 = unit(phases, 0) * 2 * PI
        val p2 = unit(phases, 16) * 2 * PI
        // The same leaves for each block on a vine with longer links.
        val slots = (LEAF_SLOTS * vine.spacing / VINE_TARGET_SPACING).roundToInt().coerceAtLeast(1)
        for (i in 0 until count - 1) {
            if (hasBroken && brokenFlags[i]) continue
            light = lights[i]
            val first = linkFirst[i]
            val pieces = linkFirst[i + 1] - first
            for (slot in 0 until slots) {
                val h = mix(seed + (i.toLong() * slots + slot + 1) * GOLDEN)
                val f = 0.15 + 0.7 * unit(h, 16)
                // By link index, not drawn length, so a stretch does not move a leaf over a cluster edge.
                if (unit(h, 0) >= clusterChance((i + f) * vine.spacing, p1, p2)) continue
                val along = f * pieces
                val piece = minOf(along.toInt(), pieces - 1)
                if (linkVisible((first + piece) * 3, LEAF_CULL_MARGIN)) leaf(first + piece, along - piece, h, grey)
            }
        }
        if (vine.startFree && !(hasBroken && brokenFlags[0])) {
            light = lights[0]
            budTip(0, 1, seed, grey)
        }
        if (vine.endFree && !(hasBroken && brokenFlags[count - 2])) {
            light = lights[count - 2]
            budTip(drawn - 1, drawn - 2, seed + 1, grey)
        }
    }

    /** The leaf chance [s] blocks down the vine: high in a cluster, low on the bare rope between clusters. */
    private fun clusterChance(s: Double, p1: Double, p2: Double): Double {
        val wave = sin(s * CLUSTER_WAVE_1 + p1) + CLUSTER_WAVE_2_WEIGHT * sin(s * CLUSTER_WAVE_2 + p2)
        return if (wave > CLUSTER_EDGE) CLUSTER_CHANCE else BARE_CHANCE
    }

    /** A closed bud of folded leaves on the free end at render node [node]; [neighbour] is the next node in. */
    private fun budTip(node: Int, neighbour: Int, h: Long, grey: Boolean) {
        val k = node * 3
        val j = neighbour * 3
        tangent.set(positions[k] - positions[j], positions[k + 1] - positions[j + 1], positions[k + 2] - positions[j + 2])
        if (tangent.lengthSquared() < MIN_LINK * MIN_LINK) return
        tangent.normalize()
        loadFrame(node, sideA0, sideB0)
        for (n in 0 until BUD_TIP_LEAVES) {
            val a = n * 2 * PI / BUD_TIP_LEAVES
            leafOut.set(sideA0).mul(cos(a)).fma(sin(a), sideB0)
            leafDir.set(tangent).mul(BUD_TIP_ALONG).fma(BUD_TIP_OUT, leafOut).normalize()
            leafDir.cross(leafOut, leafWidth).normalize()
            point.set(positions[k], positions[k + 1], positions[k + 2]).fma(BUD_TIP_SPREAD, leafOut).fma(-BUD_TIP_BACK, tangent)
            emitLeaf(n % LEAF_VARIANTS, grey, BUD_TIP_SIZE, 0.0, sway(mix(h + n), BUD_TIP_SWAY))
        }
    }

    /** A sway angle: two slow waves at a phase from [h], times [amplitude]. Vertex motion only; the rope is physics. */
    private fun sway(h: Long, amplitude: Double): Double {
        val phase = unit(h, 40) * 2 * PI
        return amplitude * (SWAY_MAIN * sin(seconds * SWAY_RATE_1 + phase) + SWAY_FLUTTER * sin(seconds * SWAY_RATE_2 + phase * SWAY_PHASE_2))
    }

    /** A moss decal on the anchor face round each held end. */
    private fun ClientLevel.drawPads(vine: ClientVine, count: Int) {
        val sprite = padSprite ?: return
        u0 = sprite.u0; u1 = sprite.u1; v0 = sprite.v0; v1 = sprite.v1
        val start = vine.startAnchor
        if (start != null && vine.startFace.valid) {
            light = lights[0]
            drawPad(start, vine.startFace)
        }
        val end = vine.endAnchor
        if (end != null && vine.endFace.valid) {
            light = lights[count - 2]
            drawPad(end, vine.endFace)
        }
    }

    private fun ClientLevel.drawPad(hold: Hold, face: EndFace) {
        val ship = hold.ship?.let { shipObjectWorld.allShips.getById(it) as? ClientShip }
        if (hold.ship != null && ship == null) return
        val a = face.axis
        val u = (a + 1) % 3
        val v = (a + 2) % 3
        val h = face.padHalf
        val cu = face.centre.get(u); val cv = face.centre.get(v)
        val uA = maxOf(cu - h, face.edge[0]); val uB = minOf(cu + h, face.edge[1])
        val vA = maxOf(cv - h, face.edge[2]); val vB = minOf(cv + h, face.edge[3])
        val plane = face.centre.get(a) + face.sign * PAD_LIFT
        padNormal.set(0.0, 0.0, 0.0).setComponent(a, face.sign)
        ship?.renderTransform?.shipToWorldRotation?.transform(padNormal)
        setNormal(padNormal.x, padNormal.y, padNormal.z)
        // The cyclic axes give e_u x e_v = e_axis, so this order faces +axis; a -axis face goes the other way round.
        padVertex(ship, face, plane, uA, vA, cu, cv)
        if (face.sign > 0) padVertex(ship, face, plane, uB, vA, cu, cv) else padVertex(ship, face, plane, uA, vB, cu, cv)
        padVertex(ship, face, plane, uB, vB, cu, cv)
        if (face.sign > 0) padVertex(ship, face, plane, uA, vB, cu, cv) else padVertex(ship, face, plane, uB, vA, cu, cv)
        for (i in 0..3) if (face.over[i] > 0.0) padWrap(ship, face, i, if (i < 2) vA else uA, if (i < 2) vB else uB)
    }

    /** One vertex on the face frame at (pa, pu, pv); the texture follows the 2 x [EndFace.padHalf] square round (cu, cv). */
    @Suppress("LongParameterList") // Ship, face, three coordinates and the square's centre.
    private fun padVertex(ship: ClientShip?, face: EndFace, pa: Double, pu: Double, pv: Double, cu: Double, cv: Double) {
        val a = face.axis
        val half = face.padHalf
        point.setComponent(a, pa)
        point.setComponent((a + 1) % 3, pu)
        point.setComponent((a + 2) % 3, pv)
        ship?.renderTransform?.shipToWorld?.transformPosition(point)
        emitWorld(point.x, point.y, point.z, (pu - cu + half) / (2 * half) * PX, (1.0 - (pv - cv + half) / (2 * half)) * PX)
    }

    /**
     * The part of the pad that runs past edge [i] (low u, high u, low v, high v), folded onto the neighbouring face of
     * the block and cut at its outline. [tLo] and [tHi] bound it along the other face axis. Both windings are drawn.
     */
    @Suppress("LongParameterList") // Ship, face, the edge and its transverse range.
    private fun ClientLevel.padWrap(ship: ClientShip?, face: EndFace, i: Int, tLo: Double, tHi: Double) {
        val a = face.axis
        val axis = if (i < 2) (a + 1) % 3 else (a + 2) % 3
        val other = if (i < 2) (a + 2) % 3 else (a + 1) % 3
        val hi = i % 2 == 1
        val len = face.over[i]
        val half = face.padHalf
        val outward = if (hi) 1.0 else -1.0
        val edgeAt = face.edge[i] + outward * PAD_LIFT
        val cAxis = face.centre.get(axis)
        padNormal.set(0.0, 0.0, 0.0).setComponent(axis, outward)
        ship?.renderTransform?.shipToWorldRotation?.transform(padNormal)
        setNormal(padNormal.x, padNormal.y, padNormal.z)
        for (pass in 0..1) {
            for (c in 0..3) {
                val k = if (pass == 0) c else 3 - c
                val t = if (k == 0 || k == 3) tLo else tHi
                val d = if (k < 2) 0.0 else len
                point.setComponent(axis, edgeAt)
                point.setComponent(other, t)
                point.setComponent(a, face.centre.get(a) - face.sign * d)
                ship?.renderTransform?.shipToWorld?.transformPosition(point)
                val along = (face.edge[i] - cAxis + outward * d + half) / (2 * half)
                val across = (t - face.centre.get(other) + half) / (2 * half)
                val fu = if (i < 2) along else across
                val fv = if (i < 2) across else along
                emitWorld(point.x, point.y, point.z, fu * PX, (1.0 - fv) * PX)
            }
        }
    }

    /** Moss strands under the rope, within [BEARD_DRAW_DISTANCE] of the camera; placed by link, so they stay put. */
    private fun drawBeard(vine: ClientVine, count: Int, hasBroken: Boolean, shed: Boolean) {
        val length = (count - 1) * vine.spacing
        val drop = if (vine.bothFixed) 1.0 else BEARD_FREE_SCALE
        var s = BEARD_EDGE
        var n = 0L
        var shedCount = 0
        while (s < length - BEARD_EDGE && shedCount < SHED_MAX) {
            n++
            val h = mix(vine.key + n * GOLDEN + BEARD_SALT)
            if (unit(h, 0) >= BEARD_SKIP && ropePoint(s, vine.spacing, count, hasBroken) && nearCamera(BEARD_DRAW_DISTANCE)) {
                if (shed) {
                    shedAt(EKParticles.woggakVineShedBeard(), point.x, point.y - BEARD_DROP, point.z)
                    shedCount++
                } else {
                    strand(h, drop)
                }
            }
            s += BEARD_STEP
        }
        stash = null
    }

    /** One strand hung straight down from [point], in world space, so it hangs right on a ship too. */
    private fun strand(h: Long, drop: Double) {
        val h2 = mix(h)
        val height = (BEARD_MIN + BEARD_RANGE * unit(h, 16)) * drop
        val turn = unit(h, 32) * 2 * PI
        // Top edge: the per-strand yaw blends into the rope's horizontal direction as the rope flattens, so a swinging steep vine never snaps.
        val flat = sqrt(ropeDir.x * ropeDir.x + ropeDir.z * ropeDir.z)
        val blend = minOf(flat / BEARD_FLAT_FULL, 1.0)
        var yawX = cos(turn)
        var yawZ = sin(turn)
        if (yawX * ropeDir.x + yawZ * ropeDir.z < 0.0) { yawX = -yawX; yawZ = -yawZ }
        val scale = if (flat > EPSILON) blend / flat else 0.0
        ropeDir.set(yawX * (1.0 - blend) + ropeDir.x * scale, 0.0, yawZ * (1.0 - blend) + ropeDir.z * scale).normalize()
        val jitter = (unit(h, 48) - 0.5) * BEARD_JITTER
        val tx = point.x + ropeDir.x * jitter
        val ty = point.y - BEARD_DROP
        val tz = point.z + ropeDir.z * jitter
        // Swing the down axis about the top edge.
        val w = sway(h2, BEARD_SWAY)
        hangDir.set(0.0, -1.0, 0.0)
        sideDir.set(ropeDir).cross(hangDir)
        hangDir.mul(cos(w)).fma(sin(w), sideDir)
        sideDir.set(ropeDir).cross(hangDir)
        setNormal(sideDir.x, sideDir.y, sideDir.z)
        val wx = ropeDir.x * BEARD_HALF_WIDTH
        val wy = ropeDir.y * BEARD_HALF_WIDTH
        val wz = ropeDir.z * BEARD_HALF_WIDTH
        val hx = hangDir.x * height
        val hy = hangDir.y * height
        val hz = hangDir.z * height
        stash = beardStashes[((h2 ushr 32) % BEARD_VARIANTS).toInt()]
        u0 = 0f; u1 = 1f; v0 = 0f; v1 = 1f
        emitWorld(tx - wx, ty - wy, tz - wz, 0.0, 0.0)
        emitWorld(tx + wx, ty + wy, tz + wz, PX, 0.0)
        emitWorld(tx + wx + hx, ty + wy + hy, tz + wz + hz, PX, PX)
        emitWorld(tx - wx + hx, ty - wy + hy, tz - wz + hz, 0.0, PX)
    }

    /** One particle at world (x, y, z), thrown a little sideways, for an extra that falls off a fading vine. */
    private fun shedAt(type: SimpleParticleType, x: Double, y: Double, z: Double) {
        val level = Minecraft.getInstance().level ?: return
        level.addParticle(
            type, x, y, z,
            (level.random.nextDouble() - 0.5) * SHED_DRIFT, level.random.nextDouble() * SHED_DRIFT, (level.random.nextDouble() - 0.5) * SHED_DRIFT,
        )
    }

    /** Flakes of moss fall from the anchor face of each held end. */
    private fun shedPads(vine: ClientVine) {
        shedPad(vine.startAnchor, vine.startFace)
        shedPad(vine.endAnchor, vine.endFace)
    }

    private fun shedPad(hold: Hold?, face: EndFace) {
        if (hold == null || !face.valid) return
        val level = Minecraft.getInstance().level ?: return
        val ship = hold.ship?.let { level.shipObjectWorld.allShips.getById(it) as? ClientShip }
        if (hold.ship != null && ship == null) return
        point.set(face.centre)
        ship?.renderTransform?.shipToWorld?.transformPosition(point)
        repeat(SHED_PAD_FLAKES) { shedAt(EKParticles.woggakVineShedMoss(), point.x, point.y, point.z) }
    }

    /**
     * Puts the rope axis [s] blocks down the vine, by link spacing, into [point]
     * and the link light into [light]; false on a broken link. [count] is the chain node count.
     */
    private fun ropePoint(s: Double, spacing: Double, count: Int, hasBroken: Boolean): Boolean {
        val q = s / spacing
        val link = minOf(q.toInt(), count - 2)
        if (hasBroken && brokenFlags[link]) return false
        val first = linkFirst[link]
        val pieces = linkFirst[link + 1] - first
        val along = (q - link).coerceIn(0.0, 1.0) * pieces
        val piece = minOf(along.toInt(), pieces - 1)
        val k = (first + piece) * 3
        val t = along - piece
        point.set(
            positions[k] + (positions[k + 3] - positions[k]) * t,
            positions[k + 1] + (positions[k + 4] - positions[k + 1]) * t,
            positions[k + 2] + (positions[k + 5] - positions[k + 2]) * t,
        )
        light = lights[link]
        ropeDir.set(positions[k + 3] - positions[k], positions[k + 4] - positions[k + 1], positions[k + 5] - positions[k + 2])
        if (ropeDir.lengthSquared() < EPSILON) ropeDir.set(1.0, 0.0, 0.0) else ropeDir.normalize()
        return true
    }

    private fun nearCamera(distance: Double): Boolean {
        val dx = point.x - camX; val dy = point.y - camY; val dz = point.z - camZ
        return dx * dx + dy * dy + dz * dz < distance * distance
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
        val turn = (unit(h2, 16) * 2 - 1) * LEAF_TURN
        val size = LEAF_MIN + (LEAF_MAX - LEAF_MIN) * unit(h2, 32)
        emitLeaf(((h2 ushr 48) % LEAF_VARIANTS).toInt(), grey, size, turn, sway(h3, LEAF_SWAY))
    }

    /**
     * Both faces of a leaf from the attach corner [point]: [leafDir] points out, [leafWidth] is a unit axis normal to
     * it. The +U and -V edges sit 45 deg either side of [leafDir], turned by [turn]; [sway] flaps it about [leafWidth].
     */
    private fun emitLeaf(variant: Int, grey: Boolean, size: Double, turn: Double, sway: Double) {
        if (!leafRect(variant, grey)) return
        leafDir.cross(leafWidth, leafCross)
        leafDir.mul(cos(sway)).fma(-sin(sway), leafCross)
        startB.set(leafDir).mul(cos(QUARTER_TURN + turn) * size).fma(sin(QUARTER_TURN + turn) * size, leafWidth)
        endB.set(leafDir).mul(cos(turn - QUARTER_TURN) * size).fma(sin(turn - QUARTER_TURN) * size, leafWidth)
        startB.cross(endB, leafCross).normalize()
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
            u0 = 0f; u1 = 1f; v0 = (variant + 2f) / GREY_ROWS; v1 = (variant + 3f) / GREY_ROWS
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
     * Grey copies of the rope, knot and leaf textures, made in code and stacked in one column (rope, knot, then each leaf), for
     * the fade before a free vine goes. One texture keeps the fade in one buffer.
     */
    private fun Minecraft.greyType(): RenderType? {
        greyType?.let { return it }
        val image = NativeImage(GREY_SIZE, GREY_SIZE * GREY_ROWS, true)
        for (row in 0 until GREY_ROWS) {
            val file = when (row) { 0 -> ROPE_FILE; 1 -> KNOT_FILE; else -> LEAF_FILES[row - 2] }
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
    /** Blocks that a vine can move between the collider's tick sample and the drawn frame. */
    private const val PICK_MOVE_MARGIN = 1.0
    /** Both ends. */
    private const val MAX_PINS = 2
    /** Keeps the grey shell in front of the rope box, so the two do not z-fight. */
    private const val GREY_INFLATE = 0.25 / 16
    /** Keeps the fire band off the rope box, so the two do not z-fight. */
    private const val FIRE_BAND_GAP = 0.5 / 16
    private val KNOT_SIGNS = doubleArrayOf(1.0, -1.0)
    private const val PX = 16.0
    private const val CENTRE_PX = 8.0
    private const val CAP_HALF_PX = 2.0
    // The knot texture is 16 px with a 6 px square painted at 5..10; capU/capV span 4 px.
    private const val KNOT_MIN_PX = 5.0
    private const val KNOT_SIZE_PX = 6.0
    private const val KNOT_CAP_SCALE = KNOT_SIZE_PX / 4.0
    private const val SHED_MAX = 48
    private const val SHED_PAD_FLAKES = 6
    private const val SHED_DRIFT = 0.04
    private const val ROPE_COLUMNS = 4
    private const val COLUMN_PX = PX / ROPE_COLUMNS
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
    private const val GREY_ROWS = 2 + LEAF_VARIANTS
    private const val GOLDEN = -0x61c8864680b583ebL
    private const val TICKS_PER_SECOND = 20.0
    /** Blocks that the middle of a sleeping vine sways; 0 turns the sway off. */
    private const val PARKED_SWAY_AMPLITUDE = 0.12
    private const val PARKED_SWAY_RATE = 0.05
    private const val PARKED_SWAY_Z_RATE = 0.8
    private const val PARKED_SWAY_NODE_PHASE = 0.1
    private const val PARKED_SWAY_KEY_PHASE = 0.01
    private const val PARKED_SWAY_KEY_MASK = 1023L
    /** Client ticks between light samples of a sleeping vine, which does not move. */
    private const val PARKED_LIGHT_TICKS = 20L
    private val AXES = Direction.Axis.values()
    private const val FIXED_START = JOINED_START or SHIP_START or BLOCK_START
    private const val FIXED_END = JOINED_END or SHIP_END or BLOCK_END
    private const val CLUSTER_SALT = 0x2545F4914F6CDD1DL
    private const val CLUSTER_WAVE_1 = 2.3
    private const val CLUSTER_WAVE_2 = 5.1
    private const val CLUSTER_WAVE_2_WEIGHT = 0.5
    private const val CLUSTER_EDGE = 0.35
    private const val CLUSTER_CHANCE = 0.95
    private const val BARE_CHANCE = 0.06
    private const val SWAY_RATE_1 = 1.7
    private const val SWAY_RATE_2 = 3.3
    private const val SWAY_PHASE_2 = 1.7
    private const val SWAY_MAIN = 0.6
    private const val SWAY_FLUTTER = 0.4
    private const val LEAF_SWAY = 0.22
    private const val BEARD_SWAY = 0.16
    private const val BUD_TIP_SWAY = 0.06
    /** Moss strands draw only this near the camera, in blocks. */
    private const val BEARD_DRAW_DISTANCE = 16.0
    private const val BEARD_VARIANTS = 3
    private const val BEARD_SALT = 0x3C6EF372FE94F82BL
    private const val BEARD_EDGE = 0.3
    private const val BEARD_STEP = 0.22
    private const val BEARD_SKIP = 0.35
    private const val BEARD_MIN = 0.55
    private const val BEARD_RANGE = 0.45
    private const val BEARD_FREE_SCALE = 0.7
    private const val BEARD_HALF_WIDTH = 0.25
    private const val BEARD_DROP = 0.08
    // At this horizontal share of the rope direction the top edge fully follows the rope.
    private const val BEARD_FLAT_FULL = 0.3
    private const val BEARD_JITTER = 0.06
    private const val PAD_SIZE = 0.9
    /** Keeps the moss pad off the block face, so the two do not z-fight. */
    private const val PAD_LIFT = 0.005
    private const val INSET_DEPTH = 0.22
    private const val INSET_MARGIN = 1.0 / 32
    /** A hold point farther than this from every face of its block shape gets no pad and no inset. */
    private const val FACE_SNAP = 0.25
    private const val BUD_TIP_LEAVES = 3
    private const val BUD_TIP_SIZE = 5.0 / 16
    private const val BUD_TIP_ALONG = 0.9
    private const val BUD_TIP_OUT = 0.18
    private const val BUD_TIP_SPREAD = 0.05
    private const val BUD_TIP_BACK = 0.08
    /** Client ticks per drop per span, on average: about 7 s. */
    private const val DRIP_TICKS = 140
    private const val DRIP_DISTANCE = 32.0
    /** Client ticks a replaced vine waits for its pieces, as a landed throw waits for its vine. */
    private const val MAX_RETIRE_TICKS = 40
    private const val MAX_RETIRING = 64
    private const val DRIP_GAP = 0.02
    /** End-to-end distance over rest length where a span starts to thin; at 1 it has the full thinning. */
    private const val TAUT_SLACK = 0.95
    private const val TAUT_THIN = 0.15
}
