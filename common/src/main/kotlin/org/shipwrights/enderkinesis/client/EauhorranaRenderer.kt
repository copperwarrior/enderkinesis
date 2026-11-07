package org.shipwrights.enderkinesis.client

import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.MobRenderer
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.client.model.EauhorranaModel
import org.shipwrights.enderkinesis.entity.EauhorranaEntity

class EauhorranaRenderer(ctx: EntityRendererProvider.Context) : MobRenderer<EauhorranaEntity, EauhorranaModel>(
    ctx,
    EauhorranaModel(ctx.bakeLayer(EauhorranaModel.LAYER_LOCATION)),
    SHADOW_RADIUS,
) {
    override fun getTextureLocation(entity: EauhorranaEntity): ResourceLocation = SKIN

    // The fish may tick before its target, so its own lerp trails the target by a tick; draw it on the target instead.
    override fun getRenderOffset(entity: EauhorranaEntity, partialTicks: Float): Vec3 {
        val target = entity.latchedTarget() ?: return super.getRenderOffset(entity, partialTicks)
        val at = entity.latchPoint(target, partialTicks)
        return Vec3(
            at.x - Mth.lerp(partialTicks.toDouble(), entity.xo, entity.x),
            at.y - Mth.lerp(partialTicks.toDouble(), entity.yo, entity.y),
            at.z - Mth.lerp(partialTicks.toDouble(), entity.zo, entity.z),
        )
    }

    companion object {
        private const val SHADOW_RADIUS = 0.5f
        private val SKIN: ResourceLocation = EnderkinesisMod.id("textures/entity/${EauhorranaEntity.ID_PATH}.png")
    }
}
