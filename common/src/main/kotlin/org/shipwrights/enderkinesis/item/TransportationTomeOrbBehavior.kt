package org.shipwrights.enderkinesis.item

import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.blockentity.OrbOfLinkingBlockEntity

/**
 * Tome of Transportation behavior — moves item stacks between linked orbs at 1 block/tick.
 * The source is the [net.minecraft.world.Container] adjacent to the SEND orb's active face;
 * the sink is the container adjacent to each RECEIVE orb's active face. Everything else —
 * round-robin, in-flight tracking, visual flight, bounce-on-failure — is shared with the
 * other item-mover tomes via [ItemMoverTomeOrbBehavior].
 */
object TransportationTomeOrbBehavior : ItemMoverTomeOrbBehavior() {

    override val tomeKind: ResourceLocation = EnderkinesisMod.id("tome_of_transportation")

    /** Pull one item from the active-face container, honouring the SEND orb's filter
     *  (empty filter = pull anything; set filter = only matching items). Returns null if
     *  there's no container or nothing filter-eligible. */
    override fun pullSourceStack(level: ServerLevel, sendBe: OrbOfLinkingBlockEntity): ItemStack? {
        val sourceFacing = sendBe.facing
        val sourcePos = sendBe.blockPos.relative(sourceFacing)
        val source = resolveContainer(level, sourcePos) ?: return null
        return pullOneItem(source, sourceFacing.opposite, sendBe.filter)
    }

    /** Peek the next item *without* removing it — feeds the dispatch-time receiver filter
     *  so we don't dispatch a stack the destination can't actually take. Honours the SEND
     *  filter the same way [pullSourceStack] does. */
    override fun peekSourceStack(level: ServerLevel, sendBe: OrbOfLinkingBlockEntity): ItemStack? {
        val sourceFacing = sendBe.facing
        val sourcePos = sendBe.blockPos.relative(sourceFacing)
        val source = resolveContainer(level, sourcePos) ?: return null
        return peekOneItem(source, sourceFacing.opposite, sendBe.filter)
    }

    // pushBackToSource is the base default — push into the active-face container, which is
    // the same container we pulled from. No override needed.

    /** Item-aware destination check: the receiver must (a) be a container, (b) match the
     *  RECEIVE orb's own filter if one is set, and (c) have a slot that can actually
     *  accept [stack]. Filter check is RECEIVE-side — a RECEIVE orb filtered to dragon's
     *  breath refuses any other item even if its container has room. */
    override fun canReceiverAccept(
        level: ServerLevel, recvBe: OrbOfLinkingBlockEntity, stack: ItemStack,
    ): Boolean {
        if (!matchesFilter(recvBe.filter, stack)) return false
        val recvFacing = recvBe.facing
        val destPos = recvBe.blockPos.relative(recvFacing)
        val dest = resolveContainer(level, destPos) ?: return false
        return hasSpaceFor(dest, recvFacing.opposite, stack)
    }
}
