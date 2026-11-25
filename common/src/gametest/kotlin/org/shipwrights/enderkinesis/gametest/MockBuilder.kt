package org.shipwrights.enderkinesis.gametest

import com.mojang.authlib.GameProfile
import io.netty.channel.embedded.EmbeddedChannel
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.Connection
import net.minecraft.network.PacketSendListener
import net.minecraft.network.protocol.Packet
import net.minecraft.network.ConnectionProtocol
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Entity
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.GameType
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import java.util.UUID

/** Drops every packet before netty sees it: an EmbeddedChannel claims every thread as its event loop, so concurrent sends corrupt its queue. */
private class DiscardingConnection : Connection(PacketFlow.SERVERBOUND) {
    override fun send(packet: Packet<*>) = Unit

    override fun send(packet: Packet<*>, listener: PacketSendListener?) = Unit
}

/** A player in the player list, so VS2 loads and simulates the ships near it, and the build has an online owner. */
internal class MockBuilder private constructor(val player: ServerPlayer, private val channel: EmbeddedChannel) {

    /** A right-click with [item] on [face] of the block at [cell], through the item's own use path. */
    fun useItemOn(item: Item, cell: BlockPos, face: Direction): InteractionResult {
        val stack = ItemStack(item)
        player.setItemInHand(InteractionHand.MAIN_HAND, stack)
        val hit = BlockHitResult(Vec3.atCenterOf(cell).relative(face, HALF_BLOCK), face, cell, false)
        return stack.useOn(UseOnContext(player, InteractionHand.MAIN_HAND, hit))
    }

    /** A right-click on [entity] with [item] in hand. */
    fun interactWith(entity: Entity, item: Item): InteractionResult {
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(item))
        return player.interactOn(entity, InteractionHand.MAIN_HAND)
    }

    fun leave() {
        player.server.playerList.remove(player)
        channel.finishAndReleaseAll()
    }

    companion object {
        private const val NAME = "gametest-builder"
        private const val HALF_BLOCK = 0.5

        fun join(level: ServerLevel, at: Vec3): MockBuilder {
            val player = ServerPlayer(level.server, level, GameProfile(UUID.randomUUID(), NAME))
            val connection = DiscardingConnection()
            val channel = EmbeddedChannel(connection)
            channel.attr(Connection.ATTRIBUTE_PROTOCOL).set(ConnectionProtocol.PLAY)
            level.server.playerList.placeNewPlayer(connection, player)
            player.setGameMode(GameType.CREATIVE)
            player.teleportTo(level, at.x, at.y, at.z, 0f, 0f)
            // The player never ticks, so the chunk map hears of the move only here.
            level.chunkSource.move(player)
            if (!player.abilities.mayBuild) gameTestFail("the mock builder may not build")
            return MockBuilder(player, channel)
        }
    }
}
