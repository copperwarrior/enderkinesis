package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Block

internal const val SECTION_MAX = 15

/**
 * Visits each [block] in the box until [visit] returns true, and returns that position; [cursor] is scratch.
 * Unloaded chunks and sections whose palette has no [block] are skipped, so a scan over open terrain is a few palette checks.
 */
internal inline fun ServerLevel.scanForBlock(
    block: Block,
    center: BlockPos,
    radius: Int,
    radiusY: Int,
    cursor: BlockPos.MutableBlockPos,
    visit: (BlockPos.MutableBlockPos) -> Boolean,
): BlockPos? {
    val minY = maxOf(center.y - radiusY, minBuildHeight)
    val maxY = minOf(center.y + radiusY, maxBuildHeight - 1)
    for (sx in SectionPos.blockToSectionCoord(center.x - radius)..SectionPos.blockToSectionCoord(center.x + radius)) {
        for (sz in SectionPos.blockToSectionCoord(center.z - radius)..SectionPos.blockToSectionCoord(center.z + radius)) {
            val chunk = chunkSource.getChunkNow(sx, sz) ?: continue
            for (sy in SectionPos.blockToSectionCoord(minY)..SectionPos.blockToSectionCoord(maxY)) {
                val section = chunk.getSection(chunk.getSectionIndexFromSectionY(sy))
                if (section.hasOnlyAir() || !section.maybeHas { it.`is`(block) }) continue
                val x0 = maxOf(center.x - radius, SectionPos.sectionToBlockCoord(sx))
                val x1 = minOf(center.x + radius, SectionPos.sectionToBlockCoord(sx) + SECTION_MAX)
                val y0 = maxOf(minY, SectionPos.sectionToBlockCoord(sy))
                val y1 = minOf(maxY, SectionPos.sectionToBlockCoord(sy) + SECTION_MAX)
                val z0 = maxOf(center.z - radius, SectionPos.sectionToBlockCoord(sz))
                val z1 = minOf(center.z + radius, SectionPos.sectionToBlockCoord(sz) + SECTION_MAX)
                for (y in y0..y1) for (x in x0..x1) for (z in z0..z1) {
                    cursor.set(x, y, z)
                    if (section.getBlockState(x and SECTION_MAX, y and SECTION_MAX, z and SECTION_MAX).`is`(block) &&
                        visit(cursor)
                    ) {
                        return cursor.immutable()
                    }
                }
            }
        }
    }
    return null
}

