package org.shipwrights.enderkinesis.forge.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
import org.shipwrights.enderkinesis.client.SselithRepertoryLighting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Forge twin of {@link org.shipwrights.enderkinesis.fabric.mixin.ModelBlockRendererFlatSselithFabricMixin}. */
@Mixin(ModelBlockRenderer.class)
public abstract class ModelBlockRendererFlatSselithForgeMixin {

    // Forge's 10-arg overload only forwards here, so it never calls getLightColor.
    @WrapOperation(
        method = "tesselateWithoutAO(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/client/resources/model/BakedModel;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;ZLnet/minecraft/util/RandomSource;JILnet/minecraftforge/client/model/data/ModelData;Lnet/minecraft/client/renderer/RenderType;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;getLightColor(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)I"
        )
    )
    private int enderkinesis$attenuateSelithSkyFlat(
        BlockAndTintGetter level, BlockState state, BlockPos pos,
        Operation<Integer> original,
        @Local Direction direction
    ) {
        return SselithRepertoryLighting.attenuateFlatSkyLight(original.call(level, state, pos), direction);
    }
}
