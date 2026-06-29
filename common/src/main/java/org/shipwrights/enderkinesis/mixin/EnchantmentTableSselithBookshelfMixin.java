package org.shipwrights.enderkinesis.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EnchantmentTableBlock;
import org.shipwrights.enderkinesis.registry.EKBlocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla {@code isValidBookShelf} hard-checks {@code Blocks.BOOKSHELF}; Forge rewrites the body
 * to test {@code getEnchantPowerBonus != 0}. Both paths reject the Sselith bookshelf, so a HEAD
 * inject short-circuits before either runs. Mid-block air check is replicated so the Sselith
 * shelf has the same line-of-sight requirement as vanilla. Apotheosis / Zenith bypass this
 * method entirely via their own enchanting-stats system (handled by the bundled datapack files),
 * so this mixin only matters when neither is installed.
 */
@Mixin(EnchantmentTableBlock.class)
public class EnchantmentTableSselithBookshelfMixin {

    @Inject(method = "isValidBookShelf", at = @At("HEAD"), cancellable = true)
    private static void enderkinesis$treatSselithAsBookshelf(
        Level level, BlockPos tablePos, BlockPos offset, CallbackInfoReturnable<Boolean> cir
    ) {
        if (!level.getBlockState(tablePos.offset(offset)).is(EKBlocks.INSTANCE.getSSELITH_BOOKSHELF().get())) return;
        BlockPos mid = tablePos.offset(offset.getX() / 2, offset.getY() / 2, offset.getZ() / 2);
        cir.setReturnValue(level.isEmptyBlock(mid));
    }
}
