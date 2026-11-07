package org.shipwrights.enderkinesis.ritual

import com.google.gson.JsonObject
import dev.architectury.networking.NetworkManager
import io.netty.buffer.Unpooled
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.BaseFireBlock
import org.shipwrights.enderkinesis.EnderkinesisMod

/**
 * Ritual of Conflagration: a wave moves out from the rite over [ticks] and sets fire beside every flammable block
 * within [radius]. It is vanilla fire, so rain puts it out and it spreads as fire does.
 * A fire whirlwind rises from the centre while the wave runs; clients draw it.
 */
class Conflagration(private val radius: Int, private val ticks: Int) : RitualEffect {

    override fun resolve(context: RitualContext, offered: List<ItemStack>): ItemStack {
        val level = context.level
        val center = context.center.immutable()
        val offsets = offsetsByDistance(radius)
        val cursor = BlockPos.MutableBlockPos()
        val air = BlockPos.MutableBlockPos()
        var next = 0
        sendWhirlwind(level, center)
        RitualTasks.run(ticks) { tick ->
            val reach = radius * (tick + 1) / ticks.toDouble()
            val reachSq = reach * reach
            while (next < offsets.size / 3) {
                val dx = offsets[next * 3]
                val dy = offsets[next * 3 + 1]
                val dz = offsets[next * 3 + 2]
                if (dx * dx + dy * dy + dz * dz > reachSq) break
                next++
                cursor.setWithOffset(center, dx, dy, dz)
                ignite(level, cursor, air)
            }
        }
        return ItemStack.EMPTY
    }

    private fun sendWhirlwind(level: ServerLevel, center: BlockPos) {
        for (player in level.players()) {
            if (player.blockPosition().distSqr(center) > SEND_RANGE * SEND_RANGE) continue
            val buf = FriendlyByteBuf(Unpooled.buffer())
            buf.writeLong(center.asLong())
            buf.writeVarInt(radius)
            buf.writeVarInt(ticks)
            NetworkManager.sendToPlayer(player, S2C_WHIRLWIND, buf)
        }
    }

    private fun ignite(level: ServerLevel, pos: BlockPos, air: BlockPos.MutableBlockPos) {
        if (!level.isLoaded(pos) || !level.getBlockState(pos).ignitedByLava()) return
        for (side in Direction.values()) {
            air.setWithOffset(pos, side)
            if (!level.getBlockState(air).isAir) continue
            level.setBlockAndUpdate(air, BaseFireBlock.getState(level, air))
            return
        }
    }

    companion object {
        val S2C_WHIRLWIND: ResourceLocation = EnderkinesisMod.id("ritual/fire_whirlwind")
        private const val SEND_RANGE = 160.0
        private const val DEFAULT_RADIUS = 16
        private const val DEFAULT_TICKS = 100
        private const val MAX_RADIUS = 32

        fun parse(json: JsonObject): Conflagration? {
            val radius = json.get("radius")?.asInt ?: DEFAULT_RADIUS
            val ticks = json.get("duration")?.asInt ?: DEFAULT_TICKS
            if (radius !in 1..MAX_RADIUS || ticks <= 0) return null
            return Conflagration(radius, ticks)
        }

        /** x, y, z triples of every offset within [radius], nearest first, so the wave visits each block once. */
        private fun offsetsByDistance(radius: Int): IntArray {
            val within = ArrayList<IntArray>()
            for (dx in -radius..radius) for (dy in -radius..radius) for (dz in -radius..radius) {
                if (dx * dx + dy * dy + dz * dz <= radius * radius) within.add(intArrayOf(dx, dy, dz))
            }
            within.sortBy { it[0] * it[0] + it[1] * it[1] + it[2] * it[2] }
            val out = IntArray(within.size * 3)
            within.forEachIndexed { i, o -> o.copyInto(out, i * 3) }
            return out
        }
    }
}
