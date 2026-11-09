package org.shipwrights.enderkinesis.ritual

import com.google.gson.JsonObject
import dev.architectury.networking.NetworkManager
import io.netty.buffer.Unpooled
import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.RandomSource
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.WeatheringCopper
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.util.CrossFrameBlocks
import org.valkyrienskies.mod.common.toWorldCoordinates

/**
 * Ritual of Weathering: for [duration] ticks, blocks within [radius] of the rite age as if years went by.
 * Copper oxidizes, bricks crack, and cobblestone and stone bricks go mossy.
 */
class Weathering(private val radius: Int, private val duration: Int) : RitualEffect {

    override fun resolve(context: RitualContext, offered: List<ItemStack>): ItemStack {
        RitualWeathering.start(context.level, context.center, radius, duration)
        return ItemStack.EMPTY
    }

    companion object {
        private const val DEFAULT_RADIUS = 16
        private const val DEFAULT_DURATION = 1200
        private const val MAX_RADIUS = 48

        fun parse(json: JsonObject): Weathering? {
            val radius = json.get("radius")?.asInt ?: DEFAULT_RADIUS
            val duration = json.get("duration")?.asInt ?: DEFAULT_DURATION
            if (radius !in 1..MAX_RADIUS || duration <= 0) return null
            return Weathering(radius, duration)
        }
    }
}

object RitualWeathering {

    val S2C_FOG: ResourceLocation = EnderkinesisMod.id("ritual/weathering_fog")

    // Over a minute at radius 16 a block is picked about three times, so most age about two steps.
    private const val SAMPLES_PER_TICK = 96
    private const val STEP_CHANCE = 0.6f
    private const val SEND_RANGE = 160.0

    fun start(level: ServerLevel, center: BlockPos, radius: Int, duration: Int) {
        val centre = Vec3.atCenterOf(center)
        // Each tick, so a ship that moved, came or went since the rite began is seen where it is now.
        RitualTasks.run(duration) { weather(level, CrossFrameBlocks(level, centre, radius.toDouble()), radius) }
        val worldCentre = level.toWorldCoordinates(centre)
        for (player in level.players()) {
            if (player.distanceToSqr(worldCentre) > SEND_RANGE * SEND_RANGE) continue
            val buf = FriendlyByteBuf(Unpooled.buffer())
            buf.writeLong(center.asLong())
            buf.writeVarInt(radius)
            buf.writeVarInt(duration)
            NetworkManager.sendToPlayer(player, S2C_FOG, buf)
        }
    }

    // Uniform samples in the cube; those outside the sphere are skipped, so the count is a hard cap.
    private fun weather(level: ServerLevel, blocks: CrossFrameBlocks, r: Int) {
        val random = level.random
        val pos = BlockPos.MutableBlockPos()
        val o = blocks.origin
        repeat(SAMPLES_PER_TICK) {
            val dx = random.nextIntBetweenInclusive(-r, r)
            val dy = random.nextIntBetweenInclusive(-r, r)
            val dz = random.nextIntBetweenInclusive(-r, r)
            if (dx * dx + dy * dy + dz * dz > r * r) return@repeat
            age(level, pos.set(o.x + dx, o.y + dy, o.z + dz), random)
        }
        // Ships sample in their own grid, so a scaled ship ages every block at the world rate.
        blocks.forEachShipSample(r.toDouble(), SAMPLES_PER_TICK, random) { shipPos, _ -> age(level, shipPos, random) }
    }

    private fun age(level: ServerLevel, pos: BlockPos, random: RandomSource) {
        if (!level.isLoaded(pos)) return
        val aged = aged(level.getBlockState(pos), random) ?: return
        if (random.nextFloat() < STEP_CHANCE) level.setBlockAndUpdate(pos, aged)
    }

    private fun aged(state: BlockState, random: RandomSource): BlockState? {
        (state.block as? WeatheringCopper)?.getNext(state)?.orElse(null)?.let { return it }
        val next = AGED[state.block] ?: return null
        return next[random.nextInt(next.size)].withPropertiesOf(state)
    }

    // Each block's aged forms; one is picked at random.
    private val AGED: Map<Block, List<Block>> by lazy {
        mapOf(
            Blocks.COBBLESTONE to listOf(Blocks.MOSSY_COBBLESTONE),
            Blocks.COBBLESTONE_STAIRS to listOf(Blocks.MOSSY_COBBLESTONE_STAIRS),
            Blocks.COBBLESTONE_SLAB to listOf(Blocks.MOSSY_COBBLESTONE_SLAB),
            Blocks.COBBLESTONE_WALL to listOf(Blocks.MOSSY_COBBLESTONE_WALL),
            Blocks.STONE_BRICKS to listOf(Blocks.MOSSY_STONE_BRICKS, Blocks.CRACKED_STONE_BRICKS),
            Blocks.STONE_BRICK_STAIRS to listOf(Blocks.MOSSY_STONE_BRICK_STAIRS),
            Blocks.STONE_BRICK_SLAB to listOf(Blocks.MOSSY_STONE_BRICK_SLAB),
            Blocks.STONE_BRICK_WALL to listOf(Blocks.MOSSY_STONE_BRICK_WALL),
            Blocks.INFESTED_STONE_BRICKS to listOf(Blocks.INFESTED_MOSSY_STONE_BRICKS, Blocks.INFESTED_CRACKED_STONE_BRICKS),
            Blocks.DEEPSLATE_BRICKS to listOf(Blocks.CRACKED_DEEPSLATE_BRICKS),
            Blocks.DEEPSLATE_TILES to listOf(Blocks.CRACKED_DEEPSLATE_TILES),
            Blocks.POLISHED_BLACKSTONE_BRICKS to listOf(Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS),
            Blocks.NETHER_BRICKS to listOf(Blocks.CRACKED_NETHER_BRICKS),
        )
    }
}
