package org.shipwrights.enderkinesis.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.LecternRenderer;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;
import org.shipwrights.enderkinesis.item.ScrollOfSculkCatastropheItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Renders the Scroll of Sculk Catastrophe as a flat page on the lectern
 * instead of an open book — the scroll has no pages to paginate and the book
 * geometry would clip through it.
 *
 * <p>Mirrors the vanilla {@link LecternRenderer#render} matrix prelude exactly
 * (translate to lectern top, yaw by facing.clockwise, 67.5° tilt forward) so
 * the quad lands flush with the same slot the book would occupy. Then draws a
 * single horizontal quad on the lectern's reading surface using the
 * {@code scroll_flat_page} texture, and cancels the original render so
 * vanilla's BookModel doesn't paint over it.
 *
 * <p>Limited to scrolls only — every other lectern book (Almanac, Wylland,
 * Stratus, vanilla written / writable) still falls through to vanilla.
 */
@Mixin(LecternRenderer.class)
public class LecternRendererScrollFlatPageMixin {

    @Unique
    private static final ResourceLocation enderkinesis$SCROLL_FLAT_PAGE =
        new ResourceLocation("enderkinesis", "textures/item/scroll_flat_page.png");

    @Inject(method = "render(Lnet/minecraft/world/level/block/entity/LecternBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
        at = @At("HEAD"), cancellable = true)
    private void enderkinesis$flatScrollRender(
        LecternBlockEntity be, float partialTick, PoseStack pose,
        MultiBufferSource buffer, int light, int overlay, CallbackInfo ci
    ) {
        BlockState state = be.getBlockState();
        if (!state.getValue(LecternBlock.HAS_BOOK)) return;
        if (!(be.getBook().getItem() instanceof ScrollOfSculkCatastropheItem)) return;

        pose.pushPose();
        pose.translate(0.5f, 1.0625f, 0.5f);
        float yaw = -state.getValue(LecternBlock.FACING).getClockWise().toYRot();
        pose.mulPose(Axis.YP.rotationDegrees(yaw));
        pose.mulPose(Axis.ZP.rotationDegrees(67.5f));
        pose.translate(0.0f, -0.125f, 0.0f);

        // The scroll lies flat on the tilted reading surface — after the
        // 67.5° Z rotation, the lectern's reading slope is the local XZ
        // plane and +Y is its outward normal. Draw a quad slightly above
        // the surface so vanilla's lectern shading doesn't z-fight.
        // Width / length tuned to roughly match the open-book footprint.
        final float halfW = 0.18f;  // along Z (across the reader's view)
        final float halfL = 0.30f;  // along X (down the slope)
        final float y = 0.0001f;

        VertexConsumer cons = buffer.getBuffer(RenderType.entityCutout(enderkinesis$SCROLL_FLAT_PAGE));
        Matrix4f m = pose.last().pose();
        // Wind CCW viewed from +Y so the front face matches the surface
        // normal vanilla expects (so directional lighting hits the page).
        cons.vertex(m, -halfL, y, -halfW).color(255, 255, 255, 255)
            .uv(0.0f, 0.0f).overlayCoords(overlay).uv2(light).normal(0f, 1f, 0f).endVertex();
        cons.vertex(m, -halfL, y, halfW).color(255, 255, 255, 255)
            .uv(0.0f, 1.0f).overlayCoords(overlay).uv2(light).normal(0f, 1f, 0f).endVertex();
        cons.vertex(m, halfL, y, halfW).color(255, 255, 255, 255)
            .uv(1.0f, 1.0f).overlayCoords(overlay).uv2(light).normal(0f, 1f, 0f).endVertex();
        cons.vertex(m, halfL, y, -halfW).color(255, 255, 255, 255)
            .uv(1.0f, 0.0f).overlayCoords(overlay).uv2(light).normal(0f, 1f, 0f).endVertex();

        pose.popPose();
        ci.cancel();
    }
}
