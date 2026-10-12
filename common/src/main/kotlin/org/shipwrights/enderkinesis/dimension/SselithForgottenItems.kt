package org.shipwrights.enderkinesis.dimension

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.SectionPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.RandomSource
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.ChunkAccess
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.blockentity.ForgottenItemBlockEntity
import org.shipwrights.enderkinesis.registry.EKBlocks

object SselithForgottenItems {
    val LOOT_TABLE: ResourceLocation = EnderkinesisMod.id("forgotten_item/sselith")
    val SHELF_LOOT_TABLE: ResourceLocation = EnderkinesisMod.id("forgotten_item/sselith_shelf")

    // A 5x5 platform gets about a 1 in 100 chance from each attempt, so 5 gives about 1 in 20.
    private const val ATTEMPTS_PER_CHUNK = 5
    // One maze cell layer, so each attempt can find the floor of the layer it starts in.
    private const val MAX_SCAN = 24
    private const val SEED_SALT = 0x5DEECE66DL
    private const val SHELF_SEED_SALT = 0x2545F4914F6CDD1DL
    // About half of the columns on a shelf row have an open stack top: about 1.6 items for each row.
    private const val SHELF_TOP_DENOM = 16
    private const val SECTION_SIZE = 16
    private const val FULL_TURN_DEG = 360f

    // Runs on chunk-gen worker threads: keep all state local.
    fun scatter(level: WorldGenLevel, chunk: ChunkAccess) {
        val random = RandomSource.create(level.seed xor (chunk.pos.toLong() * SEED_SALT))
        val state = EKBlocks.FORGOTTEN_ITEM.get().defaultBlockState()
        val pos = BlockPos.MutableBlockPos()
        repeat(ATTEMPTS_PER_CHUNK) {
            val x = chunk.pos.minBlockX + random.nextInt(16)
            val z = chunk.pos.minBlockZ + random.nextInt(16)
            val top = chunk.minBuildHeight + 1 + random.nextInt(chunk.height - 1)
            if (!findFloor(chunk, pos, x, top, z, MAX_SCAN)) return@repeat
            place(level, pos, state, LOOT_TABLE, random)
        }
    }

    fun shelfRandom(level: WorldGenLevel, chunk: ChunkAccess): RandomSource =
        RandomSource.create(level.seed xor (chunk.pos.toLong() * SHELF_SEED_SALT))

    /** Rolls one item for each air block that is directly above a Sselith bookshelf in column ([x], [z]). */
    fun scatterOnShelfColumn(level: WorldGenLevel, chunk: ChunkAccess, random: RandomSource, x: Int, z: Int) {
        val shelf = EKBlocks.SSELITH_BOOKSHELF.get()
        val state = EKBlocks.FORGOTTEN_ITEM.get().defaultBlockState()
        val pos = BlockPos.MutableBlockPos()
        // False at the start: there is no position above the build limit.
        var aboveIsAir = false
        for (index in chunk.sectionsCount - 1 downTo 0) {
            val section = chunk.getSection(index)
            if (section.hasOnlyAir()) {
                aboveIsAir = true
                continue
            }
            val baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(index))
            for (localY in SECTION_SIZE - 1 downTo 0) {
                val block = section.getBlockState(x and 15, localY, z and 15)
                val wasAir = aboveIsAir
                aboveIsAir = block.isAir
                if (!wasAir || !block.`is`(shelf) || random.nextInt(SHELF_TOP_DENOM) != 0) continue
                place(level, pos.set(x, baseY + localY + 1, z), state, SHELF_LOOT_TABLE, random)
            }
        }
    }

    private fun place(
        level: WorldGenLevel, pos: BlockPos, state: BlockState, table: ResourceLocation, random: RandomSource,
    ) {
        if (!level.setBlock(pos, state, Block.UPDATE_CLIENTS)) return
        (level.getBlockEntity(pos) as? ForgottenItemBlockEntity)
            ?.setLootTable(table, random.nextLong(), random.nextFloat() * FULL_TURN_DEG)
    }

    /** Leaves [pos] on the first air block with a sturdy floor at or below [top]. */
    private fun findFloor(
        chunk: ChunkAccess, pos: BlockPos.MutableBlockPos, x: Int, top: Int, z: Int, maxScan: Int,
    ): Boolean {
        val bottom = maxOf(chunk.minBuildHeight + 1, top - maxScan)
        for (y in top downTo bottom) {
            if (!chunk.getBlockState(pos.set(x, y, z)).isAir) continue
            pos.setY(y - 1)
            val sturdy = chunk.getBlockState(pos).isFaceSturdy(chunk, pos, Direction.UP)
            pos.setY(y)
            if (sturdy) return true
        }
        return false
    }
}
