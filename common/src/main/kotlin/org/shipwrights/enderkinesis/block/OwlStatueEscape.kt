package org.shipwrights.enderkinesis.block

import dev.architectury.networking.NetworkManager
import io.netty.buffer.Unpooled
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.registry.EKParticles
import org.valkyrienskies.mod.common.getShipManagingPos
import org.valkyrienskies.mod.common.toWorldCoordinates
import kotlin.math.sqrt

/** A broken owl statue does not drop. It moves to a perch above the player who broke it. */
object OwlStatueEscape {

    /** S2C, one long: the block position where an owl statue arrived. */
    val ARRIVED_PACKET: ResourceLocation = EnderkinesisMod.id("owl_statue/arrived")

    private const val RADIUS = 24
    private const val DIAMETER = 2 * RADIUS + 1
    private const val COLUMNS = DIAMETER * DIAMETER

    /** Has no common factor with [COLUMNS], so the strided walk visits each column one time. */
    private const val COLUMN_STRIDE = 1009
    private const val SECTION_MASK = 15
    private const val NO_PERCH = Int.MIN_VALUE
    private const val STATUE_HEIGHT = 2
    private const val HALF_WIDTH = 6.0 / 16.0
    private const val DUST_COUNT = 80
    private const val SCREAM_VOLUME = 2.0f
    private const val SCREAM_PITCH = 0.8f
    private const val FLUTTER_VOLUME = 1.5f
    private const val FLUTTER_PITCH = 0.7f

    fun escapes(player: Player): Boolean =
        !player.isCreative && !EnchantmentHelper.hasSilkTouch(player.mainHandItem)

    /** [state] goes to the new position. A null [breaker] removes the height rule. */
    fun escape(level: ServerLevel, pos: BlockPos, state: BlockState, breaker: ServerPlayer?) {
        val halfHeight = STATUE_HEIGHT / 2.0
        // On a VS2 ship, pos is in the shipyard. Sound and particles need world coordinates.
        val centre = level.toWorldCoordinates(Vec3(pos.x + 0.5, pos.y + halfHeight, pos.z + 0.5))
        level.playSound(
            null, centre.x, centre.y, centre.z, SoundEvents.SOUL_ESCAPE, SoundSource.BLOCKS,
            SCREAM_VOLUME, SCREAM_PITCH,
        )
        level.playSound(
            null, centre.x, centre.y, centre.z, SoundEvents.PARROT_FLY, SoundSource.BLOCKS,
            FLUTTER_VOLUME, FLUTTER_PITCH,
        )
        // sendParticles spreads with a Gaussian; half of each half extent keeps the cloud in the statue.
        level.sendParticles(
            EKParticles.sselithDust(), centre.x, centre.y, centre.z,
            DUST_COUNT, HALF_WIDTH / 2.0, halfHeight / 2.0, HALF_WIDTH / 2.0, 0.0,
        )

        val floorY = if (breaker == null) Int.MIN_VALUE else breakerBlockY(level, pos, breaker)
        val perch = findPerch(level, pos, floorY) ?: return
        StatueBlock.place(level, perch, state)
        if (breaker != null) {
            val buf = FriendlyByteBuf(Unpooled.buffer())
            buf.writeLong(perch.asLong())
            NetworkManager.sendToPlayer(breaker, ARRIVED_PACKET, buf)
        }
    }

    /** The Y of [breaker] in the frame of [pos]: the shipyard if a VS2 ship holds the statue. */
    private fun breakerBlockY(level: ServerLevel, pos: BlockPos, breaker: ServerPlayer): Int {
        val ship = level.getShipManagingPos(pos) ?: return breaker.blockY
        val local = ship.transform.worldToShip.transformPosition(Vector3d(breaker.x, breaker.y, breaker.z))
        return local.y.toInt()
    }

    /** Visits each column of the sphere one time, in a scattered order, and stops at the first perch. */
    private fun findPerch(level: ServerLevel, origin: BlockPos, floorY: Int): BlockPos? {
        val low = maxOf(floorY + 1, origin.y - RADIUS, level.minBuildHeight + 1)
        val high = minOf(origin.y + RADIUS, level.maxBuildHeight - STATUE_HEIGHT)
        if (low > high) return null
        val random = level.random
        val cursor = BlockPos.MutableBlockPos()
        val first = random.nextInt(COLUMNS)
        for (n in 0 until COLUMNS) {
            val index = (first + n * COLUMN_STRIDE) % COLUMNS
            val dx = index / DIAMETER - RADIUS
            val dz = index % DIAMETER - RADIUS
            val reachSq = RADIUS * RADIUS - dx * dx - dz * dz
            if (reachSq < 0 || (dx == 0 && dz == 0)) continue
            val reach = sqrt(reachSq.toDouble()).toInt()
            val from = maxOf(low, origin.y - reach)
            val to = minOf(high, origin.y + reach)
            val x = origin.x + dx
            val z = origin.z + dz
            if (from > to || !level.worldBorder.isWithinBounds(x.toDouble(), z.toDouble())) continue
            // getChunkNow does not load or generate a chunk.
            val chunk = level.chunkSource.getChunkNow(x shr 4, z shr 4) ?: continue
            val start = from + random.nextInt(to - from + 1)
            cursor.set(x, 0, z)
            var y = firstPerch(level, chunk, cursor, start, to)
            if (y == NO_PERCH) y = firstPerch(level, chunk, cursor, from, start - 1)
            if (y != NO_PERCH) return BlockPos(x, y, z)
        }
        return null
    }

    /** The lowest perch Y in [from]..[to] of the column at [cursor], or [NO_PERCH]. */
    private fun firstPerch(
        level: ServerLevel, chunk: LevelChunk, cursor: BlockPos.MutableBlockPos, from: Int, to: Int,
    ): Int {
        val x = cursor.x and SECTION_MASK
        val z = cursor.z and SECTION_MASK
        var y = from
        while (y <= to) {
            val floorY = y - 1
            val floorSection = chunk.getSection(chunk.getSectionIndex(floorY))
            if (floorSection.hasOnlyAir()) {
                // No floor in this section: go to the first Y that has its floor in the next section.
                y = (floorY or SECTION_MASK) + 2
                continue
            }
            val floor = floorSection.getBlockState(x, floorY and SECTION_MASK, z)
            if (!floor.isAir && isAir(chunk, x, y, z) && isAir(chunk, x, y + 1, z) &&
                floor.isFaceSturdy(level, cursor.setY(floorY), Direction.UP)
            ) {
                return y
            }
            y++
        }
        return NO_PERCH
    }

    private fun isAir(chunk: LevelChunk, x: Int, y: Int, z: Int): Boolean {
        val section = chunk.getSection(chunk.getSectionIndex(y))
        return section.hasOnlyAir() || section.getBlockState(x, y and SECTION_MASK, z).isAir
    }
}
