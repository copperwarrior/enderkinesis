package org.shipwrights.enderkinesis.vine

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BowItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.UseAnim
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.valkyrienskies.mod.common.getShipManagingPos

class WoggakVineItem(properties: Properties) : Item(properties) {

    override fun useOn(context: UseOnContext): InteractionResult {
        val level = context.level as? ServerLevel ?: return InteractionResult.SUCCESS
        val player = context.player
        // A vine in front of the clicked block takes the use.
        val point = player?.let { level.vinePointAtLook(it) } ?: run {
            val clicked = context.clickedPos.immutable()
            // The use-on hit of a ship block is in shipyard coordinates, the same frame as the Body anchor.
            val hit = level.snapToShapeAnchor(clicked, context.clickedFace, context.clickLocation)
            VinePoint(clicked, Vector3d(hit.x, hit.y, hit.z), level.getShipManagingPos(clicked)?.id)
        }
        selectPoint(level, player, context.itemInHand, point)
        return InteractionResult.CONSUME
    }

    /** A vine under the look takes the use; with no target the use draws a throw, as a bow. */
    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        val stack = player.getItemInHand(hand)
        if (level is ServerLevel) {
            val point = level.vinePointAtLook(player)
            if (point != null) {
                selectPoint(level, player, stack, point)
                return InteractionResultHolder.consume(stack)
            }
        } else if (player.looksAtClientVine()) {
            return InteractionResultHolder.consume(stack)
        }
        player.startUsingItem(hand)
        return InteractionResultHolder.consume(stack)
    }

    override fun getUseDuration(stack: ItemStack): Int = THROW_USE_TICKS

    override fun getUseAnimation(stack: ItemStack): UseAnim = UseAnim.BOW

    override fun releaseUsing(stack: ItemStack, level: Level, entity: LivingEntity, timeLeft: Int) {
        if (level !is ServerLevel || entity !is ServerPlayer) return
        val power = BowItem.getPowerForTime(getUseDuration(stack) - timeLeft)
        if (power < MIN_THROW_POWER) return
        if (!level.throwWoggakVine(entity, entity.usedItemHand, stack, power)) {
            entity.displayClientMessage(Component.translatable("item.enderkinesis.woggak_vine.unsupported"), true)
        }
    }

    /** The client's guess of the server vine pick, so that both sides agree on a throw draw. */
    private fun Player.looksAtClientVine(): Boolean {
        val eye = eyePosition
        val look = getViewVector(1f)
        return clientVineCollider.pick(Vector3d(eye.x, eye.y, eye.z), Vector3d(look.x, look.y, look.z), PICK_REACH, VineNodeRef())
    }

    private fun ServerLevel.vinePointAtLook(player: Player): VinePoint? {
        val pick = pickWoggakVine(player) ?: return null
        val node = nearestNode(pick)
        return VinePoint(BlockPos.containing(pick.hit.x, pick.hit.y, pick.hit.z), Vector3d(pick.hit), null, VineJoint(pick.vine.id, node, pick.vine.home))
    }

    private fun selectPoint(level: ServerLevel, player: Player?, stack: ItemStack, point: VinePoint) {
        val dimension = level.dimension().location().toString()
        val first = stack.tag?.takeIf { it.contains(FIRST_KEY, Tag.TAG_COMPOUND.toInt()) }?.getCompound(FIRST_KEY)
        if (first == null || first.getString(DIMENSION_KEY) != dimension) {
            stack.orCreateTag.put(FIRST_KEY, point.save().apply { putString(DIMENSION_KEY, dimension) })
            player?.displayClientMessage(Component.translatable("item.enderkinesis.woggak_vine.first"), true)
            return
        }
        val creative = player?.abilities?.instabuild == true
        val result = level.createWoggakVine(VinePoint.load(first), point, if (creative) Int.MAX_VALUE else stack.count)
        if (result.error != null) {
            player?.displayClientMessage(result.error, true)
            return
        }
        stack.removeTagKey(FIRST_KEY)
        if (!creative) stack.shrink(result.cost)
        player?.displayClientMessage(Component.translatable("item.enderkinesis.woggak_vine.made"), true)
    }

    private companion object {
        const val FIRST_KEY = "woggak_vine_first"
        const val DIMENSION_KEY = "dimension"
        /** Vanilla bow use time. */
        const val THROW_USE_TICKS = 72000
    }
}

/** [hit] snapped to the anchor point of [face] on the block shape at [pos], in the frame of [pos]. */
internal fun ServerLevel.snapToShapeAnchor(pos: BlockPos, face: Direction, hit: Vec3): Vec3 {
    val origin = Vec3.atLowerCornerOf(pos)
    val boxes = getBlockState(pos).getShape(this, pos).toAabbs()
    return snapToFaceAnchor(boxes, face, hit.subtract(origin)).add(origin)
}
