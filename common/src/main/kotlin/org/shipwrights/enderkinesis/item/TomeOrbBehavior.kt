package org.shipwrights.enderkinesis.item

import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import org.shipwrights.enderkinesis.blockentity.OrbOfLinkingBlockEntity
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-tome lifecycle hooks for the generic orb network. Each tome (Signal, Binding, Chaining,
 * future) implements this to do its specific work at link/unlink/tick time without the BE
 * having to know about every tome.
 *
 *  - [onLinked] fires once after a (send, receiver) pair is recorded by
 *    [OrbOfLinkingBlockEntity.addOutgoingLink].
 *  - [onUnlinking] fires once *before* a pair is severed (sneak-click, dismantle, block break,
 *    runtime drop). Tear down per-pair state here (e.g. release joints).
 *  - [serverTick] runs every server game tick for every SEND orb of this tome — use it for
 *    chunk-load rebind, distance enforcement, periodic checks. Default no-op.
 *  - [onNeighborChanged] runs when the block's vanilla `neighborChanged` fires AND this orb
 *    has outgoing links for this tome. Signal uses it to re-sample redstone input.
 *  - [onIncomingChanged] runs when a peer SEND has propagated changed state to this orb (as
 *    a RECEIVE end) for this tome. Signal uses it to recompute the receiver's aggregate POWER.
 *
 * Implementations are stateless — all per-orb / per-link state lives on the BE, accessed via
 * its per-(tomeKind, peerPos) joint and metadata accessors. Register an instance against a
 * tome-kind via [TomeOrbBehaviors.register].
 */
interface TomeOrbBehavior {

    /** The tome this behavior owns. Implementations pass this to the BE's per-link accessors
     *  to scope reads / writes to their own links. */
    val tomeKind: ResourceLocation

    fun onLinked(level: ServerLevel, sendBe: OrbOfLinkingBlockEntity, receiverPos: BlockPos) {}
    fun onUnlinking(level: ServerLevel, sendBe: OrbOfLinkingBlockEntity, receiverPos: BlockPos) {}
    fun serverTick(level: ServerLevel, sendBe: OrbOfLinkingBlockEntity) {}
    fun onNeighborChanged(level: ServerLevel, sendBe: OrbOfLinkingBlockEntity) {}
    /** Called on a RECEIVE-end orb when a peer SEND has updated something this tome cares
     *  about (Signal: a sender's signal level changed; the receiver should recompute aggregate
     *  POWER). The BE invokes this through [TomeOrbBehaviors] after symmetric mutations. */
    fun onIncomingChanged(level: ServerLevel, recvBe: OrbOfLinkingBlockEntity) {}

    /** Called once on the first server tick after this orb's chunk loads, for every tome the
     *  orb participates in (either direction). Tomes use it to refresh state that may have
     *  gone stale while the chunk was unloaded — Signal re-samples redstone neighbours on
     *  sender orbs and recomputes aggregate POWER on receivers; joint tomes' rebind happens
     *  through their per-tick logic and need not implement this. */
    fun onLoad(level: ServerLevel, be: OrbOfLinkingBlockEntity) {}

    /** Optional per-orb state factory. The first time a behavior asks for state via
     *  [OrbOfLinkingBlockEntity.tomeState] it lazily creates one via this method; subsequent
     *  calls return the same instance. State is **transient** — not persisted across chunk
     *  reload — so it suits round-robin cursors, in-flight tracking, and other ephemeral
     *  bookkeeping. Tomes that need none can leave the default null. */
    fun createState(): Any? = null
}

/**
 * Tome-kind → [TomeOrbBehavior] registry. Each tome registers its own implementation at mod
 * init (alongside [TomeBeamPalette.register]); the BE looks up the right behavior via
 * [behaviorFor] on every lifecycle hook.
 */
object TomeOrbBehaviors {

    private val map = ConcurrentHashMap<ResourceLocation, TomeOrbBehavior>()

    fun register(kind: ResourceLocation, behavior: TomeOrbBehavior) {
        map[kind] = behavior
    }

    /** Look up the behavior for [kind]. Null is a valid return — it means the tome has no
     *  custom lifecycle (e.g., Tome of Signal whose entire behaviour is the generic BE +
     *  redstone wiring), and the BE just skips the hook. */
    fun behaviorFor(kind: ResourceLocation?): TomeOrbBehavior? = kind?.let { map[it] }
}
