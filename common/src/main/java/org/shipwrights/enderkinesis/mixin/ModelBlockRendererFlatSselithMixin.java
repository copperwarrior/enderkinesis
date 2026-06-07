package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
import org.shipwrights.enderkinesis.client.SselithRepertoryLighting;
import org.shipwrights.enderkinesis.dimension.SselithRepertory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Attenuates the **sky-light** component of the per-face packed lightmap UV for blocks
 * rendered with flat lighting in {@link org.shipwrights.enderkinesis.dimension.SselithRepertory}.
 * Block-light is left untouched, so torches stay full brightness on shadow faces — the
 * directional cast affects only what skylight contributes.
 *
 * <p>{@code ModelBlockRenderer.tesselateWithoutAO} iterates {@code Direction.values()} and
 * for each face calls {@code LevelRenderer.getLightColor(level, state, neighborPos)} to
 * pack {@code (block_light, sky_light)} into the int that becomes the vertex lightmap UV.
 * We wrap that call with MixinExtras's {@code @WrapOperation}; the {@code @Local Direction}
 * captures the loop variable so {@link SselithRepertoryLighting#attenuateSkyLight} can
 * scale the sky-light nibble by `dot(face_normal, sunDir)` while leaving the block-light
 * nibble alone.
 *
 * <p>Smooth (AO) lighting goes through a different code path — see
 * {@link ModelBlockRendererAOSselithMixin}.
 */
@Mixin(ModelBlockRenderer.class)
public abstract class ModelBlockRendererFlatSselithMixin {

    // Method name only (no descriptor): Forge's tesselateWithoutAO has 12 args
    // (adds ModelData + RenderType), Fabric's has 10. Mixin's fuzzy match picks the
    // right method per platform.
    @WrapOperation(
        method = "tesselateWithoutAO",
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
        int packedLight = original.call(level, state, pos);
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.level.dimension() != SselithRepertory.INSTANCE.getLEVEL_KEY()) {
            return packedLight;
        }
        return SselithRepertoryLighting.attenuateSkyLight(packedLight, direction);
    }
}
