package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import org.shipwrights.enderkinesis.block.HeartOfTheWildManager;
import org.shipwrights.enderkinesis.registry.EKEnchantments;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Blocks broken with Love of the Mother do not enroll for Heart or Wohlon regrowth. */
@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeLoveOfTheMotherMixin {

    @Shadow @Final protected ServerPlayer player;

    @WrapMethod(method = "destroyBlock")
    private boolean enderkinesis$spareFromRegrowth(BlockPos pos, Operation<Boolean> original) {
        if (!EKEnchantments.holdsLoveOfTheMother(player)) return original.call(pos);
        return HeartOfTheWildManager.INSTANCE.withEnrollSuppressed(() -> original.call(pos));
    }
}
