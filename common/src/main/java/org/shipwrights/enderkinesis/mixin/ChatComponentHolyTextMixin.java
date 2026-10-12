package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import org.shipwrights.enderkinesis.client.HolyText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ChatComponent.class)
public abstract class ChatComponentHolyTextMixin {

    @ModifyVariable(
        method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;ILnet/minecraft/client/GuiMessageTag;Z)V",
        at = @At("HEAD"),
        argsOnly = true
    )
    private Component enderkinesis$markHolyText(Component message) {
        FormattedText marked = HolyText.mark(message);
        return marked instanceof Component component ? component : message;
    }
}
