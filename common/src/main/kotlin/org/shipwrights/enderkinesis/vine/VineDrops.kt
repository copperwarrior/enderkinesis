package org.shipwrights.enderkinesis.vine

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.GameRules
import org.shipwrights.enderkinesis.registry.EKItems
import org.valkyrienskies.core.api.chains.ChainStatus
import org.valkyrienskies.core.api.chains.ServerChain
import org.valkyrienskies.core.api.util.GameTickOnly
import kotlin.math.floor
import kotlin.math.min

/** Sums of reeled spacings are a hair under a whole item; this lets them count. */
private const val ITEM_EPS = 1e-9

/** Vine items that a faded vine of [length] gives back: one for each full [BLOCKS_PER_ITEM] blocks. */
fun woggakVineRefund(length: Double): Int =
    if (length.isFinite() && length > 0) floor(length / BLOCKS_PER_ITEM + ITEM_EPS).toInt() else 0

/** Vine items for [reeled] blocks on top of a [credit] of earlier reeled blocks. */
internal fun reelItems(credit: Double, reeled: Double): Int = woggakVineRefund(credit + reeled)

/** The credit left after [reelItems] paid for [reeled] on top of [credit]; in 0 until [BLOCKS_PER_ITEM]. */
internal fun reelCreditLeft(credit: Double, reeled: Double): Double =
    validReelCredit(credit + reeled - reelItems(credit, reeled) * BLOCKS_PER_ITEM)

/**
 * A saved or computed credit; a bad value is 0, so no item comes from nothing. The limit is under [BLOCKS_PER_ITEM] by
 * the [ITEM_EPS] share, because [woggakVineRefund] pays a full item from that band.
 */
internal fun validReelCredit(credit: Double): Double =
    if (credit.isFinite() && credit > 0.0 && credit < BLOCKS_PER_ITEM * (1.0 - ITEM_EPS)) credit else 0.0

/**
 * The length that a vine is still worth in items: its rest length, or a pending reel target under it, because that
 * length is about to go. A reel pays a link only when it comes in, so this can pay one link less, never more.
 */
internal fun ChainStatus.paidLength(): Double {
    val target = reel?.control?.targetLength ?: return currentRestLength
    return min(currentRestLength, target)
}

/** Drops the refund of a faded vine, its paid length plus its reel [credit], at the centre of its world nodes. */
@OptIn(GameTickOnly::class)
internal fun ServerLevel.dropFadedVine(chain: ServerChain, credit: Double) {
    if (!gameRules.getBoolean(GameRules.RULE_DOBLOCKDROPS)) return
    val status = chain.status
    var count = woggakVineRefund(status.paidLength() + validReelCredit(credit))
    val nodes = status.nodeCount
    if (count <= 0 || nodes < 1) return
    val data = woggakVines
    if (data.scratch.size < nodes * 3) data.scratch = DoubleArray(nodes * 3)
    if (chain.copyNodePositions(data.scratch) != nodes) {
        LOG.warn("Woggak Vine {}: no node positions, so its {} items are not dropped", chain.id.value, count)
        return
    }
    var x = 0.0; var y = 0.0; var z = 0.0
    for (i in 0 until nodes) {
        x += data.scratch[i * 3]; y += data.scratch[i * 3 + 1]; z += data.scratch[i * 3 + 2]
    }
    x /= nodes; y /= nodes; z /= nodes
    val item = EKItems.WOGGAK_VINE.get()
    while (count > 0) {
        val n = minOf(count, item.maxStackSize)
        addFreshEntity(ItemEntity(this, x, y, z, ItemStack(item, n)).apply { setDefaultPickUpDelay() })
        count -= n
    }
}
