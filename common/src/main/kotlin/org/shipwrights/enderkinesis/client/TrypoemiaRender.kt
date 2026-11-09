package org.shipwrights.enderkinesis.client

import com.google.common.collect.ImmutableMap
import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.blaze3d.vertex.VertexFormat
import com.mojang.blaze3d.vertex.VertexFormatElement
import dev.architectury.event.events.client.ClientReloadShadersEvent
import java.util.WeakHashMap
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderStateShard
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.ShaderInstance
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.LivingEntity
import org.joml.Matrix4f
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.registry.EKEffects

/**
 * Draws an infected mob's body with the Trypoemia shader, which carves holes into each box part as voxels.
 * Vanilla entity vertices carry no part-local data, so [Writer] adds it from the cube that `ModelPart` is compiling.
 */
object TrypoemiaRender {

    private fun generic(): VertexFormatElement =
        VertexFormatElement(0, VertexFormatElement.Type.FLOAT, VertexFormatElement.Usage.GENERIC, 4)

    /** NEW_ENTITY, then per-part data: local position + part id, box size, local normal, local-to-view columns. */
    val FORMAT: VertexFormat = VertexFormat(
        ImmutableMap.builder<String, VertexFormatElement>()
            .put("Position", DefaultVertexFormat.ELEMENT_POSITION)
            .put("Color", DefaultVertexFormat.ELEMENT_COLOR)
            .put("UV0", DefaultVertexFormat.ELEMENT_UV0)
            .put("UV1", DefaultVertexFormat.ELEMENT_UV1)
            .put("UV2", DefaultVertexFormat.ELEMENT_UV2)
            .put("Normal", DefaultVertexFormat.ELEMENT_NORMAL)
            .put("Padding", DefaultVertexFormat.ELEMENT_PADDING)
            .put("TrypLocal", generic())
            .put("TrypSize", generic())
            .put("TrypNormal", generic())
            .put("TrypAxisX", generic())
            .put("TrypAxisY", generic())
            .put("TrypAxisZ", generic())
            .build(),
    )

    private var shader: ShaderInstance? = null

    // Render thread only. Weak keys drop the state when the client forgets the entity.
    private val states = WeakHashMap<LivingEntity, EntityState>()
    private var active: Writer? = null

    fun init() {
        ClientReloadShadersEvent.EVENT.register { provider, sink ->
            sink.registerShader(ShaderInstance(provider.preferringModNamespace(), "rendertype_trypoemia", FORMAT)) { shader = it }
        }
    }

    /** Returns the consumer for the body model: a Trypoemia writer when [entity] is infected, else [consumer]. */
    @JvmStatic
    fun begin(
        entity: LivingEntity,
        consumer: VertexConsumer,
        buffers: MultiBufferSource,
        texture: ResourceLocation,
    ): VertexConsumer {
        // A wrapped consumer (outline, tint) means another pass owns this draw.
        if (shader == null || consumer !is BufferBuilder || entity.isInvisible) return consumer
        val effect = entity.getEffect(EKEffects.TRYPOEMIA.get()) ?: return consumer
        val state = states.getOrPut(entity) {
            EntityState((entity.uuid.leastSignificantBits xor entity.uuid.mostSignificantBits) ushr 1)
        }
        state.amplifier = effect.amplifier.coerceIn(0, THRESHOLDS.size - 1)
        val builder = buffers.getBuffer(state.renderType(texture)) as? BufferBuilder ?: return consumer
        val writer = state.writer
        writer.reset(builder)
        active = writer
        return writer
    }

    @JvmStatic
    fun end() {
        active?.finish()
        active = null
    }

    /** [pose] maps the cube's model units / 16 to the vertex output space. */
    @JvmStatic
    fun beginCube(polygons: Array<ModelPart.Polygon>, pose: Matrix4f) {
        active?.beginCube(polygons, pose)
    }

    private class EntityState(seed: Long) {
        val writer = Writer()
        val seedValue = seed.rem(SEED_RANGE).toFloat()
        var amplifier = 0
        private val types = HashMap<ResourceLocation, RenderType>()

        fun renderType(texture: ResourceLocation): RenderType = types.getOrPut(texture) {
            RenderType.create(
                "${EnderkinesisMod.MOD_ID}_trypoemia", FORMAT, VertexFormat.Mode.QUADS, BUFFER_SIZE, true, false,
                RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.ShaderStateShard { shader })
                    .setTextureState(RenderStateShard.TextureStateShard(texture, false, false))
                    .setTexturingState(
                        RenderStateShard.TexturingStateShard("${EnderkinesisMod.MOD_ID}_trypoemia_entity", ::applyUniforms) {},
                    )
                    .setTransparencyState(RenderStateShard.NO_TRANSPARENCY)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setLightmapState(RenderStateShard.LIGHTMAP)
                    .setOverlayState(RenderStateShard.OVERLAY)
                    .createCompositeState(true),
            )
        }

        private fun applyUniforms() {
            val s = shader ?: return
            s.safeGetUniform("TrypSeed").set(seedValue)
            s.safeGetUniform("TrypThreshold").set(THRESHOLDS[amplifier])
        }
    }

    private class Writer : VertexConsumer {
        private lateinit var out: BufferBuilder
        private var polygons: Array<ModelPart.Polygon>? = null
        private val axes = Matrix4f()
        private var vertex = 0
        private var part = 0
        private var minX = 0f
        private var minY = 0f
        private var minZ = 0f
        private var sizeX = 0f
        private var sizeY = 0f
        private var sizeZ = 0f

        fun reset(builder: BufferBuilder) {
            out = builder
            polygons = null
            part = -1
        }

        fun finish() {
            polygons = null
        }

        fun beginCube(polys: Array<ModelPart.Polygon>, pose: Matrix4f) {
            polygons = polys
            vertex = 0
            part++
            axes.set(pose)
            // Bounds from the vertices, so inflated and per-face-UV cubes measure what is drawn.
            var x0 = Float.MAX_VALUE
            var y0 = Float.MAX_VALUE
            var z0 = Float.MAX_VALUE
            var x1 = -Float.MAX_VALUE
            var y1 = -Float.MAX_VALUE
            var z1 = -Float.MAX_VALUE
            for (poly in polys) {
                for (v in poly.vertices) {
                    x0 = minOf(x0, v.pos.x()); y0 = minOf(y0, v.pos.y()); z0 = minOf(z0, v.pos.z())
                    x1 = maxOf(x1, v.pos.x()); y1 = maxOf(y1, v.pos.y()); z1 = maxOf(z1, v.pos.z())
                }
            }
            minX = x0; minY = y0; minZ = z0
            sizeX = (x1 - x0) / UNITS; sizeY = (y1 - y0) / UNITS; sizeZ = (z1 - z0) / UNITS
        }

        override fun vertex(x: Double, y: Double, z: Double): VertexConsumer = apply { out.vertex(x, y, z) }

        override fun color(r: Int, g: Int, b: Int, a: Int): VertexConsumer = apply { out.color(r, g, b, a) }

        override fun uv(u: Float, v: Float): VertexConsumer = apply { out.uv(u, v) }

        override fun overlayCoords(u: Int, v: Int): VertexConsumer = apply { out.overlayCoords(u, v) }

        override fun uv2(u: Int, v: Int): VertexConsumer = apply { out.uv2(u, v) }

        override fun normal(x: Float, y: Float, z: Float): VertexConsumer = apply { out.normal(x, y, z) }

        override fun defaultColor(r: Int, g: Int, b: Int, a: Int) = out.defaultColor(r, g, b, a)

        override fun unsetDefaultColor() = out.unsetDefaultColor()

        override fun endVertex() {
            val polys = polygons
            val index = vertex++
            if (polys == null || index >= polys.size * QUAD) {
                // Not from a cube: part -1 tells the shader to draw plain skin.
                repeat(EXTRA_ELEMENTS) { put(0f, 0f, 0f, -1f) }
            } else {
                val poly = polys[index / QUAD]
                val pos = poly.vertices[index % QUAD].pos
                val n = poly.normal
                put((pos.x() - minX) / UNITS, (pos.y() - minY) / UNITS, (pos.z() - minZ) / UNITS, part.toFloat())
                put(sizeX, sizeY, sizeZ, 0f)
                put(n.x(), n.y(), n.z(), 0f)
                put(axes.m00(), axes.m01(), axes.m02(), 0f)
                put(axes.m10(), axes.m11(), axes.m12(), 0f)
                put(axes.m20(), axes.m21(), axes.m22(), 0f)
            }
            out.endVertex()
        }

        private fun put(a: Float, b: Float, c: Float, d: Float) {
            out.putFloat(0, a)
            out.putFloat(FLOAT_BYTES, b)
            out.putFloat(FLOAT_BYTES * 2, c)
            out.putFloat(FLOAT_BYTES * 3, d)
            out.nextElement()
        }
    }

    // Per amplifier: cluster noise threshold; lower opens more hollows.
    private val THRESHOLDS = floatArrayOf(0.6f, 0.5f, 0.4f, 0.28f)
    private const val SEED_RANGE = 997L
    private const val BUFFER_SIZE = 256
    private const val UNITS = 16f
    private const val QUAD = 4
    private const val EXTRA_ELEMENTS = 6
    private const val FLOAT_BYTES = 4
}
