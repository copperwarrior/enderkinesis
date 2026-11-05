package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.brigadier.exceptions.CommandSyntaxException
import com.mojang.logging.LogUtils
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.ItemBlockRenderTypes
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.commands.arguments.blocks.BlockStateParser
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtIo
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.FastColor
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BedPart
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.ChestType
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
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

    /** The yaw the sketch looks along. */
    open val viewYaw: Float get() = StrikeSketch.ISO_YAW

    /** How far down the sketch looks. */
    open val viewPitch: Float get() = StrikeSketch.ISO_PITCH

    /** The least alpha that the fit holds in frame; fainter parts may run off the edge. */
    open val fitAlpha: Int get() = HALF_ALPHA

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
                pose.pushPose()
                pose.translate(offset.x - centreX, offset.y - centreY, offset.z - centreZ)
                drawState(part, null, pose, source)
                pose.popPose()
            }
        }
    }

    private class EntityScene(private val entity: Entity) : SketchScene() {
        override val extentHint = max(BLOCK_EXTENT, max(entity.bbWidth, entity.bbHeight) * ENTITY_EXTENT_PER_SIZE)

        override fun draw(pose: PoseStack, source: MultiBufferSource, normals: Boolean) {
            val dispatcher = Minecraft.getInstance().entityRenderDispatcher
            pose.pushPose()
            pose.translate(0f, -entity.bbHeight / 2f, 0f)
            // As InventoryScreen does: a shadow would sample the world at the origin.
            dispatcher.setRenderShadow(false)
            try {
                dispatcher.render(entity, 0.0, 0.0, 0.0, ENTITY_YAW, 1f, pose, source, LightTexture.FULL_BRIGHT)
            } catch (e: RuntimeException) {
                if (failed.add(entity.type)) LOG.warn("Sketch skips the renderer of {}", entity.type, e)
            } finally {
                dispatcher.setRenderShadow(true)
                pose.popPose()
            }
        }
    }

    private class StructureScene(private val template: StructureTemplate) : SketchScene() {
        private val blocks = (template as StructureTemplateAccessor).`enderkinesis$getPalettes`()
            .firstOrNull()?.blocks().orEmpty().take(MAX_STRUCTURE_BLOCKS)

        override val extentHint: Float
            get() {
                val size = template.size
                val across = (size.x + size.z) * ISO_HALF_ACROSS
                val tall = size.y * ISO_HALF_TALL + (size.x + size.z) * ISO_HALF_DEPTH
                return max(BLOCK_EXTENT, max(across, tall))
            }

        override fun draw(pose: PoseStack, source: MultiBufferSource, normals: Boolean) {
            val size = template.size
            pose.pushPose()
            pose.translate(-size.x / 2f, -size.y / 2f, -size.z / 2f)
            for (info in blocks) {
                val state = finalState(info.state(), info.nbt()) ?: continue
                pose.pushPose()
                pose.translate(info.pos().x.toFloat(), info.pos().y.toFloat(), info.pos().z.toFloat())
                drawState(state, info.nbt(), pose, source)
                pose.popPose()
            }
            pose.popPose()
        }

        // Jigsaw blocks become their final state when the structure places; structure voids place nothing.
        private fun finalState(state: BlockState, nbt: CompoundTag?): BlockState? {
            if (state.isAir || state.`is`(Blocks.STRUCTURE_VOID)) return null
            if (!state.`is`(Blocks.JIGSAW)) return state
            val text = nbt?.getString("final_state")?.ifEmpty { null } ?: return null
            return try {
                BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(), text, false).blockState()
                    .takeUnless { it.isAir }
            } catch (_: CommandSyntaxException) {
                null
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
            var fade = 1f
            var ground = false
            // A renderer may hold two buffers at once, so each gets its own wrapper; it reads the state of the moment.
            val faded = MultiBufferSource { type -> Fading(source.getBuffer(type), normals, { fade }, { ground }) }
            for (i in from until minOf(to, blocks.size)) {
                val block = blocks[i]
                fade = block.fade
                ground = block.ground
                pose.pushPose()
                pose.translate(block.offset.x, block.offset.y, block.offset.z)
                drawState(block.state, block.nbt, pose, faded)
                pose.popPose()
            }
            if (to <= blocks.size) return
            ground = false
            val dispatcher = Minecraft.getInstance().entityRenderDispatcher
            dispatcher.setRenderShadow(false)
            try {
                for (view in entities) {
                    val entity = view.entity
                    fade = view.fade
                    try {
                        dispatcher.render(
                            entity, view.offset.x, view.offset.y, view.offset.z, entity.yRot, 1f, pose, faded,
                            LightTexture.FULL_BRIGHT,
                        )
                    } catch (e: RuntimeException) {
                        if (failed.add(entity.type)) LOG.warn("Sketch skips the renderer of {}", entity.type, e)
                    }
                }
            } finally {
                dispatcher.setRenderShadow(true)
            }
        }
    }

    /** Scales vertex alpha by [fade]; in the normals pass, red carries the [ground] flag instead of the tint. */
    private class Fading(
        private val inner: VertexConsumer,
        private val normals: Boolean,
        private val fade: () -> Float,
        private val ground: () -> Boolean,
    ) : VertexConsumer {
        override fun vertex(x: Double, y: Double, z: Double): VertexConsumer = apply { inner.vertex(x, y, z) }
        override fun color(r: Int, g: Int, b: Int, a: Int): VertexConsumer =
            apply { inner.color(red(r), g, b, (a * fade()).toInt()) }
        override fun uv(u: Float, v: Float): VertexConsumer = apply { inner.uv(u, v) }
        override fun overlayCoords(u: Int, v: Int): VertexConsumer = apply { inner.overlayCoords(u, v) }
        override fun uv2(u: Int, v: Int): VertexConsumer = apply { inner.uv2(u, v) }
        override fun normal(x: Float, y: Float, z: Float): VertexConsumer = apply { inner.normal(x, y, z) }
        override fun endVertex() = inner.endVertex()
        override fun defaultColor(r: Int, g: Int, b: Int, a: Int) = inner.defaultColor(red(r), g, b, (a * fade()).toInt())
        override fun unsetDefaultColor() = inner.unsetDefaultColor()

        // strike_normals reads red as the ground flag; the sketch takes above 0.75 as ground.
        private fun red(r: Int): Int = if (!normals) r else if (ground()) GROUND_RED else OTHER_RED
    }

    /** A block of a view; [offset] is its minimum corner, and [fade] scales its coverage from 0 to 1. */
    class ViewBlock(val offset: Vec3, val state: BlockState, val nbt: CompoundTag?, val fade: Float, val ground: Boolean)

    /** A live entity of a view; [offset] is its feet. */
    class ViewEntity(val offset: Vec3, val entity: Entity, val fade: Float)

    companion object {
        private val LOG = LogUtils.getLogger()

        // Render thread only: warn once for each block or entity type.
        private val failed = HashSet<Any>()

        // Half of a unit cube's widest isometric span (about 0.79), with a margin for the outline and wobble.
        private const val BLOCK_EXTENT = 0.9f
        private const val ENTITY_EXTENT_PER_SIZE = 0.75f
        private const val ISO_HALF_ACROSS = 0.37f
        private const val ISO_HALF_TALL = 0.45f
        private const val ISO_HALF_DEPTH = 0.19f
        private const val MAX_STRUCTURE_BLOCKS = 32768
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

        fun ofCamera(blocks: List<ViewBlock>, entities: List<ViewEntity>, camera: SceneCamera): SketchScene =
            ViewScene(blocks.take(MAX_STRUCTURE_BLOCKS), entities, camera)

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
            is SketchSubject.Picture -> null
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

        private fun drawState(state: BlockState, nbt: CompoundTag?, pose: PoseStack, source: MultiBufferSource) {
            if (state.`is`(EKBlocks.FRACTAL_PROJECTOR.get())) {
                drawOrb(pose, source)
                return
            }
            val minecraft = Minecraft.getInstance()
            val blocks = minecraft.blockRenderer
            when (state.renderShape) {
                RenderShape.MODEL -> {
                    val tint = minecraft.blockColors.getColor(state, null, null, 0)
                    blocks.modelRenderer.renderModel(
                        pose.last(), source.getBuffer(ItemBlockRenderTypes.getRenderType(state, true)), state,
                        blocks.getBlockModel(state),
                        channel(tint, FastColor.ARGB32::red), channel(tint, FastColor.ARGB32::green),
                        channel(tint, FastColor.ARGB32::blue),
                        LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY,
                    )
                    drawBlockEntity(state, nbt, pose, source)
                }
                RenderShape.ENTITYBLOCK_ANIMATED -> if (!drawBlockEntity(state, nbt, pose, source)) {
                    blocks.renderSingleBlock(state, pose, source, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY)
                }
                else -> drawBlockEntity(state, nbt, pose, source)
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
            pose.pushPose()
            try {
                nbt?.let { entity.load(it) }
                renderer.render(entity, 0f, pose, source, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY)
            } catch (e: RuntimeException) {
                // Any mod's renderer can assume a real world position; skip its part and keep the rest of the sketch.
                if (failed.add(state.block)) LOG.warn("Sketch skips the block entity renderer of {}", state, e)
                return false
            } finally {
                pose.popPose()
            }
            return true
        }

        // The projector is a worldgen marker: it spawns an Orb of Potential and removes itself, so the sketch shows the orb.
        // OrbOfPotentialRenderer's fractal needs world-camera uniforms, so this is a plain lit sphere of the same radius.
        private fun drawOrb(pose: PoseStack, source: MultiBufferSource) {
            val consumer = source.getBuffer(RenderType.entityCutoutNoCull(StrikeSketchRenderTypes.white))
            val last = pose.last()
            for (stack in 0 until ORB_STACKS) {
                for (slice in 0 until ORB_SLICES) {
                    for ((ds, dt) in ORB_QUAD) orbVertex(consumer, last, stack + ds, slice + dt)
                }
            }
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
