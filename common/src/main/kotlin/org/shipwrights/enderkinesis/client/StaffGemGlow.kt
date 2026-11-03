package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.blaze3d.vertex.VertexFormat
import dev.architectury.event.events.client.ClientReloadShadersEvent
import net.minecraft.Util
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.ItemBlockRenderTypes
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderStateShard
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.ShaderInstance
import net.minecraft.client.renderer.block.model.BakedQuad
import net.minecraft.client.renderer.block.model.ItemOverrides
import net.minecraft.client.renderer.block.model.ItemTransforms
import net.minecraft.client.renderer.entity.ItemRenderer
import net.minecraft.client.resources.model.BakedModel
import net.minecraft.client.resources.model.SimpleBakedModel
import com.google.gson.JsonParser
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.inventory.InventoryMenu
import org.joml.Matrix4f
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceProvider
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import org.joml.Quaternionf
import org.joml.Vector3f
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.registry.EKItems
import java.util.EnumMap
import java.util.IdentityHashMap
import java.util.WeakHashMap

object StaffGemGlow {

    enum class Spin { NONE, SHAFT_AXIS, TUMBLE }

    /** Turns a [Spin.SHAFT_AXIS] gem away from its spin. [pose] maps the gem's model space to view space.
     *  Writes the gem's rotation about its centre into [out]; `out.rotationY(spinYaw)` keeps the plain spin.
     *  A [Spin.NONE] gem also calls it (spinYaw = 0), so the aim can see where the gem draws; its result is ignored. */
    fun interface Aim {
        fun rotation(
            stack: ItemStack, pose: Matrix4f, centerX: Float, centerY: Float, centerZ: Float, spinYaw: Float, out: Quaternionf,
        ): Quaternionf
    }

    /** [gemElement] names the model element that is the gem, for a model whose gem shares the body's texture. */
    class Style(
        color: Int,
        val spin: Spin = Spin.NONE,
        val dark: Boolean = false,
        val shaftPeriodMs: Long = SHAFT_PERIOD_MS,
        val gemElement: String? = null,
        val noCull: Boolean = false,
        val aim: Aim? = null,
    ) {
        val red = (color shr 16 and 0xFF) / 255f
        val green = (color shr 8 and 0xFF) / 255f
        val blue = (color and 0xFF) / 255f
    }

    class Gem internal constructor(
        val model: BakedModel,
        private val centerX: Float,
        private val centerY: Float,
        private val centerZ: Float,
        private val radius: Float,
    ) {
        /** [clockMs] sets the spin phase. A caller can advance it at a different rate. */
        fun spin(
            style: Style, poseStack: PoseStack, clockMs: Double = Util.getMillis().toDouble(), stack: ItemStack? = null,
        ) {
            val rotation = when (style.spin) {
                Spin.SHAFT_AXIS -> {
                    val spinYaw = turn(clockMs, style.shaftPeriodMs)
                    val aim = style.aim
                    if (aim != null && stack != null) {
                        aim.rotation(stack, poseStack.last().pose(), centerX, centerY, centerZ, spinYaw, scratchRotation)
                    } else {
                        scratchRotation.rotationY(spinYaw)
                    }
                }
                Spin.TUMBLE -> scratchRotation.rotationYXZ(
                    turn(clockMs, TUMBLE_Y_PERIOD_MS),
                    turn(clockMs, TUMBLE_X_PERIOD_MS),
                    turn(clockMs, TUMBLE_Z_PERIOD_MS),
                )
                Spin.NONE -> {
                    if (stack != null) style.aim?.rotation(stack, poseStack.last().pose(), centerX, centerY, centerZ, 0f, scratchRotation)
                    return
                }
            }
            poseStack.rotateAround(rotation, centerX, centerY, centerZ)
        }

        /** Returns the halo's render type, or null when the halo shader is not loaded. */
        fun queueHalo(style: Style, poseStack: PoseStack, buffers: MultiBufferSource): RenderType? {
            val shader = shaderInstance ?: return null
            shader.setSampler(DEPTH_SAMPLER, Minecraft.getInstance().mainRenderTarget.depthTextureId)
            val haloType = if (style.dark) DARK_HALO else ADDITIVE_HALO
            val haloConsumer = buffers.getBuffer(haloType)
            val matrix = poseStack.last().pose()
            // The pose result is view space. The vertex shader adds the UV offset there, so the quad faces the camera.
            val center = matrix.transformPosition(centerX, centerY, centerZ, scratchCenter)
            val rim = radius * HALO_RADIUS_FACTOR * matrix.getScale(scratchScale).x()
            haloVertex(haloConsumer, style, center, -rim, -rim)
            haloVertex(haloConsumer, style, center, rim, -rim)
            haloVertex(haloConsumer, style, center, rim, rim)
            haloVertex(haloConsumer, style, center, -rim, rim)
            return haloType
        }
    }

    class Parts internal constructor(val body: BakedModel, private val gem: Gem, private val style: Style) {
        val gemModel: BakedModel get() = gem.model

        fun spin(stack: ItemStack, poseStack: PoseStack) = gem.spin(style, poseStack, stack = stack)

        /** Draws the halo after the staff body. Returns the consumer to use for the gem. */
        fun emitHalo(
            stack: ItemStack, poseStack: PoseStack, buffers: MultiBufferSource, vertexConsumer: VertexConsumer,
        ): VertexConsumer {
            val itemBatchEnded = drawHalo(stack, poseStack, buffers)
            return when {
                style.noCull -> ItemRenderer.getFoilBufferDirect(buffers, NO_CULL_GEM, true, stack.hasFoil())
                // endBatch ended the item builder, so the caller's consumer is stale.
                itemBatchEnded -> ItemRenderer.getFoilBufferDirect(
                    buffers, ItemBlockRenderTypes.getRenderType(stack, true), true, stack.hasFoil(),
                )
                else -> vertexConsumer
            }
        }

        /** Returns true when it ended the item batch. */
        private fun drawHalo(stack: ItemStack, poseStack: PoseStack, buffers: MultiBufferSource): Boolean {
            if (shaderInstance == null) return false
            val source = buffers as? MultiBufferSource.BufferSource ?: return false
            // ItemRenderer.render uses this same type for an item that is not a BlockItem.
            // The halo shader reads the body depth, so the body must draw first.
            source.endBatch(ItemBlockRenderTypes.getRenderType(stack, true))
            // Draw the halo now: the gem draws later, on top, so the halo does not wash it out.
            gem.queueHalo(style, poseStack, source)?.let(source::endBatch)
            return true
        }
    }

    private fun haloVertex(
        vertexConsumer: VertexConsumer, style: Style, center: Vector3f,
        offsetX: Float, offsetY: Float,
    ) {
        vertexConsumer.vertex(center.x().toDouble(), center.y().toDouble(), center.z().toDouble())
            .uv(offsetX, offsetY)
            .color(style.red, style.green, style.blue, PEAK_ALPHA)
            .endVertex()
    }

    private var shaderInstance: ShaderInstance? = null

    // Same sheet as the item's own translucent type, without the back-face cull.
    private val NO_CULL_GEM: RenderType = RenderType.entityTranslucent(InventoryMenu.BLOCK_ATLAS)

    private val ADDITIVE_HALO = haloType("additive", RenderStateShard.LIGHTNING_TRANSPARENCY)

    // An additive halo cannot show a dark color.
    private val DARK_HALO = haloType("dark", RenderStateShard.TRANSLUCENT_TRANSPARENCY)

    private fun haloType(name: String, transparency: RenderStateShard.TransparencyStateShard): RenderType =
        RenderType.create(
            "${EnderkinesisMod.MOD_ID}_staff_gem_halo_$name",
            DefaultVertexFormat.POSITION_TEX_COLOR,
            VertexFormat.Mode.QUADS,
            256,
            false,
            true,
            RenderType.CompositeState.builder()
                .setShaderState(RenderStateShard.ShaderStateShard { shaderInstance })
                .setTransparencyState(transparency)
                // The shader does the depth occlusion, so that near geometry cuts the halo softly.
                .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
                .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                .setCullState(RenderStateShard.NO_CULL)
                .setOutputState(RenderStateShard.MAIN_TARGET)
                .createCompositeState(false),
        )

    fun init() {
        ClientReloadShadersEvent.EVENT.register { provider, sink ->
            sink.registerShader(
                ShaderInstance(
                    namespaced(provider, EnderkinesisMod.MOD_ID),
                    "rendertype_staff_gem_halo",
                    DefaultVertexFormat.POSITION_TEX_COLOR,
                )
            ) { instance -> shaderInstance = instance }
        }
    }

    // Same workaround as ScryingRenderTypes: ShaderInstance fails on a namespaced shader name.
    private fun namespaced(delegate: ResourceProvider, namespace: String) =
        ResourceProvider { loc ->
            if (loc.namespace == ResourceLocation.DEFAULT_NAMESPACE) {
                val attempt = delegate.getResource(ResourceLocation(namespace, loc.path))
                if (attempt.isPresent) attempt else delegate.getResource(loc)
            } else {
                delegate.getResource(loc)
            }
        }

    // Each color is the darkest gem texel that the staff model shows, so the halo is not brighter than the gem.
    private val styles: Map<Item, Style> by lazy {
        IdentityHashMap<Item, Style>().apply {
            put(EKItems.STAFF_OF_COMMAND.get(), Style(0x250002))
            put(EKItems.STAFF_OF_CONCEALMENT.get(), Style(0x2CE0D8, Spin.TUMBLE))
            put(EKItems.STAFF_OF_DENSITY.get(), Style(0x0C0E10, dark = true))
            put(EKItems.STAFF_OF_SCALES.get(), Style(0x2A6200))
            put(EKItems.STAFF_OF_FUSION.get(), Style(0x7B0AFB))
            put(EKItems.STAFF_OF_AEGIS.get(), Style(0x6B6B6B, Spin.SHAFT_AXIS))
            put(EKItems.STAFF_OF_SUNDERING.get(), Style(0x7E5014))
            // The skull is bone grey; its glow is the Sselith glyph yellow.
            put(
                EKItems.GRAND_STAFF_OF_THE_DEAD.get(),
                Style(
                    0xE6C333, Spin.SHAFT_AXIS, shaftPeriodMs = GRAND_STAFF_PERIOD_MS,
                    gemElement = "skull_emoji", noCull = true, aim = GrandStaffClient,
                ),
            )
            put(
                EKItems.GRAND_STAFF_OF_DREAD.get(),
                Style(
                    0x032620, dark = true, gemElement = "orb", aim = GrandStaffDreadClient,
                ),
            )
            put(EKItems.GRAND_STAFF_OF_DECAY.get(), Style(0x0485C3, gemElement = "gem", aim = GrandStaffDecayClient))
        }
    }

    // Weak keys: a resource reload replaces every BakedModel.
    private val partsByModel = WeakHashMap<BakedModel, Parts>()
    private val gemsByModel = WeakHashMap<BakedModel, Gem>()
    private val scratchRotation = Quaternionf()
    private val scratchCenter = Vector3f()
    private val scratchScale = Vector3f()

    @JvmStatic
    fun partsOf(stack: ItemStack, model: BakedModel): Parts? {
        val style = styles[stack.item] ?: return null
        return partsByModel.getOrPut(model) { split(model, style, stack.item) }
    }

    /** A whole model as one gem, for a renderer that keeps its gem in a separate model. */
    fun gemOf(model: BakedModel): Gem =
        gemsByModel.getOrPut(model) { gem(model, BakedItemModels.quadsOf(model)) }

    private fun split(model: BakedModel, style: Style, item: Item): Parts {
        val box = style.gemElement?.let { elementBox(item, it) }
        val (gemQuads, bodyQuads) = BakedItemModels.quadsOf(model).partition {
            if (box != null) return@partition centroidInside(it, box)
            val name = it.sprite.contents().name()
            name.namespace == EnderkinesisMod.MOD_ID && name.path.startsWith(GEM_SPRITE_PREFIX)
        }
        return Parts(partModel(model, bodyQuads), gem(partModel(model, gemQuads), gemQuads), style)
    }

    /** The named element's from/to box in baked units (block / 16), ignoring its rotation. Null when absent. */
    private fun elementBox(item: Item, element: String): FloatArray? {
        val id = BuiltInRegistries.ITEM.getKey(item)
        val location = ResourceLocation(id.namespace, "models/item/${id.path}.json")
        val resource = Minecraft.getInstance().resourceManager.getResource(location).orElse(null) ?: return null
        val json = resource.openAsReader().use { JsonParser.parseReader(it).asJsonObject }
        for (entry in json.getAsJsonArray("elements") ?: return null) {
            val obj = entry.asJsonObject
            if (obj.get("name")?.asString != element) continue
            val from = obj.getAsJsonArray("from")
            val to = obj.getAsJsonArray("to")
            return FloatArray(BOX_FLOATS) { i ->
                val axis = i % AXES
                (if (i < AXES) from[axis] else to[axis]).asFloat / MODEL_UNITS
            }
        }
        LOG.warn("Staff model {} has no element named {}", location, element)
        return null
    }

    private fun centroidInside(quad: BakedQuad, box: FloatArray): Boolean {
        val vertices = quad.vertices
        for (axis in 0 until AXES) {
            var sum = 0f
            for (i in 0 until VERTICES_PER_QUAD) sum += Float.fromBits(vertices[i * VERTEX_STRIDE + axis])
            val c = sum / VERTICES_PER_QUAD
            if (c < box[axis] - BOX_EPSILON || c > box[axis + AXES] + BOX_EPSILON) return false
        }
        return true
    }

    private fun gem(model: BakedModel, gemQuads: List<BakedQuad>): Gem {
        if (gemQuads.isEmpty()) return Gem(model, 0f, 0f, 0f, 0f)
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        for (quad in gemQuads) {
            val vertices = quad.vertices
            for (i in 0 until VERTICES_PER_QUAD) {
                val o = i * VERTEX_STRIDE
                val x = Float.fromBits(vertices[o])
                val y = Float.fromBits(vertices[o + 1])
                val z = Float.fromBits(vertices[o + 2])
                minX = minOf(minX, x); maxX = maxOf(maxX, x)
                minY = minOf(minY, y); maxY = maxOf(maxY, y)
                minZ = minOf(minZ, z); maxZ = maxOf(maxZ, z)
            }
        }
        return Gem(
            model,
            (minX + maxX) / 2f, (minY + maxY) / 2f, (minZ + maxZ) / 2f,
            Vector3f(maxX - minX, maxY - minY, maxZ - minZ).length() / 2f,
        )
    }

    private fun partModel(source: BakedModel, quads: List<BakedQuad>): BakedModel {
        // renderModelLists reads each direction's list without a null check.
        val culled = EnumMap<Direction, List<BakedQuad>>(Direction::class.java)
        for (dir in Direction.values()) culled[dir] = emptyList()
        return SimpleBakedModel(
            quads, culled,
            source.useAmbientOcclusion(), source.usesBlockLight(), source.isGui3d,
            source.particleIcon, ItemTransforms.NO_TRANSFORMS, ItemOverrides.EMPTY,
        )
    }

    private fun turn(clockMs: Double, periodMs: Long): Float =
        (clockMs % periodMs / periodMs).toFloat() * TAU

    private const val GEM_SPRITE_PREFIX = "item/ulder_"
    private const val VERTICES_PER_QUAD = 4
    // DefaultVertexFormat.BLOCK: 8 ints for each vertex.
    private const val VERTEX_STRIDE = 8
    private const val TAU = (Math.PI * 2.0).toFloat()
    private const val AXES = 3
    private const val BOX_FLOATS = 6
    private const val MODEL_UNITS = 16f
    private const val BOX_EPSILON = 0.01f
    private val LOG = com.mojang.logging.LogUtils.getLogger()

    private const val SHAFT_PERIOD_MS = 4000L
    private const val GRAND_STAFF_PERIOD_MS = 12000L
    private const val TUMBLE_Y_PERIOD_MS = 5000L
    private const val TUMBLE_X_PERIOD_MS = 7000L
    private const val TUMBLE_Z_PERIOD_MS = 11000L

    private const val HALO_RADIUS_FACTOR = 1.75f
    private const val PEAK_ALPHA = 1f
    private const val DEPTH_SAMPLER = "SceneDepth"
}
