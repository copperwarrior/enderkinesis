package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.shipwrights.enderkinesis.spell.AbyssalPockets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.valkyrienskies.mod.common.render.batched.BatchedModelRenderer;
import org.valkyrienskies.mod.common.render.batched.ShipSectionCompiler;

/** VS2 meshes ships by default with its batched compiler, which reads chunk sections directly and skips the render-region hooks. */
@Mixin(value = ShipSectionCompiler.class, remap = false)
public abstract class ShipSectionCompilerPhasedMixin {

    // No descriptor: the AP does not remap a mod method's descriptor, so a Minecraft type in it breaks in production.
    @WrapOperation(
        method = "compileShip",
        at = @At(value = "INVOKE", target = "Lorg/valkyrienskies/mod/common/render/batched/BatchedModelRenderer;renderModel"),
        remap = false
    )
    private void enderkinesis$skipPhased(
        BatchedModelRenderer instance, BlockRenderDispatcher dispatcher, BlockState state, BlockPos pos,
        BlockAndTintGetter view, PoseStack poseStack, VertexConsumer consumer, boolean checkSides,
        RandomSource random, Object modelData, RenderType renderType, Operation<Void> original
    ) {
        if (AbyssalPockets.isPhasedClient(pos)) return;
        original.call(instance, dispatcher, state, pos, view, poseStack, consumer, checkSides, random, modelData, renderType);
    }
}
