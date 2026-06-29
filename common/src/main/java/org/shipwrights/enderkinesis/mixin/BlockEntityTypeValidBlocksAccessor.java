package org.shipwrights.enderkinesis.mixin;

import java.util.Set;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@link BlockEntityType#validBlocks} so the mod can extend the set
 * after vanilla initialises it. Used to register the Sselith Lectern as a
 * valid carrier of the vanilla {@code BlockEntityType.LECTERN} — subclassing
 * {@code LecternBlock} keeps all the book-holding behaviour for free, but
 * the BE only persists across chunk reloads if its block is in
 * {@code validBlocks}, which is an immutable set built by
 * {@code BlockEntityType.Builder.build}. We rebuild it with our block added.
 */
@Mixin(BlockEntityType.class)
public interface BlockEntityTypeValidBlocksAccessor {
    @Accessor("validBlocks")
    Set<Block> getValidBlocks();

    @Mutable
    @Accessor("validBlocks")
    void setValidBlocks(Set<Block> validBlocks);
}
