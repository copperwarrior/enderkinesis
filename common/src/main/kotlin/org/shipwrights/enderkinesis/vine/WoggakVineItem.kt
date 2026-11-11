package org.shipwrights.enderkinesis.vine

import net.minecraft.core.BlockPos
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.Level
import org.joml.Vector3d
import org.valkyrienskies.mod.common.getShipManagingPos

class WoggakVineItem(properties: Properties) : Item(properties) {

    override fun useOn(context: UseOnContext): InteractionResult {
        val level = context.level as? ServerLevel ?: return InteractionResult.SUCCESS
        val player = context.player
        // A vine in front of the clicked block takes the use.
        val point = player?.let { level.vinePointAtLook(it) } ?: run {
            val clicked = context.clickedPos.immutable()
            val hit = context.clickLocation
            // The use-on hit of a ship block is in shipyard coordinates, the same frame as the Body anchor.
            VinePoint(clicked, Vector3d(hit.x, hit.y, hit.z), level.getShipManagingPos(clicked)?.id)
        }
        selectPoint(level, player, context.itemInHand, point)
        return InteractionResult.CONSUME
    }

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        val stack = player.getItemInHand(hand)
        if (level !is ServerLevel) return InteractionResultHolder.pass(stack)
        val point = level.vinePointAtLook(player) ?: return InteractionResultHolder.pass(stack)
        selectPoint(level, player, stack, point)
        return InteractionResultHolder.consume(stack)
    }

    private fun ServerLevel.vinePointAtLook(player: Player): VinePoint? {
        val pick = pickWoggakVine(player) ?: return null
        val node = nearestNode(pick)
        return VinePoint(BlockPos.containing(pick.hit.x, pick.hit.y, pick.hit.z), Vector3d(pick.hit), null, VineJoint(pick.vine.chain, node))
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
    }
}
