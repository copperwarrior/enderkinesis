@file:JvmName("SupplyWatch")

package org.shipwrights.enderkinesis.construction

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.Container
import net.minecraft.world.level.block.entity.BlockEntity
import java.util.concurrent.atomic.AtomicInteger

/** Sum of every level's watched positions; zero lets [blockEntityChanged] skip the saved-data lookup. */
internal val watchedSupplyCount = AtomicInteger()

/** Called from every block entity's `setChanged`, so it must stay cheap for blocks that are no linked supply. */
fun blockEntityChanged(blockEntity: BlockEntity) {
    if (watchedSupplyCount.get() == 0 || blockEntity !is Container) return
    val level = blockEntity.level as? ServerLevel ?: return
    // Worldgen threads can mark a block entity changed; build jobs live on the server thread only.
    if (!level.server.isSameThread) return
    BuildJobs.get(level).supplyChanged(blockEntity.blockPos)
}
