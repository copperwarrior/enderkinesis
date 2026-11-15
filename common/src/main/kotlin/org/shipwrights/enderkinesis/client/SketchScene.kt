package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.brigadier.exceptions.CommandSyntaxException
import com.mojang.logging.LogUtils
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.ItemBlockRenderTypes
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.block.model.BakedQuad
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.client.resources.model.BakedModel
import net.minecraft.commands.arguments.blocks.BlockStateParser
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.Vec3i
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtIo
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.FastColor
import net.minecraft.util.RandomSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.inventory.InventoryMenu
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BedPart
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.ChestType
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate
import net.minecraft.world.phys.Vec3
import org.joml.Matrix3f
import org.joml.Matrix4f
import org.joml.Vector3f
import org.shipwrights.enderkinesis.block.FractalProjectorBlock
import org.shipwrights.enderkinesis.construction.ClientPlans
import org.shipwrights.enderkinesis.construction.PLAN_SECTION_EDGE
import org.shipwrights.enderkinesis.construction.PlanSection
import org.shipwrights.enderkinesis.construction.PlanSections
import org.shipwrights.enderkinesis.item.SketchSubject
import org.shipwrights.enderkinesis.mixin.BlockEntityLevelAccessor
import org.shipwrights.enderkinesis.mixin.StructureTemplateAccessor
import org.shipwrights.enderkinesis.registry.EKBlocks
import java.io.IOException
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Draws one sketch subject centred on the origin, in block units, for the sketch's colour and normal passes. */
internal sealed class SketchScene {

    /** Half-width of a view that should hold the subject; the sketch widens it if the render still touches an edge. */
    abstract val extentHint: Float

    /** [normals] is true in the normals pass, which reads vertex colour only for view scenes' ground flag. */
    abstract fun draw(pose: PoseStack, source: MultiBufferSource, normals: Boolean)

    /** How many parts [drawPart] splits the scene into, so a large scene can draw over several ticks. */
    open val parts: Int get() = 1

    /** Draws parts [from] until [to]; drawing every part once equals [draw]. */
    open fun drawPart(pose: PoseStack, source: MultiBufferSource, normals: Boolean, from: Int, to: Int) =
        draw(pose, source, normals)

    /** How many parts a staged bake draws at once. */
    open val partBatch: Int get() = DEFAULT_PART_BATCH

    /** False while the data of parts [from] until [to] is still on its way; the bake waits. */
    open fun partsReady(from: Int, to: Int): Boolean = true

    /** The yaw the sketch looks along. */
    open val viewYaw: Float get() = StrikeSketch.ISO_YAW

    /** How far down the sketch looks. */
    open val viewPitch: Float get() = StrikeSketch.ISO_PITCH

    /** The least alpha that the fit holds in frame; fainter parts may run off the edge. */
    open val fitAlpha: Int get() = HALF_ALPHA

    /** The view of the first fit render; null starts at the origin with [extentHint]. */
    open val firstView: SketchView? get() = null

    /** A fixed perspective camera; when set, the sketch draws through it and does not fit the view. */
    open val camera: SceneCamera? get() = null

    /** [rotation] turns the world to the view; [projection] is a perspective with the frame's field of view. */
    class SceneCamera(val rotation: Matrix4f, val projection: Matrix4f)

    private class BlockScene(state: BlockState) : SketchScene() {
        private val pieces = wholeBlock(state)
        override val extentHint = BLOCK_EXTENT * pieces.size

        override fun draw(pose: PoseStack, source: MultiBufferSource, normals: Boolean) {
            val minX = pieces.minOf { it.first.x }
            val minY = pieces.minOf { it.first.y }
            val minZ = pieces.minOf { it.first.z }
            val centreX = (minX + pieces.maxOf { it.first.x } + 1) / 2f
            val centreY = (minY + pieces.maxOf { it.first.y } + 1) / 2f
            val centreZ = (minZ + pieces.maxOf { it.first.z } + 1) / 2f
            for ((offset, part) in pieces) {
                drawStateAt(
                    part, null, pose, source, normals,
                    (offset.x - centreX).toDouble(), (offset.y - centreY).toDouble(), (offset.z - centreZ).toDouble(),
                )
            }
        }
    }

    private class EntityScene(private val entity: Entity) : SketchScene() {
        override val extentHint = max(BLOCK_EXTENT, max(entity.bbWidth, entity.bbHeight) * ENTITY_EXTENT_PER_SIZE)

        override fun draw(pose: PoseStack, source: MultiBufferSource, normals: Boolean) {
            val dispatcher = Minecraft.getInstance().entityRenderDispatcher
            val base = pose.last()
            pose.pushPose()
            pose.translate(0f, -entity.bbHeight / 2f, 0f)
            // As InventoryScreen does: a shadow would sample the world at the origin.
            dispatcher.setRenderShadow(false)
            val flames = WillfaerFlameVolume.mark()
            try {
                dispatcher.render(entity, 0.0, 0.0, 0.0, ENTITY_YAW, 1f, pose, source, LightTexture.FULL_BRIGHT)
                // Renderers queue flames in world space; the pose moves the drawn entity down by half its height.
                WillfaerFlameVolume.shift(flames, -entity.x, -entity.y - entity.bbHeight / 2.0, -entity.z)
            } catch (e: RuntimeException) {
                if (failed.add(entity.type)) LOG.warn("Sketch skips the renderer of {}", entity.type, e)
            } finally {
                dispatcher.setRenderShadow(true)
                pose.popTo(base)
            }
        }
    }

    private class StructureScene(private val template: StructureTemplate) : SketchScene() {
        private val blocks = (template as StructureTemplateAccessor).`enderkinesis$getPalettes`()
            .firstOrNull()?.blocks().orEmpty().take(MAX_STRUCTURE_BLOCKS)

        override val extentHint: Float get() = structureExtent(template.size)

        override fun draw(pose: PoseStack, source: MultiBufferSource, normals: Boolean) {
            val size = template.size
            for (info in blocks) {
                val state = finalState(info.state(), info.nbt()) ?: continue
                drawStateAt(
                    state, info.nbt(), pose, source, normals,
                    info.pos().x - size.x / 2.0, info.pos().y - size.y / 2.0, info.pos().z - size.z / 2.0,
                )
            }
        }
    }

    /**
     * A stored or local plan, one part for each non-empty section, so a staged bake draws it a section at a time. A
     * part is ready once its section is here; the check holds it, so a cache eviction cannot drop it before the draw.
     */
    private class PlanScene(private val sections: PlanSections) : SketchScene() {
        private var heldIndex = -1
        private var held: PlanSection? = null

        override val extentHint: Float get() = structureExtent(sections.size)
        override val firstView: SketchView by lazy(LazyThreadSafetyMode.NONE) { planView(sections, viewPitch, viewYaw) }
        override val parts: Int get() = sections.nonEmptyCount
        override val partBatch: Int get() = 1

        override fun partsReady(from: Int, to: Int): Boolean {
            if (from >= to) return true
            // Asking a few sections ahead keeps the fetches going while this one draws.
            for (ahead in from + 1 until minOf(parts, from + PREFETCH + 1)) sections.sectionAt(sections.nonEmptyIndex(ahead))
            val index = sections.nonEmptyIndex(from)
            if (heldIndex != index) {
                held = sections.sectionAt(index)
                heldIndex = if (held == null) -1 else index
            }
            return held != null
        }

        override fun draw(pose: PoseStack, source: MultiBufferSource, normals: Boolean) =
            drawPart(pose, source, normals, 0, parts)

        override fun drawPart(pose: PoseStack, source: MultiBufferSource, normals: Boolean, from: Int, to: Int) {
            val size = sections.size
            for (i in from until to) {
                val index = sections.nonEmptyIndex(i)
                val section = (if (index == heldIndex) held else sections.sectionAt(index)) ?: continue
                val origin = sections.grid.origin(index)
                section.forEachNonAir { x, y, z, planState ->
                    val state = finalState(planState, null) ?: return@forEachNonAir
                    drawStateAt(
                        state, null, pose, source, normals,
                        origin.x + x - size.x / 2.0, origin.y + y - size.y / 2.0, origin.z + z - size.z / 2.0,
                    )
                }
            }
        }
    }

    /**
     * A plan too large to keep its sections in the client cache over every pass, at reduced detail: each cube of
     * [CoarsePlanCells.factor] blocks draws as one scaled block, the topmost block in it. The sections are read once, a
     * few in each step, before the first draw; a part is one layer of cells.
     */
    private class CoarsePlanScene(private val sections: PlanSections) : SketchScene() {
        private val cells = CoarsePlanCells<BlockState>(sections.size.x, sections.size.y, sections.size.z, COARSE_EDGE)
        private val faceSteps = PlanAxes.IDENTITY.localFaceSteps()
        private var gathered = 0

        override val extentHint: Float get() = structureExtent(sections.size)
        override val firstView: SketchView by lazy(LazyThreadSafetyMode.NONE) { planView(sections, viewPitch, viewYaw) }
        override val parts: Int get() = cells.countY
        override val partBatch: Int get() = COARSE_LAYER_BATCH

        override fun partsReady(from: Int, to: Int): Boolean {
            val count = sections.nonEmptyCount
            val end = minOf(count, gathered + GATHER_PER_STEP)
            for (ahead in gathered until minOf(count, gathered + PREFETCH)) sections.sectionAt(sections.nonEmptyIndex(ahead))
            while (gathered < end) {
                val index = sections.nonEmptyIndex(gathered)
                val section = sections.sectionAt(index) ?: return false
                gather(section, sections.grid.origin(index))
                gathered++
            }
            return gathered == count
        }

        // Sections come bottom-up and cells y-last, so the last block put into a cell is its topmost one.
        private fun gather(section: PlanSection, origin: BlockPos) {
            section.forEachNonAir { x, y, z, planState ->
                val state = finalState(planState, null) ?: return@forEachNonAir
                cells.put(origin.x + x, origin.y + y, origin.z + z, state)
            }
        }

        override fun draw(pose: PoseStack, source: MultiBufferSource, normals: Boolean) =
            drawPart(pose, source, normals, 0, parts)

        override fun drawPart(pose: PoseStack, source: MultiBufferSource, normals: Boolean, from: Int, to: Int) {
            val size = sections.size
            val factor = cells.factor
            for (cy in from until to) {
                for (cz in 0 until cells.countZ) {
                    for (cx in 0 until cells.countX) {
                        val state = cells.get(cx, cy, cz) ?: continue
                        val hidden = if (!hidesSharedFaces(state)) 0
                        else ghostHiddenFaces(cx, cy, cz, faceSteps) { x, y, z -> cells.get(x, y, z)?.let { hidesSharedFaces(it) } == true }
                        val faces = ALL_FACES and hidden.inv()
                        if (faces == 0) continue
                        drawStateAt(
                            state, null, pose, source, normals,
                            cx * factor - size.x / 2.0, cy * factor - size.y / 2.0, cz * factor - size.z / 2.0,
                            faces = faces, scale = factor.toFloat(),
                        )
                    }
                }
            }
        }
    }

    /**
     * A view rebuilt from the blocks and entities that showed in a viewfinder frame, drawn through the frame's own
     * [camera]; offsets are from the eye. Each one's fade goes into its alpha, which the sketch reads as coverage. In
     * the normals pass the vertex red flags ground blocks, so the sketch can put ground marks under all their edges.
     */
    private class ViewScene(
        private val blocks: List<ViewBlock>,
        private val entities: List<ViewEntity>,
        override val camera: SceneCamera,
    ) : SketchScene() {
        override val extentHint = BLOCK_EXTENT

        // One part for each block, then one for all the entities.
        override val parts: Int get() = blocks.size + 1

        override fun draw(pose: PoseStack, source: MultiBufferSource, normals: Boolean) =
            drawPart(pose, source, normals, 0, parts)

        override fun drawPart(pose: PoseStack, source: MultiBufferSource, normals: Boolean, from: Int, to: Int) {
            val now = FadeState()
            // A renderer may hold two buffers at once, so each gets its own wrapper; it reads the state of the moment.
            val faded = MultiBufferSource { type -> Fading(source.getBuffer(type), normals, now) }
            for (i in from until minOf(to, blocks.size)) {
                val block = blocks[i]
                now.fade = block.fade
                now.ground = block.ground
                drawStateAt(block.state, block.nbt, pose, faded, normals, block.offset.x, block.offset.y, block.offset.z, block.tint, block.faces)
                if (!block.state.fluidState.isEmpty) drawFluid(block, pose, faded)
            }
            if (to <= blocks.size) return
            now.ground = false
            val dispatcher = Minecraft.getInstance().entityRenderDispatcher
            val base = pose.last()
            dispatcher.setRenderShadow(false)
            try {
                for (view in entities) {
                    val entity = view.entity
                    now.fade = view.fade
                    val flames = WillfaerFlameVolume.mark()
                    try {
                        dispatcher.render(
                            entity, view.offset.x, view.offset.y, view.offset.z, entity.yRot, 1f, pose, faded,
                            LightTexture.FULL_BRIGHT,
                        )
                        // Renderers queue flames in world space at the current position; move them to the drawn feet.
                        WillfaerFlameVolume.shift(
                            flames, view.offset.x - entity.x, view.offset.y - entity.y, view.offset.z - entity.z,
                        )
                    } catch (e: RuntimeException) {
                        pose.popTo(base)
                        if (failed.add(entity.type)) LOG.warn("Sketch skips the renderer of {}", entity.type, e)
                    }
                }
            } finally {
                dispatcher.setRenderShadow(true)
            }
        }
    }

    private class FadeState {
        var fade = 1f
        var ground = false
    }

    /** Scales vertex alpha by the fade of [now]; in the normals pass, red carries its ground flag instead of the tint. */
    private class Fading(
        private val inner: VertexConsumer,
        private val normals: Boolean,
        private val now: FadeState,
    ) : VertexConsumer {
        override fun vertex(x: Double, y: Double, z: Double): VertexConsumer = apply { inner.vertex(x, y, z) }
        override fun color(r: Int, g: Int, b: Int, a: Int): VertexConsumer =
            apply { inner.color(red(r), g, b, (a * now.fade).toInt()) }
        override fun uv(u: Float, v: Float): VertexConsumer = apply { inner.uv(u, v) }
        override fun overlayCoords(u: Int, v: Int): VertexConsumer = apply { inner.overlayCoords(u, v) }
        override fun uv2(u: Int, v: Int): VertexConsumer = apply { inner.uv2(u, v) }
        override fun normal(x: Float, y: Float, z: Float): VertexConsumer = apply { inner.normal(x, y, z) }
        override fun endVertex() = inner.endVertex()
        override fun defaultColor(r: Int, g: Int, b: Int, a: Int) = inner.defaultColor(red(r), g, b, (a * now.fade).toInt())
        override fun unsetDefaultColor() = inner.unsetDefaultColor()

        // strike_normals reads red as the ground flag; the sketch takes above 0.75 as ground.
        private fun red(r: Int): Int = if (!normals) r else if (now.ground) GROUND_RED else OTHER_RED
    }

    /**
     * A block of a view; [pos] is its place in the level and [offset] its minimum corner. [fade] scales its coverage
     * from 0 to 1, and [faces] has bit `Direction.ordinal` set for each face the level draws.
     */
    @Suppress("LongParameterList") // One block's state as the viewfinder saw it.
    class ViewBlock(
        val pos: BlockPos, val offset: Vec3, val state: BlockState, val nbt: CompoundTag?, val fade: Float, val ground: Boolean,
        val faces: Int, val tint: Int,
    )

    /**
     * LiquidBlockRenderer output, made to fit an entity render type: it writes section-local positions with no pose, no
     * overlay and the level's light. The pose moves it to [x], [y], [z] less the section offset, and the light is full,
     * as for every other part of a sketch.
     */
    private class FluidVertices(
        private val inner: VertexConsumer,
        private val pose: PoseStack.Pose,
        private val x: Float,
        private val y: Float,
        private val z: Float,
    ) : VertexConsumer {
        private val position = Vector3f()
        private val normal = Vector3f()

        override fun vertex(x: Double, y: Double, z: Double): VertexConsumer = apply {
            pose.pose().transformPosition(x.toFloat() + this.x, y.toFloat() + this.y, z.toFloat() + this.z, position)
            inner.vertex(position.x.toDouble(), position.y.toDouble(), position.z.toDouble())
        }
        override fun color(r: Int, g: Int, b: Int, a: Int): VertexConsumer = apply { inner.color(r, g, b, a) }
        override fun uv(u: Float, v: Float): VertexConsumer = apply { inner.uv(u, v).overlayCoords(OverlayTexture.NO_OVERLAY) }
        override fun overlayCoords(u: Int, v: Int): VertexConsumer = this
        override fun uv2(u: Int, v: Int): VertexConsumer = apply { inner.uv2(LightTexture.FULL_BRIGHT) }
        override fun normal(x: Float, y: Float, z: Float): VertexConsumer = apply {
            pose.normal().transform(normal.set(x, y, z))
            inner.normal(normal.x, normal.y, normal.z)
        }
        override fun endVertex() = inner.endVertex()
        override fun defaultColor(r: Int, g: Int, b: Int, a: Int) = inner.defaultColor(r, g, b, a)
        override fun unsetDefaultColor() = inner.unsetDefaultColor()
    }

    /** A live entity of a view; [offset] is its feet. */
    class ViewEntity(val offset: Vec3, val entity: Entity, val fade: Float)

    companion object {
        private val LOG = LogUtils.getLogger()

        // Render thread only: warn once for each block or entity type.
        private val failed = HashSet<Any>()

        // Render thread only.
        private val modelRandom = RandomSource.create()
        private const val MODEL_SEED = 42L
        private const val ALL_FACES = 0x3F
        private const val SECTION_MASK = 15

        // Half of a unit cube's widest isometric span (about 0.79), with a margin for the outline and wobble.
        private const val BLOCK_EXTENT = 0.9f
        private const val ENTITY_EXTENT_PER_SIZE = 0.75f
        private const val ISO_HALF_ACROSS = 0.37f
        private const val ISO_HALF_TALL = 0.45f
        private const val ISO_HALF_DEPTH = 0.19f
        private const val MAX_STRUCTURE_BLOCKS = 32768
        private const val DEFAULT_PART_BATCH = 256
        private const val PREFETCH = 8

        // A plan above a quarter of the client section cache would read its sections again for each bake pass.
        private const val HUGE_PLAN_SECTIONS = ClientPlans.SECTIONS / 4
        private const val COARSE_EDGE = 64
        private const val COARSE_LAYER_BATCH = 4
        private const val GATHER_PER_STEP = 32
        private const val HALF_ALPHA = 128
        private const val GROUND_RED = 255
        private const val OTHER_RED = 128

        // Relative to StrikeSketch.ISO_YAW: +90 is a left-facing profile; +45 turns 45° of the front to the viewer.
        private const val ENTITY_YAW = StrikeSketch.ISO_YAW + 45f
        private const val UNTINTED = -1
        private const val ORB_RADIUS = 1.5f
        // Mid grey, so the sphere shades as a ball instead of falling into the paper-white light family.
        private const val ORB_TONE = 0.6f
        private const val ORB_STACKS = 12
        private const val ORB_SLICES = 24
        private const val HALF = 0.5f
        // (stack, slice) offsets of each quad's corners; the render type does not cull, so winding does not matter.
        private val ORB_QUAD = listOf(0 to 0, 0 to 1, 1 to 1, 1 to 0)
        private const val CHANNEL_MAX = 255f

        // Fluids are translucent and write depth, so they draw after every solid block, or the bed behind them is lost.
        fun ofCamera(blocks: List<ViewBlock>, entities: List<ViewEntity>, camera: SceneCamera): SketchScene =
            ViewScene(blocks.take(MAX_STRUCTURE_BLOCKS).sortedBy { !it.state.fluidState.isEmpty }, entities, camera)

        /** The block's fluid, as the level draws it: biome colour, and only the sides that meet no fluid or solid face. */
        private fun drawFluid(block: ViewBlock, pose: PoseStack, source: MultiBufferSource) {
            val level = Minecraft.getInstance().level ?: return
            val pos = block.pos
            val consumer = FluidVertices(
                source.getBuffer(RenderType.entityTranslucentCull(InventoryMenu.BLOCK_ATLAS)), pose.last(),
                (block.offset.x - (pos.x and SECTION_MASK)).toFloat(),
                (block.offset.y - (pos.y and SECTION_MASK)).toFloat(),
                (block.offset.z - (pos.z and SECTION_MASK)).toFloat(),
            )
            try {
                Minecraft.getInstance().blockRenderer.renderLiquid(pos, level, consumer, block.state, block.state.fluidState)
            } catch (e: RuntimeException) {
                if (failed.add(block.state.fluidState.type)) LOG.warn("Sketch skips the fluid {}", block.state.fluidState, e)
            }
        }

        /**
         * Every part of the block that [state] is a part of, placed as the game places them, so that either half of a
         * door, a tall flower, a bed or a double chest sketches the whole.
         */
        private fun wholeBlock(state: BlockState): List<Pair<BlockPos, BlockState>> = when {
            state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) -> listOf(
                BlockPos.ZERO to state.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER),
                BlockPos.ZERO.above() to state.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER),
            )
            state.hasProperty(BlockStateProperties.BED_PART) && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING) -> listOf(
                BlockPos.ZERO to state.setValue(BlockStateProperties.BED_PART, BedPart.FOOT),
                BlockPos.ZERO.relative(state.getValue(BlockStateProperties.HORIZONTAL_FACING)) to
                    state.setValue(BlockStateProperties.BED_PART, BedPart.HEAD),
            )
            state.block is ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE -> listOf(
                BlockPos.ZERO to state,
                BlockPos.ZERO.relative(ChestBlock.getConnectedDirection(state)) to
                    state.setValue(ChestBlock.TYPE, state.getValue(ChestBlock.TYPE).opposite),
            )
            else -> listOf(BlockPos.ZERO to state)
        }

        /** Null when the subject cannot draw here: no client level for an entity, or an unknown structure. */
        fun of(subject: SketchSubject): SketchScene? = when (subject) {
            is SketchSubject.Block -> BlockScene(subject.state)
            is SketchSubject.Entity -> createEntity(subject.type, subject.nbt)?.let { EntityScene(it) }
            is SketchSubject.Structure -> loadStructure(subject.id)?.let { StructureScene(it) }
            is SketchSubject.Picture, is SketchSubject.BlockTag -> null
        }

        /** Plans with more non-empty sections than [HUGE_PLAN_SECTIONS] sketch at reduced detail. */
        fun ofPlan(sections: PlanSections): SketchScene =
            if (sections.nonEmptyCount > HUGE_PLAN_SECTIONS) CoarsePlanScene(sections) else PlanScene(sections)

        /** The first fit view from the box of the plan's non-empty sections, in scene units. */
        private fun planView(sections: PlanSections, pitch: Float, yaw: Float): SketchView {
            val grid = sections.grid
            var minX = Int.MAX_VALUE
            var minY = Int.MAX_VALUE
            var minZ = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE
            var maxY = Int.MIN_VALUE
            var maxZ = Int.MIN_VALUE
            for (i in 0 until sections.nonEmptyCount) {
                val index = sections.nonEmptyIndex(i)
                minX = minOf(minX, grid.sectionX(index))
                minY = minOf(minY, grid.sectionY(index))
                minZ = minOf(minZ, grid.sectionZ(index))
                maxX = maxOf(maxX, grid.sectionX(index))
                maxY = maxOf(maxY, grid.sectionY(index))
                maxZ = maxOf(maxZ, grid.sectionZ(index))
            }
            val size = sections.size
            if (minX > maxX) return SketchView(0f, 0f, structureExtent(size))
            return boxView(
                Vector3f(sectionEdge(minX, 0, size.x), sectionEdge(minY, 0, size.y), sectionEdge(minZ, 0, size.z)),
                Vector3f(sectionEdge(maxX, 1, size.x), sectionEdge(maxY, 1, size.y), sectionEdge(maxZ, 1, size.z)),
                pitch, yaw,
            )
        }

        /** The scene coordinate of the near ([end] 0) or far ([end] 1) edge of a section, centred as the plan draws. */
        private fun sectionEdge(section: Int, end: Int, size: Int): Float =
            minOf((section + end) * PLAN_SECTION_EDGE, size) - size / 2f

        private fun structureExtent(size: Vec3i): Float {
            val across = (size.x + size.z) * ISO_HALF_ACROSS
            val tall = size.y * ISO_HALF_TALL + (size.x + size.z) * ISO_HALF_DEPTH
            return max(BLOCK_EXTENT, max(across, tall))
        }

        // Jigsaw blocks become their final state when the structure places; structure voids and data markers place nothing.
        private fun finalState(state: BlockState, nbt: CompoundTag?): BlockState? {
            if (state.isAir || state.`is`(Blocks.STRUCTURE_VOID) || state.`is`(Blocks.STRUCTURE_BLOCK)) return null
            if (!state.`is`(Blocks.JIGSAW)) return state
            val text = nbt?.getString("final_state")?.ifEmpty { null } ?: return null
            return try {
                BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(), text, false).blockState()
                    .takeUnless { it.isAir }
            } catch (_: CommandSyntaxException) {
                null
            }
        }

        private fun createEntity(type: EntityType<*>, nbt: CompoundTag?): Entity? {
            val level = Minecraft.getInstance().level ?: return null
            val tag = nbt?.copy() ?: CompoundTag()
            tag.putString("id", BuiltInRegistries.ENTITY_TYPE.getKey(type).toString())
            val entity = try {
                EntityType.create(tag, level).orElse(null)
            } catch (e: RuntimeException) {
                LOG.warn("Sketch cannot create {}", type, e)
                null
            } ?: return null
            entity.yRot = ENTITY_YAW
            entity.yRotO = ENTITY_YAW
            entity.xRot = 0f
            if (entity is LivingEntity) {
                entity.yBodyRot = ENTITY_YAW
                entity.yBodyRotO = ENTITY_YAW
                entity.yHeadRot = ENTITY_YAW
                entity.yHeadRotO = ENTITY_YAW
            }
            return entity
        }

        /** The integrated server sees datapacks; otherwise the template comes from the jars on the classpath. */
        private fun loadStructure(id: ResourceLocation): StructureTemplate? {
            try {
                Minecraft.getInstance().singleplayerServer?.structureManager?.get(id)?.orElse(null)?.let { return it }
                val path = "/data/${id.namespace}/structures/${id.path}.nbt"
                val stream = SketchScene::class.java.getResourceAsStream(path)
                    ?: Minecraft::class.java.getResourceAsStream(path)
                    ?: return null
                return stream.use { input ->
                    StructureTemplate().apply { load(BuiltInRegistries.BLOCK.asLookup(), NbtIo.readCompressed(input)) }
                }
            } catch (e: IOException) {
                LOG.warn("Sketch cannot read structure {}", id, e)
            } catch (e: RuntimeException) {
                LOG.warn("Sketch cannot read structure {}", id, e)
            }
            return null
        }

        /** Draws [state] with its minimum corner at ([x], [y], [z]). */
        @Suppress("LongParameterList") // A position and tint on top of drawState's parameters.
        private fun drawStateAt(
            state: BlockState, nbt: CompoundTag?, pose: PoseStack, source: MultiBufferSource, normals: Boolean,
            x: Double, y: Double, z: Double,
            // Without a level and position, grass and foliage colours fall back to a fixed default, not the biome's.
            tint: Int = Minecraft.getInstance().blockColors.getColor(state, null, null, 0),
            faces: Int = ALL_FACES,
            scale: Float = 1f,
        ) {
            pose.pushPose()
            pose.translate(x, y, z)
            if (scale != 1f) pose.scale(scale, scale, scale)
            val flames = WillfaerFlameVolume.mark()
            drawState(state, nbt, pose, source, normals, tint, faces)
            // Block entity renderers queue flames in world space, and the detached block entity sits at the origin.
            WillfaerFlameVolume.shift(flames, x, y, z)
            pose.popPose()
        }

        @Suppress("LongParameterList") // A block's state, data, tint and drawn faces, and where to draw it.
        private fun drawState(
            state: BlockState, nbt: CompoundTag?, pose: PoseStack, source: MultiBufferSource, normals: Boolean, tint: Int, faces: Int,
        ) {
            if (state.`is`(EKBlocks.FRACTAL_PROJECTOR.get())) {
                drawOrb(pose, source, normals, state.getValue(FractalProjectorBlock.FRACTAL_TYPE))
                return
            }
            val minecraft = Minecraft.getInstance()
            val blocks = minecraft.blockRenderer
            when (state.renderShape) {
                RenderShape.MODEL -> {
                    drawModel(state, blocks.getBlockModel(state), pose, source.getBuffer(ItemBlockRenderTypes.getRenderType(state, true)), tint, faces)
                    drawBlockEntity(state, nbt, pose, source)
                }
                RenderShape.ENTITYBLOCK_ANIMATED -> if (!drawBlockEntity(state, nbt, pose, source)) {
                    blocks.renderSingleBlock(state, pose, source, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY)
                }
                else -> drawBlockEntity(state, nbt, pose, source)
            }
        }

        /** As ModelBlockRenderer.renderModel, but only the quads of [faces] and those that face no side. */
        @Suppress("LongParameterList") // renderModel's parameters, with the faces to draw.
        private fun drawModel(state: BlockState, model: BakedModel, pose: PoseStack, consumer: VertexConsumer, tint: Int, faces: Int) {
            val red = channel(tint, FastColor.ARGB32::red)
            val green = channel(tint, FastColor.ARGB32::green)
            val blue = channel(tint, FastColor.ARGB32::blue)
            for (direction in Direction.entries) {
                if (faces and (1 shl direction.ordinal) == 0) continue
                // renderModel seeds 42 before each quad list, so random models pick the same variants.
                modelRandom.setSeed(MODEL_SEED)
                drawQuads(model.getQuads(state, direction, modelRandom), pose, consumer, red, green, blue)
            }
            modelRandom.setSeed(MODEL_SEED)
            drawQuads(model.getQuads(state, null, modelRandom), pose, consumer, red, green, blue)
        }

        @Suppress("LongParameterList") // A quad list and its tint.
        private fun drawQuads(quads: List<BakedQuad>, pose: PoseStack, consumer: VertexConsumer, red: Float, green: Float, blue: Float) {
            for (quad in quads) {
                val tinted = quad.isTinted
                consumer.putBulkData(
                    pose.last(), quad, if (tinted) red else 1f, if (tinted) green else 1f, if (tinted) blue else 1f,
                    LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY,
                )
            }
        }

        /** Runs the block's own BlockEntityRenderer on a detached block entity. False when there is none to run. */
        private fun drawBlockEntity(state: BlockState, nbt: CompoundTag?, pose: PoseStack, source: MultiBufferSource): Boolean {
            val block = state.block as? EntityBlock ?: return false
            val minecraft = Minecraft.getInstance()
            val entity = block.newBlockEntity(BlockPos.ZERO, state) ?: return false
            // Set the field, not setLevel: setLevel overrides can register the block entity in the world.
            minecraft.level?.let { (entity as BlockEntityLevelAccessor).`enderkinesis$setLevel`(it) }
            val renderer = minecraft.blockEntityRenderDispatcher.getRenderer(entity) ?: return false
            val base = pose.last()
            pose.pushPose()
            try {
                nbt?.let { entity.load(it) }
                renderer.render(entity, 0f, pose, source, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY)
            } catch (e: RuntimeException) {
                // Any mod's renderer can assume a real world position; skip its part and keep the rest of the sketch.
                if (failed.add(state.block)) LOG.warn("Sketch skips the block entity renderer of {}", state, e)
                return false
            } finally {
                pose.popTo(base)
            }
            return true
        }

        // A renderer that throws can leave its own pushes on the stack; without this, the next blocks draw offset.
        private fun PoseStack.popTo(base: PoseStack.Pose) {
            while (last() !== base && !clear()) popPose()
        }

        // The projector is a worldgen marker: it spawns an Orb of Potential and removes itself, so the sketch shows the orb.
        // The normals pass and a missing shader use a plain sphere; the lit pass draws the real fractal.
        private fun drawOrb(pose: PoseStack, source: MultiBufferSource, normals: Boolean, fractalType: Int) {
            if (!normals && FractalProjectorRenderTypes.isReady()) {
                drawFractalOrb(pose, source, fractalType)
                return
            }
            val consumer = source.getBuffer(RenderType.entityCutoutNoCull(StrikeSketchRenderTypes.white))
            val last = pose.last()
            for (stack in 0 until ORB_STACKS) {
                for (slice in 0 until ORB_SLICES) {
                    for ((ds, dt) in ORB_QUAD) orbVertex(consumer, last, stack + ds, slice + dt)
                }
            }
        }

        private fun drawFractalOrb(pose: PoseStack, source: MultiBufferSource, fractalType: Int) {
            pose.pushPose()
            pose.translate(HALF, HALF, HALF)
            val viewToWorld = Matrix3f(pose.last().pose()).transpose()
            // An orthographic projection has 1 in its last element; a perspective has 0.
            val orthographic = RenderSystem.getProjectionMatrix().m33() == 1f
            OrbOfPotentialRenderer.drawSketchSphere(
                source.getBuffer(FractalProjectorRenderTypes.FRACTAL_SPHERE), pose.last().pose(), viewToWorld, orthographic,
                fractalType,
            )
            pose.popPose()
        }

        private fun orbVertex(consumer: VertexConsumer, last: PoseStack.Pose, stack: Int, slice: Int) {
            val polar = Math.PI * stack / ORB_STACKS
            val azimuth = 2.0 * Math.PI * slice / ORB_SLICES
            val nx = (sin(polar) * cos(azimuth)).toFloat()
            val ny = cos(polar).toFloat()
            val nz = (sin(polar) * sin(azimuth)).toFloat()
            consumer.vertex(last.pose(), HALF + nx * ORB_RADIUS, HALF + ny * ORB_RADIUS, HALF + nz * ORB_RADIUS)
                .color(ORB_TONE, ORB_TONE, ORB_TONE, 1f).uv(0f, 0f).overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(LightTexture.FULL_BRIGHT).normal(last.normal(), nx, ny, nz).endVertex()
        }


        private fun channel(tint: Int, part: (Int) -> Int): Float =
            if (tint == UNTINTED) 1f else part(tint) / CHANNEL_MAX
    }
}
