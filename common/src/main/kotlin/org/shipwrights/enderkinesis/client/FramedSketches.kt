package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.math.Axis
import dev.architectury.event.events.client.ClientTickEvent
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack
import org.joml.Matrix4f
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.item.SketchSpec
import org.shipwrights.enderkinesis.item.SketchSubject
import org.shipwrights.enderkinesis.item.StrikeReadoutItem
import org.shipwrights.enderkinesis.registry.EKItems
import java.util.Optional
import java.util.WeakHashMap

/** Strike readouts in item frames: the paper fills the frame like a map, with the sketch on it. */
object FramedSketches {

    private const val CACHE_LIMIT = 16
    private const val BAKE_SCALE = 4

    // Map units: the frame face is 128 across.
    private const val FACE = 128f
    private const val SKETCH_Z = -0.1f

    // The paper on strike_readout.png, in texels of the 256 sheet.
    private const val PAPER_LEFT = 20f
    private const val PAPER_TOP = 7f
    private const val PAPER_RIGHT = 159f
    private const val PAPER_BOTTOM = 181f
    private const val SHEET = 256f
    private val PAPER = ResourceLocation(EnderkinesisMod.MOD_ID, "textures/gui/strike_readout.png")

    private class Baked(val location: ResourceLocation?)

    // Render thread only. A bake during the level pass would flush the level's buffers into the sketch target.
    private val specs = WeakHashMap<CompoundTag, Optional<SketchSpec>>()
    private val pending = LinkedHashSet<SketchSpec>()
    private var nextId = 0
    private val baked = object : LinkedHashMap<SketchSpec, Baked>(CACHE_LIMIT, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<SketchSpec, Baked>): Boolean {
            if (size <= CACHE_LIMIT) return false
            eldest.value.location?.let(Minecraft.getInstance().textureManager::release)
            return true
        }
    }

    fun init() {
        ClientTickEvent.CLIENT_POST.register { bakeNext() }
    }

    fun shows(stack: ItemStack): Boolean = spec(stack) != null

    /** Draws in the pose the item frame gives its item; [rotation] is the frame's 0..7 rotation. */
    fun render(stack: ItemStack, pose: PoseStack, buffers: MultiBufferSource, light: Int, rotation: Int) {
        val spec = spec(stack) ?: return
        pose.pushPose()
        // Undo the item's half scale and 45° steps; like maps, the paper turns in 90° steps.
        pose.scale(2f, 2f, 2f)
        pose.mulPose(Axis.ZP.rotationDegrees(rotation % 4 * 90f - rotation * 45f + 180f))
        pose.scale(1f / FACE, 1f / FACE, 1f / FACE)
        pose.translate(-FACE / 2f, -FACE / 2f, -1f)
        val matrix = pose.last().pose()

        val paperHeight = PAPER_BOTTOM - PAPER_TOP
        val units = FACE / paperHeight
        val paperHalfWidth = (PAPER_RIGHT - PAPER_LEFT) * units / 2f
        quad(
            buffers.getBuffer(RenderType.text(PAPER)), matrix, light,
            FACE / 2f - paperHalfWidth, 0f, FACE / 2f + paperHalfWidth, FACE, 0f,
            PAPER_LEFT / SHEET, PAPER_TOP / SHEET, PAPER_RIGHT / SHEET, PAPER_BOTTOM / SHEET,
        )
        texture(spec)?.let { location ->
            val half = spec.size * units / 2f
            quad(
                buffers.getBuffer(RenderType.text(location)), matrix, light,
                FACE / 2f - half, FACE / 2f - half, FACE / 2f + half, FACE / 2f + half, SKETCH_Z,
                0f, 0f, 1f, 1f,
            )
        }
        pose.popPose()
    }

    private fun quad(
        consumer: VertexConsumer, matrix: Matrix4f, light: Int,
        x0: Float, y0: Float, x1: Float, y1: Float, z: Float,
        u0: Float, v0: Float, u1: Float, v1: Float,
    ) {
        consumer.vertex(matrix, x0, y1, z).color(255, 255, 255, 255).uv(u0, v1).uv2(light).endVertex()
        consumer.vertex(matrix, x1, y1, z).color(255, 255, 255, 255).uv(u1, v1).uv2(light).endVertex()
        consumer.vertex(matrix, x1, y0, z).color(255, 255, 255, 255).uv(u1, v0).uv2(light).endVertex()
        consumer.vertex(matrix, x0, y0, z).color(255, 255, 255, 255).uv(u0, v0).uv2(light).endVertex()
    }

    private fun spec(stack: ItemStack): SketchSpec? {
        if (!stack.`is`(EKItems.STRIKE_READOUT.get())) return null
        val tag = stack.tag ?: return null
        return specs.getOrPut(tag) {
            val readout = StrikeReadoutItem.readout(stack)
            readout?.let(SketchPictures::hold)
            Optional.ofNullable(readout?.sketch)
        }.orElse(null)
    }

    private fun texture(spec: SketchSpec): ResourceLocation? {
        val entry = baked[spec]
        if (entry == null) pending.add(spec)
        return entry?.location
    }

    // One bake each tick keeps a wall of frames from stalling a single frame.
    private fun bakeNext() {
        if (StrikeSketch.shader == null) return
        val spec = pending.firstOrNull() ?: return
        pending.remove(spec)
        if (spec in baked) return
        val subject = spec.subject
        val image = if (subject is SketchSubject.Picture) SketchPictures.image(subject.key) else StrikeSketch(spec).bakeImage(BAKE_SCALE)
        val location = image?.let {
            val id = ResourceLocation(EnderkinesisMod.MOD_ID, "strike_sketch/framed_${nextId++}")
            Minecraft.getInstance().textureManager.register(id, DynamicTexture(it))
            id
        }
        baked[spec] = Baked(location)
    }
}
