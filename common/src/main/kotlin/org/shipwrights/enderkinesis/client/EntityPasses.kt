package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.Vec3
import org.joml.Matrix3f
import org.joml.Matrix4f
import org.joml.Vector3f
import org.shipwrights.enderkinesis.mixin.CompositeRenderTypeAccessor
import org.shipwrights.enderkinesis.mixin.CompositeStateAccessor
import org.shipwrights.enderkinesis.mixin.EmptyTextureStateShardInvoker
import org.shipwrights.enderkinesis.spell.SpellVisuals
import java.util.IdentityHashMap
import java.util.function.Consumer

/**
 * Every entity is drawn through here, whatever its renderer, so spells change anything that
 * renders, not only ordinary mob models:
 *
 * - **Dying a spell's way** ([DeathStyles]): every entity-textured buffer the entity draws into
 *   (its body, armour, its layers) is drawn instead through the death shader on the same
 *   texture, each vertex carrying the death and its height in the entity's own space.
 * - **Crystallized** ([CrystalLayer]): every quad the entity draws is caught, in its own space,
 *   and the crystals grow on those.
 * - **Status layers** ([SpellLayers]) go round its hitbox afterwards.
 */
object EntityPasses {
    /** A face the entity drew, in its own space (feet at the origin, world axes). */
    class Quad(val corners: Array<Vec3>, val normal: Vec3)

    private val textures = IdentityHashMap<RenderType, ResourceLocation?>()

    /** The texture [type] draws with, if it's one an entity's body is drawn with. */
    fun entityTexture(type: RenderType): ResourceLocation? = textures.getOrPut(type) {
        if (type.format() != DefaultVertexFormat.NEW_ENTITY) return@getOrPut null
        runCatching {
            val state = (type as CompositeRenderTypeAccessor).`enderkinesis$state`()
            ((state as CompositeStateAccessor).`enderkinesis$textureState`() as EmptyTextureStateShardInvoker).`enderkinesis$cutoutTexture`().orElse(null)
        }.getOrNull()
    }

    @JvmStatic
    fun render(entity: Entity, partialTick: Float, pose: PoseStack, buffers: MultiBufferSource, light: Int, draw: Consumer<MultiBufferSource>) {
        val living = entity as? LivingEntity
        if (living == null) return draw.accept(buffers)
        val death = DeathStyles.body(living, partialTick)
        // Nothing grows on what's invisible to you (its armour, say).
        val crystallized = SpellVisuals.clientBits(entity.id) and SpellVisuals.CRYSTALLIZED != 0 && death == null && !SpellLayers.invisible(living)
        if (death != null && death.gone) return
        if (death == null && !crystallized) {
            draw.accept(buffers)
            SpellLayers.render(living, partialTick, pose, buffers, light)
            return
        }
        val toLocal = Matrix4f(pose.last().pose()).invert()
        var source = buffers
        if (death != null) source = DeathSource(source, toLocal, death)
        val quads = if (crystallized) ArrayList<Quad>() else null
        if (quads != null) source = CaptureSource(source, toLocal, quads)
        pose.pushPose()
        death?.adjust?.invoke(pose)
        draw.accept(source)
        pose.popPose()
        if (quads != null) CrystalLayer.render(living, quads, buffers, pose, light, partialTick)
        SpellLayers.render(living, partialTick, pose, buffers, light)
    }

    // ---- Dying ---------------------------------------------------------------------------------

    private class DeathSource(private val inner: MultiBufferSource, private val toLocal: Matrix4f, private val death: DeathStyles.Body) : MultiBufferSource {
        override fun getBuffer(type: RenderType): VertexConsumer {
            val texture = entityTexture(type) ?: return inner.getBuffer(type)
            return Encoded(inner.getBuffer(DeathStyles.type(texture)), toLocal, death)
        }
    }

    /** Passes vertices on with the death in their colour: style, time, height in the entity's own
     *  space (as a share of its hitbox, from a quarter below its feet to a quarter above its top),
     *  opacity. */
    private class Encoded(private val inner: VertexConsumer, private val toLocal: Matrix4f, private val death: DeathStyles.Body) : VertexConsumer {
        private var height = 0
        private val v = Vector3f()

        override fun vertex(x: Double, y: Double, z: Double): VertexConsumer {
            toLocal.transformPosition(v.set(x.toFloat(), y.toFloat(), z.toFloat()))
            height = (((v.y / death.height + 0.25f) / 1.5f).coerceIn(0f, 1f) * 255f).toInt()
            inner.vertex(x, y, z)
            return this
        }

        override fun color(red: Int, green: Int, blue: Int, a: Int): VertexConsumer {
            inner.color(death.style, death.progress, height, (a * death.alpha).toInt().coerceIn(0, 255))
            return this
        }

        override fun uv(u: Float, v: Float): VertexConsumer { inner.uv(u, v); return this }
        override fun overlayCoords(u: Int, v: Int): VertexConsumer { inner.overlayCoords(u, v); return this }
        override fun uv2(u: Int, v: Int): VertexConsumer { inner.uv2(u, v); return this }
        override fun normal(x: Float, y: Float, z: Float): VertexConsumer { inner.normal(x, y, z); return this }
        override fun endVertex() = inner.endVertex()
        override fun defaultColor(red: Int, green: Int, blue: Int, alpha: Int) = inner.defaultColor(red, green, blue, alpha)
        override fun unsetDefaultColor() = inner.unsetDefaultColor()
    }

    // ---- Catching the entity's faces -------------------------------------------------------------

    private class CaptureSource(private val inner: MultiBufferSource, private val toLocal: Matrix4f, private val quads: MutableList<Quad>) : MultiBufferSource {
        override fun getBuffer(type: RenderType): VertexConsumer {
            val c = inner.getBuffer(type)
            return if (entityTexture(type) != null) Capture(c, toLocal, quads) else c
        }
    }

    /** Passes every vertex on unchanged, and keeps each quad's corners and facing. */
    private class Capture(private val inner: VertexConsumer, private val toLocal: Matrix4f, private val quads: MutableList<Quad>) : VertexConsumer {
        private val toLocalNormal = Matrix3f(toLocal)
        private val corners = arrayOfNulls<Vec3>(4)
        private var count = 0
        private var normal = Vec3.ZERO
        private val v = Vector3f()

        override fun vertex(x: Double, y: Double, z: Double): VertexConsumer {
            toLocal.transformPosition(v.set(x.toFloat(), y.toFloat(), z.toFloat()))
            corners[count] = Vec3(v.x.toDouble(), v.y.toDouble(), v.z.toDouble())
            inner.vertex(x, y, z)
            return this
        }

        override fun normal(x: Float, y: Float, z: Float): VertexConsumer {
            if (count == 0) {
                toLocalNormal.transform(v.set(x, y, z)).normalize()
                normal = Vec3(v.x.toDouble(), v.y.toDouble(), v.z.toDouble())
            }
            inner.normal(x, y, z)
            return this
        }

        override fun endVertex() {
            inner.endVertex()
            if (++count == 4) {
                count = 0
                @Suppress("UNCHECKED_CAST")
                quads += Quad((corners.clone() as Array<Vec3>), normal)
            }
        }

        override fun color(red: Int, green: Int, blue: Int, alpha: Int): VertexConsumer { inner.color(red, green, blue, alpha); return this }
        override fun uv(u: Float, v: Float): VertexConsumer { inner.uv(u, v); return this }
        override fun overlayCoords(u: Int, v: Int): VertexConsumer { inner.overlayCoords(u, v); return this }
        override fun uv2(u: Int, v: Int): VertexConsumer { inner.uv2(u, v); return this }
        override fun defaultColor(red: Int, green: Int, blue: Int, alpha: Int) = inner.defaultColor(red, green, blue, alpha)
        override fun unsetDefaultColor() = inner.unsetDefaultColor()
    }
}
