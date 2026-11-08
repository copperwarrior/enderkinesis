package org.shipwrights.enderkinesis.mixin;

import static org.shipwrights.enderkinesis.client.FrozenPoses.AGE_IN_TICKS;
import static org.shipwrights.enderkinesis.client.FrozenPoses.ATTACK_TIME;
import static org.shipwrights.enderkinesis.client.FrozenPoses.BODY_ROT;
import static org.shipwrights.enderkinesis.client.FrozenPoses.HEAD_PITCH;
import static org.shipwrights.enderkinesis.client.FrozenPoses.HEAD_YAW;
import static org.shipwrights.enderkinesis.client.FrozenPoses.LIMB_SWING;
import static org.shipwrights.enderkinesis.client.FrozenPoses.LIMB_SWING_AMOUNT;
import static org.shipwrights.enderkinesis.client.FrozenPoses.PARTIAL_TICK;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.shipwrights.enderkinesis.client.FrozenPoses;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererFrozenPoseMixin<T extends LivingEntity, M extends EntityModel<T>> {

    private static final String RENDER =
        "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V";

    @WrapOperation(method = RENDER, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/entity/LivingEntityRenderer;getAttackAnim(Lnet/minecraft/world/entity/LivingEntity;F)F"))
    private float enderkinesis$frozenAttackTime(LivingEntityRenderer<T, M> self, T entity, float partialTick,
                                                Operation<Float> original) {
        return FrozenPoses.input(entity, ATTACK_TIME, original.call(self, entity, partialTick));
    }

    @WrapOperation(method = RENDER, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/entity/LivingEntityRenderer;setupRotations(Lnet/minecraft/world/entity/LivingEntity;Lcom/mojang/blaze3d/vertex/PoseStack;FFF)V"))
    private void enderkinesis$frozenFacing(LivingEntityRenderer<T, M> self, T entity, PoseStack pose, float ageInTicks,
                                           float rotationYaw, float partialTick, Operation<Void> original) {
        original.call(self, entity, pose, FrozenPoses.input(entity, AGE_IN_TICKS, ageInTicks),
            FrozenPoses.input(entity, BODY_ROT, rotationYaw), FrozenPoses.input(entity, PARTIAL_TICK, partialTick));
    }

    @WrapOperation(method = RENDER, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/model/EntityModel;prepareMobModel(Lnet/minecraft/world/entity/Entity;FFF)V"))
    private void enderkinesis$frozenPrepare(EntityModel<T> model, Entity entity, float limbSwing, float limbSwingAmount,
                                            float partialTick, Operation<Void> original) {
        LivingEntity living = (LivingEntity) entity;
        original.call(model, entity, FrozenPoses.input(living, LIMB_SWING, limbSwing),
            FrozenPoses.input(living, LIMB_SWING_AMOUNT, limbSwingAmount), FrozenPoses.input(living, PARTIAL_TICK, partialTick));
    }

    @WrapOperation(method = RENDER, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/model/EntityModel;setupAnim(Lnet/minecraft/world/entity/Entity;FFFFF)V"))
    private void enderkinesis$frozenSetupAnim(EntityModel<T> model, Entity entity, float limbSwing, float limbSwingAmount,
                                              float ageInTicks, float headYaw, float headPitch, Operation<Void> original) {
        LivingEntity living = (LivingEntity) entity;
        original.call(model, entity, FrozenPoses.input(living, LIMB_SWING, limbSwing),
            FrozenPoses.input(living, LIMB_SWING_AMOUNT, limbSwingAmount), FrozenPoses.input(living, AGE_IN_TICKS, ageInTicks),
            FrozenPoses.input(living, HEAD_YAW, headYaw), FrozenPoses.input(living, HEAD_PITCH, headPitch));
    }

    // Layers such as sheep wool and slime outer skin pose their own model from these arguments.
    @WrapOperation(method = RENDER, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/entity/layers/RenderLayer;render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/Entity;FFFFFF)V"))
    private void enderkinesis$frozenLayer(RenderLayer<T, M> layer, PoseStack pose, MultiBufferSource buffers, int light,
                                          Entity entity, float limbSwing, float limbSwingAmount, float partialTick,
                                          float ageInTicks, float headYaw, float headPitch, Operation<Void> original) {
        LivingEntity living = (LivingEntity) entity;
        original.call(layer, pose, buffers, light, entity, FrozenPoses.input(living, LIMB_SWING, limbSwing),
            FrozenPoses.input(living, LIMB_SWING_AMOUNT, limbSwingAmount), FrozenPoses.input(living, PARTIAL_TICK, partialTick),
            FrozenPoses.input(living, AGE_IN_TICKS, ageInTicks), FrozenPoses.input(living, HEAD_YAW, headYaw),
            FrozenPoses.input(living, HEAD_PITCH, headPitch));
    }
}
