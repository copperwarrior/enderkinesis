package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.math.Axis
import dev.architectury.event.EventResult
import dev.architectury.event.events.client.ClientGuiEvent
import dev.architectury.event.events.client.ClientRawInputEvent
import dev.architectury.event.events.client.ClientTickEvent
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.culling.Frustum
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Vector3f
import org.joml.Vector4f
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30
import org.lwjgl.system.MemoryUtil
import org.shipwrights.enderkinesis.item.SilverpointItem
import org.shipwrights.enderkinesis.registry.EKSounds
import org.shipwrights.enderkinesis.item.SilverpointNetwork
import org.shipwrights.enderkinesis.item.SilverpointPicture
import org.shipwrights.enderkinesis.item.SketchSpec
import org.shipwrights.enderkinesis.item.SketchSubject
import org.shipwrights.enderkinesis.item.StrikeReadout
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.sqrt
import kotlin.random.Random
import org.shipwrights.enderkinesis.util.smoothstep

/**
 * The Silverpoint's viewfinder. While the use is held, a square frame shows on the screen and the scroll wheel zooms
 * it. On release, the next frame's depth in and around the square is read back with that frame's camera. Each pixel's
 * depth gives the block that drew it, and those blocks and the entities that show are rebuilt block for block through
 * the same camera, at the frame's scale, on empty paper, in a bake that runs over the next frames.
 * Blocks that reach past the frame's edge fade out, and so does all that lies past the first gap in depth.
 */
object SilverpointView {

    private const val SCALE = 3
    private const val VIEW_KEY = "view"

    private const val RANGE = 128
    private const val NEAR = 0.05f
    private const val MAX_BLOCKS = 65_536
    private const val SLICE_NANOS = 2_000_000L
    private const val HALF_TURN = 180f

    // A block reaching past the frame's edge fades out between these, as parts of the frame's half-width.
    private const val EDGE_FADE_FROM = 0.95f
    private const val EDGE_FADE_TO = 1.3f
    private const val MIN_FADE = 0.05f

    // The depth gap: skip the nearest part of the blocks, then a gap is GAP_SPAN blocks of depth below GAP_SHARE of
    // the busiest depth so far. Past the cut, content fades out over DEPTH_FADE_SPAN blocks.
    private const val GAP_SKIP_SHARE = 0.2f
    private const val GAP_SHARE = 0.35f
    private const val GAP_SPAN = 2
    private const val DEPTH_FADE_SPAN = 4f

    // A face point counts as seen within this many blocks of the depth drawn at its pixel, plus a part of its distance
    // for depth-buffer precision far away.
    private const val SEEN_SLACK = 0.12f
    private const val SEEN_SLACK_PER_BLOCK = 0.004f
    // A pixel's surface point, stepped this far along the view ray, is inside the block that drew it.
    private const val INTO_SURFACE = 0.02f
    private const val INTO_SURFACE_PER_BLOCK = 0.002f
    private const val INTO_SURFACE_TRIES = 3
    private const val HALF_BLOCK = 0.5

    // The bed under a liquid: looked for at every BED_STRIDE-th pixel each way, BED_STEP blocks at a time, BED_STEPS deep.
    private const val BED_STRIDE = 4
    private const val BED_STEP = 0.3f
    private const val BED_STEPS = 54

    // Bounds the search past the frame's edge: six neighbour checks for each block it takes.
    private const val MAX_EXTEND_VISITS = 120_000

    // The frame's side as a part of the screen's shorter side.
    private const val ZOOM_MIN = 0.2f
    private const val ZOOM_MAX = 1f
    private const val ZOOM_STEP = 0.08f
    private const val SHADE = 0x80000000.toInt()
    private const val FRAME_LINE = 0xFFE8E0D0.toInt()
    private const val FRAME_ALPHA = 0.7f

    /** The frame as the level drew it: its depth inside the square, and the camera that drew it. */
    private class Shot(
        val eye: Vec3,
        val rotation: Matrix4f,
        val viewProjection: Matrix4f,
        /** 1 / tan of half the vertical field of view. */
        val focal: Float,
        val near: Float,
        val far: Float,
        val screenWidth: Int,
        val screenHeight: Int,
        val left: Int,
        val bottom: Int,
        val side: Int,
        /** Depth of the read region: the frame and a margin for blocks that reach past its edge. */
        val readLeft: Int,
        val readBottom: Int,
        val readWidth: Int,
        val readHeight: Int,
        val depth: FloatArray,
    ) {
        /** The depth at a screen pixel, or 1 (nothing drawn) outside the read region. */
        fun depthAt(x: Int, y: Int): Float {
            val rx = x - readLeft
            val ry = y - readBottom
            return if (rx in 0 until readWidth && ry in 0 until readHeight) depth[ry * readWidth + rx] else 1f
        }
    }

    private class Gathered(val pos: BlockPos, val state: BlockState, var fade: Float, val ground: Boolean, val distance: Float)

    private class Gathering(
        val level: ClientLevel,
        val shot: Shot,
        val camera: SketchScene.SceneCamera,
        val halfTan: Float,
        val frustum: Frustum,
        val colour: Boolean,
    ) {
        /** Clip space to eye-relative world space, to rebuild the visible point at each pixel. */
        val unproject: Matrix4f = Matrix4f(shot.viewProjection).invert()
        var row = 0
        val visited = LongOpenHashSet()
        val blocks = ArrayList<Gathered>()

        /** Index in [blocks] by block position. */
        val index = Long2IntOpenHashMap().apply { defaultReturnValue(-1) }
        var bake: SlicedBake? = null

        /** How many of [blocks] the depth showed; the rest are past the frame's edge. */
        var seenCount = 0

        /** The paper's picture id, from when the server was asked for the paper. */
        var id: String? = null
    }

    // Render thread only.
    private var zoom = ZOOM_MAX
    private var pending = false
    private var pendingColour = false
    // A sketch has its paper once it has an id, so a new release queues behind it and never drops it.
    private val gatherings = ArrayDeque<Gathering>()

    fun init() {
        ClientRawInputEvent.MOUSE_SCROLLED.register { _, amount ->
            if (!framing()) return@register EventResult.pass()
            zoom = (zoom - amount.toFloat() * ZOOM_STEP).coerceIn(ZOOM_MIN, ZOOM_MAX)
            EventResult.interruptTrue()
        }
        ClientGuiEvent.RENDER_HUD.register { graphics, _ -> if (framing()) drawFrame(graphics) }
        ClientTickEvent.CLIENT_POST.register { sketchSounds() }
    }

    private fun sketchSounds() {
        val level = Minecraft.getInstance().level ?: return
        for (player in level.players()) {
            if (!player.isUsingItem) continue
            val item = player.useItem.item as? SilverpointItem ?: continue
            IncantationSounds.hold(player, item, EKSounds.SILVERPOINT_SKETCH.get(), SoundSource.PLAYERS, 1f)
        }
    }

    /** The frame's side as a fraction of the screen's shorter side. */
    fun frameZoom(): Float = zoom

    private fun framing(): Boolean {
        val player = Minecraft.getInstance().player ?: return false
        return player.isUsingItem && player.useItem.item is SilverpointItem
    }

    private fun drawFrame(graphics: GuiGraphics) {
        val width = graphics.guiWidth()
        val height = graphics.guiHeight()
        val side = (minOf(width, height) * zoom).toInt()
        val left = (width - side) / 2
        val top = (height - side) / 2
        val right = left + side
        val bottom = top + side
        graphics.fill(0, 0, width, top, SHADE)
        graphics.fill(0, bottom, width, height, SHADE)
        graphics.fill(0, top, left, bottom, SHADE)
        graphics.fill(right, top, width, bottom, SHADE)
        SketchStroke.rectangle(graphics, left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), FRAME_LINE, FRAME_ALPHA)
    }

    /** The use was released: the next frame's level depth is read back for the sketch. */
    fun capture() {
        pending = true
        // As a strike: a dye in the other hand at release washes the sketch.
        val player = Minecraft.getInstance().player
        pendingColour = player != null && SilverpointItem.dyeInOtherHand(player, player.usedItemHand)
    }

    /** Called after the level draws and before the hand clears the depth. */
    @JvmStatic
    fun afterLevel() {
        if (!pending) return
        pending = false
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level ?: return
        val shot = readShot(minecraft) ?: return
        // The square's field of view; the sketch is drawn through it, so the paper holds exactly the frame.
        val halfTan = shot.side / shot.screenHeight.toFloat() / shot.focal
        val projection = Matrix4f().setPerspective(2f * atan(halfTan), 1f, NEAR, RANGE * 1.5f)
        val frustum = Frustum(shot.rotation, projection).also { it.prepare(shot.eye.x, shot.eye.y, shot.eye.z) }
        gatherings.addLast(Gathering(level, shot, SketchScene.SceneCamera(shot.rotation, projection), halfTan, frustum, pendingColour))
    }

    private fun readShot(minecraft: Minecraft): Shot? {
        val main = minecraft.mainRenderTarget
        val camera = minecraft.gameRenderer.mainCamera
        val width = main.width
        val height = main.height
        val side = (minOf(width, height) * zoom).toInt()
        if (side <= 0) return null
        val left = (width - side) / 2
        val bottom = (height - side) / 2
        // Blocks that reach past the frame's edge fade out up to EDGE_FADE_TO, so read that far around it.
        val margin = (side * (EDGE_FADE_TO - 1f) / 2f).toInt()
        val readLeft = maxOf(0, left - margin)
        val readBottom = maxOf(0, bottom - margin)
        val readWidth = minOf(width, left + side + margin) - readLeft
        val readHeight = minOf(height, bottom + side + margin) - readBottom
        val buffer = MemoryUtil.memAllocFloat(readWidth * readHeight)
        val depth = try {
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId)
            GL11.glReadPixels(readLeft, readBottom, readWidth, readHeight, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, buffer)
            FloatArray(readWidth * readHeight).also { buffer.get(it) }
        } finally {
            MemoryUtil.memFree(buffer)
            main.bindWrite(true)
        }
        val projection = Matrix4f(RenderSystem.getProjectionMatrix())
        val rotation = Matrix4f()
            .rotate(Axis.XP.rotationDegrees(camera.xRot))
            .rotate(Axis.YP.rotationDegrees(camera.yRot + HALF_TURN))
        // A GL perspective holds its planes in m22 and m32.
        val near = projection.m32() / (projection.m22() - 1f)
        val far = projection.m32() / (projection.m22() + 1f)
        return Shot(
            camera.position, rotation, Matrix4f(projection).mul(rotation), projection.m11(), near, far,
            width, height, left, bottom, side, readLeft, readBottom, readWidth, readHeight, depth,
        )
    }

    /** One slice of the gather or the bake, at the start of each frame so a sketch is ready in a few frames. */
    @JvmStatic
    fun frame() {
        val gathering = gatherings.firstOrNull() ?: return
        if (Minecraft.getInstance().level !== gathering.level) {
            gatherings.forEach { it.bake?.close() }
            gatherings.clear()
            return
        }
        val deadline = System.nanoTime() + SLICE_NANOS
        val bake = gathering.bake
        if (bake != null) {
            bake.step(deadline)
            if (bake.done) {
                gatherings.removeFirst()
                send(bake, gathering)
            }
            return
        }
        while (gathering.row < gathering.shot.readHeight && gathering.blocks.size < MAX_BLOCKS) {
            gatherRow(gathering, gathering.row++)
            if (System.nanoTime() > deadline) return
        }
        val seen = gathering.blocks.size
        gathering.seenCount = seen
        extendPastEdge(gathering)
        gathering.bake = startBake(gathering)
        if (gathering.bake == null) {
            gatherings.removeFirst()
            return
        }
        // Something will be drawn, so the paper can come now; its picture follows in a few frames.
        val id = SilverpointPicture.newId()
        gathering.id = id
        SilverpointNetwork.sendBegin(id, gathering.colour)
    }

    /**
     * The blocks drawn in one row of the read region. Each pixel's depth gives the surface point it shows; a short step
     * past it along the view ray is inside the block that drew it. Neighbour pixels mostly show the same block, so a
     * block is looked up once.
     */
    private fun gatherRow(gathering: Gathering, row: Int) {
        val shot = gathering.shot
        val eye = shot.eye
        val level = gathering.level
        val pos = BlockPos.MutableBlockPos()
        val point = Vector4f()
        var last = Long.MIN_VALUE
        val screenY = shot.readBottom + row + 0.5f
        val ndcY = screenY / shot.screenHeight * 2f - 1f
        val half = shot.side / 2f
        val reachY = abs(screenY - (shot.bottom + half)) / half
        for (x in 0 until shot.readWidth) {
            val depth = shot.depth[row * shot.readWidth + x]
            if (depth >= 1f) continue
            val ndcX = (shot.readLeft + x + 0.5f) / shot.screenWidth * 2f - 1f
            gathering.unproject.transform(point.set(ndcX, ndcY, depth * 2f - 1f, 1f))
            val px = point.x / point.w
            val py = point.y / point.w
            val pz = point.z / point.w
            val distance = sqrt(px * px + py * py + pz * pz)
            if (distance > RANGE || distance < NEAR) continue
            // Far depth is coarse, so the step into the surface grows with distance.
            val step = (INTO_SURFACE + INTO_SURFACE_PER_BLOCK * distance) / distance
            // A block shows as strongly as the pixel of it nearest the frame's centre.
            val reach = maxOf(abs(shot.readLeft + x + 0.5f - (shot.left + half)) / half, reachY)
            val fade = 1f - smoothstep(EDGE_FADE_FROM, EDGE_FADE_TO, reach)
            for (k in 1..INTO_SURFACE_TRIES) {
                val t = 1f + step * k
                pos.set(Mth.floor(eye.x + px * t), Mth.floor(eye.y + py * t), Mth.floor(eye.z + pz * t))
                val key = pos.asLong()
                if (key != last) {
                    val state = level.getBlockState(pos)
                    // An entity drew this pixel; the block behind it may still be a step further.
                    if (state.isAir) continue
                    last = key
                    take(gathering, pos, state, fade)
                    // Through a liquid's surface the bed shows; look for it on a sparse grid of pixels.
                    if (state.block is LiquidBlock && row % BED_STRIDE == 0 && x % BED_STRIDE == 0) {
                        gatherBed(gathering, px, py, pz, distance, fade)
                    }
                } else {
                    gathering.index.get(key).takeIf { it >= 0 }?.let { gathering.blocks[it].fade = maxOf(gathering.blocks[it].fade, fade) }
                }
                break
            }
        }
    }

    /** Adds the block, or raises its fade when another pixel shows it nearer the frame's centre. */
    private fun take(gathering: Gathering, pos: BlockPos, state: BlockState, fade: Float) {
        val i = gathering.index.get(pos.asLong())
        if (i < 0) consider(gathering, pos, state, fade) else gathering.blocks[i].fade = maxOf(gathering.blocks[i].fade, fade)
    }

    /** The first block that is not liquid or air, down the view ray from a liquid surface point at [distance]. */
    @Suppress("LongParameterList") // A ray, its length to the surface, and the pixel's fade.
    private fun gatherBed(gathering: Gathering, px: Float, py: Float, pz: Float, distance: Float, fade: Float) {
        val eye = gathering.shot.eye
        val pos = BlockPos.MutableBlockPos()
        for (k in 1..BED_STEPS) {
            val t = 1f + BED_STEP * k / distance
            if (distance * t > RANGE) return
            pos.set(Mth.floor(eye.x + px * t), Mth.floor(eye.y + py * t), Mth.floor(eye.z + pz * t))
            val state = gathering.level.getBlockState(pos)
            if (state.isAir || state.block is LiquidBlock) continue
            take(gathering, pos, state, fade)
            return
        }
    }

    /**
     * Surface blocks just past the frame's edge, found outward from the gathered blocks that the edge fade reaches. They
     * cover the side faces of the edge blocks and fade out, so the content runs past the edge instead of ending in a cut.
     * The screen holds no depth past its own edge, so this follows the blocks, not the depth.
     */
    private fun extendPastEdge(gathering: Gathering) {
        val level = gathering.level
        val queue = LongArrayFIFOQueue()
        for (block in gathering.blocks) if (block.fade < 1f) queue.enqueue(block.pos.asLong())
        val pos = BlockPos.MutableBlockPos()
        var visits = 0
        while (!queue.isEmpty && visits < MAX_EXTEND_VISITS && gathering.blocks.size < MAX_BLOCKS) {
            val from = queue.dequeueLong()
            for (direction in Direction.entries) {
                visits++
                pos.set(from).move(direction)
                val key = pos.asLong()
                if (gathering.index.containsKey(key) || !gathering.visited.add(key)) continue
                val state = level.getBlockState(pos)
                if (state.isAir || !exposed(level, pos)) continue
                val reach = reach(gathering, pos.x, pos.y, pos.z)
                if (reach < EDGE_FADE_FROM) continue
                val before = gathering.blocks.size
                consider(gathering, pos, state, 1f - smoothstep(EDGE_FADE_FROM, EDGE_FADE_TO, reach))
                if (gathering.blocks.size > before) queue.enqueue(key)
            }
        }
    }

    /** True when a side of the block is open, so it is part of a surface and not buried. */
    private fun exposed(level: ClientLevel, pos: BlockPos): Boolean {
        val near = BlockPos.MutableBlockPos()
        return Direction.entries.any {
            near.setWithOffset(pos, it)
            !level.getBlockState(near).isSolidRender(level, near)
        }
    }

    private fun consider(gathering: Gathering, pos: BlockPos, state: BlockState, fade: Float) {
        // A liquid block's shape is invisible; its fluid is drawn on its own.
        if ((state.renderShape == RenderShape.INVISIBLE && state.fluidState.isEmpty) || fade < MIN_FADE) return
        val eye = gathering.shot.eye
        val dx = pos.x + 0.5 - eye.x
        val dy = pos.y + 0.5 - eye.y
        val dz = pos.z + 0.5 - eye.z
        val distance = sqrt(dx * dx + dy * dy + dz * dz).toFloat()
        if (distance > RANGE) return
        gathering.index.put(pos.asLong(), gathering.blocks.size)
        gathering.blocks.add(Gathered(pos.immutable(), state, fade, ObjectFinder.isGround(state), distance))
    }

    /** True when the world point is inside the frame and no deeper than the level's depth there, within [slack]. */
    private fun drawnAt(shot: Shot, px: Double, py: Double, pz: Double, slack: Float, scratch: Vector4f): Boolean {
        val eye = shot.eye
        shot.viewProjection.transform(scratch.set((px - eye.x).toFloat(), (py - eye.y).toFloat(), (pz - eye.z).toFloat(), 1f))
        if (scratch.w <= 0f) return false
        val sx = ((scratch.x / scratch.w * 0.5f + 0.5f) * shot.screenWidth).toInt()
        val sy = ((scratch.y / scratch.w * 0.5f + 0.5f) * shot.screenHeight).toInt()
        if (sx - shot.left !in 0 until shot.side || sy - shot.bottom !in 0 until shot.side) return false
        val drawn = linear(shot, shot.depthAt(sx, sy))
        val point = linear(shot, scratch.z / scratch.w * 0.5f + 0.5f)
        return point <= drawn + SEEN_SLACK + SEEN_SLACK_PER_BLOCK * point + slack
    }

    /** Distance along the view for a depth-buffer value. */
    private fun linear(shot: Shot, depth: Float): Float {
        val ndc = depth * 2f - 1f
        return 2f * shot.near * shot.far / (shot.far + shot.near - ndc * (shot.far - shot.near))
    }

    /**
     * How far the block's centre is from the frame's centre, in frame half-widths; past 1 it is outside the frame.
     * Not the outline: a near block looks large, and its corners reach far past the edge while most of it is inside.
     */
    private fun reach(gathering: Gathering, x: Int, y: Int, z: Int): Float {
        val eye = gathering.shot.eye
        val centre = gathering.shot.rotation.transformPosition(
            (x + HALF_BLOCK - eye.x).toFloat(), (y + HALF_BLOCK - eye.y).toFloat(), (z + HALF_BLOCK - eye.z).toFloat(), Vector3f(),
        )
        val depth = -centre.z
        if (depth < NEAR) return Float.MAX_VALUE
        return maxOf(abs(centre.x), abs(centre.y)) / depth / gathering.halfTan
    }

    /** The faces the level draws: a face against a neighbour that covers it is not drawn in the world or the sketch. */
    private fun drawnFaces(level: ClientLevel, pos: BlockPos, state: BlockState): Int {
        val near = BlockPos.MutableBlockPos()
        var faces = 0
        for (direction in Direction.entries) {
            near.setWithOffset(pos, direction)
            if (Block.shouldRenderFace(state, level, pos, direction, near)) faces = faces or (1 shl direction.ordinal)
        }
        return faces
    }

    private fun startBake(gathering: Gathering): SlicedBake? {
        val minecraft = Minecraft.getInstance()
        val level = gathering.level
        val shot = gathering.shot
        val eye = shot.eye
        val partialTick = minecraft.frameTime
        val hiddenViewer = if (minecraft.options.cameraType.isFirstPerson) minecraft.cameraEntity else null
        // From the visible blocks only: the blocks past the edge lie at every depth and would fill the gap.
        val cut = depthGap(gathering.blocks.subList(0, gathering.seenCount).map { it.distance })
        val blocks = gathering.blocks.mapNotNull {
            val fade = it.fade * depthFade(it.distance, cut)
            if (fade < MIN_FADE) return@mapNotNull null
            SketchScene.ViewBlock(
                it.pos, Vec3(it.pos.x - eye.x, it.pos.y - eye.y, it.pos.z - eye.z), it.state,
                level.getBlockEntity(it.pos)?.saveWithoutMetadata(), fade, it.ground, drawnFaces(level, it.pos, it.state),
                minecraft.blockColors.getColor(it.state, level, it.pos, 0),
            )
        }
        val scratch = Vector4f()
        val entities = level.entitiesForRendering()
            .filter { it !== hiddenViewer && it.position().distanceToSqr(eye) <= RANGE * RANGE.toDouble() }
            .filter { gathering.frustum.isVisible(it.boundingBoxForCulling) }
            .filter {
                // The middle lies inside the entity, so it may sit up to half the entity behind the drawn surface.
                val middle = it.boundingBox.center
                val half = maxOf(it.bbWidth, it.bbHeight) / 2f
                drawnAt(shot, middle.x, middle.y, middle.z, half, scratch)
            }
            .mapNotNull { entity ->
                val feet = entity.getPosition(partialTick)
                val fade = depthFade(feet.distanceTo(eye).toFloat(), cut)
                if (fade < MIN_FADE) null else SketchScene.ViewEntity(feet.subtract(eye), entity, fade)
            }
        if (blocks.isEmpty() && entities.isEmpty()) return null
        val scene = SketchScene.ofCamera(blocks, entities, gathering.camera)
        val spec = SketchSpec(SketchSubject.Picture(VIEW_KEY), Random.nextInt(), StrikeReadout.SKETCH_SIZE, notes = true, colour = gathering.colour)
        return SlicedBake(spec, scene, SCALE)
    }

    private fun send(bake: SlicedBake, gathering: Gathering) {
        val id = checkNotNull(gathering.id) { "a bake without its paper" }
        val resolution = StrikeReadout.SKETCH_SIZE * SCALE
        val packed = bake.image()?.use { image ->
            if (gathering.colour) {
                SilverpointPicture.packColour(resolution, IntArray(resolution * resolution) { i -> image.getPixelRGBA(i % resolution, i / resolution) })
            } else {
                SilverpointPicture.pack(resolution, ByteArray(resolution * resolution) { i -> (image.getPixelRGBA(i % resolution, i / resolution) ushr 24).toByte() })
            }
        } ?: return
        // This client shows its own picture at once; the store has it for everyone else.
        SketchPictures.put(id, packed)
        SilverpointNetwork.sendPicture(id, packed)
    }

    /**
     * Where the content ends: the first clear gap in the depths of the visible blocks after the nearest part of them,
     * where few blocks show for a while before the backdrop starts. Without a gap, the whole range.
     */
    private fun depthGap(depths: List<Float>): Float {
        val counts = IntArray(RANGE + 1)
        for (d in depths) counts[d.toInt().coerceIn(0, RANGE)]++
        val smooth = FloatArray(RANGE + 1) { i ->
            (counts[maxOf(0, i - 1)] + counts[i] + counts[minOf(RANGE, i + 1)]) / 3f
        }
        val skip = depths.size * GAP_SKIP_SHARE
        var seen = 0
        var peak = 0f
        var low = 0
        for (i in 0..RANGE) {
            seen += counts[i]
            peak = maxOf(peak, smooth[i])
            if (seen < skip) continue
            low = if (smooth[i] < peak * GAP_SHARE) low + 1 else 0
            if (low == GAP_SPAN) return (i - GAP_SPAN + 1).toFloat()
        }
        return RANGE - DEPTH_FADE_SPAN
    }

    /** 1 before the cut, falling to 0 over [DEPTH_FADE_SPAN] past it, so the back of the content has no hard edge. */
    private fun depthFade(distance: Float, cut: Float): Float = 1f - smoothstep(cut, cut + DEPTH_FADE_SPAN, distance)

}
