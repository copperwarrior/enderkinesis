package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import org.shipwrights.enderkinesis.client.BookSkin;
import org.shipwrights.enderkinesis.client.BookSkinHolder;
import org.shipwrights.enderkinesis.client.InlineSketches;
import org.shipwrights.enderkinesis.client.SketchLayout;
import org.shipwrights.enderkinesis.client.SketchPlacement;
import org.shipwrights.enderkinesis.client.PaperStain;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(BookViewScreen.class)
public abstract class BookViewScreenSkinMixin implements BookSkinHolder {

    @Unique private static final int BOOK_SIZE = 192;
    @Unique private static final int BOOK_TOP = 2;

    @Unique
    private BookSkin enderkinesis$skin;

    @Unique private int enderkinesis$pageX;
    @Unique private int enderkinesis$pageY;

    @Shadow private List<FormattedCharSequence> cachedPageComponents;

    @Unique private List<SketchPlacement> enderkinesis$sketches = List.of();

    @Unique private List<FormattedCharSequence> enderkinesis$stainedLines;
    @Unique private int[][] enderkinesis$stains;

    @Override
    public BookSkin enderkinesisSkin() {
        return enderkinesis$skin;
    }

    @Override
    public void enderkinesisSetSkin(BookSkin skin) {
        enderkinesis$skin = skin;
    }

    @ModifyExpressionValue(
        method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screens/inventory/BookViewScreen$BookAccess;getPage(I)Lnet/minecraft/network/chat/FormattedText;"
        )
    )
    private FormattedText enderkinesis$styleSkinPage(FormattedText page) {
        return enderkinesis$skin == null ? page : enderkinesis$skin.styled(page);
    }

    @WrapOperation(
        method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphics;blit(Lnet/minecraft/resources/ResourceLocation;IIIIII)V"
        )
    )
    private void enderkinesis$skinBackground(
        GuiGraphics graphics, ResourceLocation texture, int x, int y, int u, int v, int width, int height,
        Operation<Void> original
    ) {
        if (enderkinesis$skin == null) {
            original.call(graphics, texture, x, y, u, v, width, height);
        } else {
            enderkinesis$skin.blitBackground(graphics, x, y);
        }
        // Popped before the widgets render; shiftWidgets moves those for real so their hitboxes agree.
        graphics.pose().pushPose();
        if (enderkinesis$skin != null) {
            enderkinesis$pageX = x;
            enderkinesis$pageY = y;
            graphics.pose().translate(0.0F, enderkinesis$skin.getContentDy(), 0.0F);
        }
    }

    @WrapOperation(
        method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)I"
        )
    )
    private int enderkinesis$alignLineToFray(
        GuiGraphics graphics, Font font, FormattedCharSequence line, int x, int y, int color, boolean shadow,
        Operation<Integer> original
    ) {
        BookSkin skin = enderkinesis$skin;
        if (skin == null) {
            return original.call(graphics, font, line, x, y, color, shadow);
        }
        int left = skin.lineLeft(x, y, font.width(line));
        if (!skin.getMasksText()) {
            return original.call(graphics, font, line, left, y, color, shadow);
        }
        // One draw for each run of rows with the same page columns: at most LINE_HEIGHT draws.
        int result = 0;
        int row = y + skin.getContentDy();
        int limit = row + BookSkin.LINE_HEIGHT;
        while (row < limit) {
            int end = skin.maskRunEnd(enderkinesis$pageY, row, limit);
            skin.enableMask(graphics, enderkinesis$pageX, enderkinesis$pageY, row, end);
            result = original.call(graphics, font, line, left, y, color, shadow);
            graphics.disableScissor();
            row = end;
        }
        return result;
    }

    // The page is split only when it changes, so the sketch layout runs once per page turn.
    @WrapOperation(
        method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/Font;split(Lnet/minecraft/network/chat/FormattedText;I)Ljava/util/List;"
        )
    )
    private List<FormattedCharSequence> enderkinesis$layoutSketches(
        Font font, FormattedText page, int width, Operation<List<FormattedCharSequence>> original
    ) {
        SketchLayout layout = InlineSketches.INSTANCE.layout(page, text -> original.call(font, text, width));
        enderkinesis$sketches = layout.getSketches();
        return layout.getLines();
    }

    // Drawn before the pop, so the sketches move with the skin's content shift.
    @Inject(
        method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screens/Screen;render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V"
        )
    )
    private void enderkinesis$endContentShift(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        BookSkin skin = enderkinesis$skin;
        int centre = skin != null ? skin.getPageCentreX() : BookSkin.TEXT_X + BookSkin.TEXT_WIDTH / 2;
        int left = (((Screen) (Object) this).width - BOOK_SIZE) / 2;
        InlineSketches.INSTANCE.draw(graphics, enderkinesis$sketches, left + centre, BOOK_TOP + BookSkin.TEXT_Y);
        graphics.pose().popPose();
    }

    // The page number is the only Component drawString in render.
    @WrapOperation(
        method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)I"
        )
    )
    private int enderkinesis$hidePageNumber(
        GuiGraphics graphics, Font font, Component text, int x, int y, int color, boolean shadow,
        Operation<Integer> original
    ) {
        BookSkin skin = enderkinesis$skin;
        if (skin != null && !skin.getShowsPageNumber()) {
            return 0;
        }
        return original.call(graphics, font, text, x, y, color, shadow);
    }

    // The page number is the first text of the page, so the stains go below all the text.
    @Inject(
        method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)I"
        )
    )
    private void enderkinesis$stainPage(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        BookSkin skin = enderkinesis$skin;
        if (skin == null || !skin.getStainsPage()) {
            return;
        }
        if (enderkinesis$stainedLines != cachedPageComponents) {
            enderkinesis$stainedLines = cachedPageComponents;
            enderkinesis$stains = PaperStain.build(Minecraft.getInstance().font, cachedPageComponents);
        }
        PaperStain.draw(graphics, skin, enderkinesis$stains, enderkinesis$pageX);
    }

    @Inject(method = "init()V", at = @At("TAIL"))
    private void enderkinesis$shiftWidgets(CallbackInfo ci) {
        if (enderkinesis$skin != null) {
            enderkinesis$skin.shiftWidgets(((BookViewScreen) (Object) this).children());
        }
    }

    @ModifyVariable(method = "getClickedComponentStyleAt(DD)Lnet/minecraft/network/chat/Style;", at = @At("HEAD"), ordinal = 1, argsOnly = true)
    private double enderkinesis$unshiftMouseY(double mouseY) {
        return enderkinesis$skin == null ? mouseY : mouseY - enderkinesis$skin.getContentDy();
    }
}
