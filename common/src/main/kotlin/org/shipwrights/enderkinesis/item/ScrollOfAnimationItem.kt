package org.shipwrights.enderkinesis.item

import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.Item
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.gameevent.GameEvent
import org.shipwrights.enderkinesis.registry.EKEntities

/** Scroll of Animation — used on any full solid block (no block entity), consumes both
 *  the block and the scroll and animates the block into a Constriliar tamed to the
 *  user, wearing the block's texture as its body. */
class ScrollOfAnimationItem(props: Properties) : Item(props) {

    override fun useOn(context: UseOnContext): InteractionResult {
        val level = context.level
        val pos = context.clickedPos
        val state = level.getBlockState(pos)
        if (state.hasBlockEntity() || !state.isSolidRender(level, pos)) return InteractionResult.PASS
        // Bedrock and other unbreakable blocks.
        if (state.getDestroySpeed(level, pos) < 0f) return InteractionResult.PASS
        if (level !is ServerLevel) return InteractionResult.SUCCESS
        val player = context.player
        if (player != null && (!player.mayBuild() || !level.mayInteract(player, pos))) return InteractionResult.FAIL

        val entity = EKEntities.CONSTRILIAR.get().create(level) ?: return InteractionResult.FAIL
        level.removeBlock(pos, false)
        // Wake facing the awakener: the cardinal direction the player is in, seen from
        // the block — grid-square, like the block it still is.
        val yaw = player?.let {
            net.minecraft.core.Direction.getNearest(
                (it.x - (pos.x + 0.5)).toFloat(), 0f, (it.z - (pos.z + 0.5)).toFloat(),
            ).toYRot()
        } ?: 0f
        entity.moveTo(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5, yaw, 0f)
        entity.yBodyRot = yaw
        entity.yHeadRot = yaw
        entity.ownerUUID = player?.uuid
        entity.setBodyBlock(state)
        entity.startSpawnAnimation()
        level.addFreshEntity(entity)

        if (player?.abilities?.instabuild != true) context.itemInHand.shrink(1)
        level.gameEvent(player, GameEvent.ENTITY_PLACE, pos)
        return InteractionResult.CONSUME
    }
}
