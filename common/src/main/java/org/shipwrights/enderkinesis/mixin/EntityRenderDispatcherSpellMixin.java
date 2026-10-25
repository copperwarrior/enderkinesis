package org.shipwrights.enderkinesis.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.shipwrights.enderkinesis.client.DiscordDisguise;
import org.shipwrights.enderkinesis.client.SpellNetworkClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Spell effects on how entities are drawn: an Absent entity isn't drawn at all, and a player
 *  under Discord sees their allies as zombies. */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherSpellMixin {

    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private <E extends Entity> void enderkinesis$absent(E entity, Frustum frustum, double x, double y, double z,
                                                         CallbackInfoReturnable<Boolean> cir) {
        if (SpellNetworkClient.isAbsent(entity.getId())) cir.setReturnValue(false);
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private <E extends Entity> void enderkinesis$discord(E entity, double x, double y, double z, float yaw, float partialTick,
                                                          PoseStack poseStack, MultiBufferSource buffers, int light, CallbackInfo ci) {
        Entity stand = DiscordDisguise.standIn(entity);
        if (stand == null) return;
        ci.cancel();
        ((EntityRenderDispatcher) (Object) this).render(stand, x, y, z, yaw, partialTick, poseStack, buffers, light);
    }
}
