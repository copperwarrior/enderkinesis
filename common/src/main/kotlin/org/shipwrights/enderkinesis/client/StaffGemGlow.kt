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
import net.minecraft.core.Direction
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

    private class Style(color: Int, val spin: Spin = Spin.NONE, val dark: Boolean = false) {
        val red = (color shr 16 and 0xFF) / 255f
        val green = (color shr 8 and 0xFF) / 255f
        val blue = (color and 0xFF) / 255f
    }

    class Parts internal constructor(
        val body: BakedModel,
        val gem: BakedModel,
        private val centerX: Float,
        private val centerY: Float,
        private val centerZ: Float,
        private val radius: Float,
    ) {
        fun spin(stack: ItemStack, poseStack: PoseStack) {
            val rotation = when (styles[stack.item]?.spin) {
                Spin.SHAFT_AXIS -> scratchRotation.rotationY(turn(SHAFT_PERIOD_MS))
                Spin.TUMBLE -> scratchRotation.rotationYXZ(
                    turn(TUMBLE_Y_PERIOD_MS), turn(TUMBLE_X_PERIOD_MS), turn(TUMBLE_Z_PERIOD_MS),
                )
                else -> return
            }
            poseStack.rotateAround(rotation, centerX, centerY, centerZ)
        }

        /** Draws the halo after the staff body. Returns the consumer to use for the gem. */
        fun emitHalo(
            stack: ItemStack, poseStack: PoseStack, buffers: MultiBufferSource, vertexConsumer: VertexConsumer,
        ): VertexConsumer {
            val style = styles[stack.item] ?: return vertexConsumer
            val shader = shaderInstance ?: return vertexConsumer
            val source = buffers as? MultiBufferSource.BufferSource ?: return vertexConsumer
            // ItemRenderer.render uses this same type for an item that is not a BlockItem.
            val itemType = ItemBlockRenderTypes.getRenderType(stack, true)
            // The halo shader reads the body depth, so the body must draw first.
            source.endBatch(itemType)
            shader.setSampler(DEPTH_SAMPLER, Minecraft.getInstance().mainRenderTarget.depthTextureId)
            val haloType = if (style.dark) DARK_HALO else ADDITIVE_HALO
            val haloConsumer = source.getBuffer(haloType)
            val matrix = poseStack.last().pose()
            // The pose result is view space. The vertex shader adds the UV offset there, so the quad faces the camera.
            val center = matrix.transformPosition(centerX, centerY, centerZ, scratchCenter)
            val rim = radius * HALO_RADIUS_FACTOR * matrix.getScale(scratchScale).x()
            haloVertex(haloConsumer, style, center, -rim, -rim)
            haloVertex(haloConsumer, style, center, rim, -rim)
            haloVertex(haloConsumer, style, center, rim, rim)
            haloVertex(haloConsumer, style, center, -rim, rim)
            // Draw the halo now: the gem draws later, on top, so the halo does not wash it out.
            source.endBatch(haloType)
            // endBatch ended the item builder, so the caller's consumer is stale.
            return ItemRenderer.getFoilBufferDirect(source, itemType, true, stack.hasFoil())
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
            put(EKItems.STAFF_OF_RECITAL.get(), Style(0x7B0AFB))
            put(EKItems.STAFF_OF_AEGIS.get(), Style(0x6B6B6B, Spin.SHAFT_AXIS))
            put(EKItems.STAFF_OF_SUNDERING.get(), Style(0x7E5014))
        }
    }

    // Weak keys: a resource reload replaces every BakedModel.
    private val partsByModel = WeakHashMap<BakedModel, Parts>()
    private val scratchRotation = Quaternionf()
    private val scratchCenter = Vector3f()
    private val scratchScale = Vector3f()

    @JvmStatic
    fun partsOf(stack: ItemStack, model: BakedModel): Parts? {
        if (stack.item !in styles) return null
        return partsByModel.getOrPut(model) { split(model) }
    }

    private fun split(model: BakedModel): Parts {
        val (gemQuads, bodyQuads) = BakedItemModels.quadsOf(model).partition {
            val name = it.sprite.contents().name()
            name.namespace == EnderkinesisMod.MOD_ID && name.path.startsWith(GEM_SPRITE_PREFIX)
        }
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
        return Parts(
            partModel(model, bodyQuads), partModel(model, gemQuads),
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

    private fun turn(periodMs: Long): Float =
        (Util.getMillis() % periodMs).toFloat() / periodMs * TAU

    private const val GEM_SPRITE_PREFIX = "item/mystic_"
    private const val VERTICES_PER_QUAD = 4
    // DefaultVertexFormat.BLOCK: 8 ints for each vertex.
    private const val VERTEX_STRIDE = 8
    private const val TAU = (Math.PI * 2.0).toFloat()

    private const val SHAFT_PERIOD_MS = 4000L
    private const val TUMBLE_Y_PERIOD_MS = 5000L
    private const val TUMBLE_X_PERIOD_MS = 7000L
    private const val TUMBLE_Z_PERIOD_MS = 11000L

    private const val HALO_RADIUS_FACTOR = 1.75f
    private const val PEAK_ALPHA = 1f
    private const val DEPTH_SAMPLER = "SceneDepth"
}
