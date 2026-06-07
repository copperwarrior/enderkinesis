package org.shipwrights.enderkinesis.item

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.Container
import net.minecraft.world.WorldlyContainer
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.ChestBlock
import org.shipwrights.enderkinesis.blockentity.OrbOfLinkingBlockEntity

/**
 * Shared machinery for tomes that move item stacks between linked orbs at a fixed pace
 * (Transportation, Vacuum, future). Subclasses contribute:
 *
 *  - The tome identity ([tomeKind], inherited from [TomeOrbBehavior]).
 *  - [pullSourceStack] — how to acquire the next stack to dispatch this tick.
 *  - [pushBackToSource] — where a bounced-back stack ends up at the SEND end. Default
 *    drops as an item entity at the SEND orb's air-side; container-backed tomes
 *    override to push into the container instead.
 *
 * Everything else — round-robin dispatch across receivers, in-flight tracking, delivery,
 * bounce-on-failure (outbound ↔ return), broadcasting the dispatch packet — is the same
 * for every subclass and lives here. State is held per-orb via
 * [OrbOfLinkingBlockEntity.tomeState] (one [MoverState] per orb per tome) so two
 * subclasses on the same orb are fully isolated.
 *
 * The visual flight uses [TransportationNetwork.DISPATCH] regardless of subclass — the
 * client doesn't need to distinguish; both look like an item flying between two orbs. Per-
 * tome differentiation lives in the beam accent ([TomeBeamPalette]).
 */
abstract class ItemMoverTomeOrbBehavior : TomeOrbBehavior {

    /** Server ticks per block of separation. 20 = 1 block/sec. */
    protected open val ticksPerBlock: Double = 20.0
    /** Floor on travel time so adjacent or sub-block links still take a visible beat. */
    protected open val minTravelTicks: Int = 20
    /** Maximum in-flight dispatches per SEND orb. Caps new outbound dispatches; bounces
     *  scheduled via [scheduleBounce] bypass this cap so items aren't silently lost. */
    protected open val maxInFlightPerSend: Int = 16
    /** Drop offset from the orb's block centre into its air-side, used by [dropAt] so
     *  fallback drops don't spawn inside the orb's voxel shape. */
    protected open val dropOffsetFromCentre: Double = 0.4

    // ---------------------------------------------------------------------------------------

    /** Per-(send orb) state: the round-robin cursor + the pending deliveries we owe. Open
     *  so subclass tomes can attach their own per-orb scratch (e.g. Disintegration's drop
     *  queue + per-block mining progress) without losing the base mover bookkeeping. */
    open class MoverState {
        var cursor: Int = 0
        val pending: MutableList<PendingDelivery> = mutableListOf()
    }

    /** A queued delivery — at [deliveryTick] (server game time) we perform the actual stack
     *  move. `(sendPos, receiverPos)` stays constant for the life of the dispatch; only
     *  [returning] flips when a delivery fails and the stack bounces back. */
    data class PendingDelivery(
        val stack: ItemStack,
        val sendPos: BlockPos,
        val receiverPos: BlockPos,
        val returning: Boolean,
        val deliveryTick: Long,
    )

    override fun createState(): Any = MoverState()

    // ---------------------------------------------------------------------------------------
    // Subclass hooks

    /** Acquire the next stack to dispatch this tick. Returning null skips the dispatch (no
     *  source available — empty container, no items in range, etc.). */
    protected abstract fun pullSourceStack(level: ServerLevel, sendBe: OrbOfLinkingBlockEntity): ItemStack?

    /** Look at the next stack we *would* pull from the source — without removing it. Used by
     *  [tryDispatch] to pre-check destination compatibility (so a chest full of items that
     *  don't stack with what we'd send is correctly treated as "no room for *this* item",
     *  not as "has space" because of a half-full unrelated slot).
     *
     *  Returns null if there's nothing extractable right now. The returned stack is for
     *  inspection only; subclasses don't need to guarantee that a later [pullSourceStack]
     *  pulls the exact same stack (the source could change between calls in the same tick),
     *  but they should return something representative of "what we'd pull *now*". */
    protected abstract fun peekSourceStack(level: ServerLevel, sendBe: OrbOfLinkingBlockEntity): ItemStack?

    /** Place a bounced-back stack at the SEND end. Return the leftover that couldn't fit
     *  into the SEND-end container (empty = fully accepted; [processReturn] will drop any
     *  leftover at the SEND orb rather than bouncing it forward, so an infinite loop can't
     *  form when both source and receiver are saturated).
     *
     *  Default: push into the [Container] at the SEND orb's active face (where
     *  Transportation pulls from, and where a user can optionally place a chest behind a
     *  Vacuum orb to catch bounce-backs). If no container exists there, return the stack
     *  unchanged — [processReturn] will drop it on the floor. */
    protected open fun pushBackToSource(
        level: ServerLevel, sendBe: OrbOfLinkingBlockEntity, stack: ItemStack,
    ): ItemStack {
        val sourceFacing = sendBe.facing
        val sourcePos = sendBe.blockPos.relative(sourceFacing)
        val source = resolveContainer(level, sourcePos) ?: return stack
        return pushStack(source, sourceFacing.opposite, stack)
    }

    /** Will [stack] *specifically* land cleanly at [recvBe] right now? Used by [tryDispatch]
     *  to skip receivers that have no room for the item we'd actually dispatch — not just
     *  "no room for anything", which is too permissive: a chest with 26 maxed-out dragon's
     *  breath slots plus 1 slot of half-stack stone has *space* generically, but it can't
     *  accept another dragon's breath bottle. Dispatching anyway produces the bouncing
     *  oscillation the user reported.
     *
     *  Default: assume yes (subclasses that don't have a destination concept don't filter).
     *  Subclasses with a known destination container should check item-aware: empty slots
     *  (which would accept [stack]), or partially-full slots whose existing item matches
     *  [stack] and isn't yet at max stack size. */
    protected open fun canReceiverAccept(
        level: ServerLevel, recvBe: OrbOfLinkingBlockEntity, stack: ItemStack,
    ): Boolean = true

    // ---------------------------------------------------------------------------------------

    override fun serverTick(level: ServerLevel, sendBe: OrbOfLinkingBlockEntity) {
        val state = sendBe.tomeState(this) as? MoverState ?: return
        processDeliveries(level, sendBe, state)
        tryDispatch(level, sendBe, state)
    }

    override fun onUnlinking(level: ServerLevel, sendBe: OrbOfLinkingBlockEntity, receiverPos: BlockPos) {
        // Any in-flight delivery involving the severed receiver — outbound or returning —
        // drops at the receiver pos so stacks don't oscillate between a no-longer-linked
        // pair or deliver into a stranger's container.
        val state = sendBe.tomeState(this) as? MoverState ?: return
        val iter = state.pending.iterator()
        while (iter.hasNext()) {
            val d = iter.next()
            if (d.receiverPos == receiverPos) {
                dropAt(level, receiverPos, d.stack)
                iter.remove()
            }
        }
    }

    // ---------------------------------------------------------------------------------------

    private fun processDeliveries(
        level: ServerLevel,
        sendBe: OrbOfLinkingBlockEntity,
        state: MoverState,
    ) {
        if (state.pending.isEmpty()) return
        val now = level.gameTime
        val arrivals = ArrayList<PendingDelivery>()
        val iter = state.pending.iterator()
        while (iter.hasNext()) {
            val d = iter.next()
            if (now >= d.deliveryTick) {
                arrivals.add(d)
                iter.remove()
            }
        }
        for (d in arrivals) {
            if (d.returning) processReturn(level, sendBe, state, d)
            else processOutbound(level, sendBe, state, d)
        }
    }

    /** Outbound arrival — push into the receiver's container; on a delivery failure,
     *  schedule a visible return flight back to the SEND orb. The return is the user-
     *  facing signal that the destination wouldn't take this stack ("send it back up the
     *  line to the sender"); item-aware [canReceiverAccept] in [tryDispatch] is what
     *  prevents this from becoming a sustained oscillation, by refusing to dispatch in
     *  the first place when the destination has no room for the item we'd send. */
    private fun processOutbound(
        level: ServerLevel,
        sendBe: OrbOfLinkingBlockEntity,
        state: MoverState,
        d: PendingDelivery,
    ) {
        val recvBe = level.getBlockEntity(d.receiverPos) as? OrbOfLinkingBlockEntity
        val targetContainer: Container? = if (recvBe != null) {
            resolveContainer(level, d.receiverPos.relative(recvBe.facing))
        } else null
        val recvFacing = recvBe?.facing
        val leftover = if (targetContainer != null && recvFacing != null) {
            pushStack(targetContainer, recvFacing.opposite, d.stack)
        } else d.stack
        if (leftover.isEmpty) return                  // delivered cleanly
        scheduleBounce(level, state, d.copy(stack = leftover))
    }

    /** Return arrival — push back to source via [pushBackToSource]; on leftover, drop at
     *  the SEND orb rather than bouncing forward again. A second forward bounce would just
     *  re-hit the same full receiver and oscillate forever. */
    private fun processReturn(
        level: ServerLevel,
        sendBe: OrbOfLinkingBlockEntity,
        state: MoverState,
        d: PendingDelivery,
    ) {
        val leftover = pushBackToSource(level, sendBe, d.stack)
        if (leftover.isEmpty) return                  // accepted into source
        dropAt(level, sendBe.blockPos, leftover)      // source full too — pile at the SEND
    }

    /** Re-queue [d] as a return delivery (receiver→sender) and broadcast the visual flight.
     *  Bounces bypass [maxInFlightPerSend] — the slot was already counted when the original
     *  outbound was scheduled. */
    private fun scheduleBounce(
        level: ServerLevel,
        state: MoverState,
        d: PendingDelivery,
    ) {
        val sendCentre = OrbOfLinkingBlockEntity.orbWorldCenter(level, d.sendPos)
        val recvCentre = OrbOfLinkingBlockEntity.orbWorldCenter(level, d.receiverPos)
        if (sendCentre == null || recvCentre == null) {
            dropAt(level, d.receiverPos, d.stack)
            return
        }
        val dist = sendCentre.distanceTo(recvCentre)
        val travelTicks = Math.max(minTravelTicks, Math.ceil(dist / ticksPerBlock).toInt())
        state.pending.add(d.copy(
            returning = true,
            deliveryTick = level.gameTime + travelTicks,
        ))
        TransportationNetwork.broadcastDispatch(
            level = level,
            sendPos = d.receiverPos,
            receiverPos = d.sendPos,
            stack = d.stack,
            totalTicks = travelTicks,
        )
    }

    /** Round-robin dispatch with destination filtering: scan receivers starting from the
     *  cursor, pick the first one whose destination is currently accepting (per
     *  [canReceiverAccept]), pull from the source, and queue the delivery. If **every**
     *  receiver's destination is full, skip the tick without touching the source — items
     *  stay where they are instead of dispatching, bouncing back, and oscillating.
     *
     *  The cursor advances only on a successful dispatch and is anchored on the receiver
     *  we actually chose (so fairness is preserved across saturation windows). On a fully-
     *  saturated tick the cursor doesn't move; the next tick will start from the same
     *  receiver and the same scan will run again, cheaply, until *something* drains. */
    private fun tryDispatch(
        level: ServerLevel,
        sendBe: OrbOfLinkingBlockEntity,
        state: MoverState,
    ) {
        if (state.pending.size >= maxInFlightPerSend) return
        val receivers = sendBe.outgoingPeers(tomeKind)
        if (receivers.isEmpty()) return

        val sendCentre = sendBe.worldCenter() ?: return
        val startIdx = state.cursor % receivers.size

        // Peek what we'd actually dispatch — destination filtering has to be item-aware so
        // a "full chest of unrelated items" is recognised as having no room for *this*
        // stack (the false-positive that was driving the bounce loop).
        val candidate = peekSourceStack(level, sendBe) ?: return    // nothing to send this tick

        // Filter priority: if any receiver in this SEND's set has a filter that *specifies*
        // this candidate's item type, only those filter-matching receivers are eligible —
        // never fall through to a catch-all (empty-filter) receiver while a dedicated
        // receiver exists. If the dedicated receivers are all full, this tick is a no-op
        // (the catch-all is genuinely a fallback for *unmatched* items, not an overflow).
        val onlyFiltered = receivers.any { pos ->
            val recvBe = level.getBlockEntity(pos) as? OrbOfLinkingBlockEntity ?: return@any false
            !recvBe.filter.isEmpty && matchesFilter(recvBe.filter, candidate)
        }

        // Walk receivers in round-robin order from the cursor; pick the first whose
        // destination can take this specific item, respecting the filter-priority rule.
        var chosenIdx = -1
        var chosenPos: BlockPos? = null
        var chosenRecvCentre: net.minecraft.world.phys.Vec3? = null
        for (offset in receivers.indices) {
            val idx = (startIdx + offset) % receivers.size
            val pos = receivers[idx]
            val recvBe = level.getBlockEntity(pos) as? OrbOfLinkingBlockEntity ?: continue
            val recvCentre = OrbOfLinkingBlockEntity.orbWorldCenter(level, pos) ?: continue
            if (onlyFiltered && recvBe.filter.isEmpty) continue     // skip catch-alls
            if (!canReceiverAccept(level, recvBe, candidate)) continue
            chosenIdx = idx
            chosenPos = pos
            chosenRecvCentre = recvCentre
            break
        }
        if (chosenPos == null || chosenRecvCentre == null) return  // no receiver wants it

        val pulled = pullSourceStack(level, sendBe) ?: return       // raced — source emptied
        state.cursor = (chosenIdx + 1) % receivers.size

        val dist = sendCentre.distanceTo(chosenRecvCentre)
        val travelTicks = Math.max(minTravelTicks, Math.ceil(dist / ticksPerBlock).toInt())

        state.pending.add(PendingDelivery(
            stack = pulled,
            sendPos = sendBe.blockPos,
            receiverPos = chosenPos,
            returning = false,
            deliveryTick = level.gameTime + travelTicks,
        ))

        TransportationNetwork.broadcastDispatch(
            level = level,
            sendPos = sendBe.blockPos,
            receiverPos = chosenPos,
            stack = pulled,
            totalTicks = travelTicks,
        )
    }

    /** True iff [candidate] passes [filter] — empty filter matches
     *  everything; otherwise the filter is parsed into an [OrbFilter]
     *  predicate and asked. Plain item stacks compile to a single
     *  exact-item rule (cheap); written / writable books compile to
     *  one rule per page line — supporting `#minecraft:arrows` item
     *  tags, `#minecraft:sand` block tags, `minecraft:diamond_sword{nbt}`
     *  subset-NBT matches, and `minecraft:diamond_sword[enchantments={…}]`
     *  bracket components.
     *
     *  Hot-path callers (Disintegration's per-tick block + entity
     *  checks) skip the parse by calling
     *  [OrbOfLinkingBlockEntity.orbFilter] directly, which caches
     *  the compiled predicate until the filter stack changes. */
    protected fun matchesFilter(filter: ItemStack, candidate: ItemStack): Boolean {
        if (filter.isEmpty) return true
        return OrbFilter.from(filter).matchesItem(candidate)
    }

    /** True iff [container] has at least one slot accessible from [side] that would accept
     *  [stack] right now. Item-aware mirror of [pushStack]'s placement logic — exactly the
     *  same checks (`canPlaceItemThroughFace`, `canPlaceItem`, [ItemStack.isSameItemSameTags],
     *  per-slot stack-size limit), so a `true` here means [pushStack] will actually find
     *  room for at least one unit of [stack], and a `false` means it won't.
     *
     *  Item-aware filtering is what makes the dispatch loop terminate when the receive
     *  chest is "full of unrelated items" — the item-agnostic earlier version reported
     *  any partial unrelated slot as "has space" and bounced every dispatch indefinitely. */
    protected fun hasSpaceFor(
        container: Container, side: Direction, stack: ItemStack,
    ): Boolean {
        if (stack.isEmpty) return true
        val slots: IntArray = if (container is WorldlyContainer) container.getSlotsForFace(side)
        else IntArray(container.containerSize) { it }
        val maxStack = container.maxStackSize
        for (slot in slots) {
            if (container is WorldlyContainer &&
                !container.canPlaceItemThroughFace(slot, stack, side)) continue
            val existing = container.getItem(slot)
            if (existing.isEmpty) {
                if (container.canPlaceItem(slot, stack)) return true
            } else if (ItemStack.isSameItemSameTags(existing, stack)) {
                val cap = Math.min(stack.maxStackSize, maxStack)
                if (existing.count < cap) return true
            }
        }
        return false
    }

    // ---------------------------------------------------------------------------------------
    // Protected helpers (used by subclasses + this base)

    /** Resolve the [Container] at [pos] — single-block container BEs directly, plus the
     *  combined [net.minecraft.world.CompoundContainer] for double chests via
     *  [ChestBlock.getContainer]. We do this ourselves rather than calling vanilla's
     *  hopper helper so we don't inherit hopper-related mod side effects. */
    protected fun resolveContainer(level: Level, pos: BlockPos): Container? {
        val be = level.getBlockEntity(pos) as? Container ?: return null
        val state = level.getBlockState(pos)
        val block = state.block
        if (block is ChestBlock) {
            // `true` = ignore cat / blocked-on-top — we're a magical pipe, not a player.
            return ChestBlock.getContainer(block, state, level, pos, true) ?: be
        }
        return be
    }

    /** Look at the first item [pullOneItem] *would* pull, without modifying [container].
     *  Same scan order and same eligibility check as [pullOneItem] — used by [tryDispatch]
     *  to feed [canReceiverAccept] an item-aware test before committing to a pull. Returns
     *  a 1-count copy (the SAME shape [pullOneItem] would return).
     *
     *  When [filter] is non-empty, only slots whose stack matches the filter are eligible. */
    protected fun peekOneItem(
        container: Container, side: Direction, filter: ItemStack = ItemStack.EMPTY,
    ): ItemStack? {
        val slots: IntArray = if (container is WorldlyContainer) container.getSlotsForFace(side)
        else IntArray(container.containerSize) { it }
        for (slot in slots) {
            val stack = container.getItem(slot)
            if (stack.isEmpty) continue
            if (container is WorldlyContainer &&
                !container.canTakeItemThroughFace(slot, stack, side)) continue
            if (!matchesFilter(filter, stack)) continue
            return stack.copy().also { it.count = 1 }
        }
        return null
    }

    /** Pull **one item** from [container] through its [side] face. First non-empty
     *  accessible slot wins (vanilla-hopper scan order), and a single item is split off via
     *  [ItemStack.split] — the slot keeps the remainder. Returns null if no slot is eligible.
     *  One-per-tick is the standard for the item-mover tomes; full-stack moves would empty a
     *  chest in a couple of ticks and make the visual incoherent.
     *
     *  When [filter] is non-empty, only slots whose stack matches the filter are eligible —
     *  used by the SEND-side filter on Transportation. */
    protected fun pullOneItem(
        container: Container, side: Direction, filter: ItemStack = ItemStack.EMPTY,
    ): ItemStack? {
        val slots: IntArray = if (container is WorldlyContainer) container.getSlotsForFace(side)
        else IntArray(container.containerSize) { it }
        for (slot in slots) {
            val stack = container.getItem(slot)
            if (stack.isEmpty) continue
            if (container is WorldlyContainer &&
                !container.canTakeItemThroughFace(slot, stack, side)) continue
            if (!matchesFilter(filter, stack)) continue
            val pulled = stack.split(1)         // mutates `stack` in place; returns a 1-count copy
            container.setChanged()
            return pulled
        }
        return null
    }

    /** Push [stack] into [container] through its [side] face, vanilla-hopper merge rules.
     *  Returns the leftover (empty if fully accepted). */
    protected fun pushStack(container: Container, side: Direction, stack: ItemStack): ItemStack {
        if (stack.isEmpty) return stack
        var remaining = stack
        val slots: IntArray = if (container is WorldlyContainer) container.getSlotsForFace(side)
        else IntArray(container.containerSize) { it }
        var changed = false
        for (slot in slots) {
            if (remaining.isEmpty) break
            if (container is WorldlyContainer &&
                !container.canPlaceItemThroughFace(slot, remaining, side)) continue
            val existing = container.getItem(slot)
            if (existing.isEmpty) {
                if (!container.canPlaceItem(slot, remaining)) continue
                container.setItem(slot, remaining.copy())
                remaining = ItemStack.EMPTY
                changed = true
            } else if (ItemStack.isSameItemSameTags(existing, remaining)) {
                val maxStack = Math.min(existing.maxStackSize, container.maxStackSize)
                val canFit = maxStack - existing.count
                val transfer = Math.min(canFit, remaining.count)
                if (transfer > 0) {
                    existing.grow(transfer)
                    remaining.shrink(transfer)
                    container.setItem(slot, existing)
                    changed = true
                }
            }
        }
        if (changed) container.setChanged()
        return remaining
    }

    /** Spawn a player-pickable item entity for [stack] in the air-side of the orb at
     *  [pos]. Uses the orb's current FACING; defaults to DOWN if the block isn't an orb. */
    protected fun dropAt(level: ServerLevel, pos: BlockPos, stack: ItemStack) {
        if (stack.isEmpty) return
        val state = level.getBlockState(pos)
        val facing = if (state.hasProperty(org.shipwrights.enderkinesis.block.OrbOfLinkingBlock.FACING))
            state.getValue(org.shipwrights.enderkinesis.block.OrbOfLinkingBlock.FACING)
        else Direction.DOWN
        val airSide = facing.opposite
        val x = pos.x + 0.5 + airSide.stepX * dropOffsetFromCentre
        val y = pos.y + 0.5 + airSide.stepY * dropOffsetFromCentre
        val z = pos.z + 0.5 + airSide.stepZ * dropOffsetFromCentre
        val drop = ItemEntity(level, x, y, z, stack)
        drop.setDeltaMovement(0.0, 0.0, 0.0)
        level.addFreshEntity(drop)
    }
}
