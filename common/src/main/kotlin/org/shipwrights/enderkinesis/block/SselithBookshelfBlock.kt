package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import org.shipwrights.enderkinesis.client.BookshelfPageClient
import org.shipwrights.enderkinesis.dimension.SselithRepertory
import org.shipwrights.enderkinesis.registry.EKItems

class SselithBookshelfBlock(properties: Properties) : Block(properties) {

    @Deprecated("Deprecated in Java")
    override fun use(
        state: BlockState, level: Level, pos: BlockPos,
        player: Player, hand: InteractionHand, hit: BlockHitResult,
    ): InteractionResult {
        if (level.dimension() != SselithRepertory.LEVEL_KEY) return InteractionResult.PASS
        // Let the bookmark's useOn save this shelf instead of opening it.
        if (player.getItemInHand(hand).`is`(EKItems.EIDETIC_BOOKMARK.get())) return InteractionResult.PASS
        if (level.isClientSide) BookshelfPageClient.open(pos)
        return InteractionResult.sidedSuccess(level.isClientSide)
    }
}
