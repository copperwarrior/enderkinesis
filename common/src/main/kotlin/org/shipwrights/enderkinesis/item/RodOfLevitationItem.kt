package org.shipwrights.enderkinesis.item

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.BlockTags
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.FluidState
import net.minecraft.world.level.material.Fluids
import org.valkyrienskies.mod.common.getLoadedShipManagingPos
import org.valkyrienskies.mod.common.isBlockInShipyard

/**
 * Rod of Levitation. Three behaviours, split across vanilla's interaction hooks:
 *
 * - Right-click an entity: applies Levitation I for 4 seconds. Handled here in
 *   [interactLivingEntity].
 * - Right-click a block (not a ship block, not unbreakable): assembles that block into a
 *   one-block VS2 ship and hands off to [RodOfLevitationManager] for the 4-second hover +
 *   falling-block dissolve. Handled here in [useOn].
 * - Left-click a ship block: applies a slight off-COM impulse in the player's look
 *   direction. Handled by [RodOfLevitationManager.handleLeftClick] registered against
 *   Architectury's `InteractionEvent.LEFT_CLICK_BLOCK`, since vanilla `Item` has no
 *   left-click hook.
 */
class RodOfLevitationItem(properties: Properties) : Item(properties) {

    override fun interactLivingEntity(
        stack: ItemStack, player: Player, entity: LivingEntity, hand: InteractionHand,
    ): InteractionResult {
        if (player.level().isClientSide) return InteractionResult.SUCCESS
        entity.addEffect(MobEffectInstance(MobEffects.LEVITATION, RodOfLevitationManager.DURATION_TICKS, 0))
        return InteractionResult.CONSUME
    }

    override fun useOn(context: UseOnContext): InteractionResult {
        val level = context.level
        val pos = context.clickedPos
        if (level.isClientSide) return InteractionResult.SUCCESS
        if (level !is ServerLevel) return InteractionResult.PASS

        // If the clicked block lives on an existing ship, only the 1×1×1 case is
        // re-liftable — that's an unmodified rod-spawned cube the player wants to
        // bump up again. Anything bigger is a real ship the player should grab with
        // a Wylland Tome or nudge via left-click, so we pass through.
        val existing = level.getLoadedShipManagingPos(pos)
        if (existing != null) {
            val ab = existing.shipAABB ?: return InteractionResult.PASS
            val isUnitCube = (ab.maxX() - ab.minX() == 1) &&
                (ab.maxY() - ab.minY() == 1) &&
                (ab.maxZ() - ab.minZ() == 1)
            if (!isUnitCube) return InteractionResult.PASS
            val handled = RodOfLevitationManager.reLevitate(level, existing)
            return if (handled) InteractionResult.CONSUME else InteractionResult.PASS
        }
        if (level.isBlockInShipyard(pos)) return InteractionResult.PASS

        val state = level.getBlockState(pos)
        if (state.isAir) return InteractionResult.PASS
        // Unbreakable gate. `getDestroySpeed < 0` catches bedrock-style blocks (negative
        // hardness); WITHER_IMMUNE catches reinforced deepslate (positive hardness 55 but
        // intentionally indestructible), plus barriers, end portals, command blocks, etc.
        if (state.getDestroySpeed(level, pos) < 0f) return InteractionResult.PASS
        if (state.`is`(BlockTags.WITHER_IMMUNE)) return InteractionResult.PASS
        // Support gate. If the block can't survive without any neighbours, it depends on
        // its supporter (torch on a wall, tall grass on dirt, ladder on stone, sapling
        // on grass, etc.) and lifting it alone would just orphan it. The player should
        // click the supporter — the expandWithAttachments helper will pull the
        // dependant up alongside it.
        if (requiresSupport(level, state, pos)) return InteractionResult.PASS

        val handled = RodOfLevitationManager.assembleAndLift(level, pos, state)
        return if (handled) InteractionResult.CONSUME else InteractionResult.PASS
    }

    /** Returns true if removing every block surrounding [pos] (replacing them with AIR
     *  via a mock [LevelReader]) makes [state.canSurvive] fail — i.e., the block depends
     *  on at least one neighbour for structural support. Solid blocks (stone, planks, the
     *  player's hull material) survive in vacuum and return false. */
    private fun requiresSupport(level: ServerLevel, state: BlockState, pos: BlockPos): Boolean {
        val mock = AirElsewhereReader(level, pos)
        return !state.canSurvive(mock, pos)
    }
}

/** [LevelReader] facade that returns AIR everywhere except a single "keep" position.
 *  Mirrors the [org.shipwrights.enderkinesis.block.CrepusculiteLatticeBlock]'s
 *  AttachmentMockReader pattern but inverted: keep the block under inspection, hide
 *  everything else so [BlockState.canSurvive]'s neighbour reads see vacuum. */
private class AirElsewhereReader(
    private val delegate: LevelReader,
    private val keep: BlockPos,
) : LevelReader by delegate {
    override fun getBlockState(pos: BlockPos): BlockState =
        if (sameCoords(pos, keep)) delegate.getBlockState(pos) else Blocks.AIR.defaultBlockState()

    override fun getFluidState(pos: BlockPos): FluidState =
        if (sameCoords(pos, keep)) delegate.getFluidState(pos) else Fluids.EMPTY.defaultFluidState()

    private fun sameCoords(a: BlockPos, b: BlockPos): Boolean =
        a.x == b.x && a.y == b.y && a.z == b.z
}
