package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.texture.TextureAtlas
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.entity.SpellBoltEntity
import org.shipwrights.enderkinesis.spell.Spell
import kotlin.math.sin

class SpellBoltRenderer(ctx: EntityRendererProvider.Context) : EntityRenderer<SpellBoltEntity>(ctx) {

    override fun render(bolt: SpellBoltEntity, yaw: Float, partialTick: Float, pose: PoseStack, buffers: MultiBufferSource, light: Int) {
        val elements = bolt.elements()
        val spell = if (elements.isNotEmpty()) Spell(elements) else bolt.element()?.let { Spell(listOf(it)) }
        if (spell != null) {
            val time = SpellClock.time(bolt.level(), partialTick)
            val camera = entityRenderDispatcher.camera
            // Camera in renderer space: world axes, with the Bolt at the origin.
            val at = bolt.getPosition(partialTick)
            val view = SpellView(camera.position.subtract(at), Vec3(camera.leftVector), Vec3(camera.upVector))
            val flight = bolt.deltaMovement.let { if (it.lengthSqr() < 1.0e-6) Vec3(1.0, 0.0, 0.0) else it.normalize() }
            val r = CORE * (1 + 0.1 * sin(time * 0.8))
            SpellCore.thrown(SpellCore.Draw(pose.last(), buffers, view, light, time), Vec3(0.0, bolt.bbHeight / 2.0, 0.0), r, spell, flight)
        }
        super.render(bolt, yaw, partialTick, pose, buffers, light)
    }

    @Suppress("DEPRECATION") // EntityRenderer needs a texture. This renderer uses none, and LOCATION_BLOCKS is deprecated.
    override fun getTextureLocation(bolt: SpellBoltEntity): ResourceLocation = TextureAtlas.LOCATION_BLOCKS

    private companion object {
        /** In blocks. */
        const val CORE = 0.16
    }
}
