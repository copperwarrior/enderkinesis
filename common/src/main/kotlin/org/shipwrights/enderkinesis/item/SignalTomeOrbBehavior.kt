package org.shipwrights.enderkinesis.item

import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.blockentity.OrbOfLinkingBlockEntity

/**
 * Tome of Signal behavior — the only tome whose per-link state lives in the BE's `inputSignal`
 * cache rather than per-link metadata. Each SEND orb reads the strongest neighbouring redstone
 * level and pushes it to every linked receiver; receivers with multiple incoming Signal links
 * carry the max across all loaded senders.
 *
 *  - [onLinked]: bring the fresh receiver up to date by pushing the current `inputSignal` and
 *    by triggering its `onIncomingChanged` so its aggregate POWER reflects the new sender.
 *  - [onUnlinking]: tell the soon-to-be-ex receiver to recompute aggregate POWER without us.
 *  - [onNeighborChanged]: re-sample neighbour redstone; if our `inputSignal` changed, propagate
 *    a fresh value to every loaded receiver (which then each recompute their aggregate).
 *  - [onIncomingChanged]: receiver-side hook — walk every incoming Signal sender's `inputSignal`,
 *    take the max, stamp it into the receiver's POWER blockstate.
 *  - [serverTick]: no-op. Signal reacts to neighbour changes, not on a timer.
 *
 * Distance + dim rules: receivers further than [OrbOfLinkingBlockEntity.MAX_LINK_DISTANCE]
 * (world-space, ship-inclusive) silently carry 0 instead of the sender's level. Unloaded
 * receivers drop out of the max naturally (the BE lookup returns null). When a sender
 * re-loads, [onChunkLoad] fires a one-shot propagation so the receiver picks the signal back
 * up on its first server tick.
 */
object SignalTomeOrbBehavior : TomeOrbBehavior {

    override val tomeKind: ResourceLocation = EnderkinesisMod.id("tome_of_signal")

    override fun onLinked(level: ServerLevel, sendBe: OrbOfLinkingBlockEntity, receiverPos: BlockPos) {
        // Sample fresh — `inputSignal` may have been stale (the sender hasn't seen a neighbour
        // change since loading). Also doubles as the post-link initial propagation.
        sendBe.inputSignal = readActiveFaceSignal(level, sendBe)
        propagateFrom(level, sendBe)
    }

    override fun onUnlinking(level: ServerLevel, sendBe: OrbOfLinkingBlockEntity, receiverPos: BlockPos) {
        // The BE removes the link after this hook returns and then fires onIncomingChanged on
        // the peer; that will recompute the receiver's aggregate POWER without us in the mix.
        // Nothing to do here.
    }

    override fun onNeighborChanged(level: ServerLevel, sendBe: OrbOfLinkingBlockEntity) {
        val sampled = readActiveFaceSignal(level, sendBe)
        if (sampled == sendBe.inputSignal) return
        sendBe.inputSignal = sampled
        propagateFrom(level, sendBe)
    }

    override fun onIncomingChanged(level: ServerLevel, recvBe: OrbOfLinkingBlockEntity) {
        recvBe.applyPower(level, computeAggregatePower(level, recvBe))
    }

    /** First server tick after this orb's chunk loads. The receiver's POWER blockstate is
     *  loaded from disk and might lag behind the senders' current signals (a lever toggled
     *  while this chunk was unloaded would never have fired the propagate-on-change path).
     *
     *  - SEND side: re-sample neighbour redstone, push fresh state to every loaded receiver.
     *  - RECEIVE side: recompute aggregate POWER from whichever senders are currently loaded.
     *    Senders that haven't loaded yet will themselves push to this receiver when they do.
     *
     *  An orb can be both a sender and a receiver of *different* tomes, but since this hook
     *  is scoped to the Signal tome specifically, the two branches are mutually exclusive
     *  (the orb direction-commitment rule forbids being both directions for one tome). */
    override fun onLoad(level: ServerLevel, be: OrbOfLinkingBlockEntity) {
        if (be.outgoingPeers(tomeKind).isNotEmpty()) {
            be.inputSignal = readActiveFaceSignal(level, be)
            propagateFrom(level, be)
        }
        if (be.incomingPeers(tomeKind).isNotEmpty()) {
            be.applyPower(level, computeAggregatePower(level, be))
        }
    }

    /** Read the redstone signal arriving on the orb's active face — whatever the block in
     *  the [OrbOfLinkingBlockEntity.facing] direction is providing on its face touching us.
     *  `Level.getSignal` collapses the support's own emission with its best-neighbour signal
     *  when the support is a redstone conductor, so a lever attached to any side of a solid
     *  support still reaches the orb. */
    private fun readActiveFaceSignal(level: ServerLevel, be: OrbOfLinkingBlockEntity): Int {
        val facing = be.facing
        val supportPos = be.blockPos.relative(facing)
        return level.getSignal(supportPos, facing)
    }

    /** Push fresh state to every receiver of [sendBe] for this tome, in-range and loaded. */
    private fun propagateFrom(level: ServerLevel, sendBe: OrbOfLinkingBlockEntity) {
        for (receiverPos in sendBe.outgoingPeers(tomeKind)) {
            val recvBe = level.getBlockEntity(receiverPos) as? OrbOfLinkingBlockEntity ?: continue
            recvBe.applyPower(level, computeAggregatePower(level, recvBe))
        }
    }

    /** Aggregate POWER for [recvBe] = max over every loaded, in-range incoming Signal sender's
     *  current `inputSignal`. Out-of-range / unloaded senders contribute 0 (i.e. drop out of
     *  the max) — same rule the single-sender version of the code used. */
    private fun computeAggregatePower(level: ServerLevel, recvBe: OrbOfLinkingBlockEntity): Int {
        val recvCentre = recvBe.worldCenter() ?: return 0
        val maxSq = OrbOfLinkingBlockEntity.MAX_LINK_DISTANCE * OrbOfLinkingBlockEntity.MAX_LINK_DISTANCE
        var best = 0
        for (senderPos in recvBe.incomingPeers(tomeKind)) {
            val sendBe = level.getBlockEntity(senderPos) as? OrbOfLinkingBlockEntity ?: continue
            val sendCentre = sendBe.worldCenter() ?: continue
            val dx = sendCentre.x - recvCentre.x
            val dy = sendCentre.y - recvCentre.y
            val dz = sendCentre.z - recvCentre.z
            if (dx * dx + dy * dy + dz * dz > maxSq) continue
            if (sendBe.inputSignal > best) best = sendBe.inputSignal
            if (best >= 15) return 15                       // saturated
        }
        return best
    }
}
