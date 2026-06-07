package org.shipwrights.enderkinesis.blockentity

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.shipwrights.enderkinesis.block.OrbOfLinkingBlock
import org.shipwrights.enderkinesis.block.OrbRole
import org.shipwrights.enderkinesis.item.TomeOrbBehavior
import org.shipwrights.enderkinesis.item.TomeOrbBehaviors
import org.shipwrights.enderkinesis.registry.EKBlockEntities
import org.valkyrienskies.mod.common.getShipManagingPos

/**
 * Block-entity backing an [OrbOfLinkingBlock]. The orb is a **generic substrate** for the
 * whole tome suite: it stores zero or more links per tome, in either direction. An orb can
 * simultaneously be a SEND in one tome's network (e.g. Signal) and a RECEIVE in another
 * (e.g. Binding); within a single tome's network it can have multiple senders (multi-input
 * receiver) and/or multiple receivers (broadcast send).
 *
 * State is split into two maps keyed by [LinkKey] (tomeKind + peer position):
 *  - [outgoing] — pairs where THIS orb is the SEND end.
 *  - [incoming] — pairs where THIS orb is the RECEIVE end.
 *
 * Each [LinkData] carries the runtime joint id (for joint tomes like Binding/Chaining) and a
 * persistent metadata stash (for things like Chaining's locked rope length). Every mutator
 * keeps the two sides symmetric: adding an outgoing link on A automatically writes the mirror
 * incoming entry on B, and removing fires the symmetric drop plus the tome behavior's
 * `onUnlinking` hook.
 *
 * Signal-specific propagation lives in [org.shipwrights.enderkinesis.item.SignalTomeOrbBehavior]
 * — the BE just holds the tome-agnostic link bookkeeping plus an `inputSignal` cache the
 * Signal behavior populates (kept on the BE since the block's `neighborChanged` reads it).
 */
class OrbOfLinkingBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(EKBlockEntities.ORB_OF_LINKING.get(), pos, state) {

    /** All links where this orb sends to a peer. Iteration order is insertion order so the
     *  client beam renderer is stable across ticks. */
    private val outgoing: MutableMap<LinkKey, LinkData> = LinkedHashMap()
    /** All links where this orb receives from a peer. */
    private val incoming: MutableMap<LinkKey, LinkData> = LinkedHashMap()

    /** Signal-tome scratch — strongest neighbour redstone signal last sampled. Lives on the
     *  BE rather than per-link metadata because `neighborChanged` is BE-scoped, not link-
     *  scoped. Only [org.shipwrights.enderkinesis.item.SignalTomeOrbBehavior] writes it. */
    var inputSignal: Int = 0

    /** Per-orb item filter — set by [org.shipwrights.enderkinesis.item.TomeOfFilteringItem]
     *  via the right-click-with-offhand-item gesture. Empty = no filter (orb accepts /
     *  pulls any item). Currently consulted by the item-mover tomes (Transportation,
     *  Vacuum) on both the SEND side (only pull items matching the filter) and the RECEIVE
     *  side (only accept items matching the filter); other tomes ignore it. Stored as a
     *  1-count [ItemStack] so the held NBT (for filter strictness if extended later) is
     *  preserved without representing a stack quantity. */
    var filter: ItemStack = ItemStack.EMPTY
        private set

    /** Per-orb enchanted book — set by right-clicking the orb with an enchanted book in
     *  hand. Consulted by [org.shipwrights.enderkinesis.item.DisintegrationTomeOrbBehavior]
     *  for damage / mining / drop modifiers; other tomes ignore it. Storage is a single-
     *  count [ItemStack] so the book's enchantments (the whole point) are preserved. */
    var book: ItemStack = ItemStack.EMPTY
        private set

    /** True until the first server tick after load, after which it stays false. Drives the
     *  one-shot `onLoad` notification each tome behavior gets so it can refresh state that
     *  may have gone stale during chunk unload (Signal re-samples + propagates; joint tomes
     *  ignore it since their own tick already rebuilds). */
    private var pendingChunkLoadTick: Boolean = true

    /** The active-face direction — points from this orb toward its support block. Tomes that
     *  interface with the outside world (Signal, Transportation) read/write only on this
     *  side. Mirrors [OrbOfLinkingBlock.FACING]. */
    val facing: Direction get() = blockState.getValue(OrbOfLinkingBlock.FACING)

    /** Per-tome scratchpad — lazily populated via [TomeOrbBehavior.createState]. Transient,
     *  not persisted. Used by behaviors that need per-orb state outside the per-link maps
     *  (e.g. Transportation's round-robin cursor + in-flight delivery list). */
    private val tomeStates: MutableMap<ResourceLocation, Any> = HashMap()

    // --- Read-only views ---------------------------------------------------------------------

    /** All outgoing links keyed by (tomeKind, peerPos). Client uses for beam rendering. */
    fun allOutgoing(): Map<LinkKey, LinkData> = outgoing

    /** Receiver positions for [tomeKind]. */
    fun outgoingPeers(tomeKind: ResourceLocation): List<BlockPos> =
        outgoing.keys.asSequence().filter { it.tomeKind == tomeKind }.map { it.peerPos }.toList()

    /** Sender positions for [tomeKind]. */
    fun incomingPeers(tomeKind: ResourceLocation): List<BlockPos> =
        incoming.keys.asSequence().filter { it.tomeKind == tomeKind }.map { it.peerPos }.toList()

    /** Distinct tome kinds that have this orb as a SEND. Used by the block's `neighborChanged`
     *  to fire `onNeighborChanged` once per affected tome. */
    fun outgoingTomes(): Set<ResourceLocation> = outgoing.keys.mapTo(LinkedHashSet()) { it.tomeKind }

    /** Whether a specific outgoing link exists (used by tomes' toggle-off gesture). */
    fun hasOutgoingLink(tomeKind: ResourceLocation, receiverPos: BlockPos): Boolean =
        outgoing.containsKey(LinkKey(tomeKind, receiverPos))

    /** True if this orb currently has any outgoing links and no incoming. Locked into the
     *  "sender" direction across every tome it participates in until all its outgoing links
     *  are dropped. */
    fun isPureSend(): Boolean = outgoing.isNotEmpty() && incoming.isEmpty()

    /** True if this orb currently has any incoming links and no outgoing. Locked into the
     *  "receiver" direction across every tome until all its incoming links are dropped. */
    fun isPureReceive(): Boolean = incoming.isNotEmpty() && outgoing.isEmpty()

    /** True when the orb has no links of any tome in either direction — free to commit
     *  either way on the next link. */
    fun isIdle(): Boolean = outgoing.isEmpty() && incoming.isEmpty()

    // --- Mutators ----------------------------------------------------------------------------

    /** Add a SEND→RECEIVE link from this orb to [receiverPos] for [tomeKind]. Writes both the
     *  outgoing entry on this BE and the mirror incoming entry on the peer; fires
     *  `behavior.onLinked` after both ends are in place. Returns false if the link already
     *  existed or the peer's BE is missing. */
    fun addOutgoingLink(level: ServerLevel, tomeKind: ResourceLocation, receiverPos: BlockPos): Boolean {
        if (receiverPos == blockPos) return false           // self-loops are nonsense
        val key = LinkKey(tomeKind, receiverPos)
        if (outgoing.containsKey(key)) return false
        val peer = level.getBlockEntity(receiverPos) as? OrbOfLinkingBlockEntity ?: return false
        // Direction-commitment rule: a pure-receive orb can never become a sender, and a
        // pure-send orb can never become a receiver. The tome's gesture flow filters these
        // up-front for a better message; this is a defensive guard for any other caller.
        if (isPureReceive()) return false
        if (peer.isPureSend()) return false
        outgoing[key] = LinkData()
        peer.incoming[LinkKey(tomeKind, blockPos)] = LinkData()
        sync()
        peer.sync()
        TomeOrbBehaviors.behaviorFor(tomeKind)?.onLinked(level, this, receiverPos)
        return true
    }

    /** Remove the SEND→RECEIVE link from this orb to [receiverPos] for [tomeKind]. Fires
     *  `behavior.onUnlinking` first (so it can tear down joints with the link state still
     *  intact), then clears both ends. Returns false if no such link existed. */
    fun removeOutgoingLink(level: ServerLevel, tomeKind: ResourceLocation, receiverPos: BlockPos): Boolean {
        val key = LinkKey(tomeKind, receiverPos)
        if (!outgoing.containsKey(key)) return false
        TomeOrbBehaviors.behaviorFor(tomeKind)?.onUnlinking(level, this, receiverPos)
        outgoing.remove(key)
        val peer = level.getBlockEntity(receiverPos) as? OrbOfLinkingBlockEntity
        if (peer != null) {
            peer.incoming.remove(LinkKey(tomeKind, blockPos))
            peer.sync()
            // Symmetric: notify peer's tome behavior that its incoming changed (Signal uses
            // this to recompute its aggregate POWER after losing one sender).
            TomeOrbBehaviors.behaviorFor(tomeKind)?.onIncomingChanged(level, peer)
        }
        sync()
        return true
    }

    /** Drop every link for [tomeKind] through this orb, in either direction. The sneak-click
     *  gesture uses this — "the player wants this tome to leave this orb alone". */
    fun removeAllForTome(level: ServerLevel, tomeKind: ResourceLocation) {
        val outgoingPeers = outgoingPeers(tomeKind)
        for (peer in outgoingPeers) removeOutgoingLink(level, tomeKind, peer)
        val incomingPeers = incomingPeers(tomeKind)
        for (peer in incomingPeers) {
            val sendBe = level.getBlockEntity(peer) as? OrbOfLinkingBlockEntity ?: continue
            sendBe.removeOutgoingLink(level, tomeKind, blockPos)
        }
    }

    /** Block destroyed (or replaced). Clean up every link through this orb across all tomes,
     *  preserving the symmetric onUnlinking notifications. */
    fun onBlockDestroyed(level: ServerLevel) {
        for (key in outgoing.keys.toList()) {
            removeOutgoingLink(level, key.tomeKind, key.peerPos)
        }
        for (key in incoming.keys.toList()) {
            val sendBe = level.getBlockEntity(key.peerPos) as? OrbOfLinkingBlockEntity ?: continue
            sendBe.removeOutgoingLink(level, key.tomeKind, blockPos)
        }
    }

    /** Server-tick entry called by the block's ticker. On the first tick after load, fires
     *  `onLoad` once per tome the orb participates in (either direction) — gives Signal a
     *  chance to refresh propagation after chunks unloaded with stale state. Then fires
     *  `serverTick` for each outgoing tome on every tick — joint tomes use it for rebind /
     *  distance enforcement; Signal's is a no-op. */
    fun serverTick(level: ServerLevel) {
        if (pendingChunkLoadTick) {
            pendingChunkLoadTick = false
            val tomes = LinkedHashSet<net.minecraft.resources.ResourceLocation>()
            for (k in outgoing.keys) tomes.add(k.tomeKind)
            for (k in incoming.keys) tomes.add(k.tomeKind)
            for (kind in tomes) {
                TomeOrbBehaviors.behaviorFor(kind)?.onLoad(level, this)
            }
        }
        for (kind in outgoingTomes()) {
            TomeOrbBehaviors.behaviorFor(kind)?.serverTick(level, this)
        }
    }

    /** Called by the block's `neighborChanged`. Each outgoing tome's behavior decides whether
     *  the change is interesting (Signal does; joint tomes don't). */
    fun onNeighborChanged(level: ServerLevel) {
        for (kind in outgoingTomes()) {
            TomeOrbBehaviors.behaviorFor(kind)?.onNeighborChanged(level, this)
        }
    }

    // --- Per-link accessors (joint state, metadata) ------------------------------------------

    /** Return the per-orb scratchpad for [behavior], lazy-creating it via
     *  [TomeOrbBehavior.createState] on first access. Null if the behavior's factory returned
     *  null (the default). Callers cast to their state's concrete type. */
    fun tomeState(behavior: TomeOrbBehavior): Any? {
        val existing = tomeStates[behavior.tomeKind]
        if (existing != null) return existing
        val fresh = behavior.createState() ?: return null
        tomeStates[behavior.tomeKind] = fresh
        return fresh
    }

    fun getJointId(tomeKind: ResourceLocation, peerPos: BlockPos): Int =
        outgoing[LinkKey(tomeKind, peerPos)]?.jointId ?: -1

    fun setJointId(tomeKind: ResourceLocation, peerPos: BlockPos, id: Int) {
        val data = outgoing[LinkKey(tomeKind, peerPos)] ?: return
        data.jointId = id
        data.jointPending = false
    }

    fun clearJointId(tomeKind: ResourceLocation, peerPos: BlockPos): Int {
        val data = outgoing[LinkKey(tomeKind, peerPos)] ?: return -1
        val id = data.jointId
        data.jointId = -1
        data.jointPending = false
        return id
    }

    fun isJointPending(tomeKind: ResourceLocation, peerPos: BlockPos): Boolean =
        outgoing[LinkKey(tomeKind, peerPos)]?.jointPending ?: false

    fun markJointPending(tomeKind: ResourceLocation, peerPos: BlockPos) {
        outgoing[LinkKey(tomeKind, peerPos)]?.jointPending = true
    }

    fun clearJointPending(tomeKind: ResourceLocation, peerPos: BlockPos) {
        outgoing[LinkKey(tomeKind, peerPos)]?.jointPending = false
    }

    fun getLinkMetadata(tomeKind: ResourceLocation, peerPos: BlockPos): CompoundTag? =
        outgoing[LinkKey(tomeKind, peerPos)]?.metadata

    fun setLinkMetadata(tomeKind: ResourceLocation, peerPos: BlockPos, tag: CompoundTag) {
        val data = outgoing[LinkKey(tomeKind, peerPos)] ?: return
        data.metadata = tag
        setChanged()
    }

    // --- Signal-specific helper called by SignalTomeOrbBehavior ------------------------------

    /** Stamp [newPower] into the blockstate's POWER property, firing neighbour updates if it
     *  actually changed. Called by Signal's propagate path on receivers; the BE doesn't
     *  interpret the value.
     *
     *  Reads the **world** state (not the BE's cached `blockState`) and bails if the orb is
     *  gone. During destruction, vanilla sets `pos` to air *before* `Block.onRemove` runs;
     *  if a setBlock then re-placed the orb here it would resurrect the block at the
     *  player's feet and they'd have to break it twice. The cached `blockState` still says
     *  "orb" at that moment, so the world-state read is the authoritative check.
     *
     *  Updates are fired on **both** the orb's neighbours (via `UPDATE_ALL` in setBlock)
     *  **and** the support's neighbours (via an explicit `updateNeighborsAt(supportPos)`).
     *  The two-call pattern mirrors vanilla
     *  [net.minecraft.world.level.block.LeverBlock]/`ButtonBlock`: the orb's update reaches
     *  blocks adjacent to the orb itself, the support's update reaches blocks adjacent to
     *  the support (wires / repeaters / pistons on the support's other faces). Without the
     *  support-side call, those 2-hops-from-orb components don't see POWER changes and stay
     *  out of sync with the orb's emitted signal. */
    fun applyPower(level: ServerLevel, newPower: Int) {
        val st = level.getBlockState(blockPos)
        if (st.block !is OrbOfLinkingBlock) return
        val cur = st.getValue(OrbOfLinkingBlock.POWER)
        if (cur == newPower) return
        val want = st.setValue(OrbOfLinkingBlock.POWER, newPower)
        level.setBlock(blockPos, want, Block.UPDATE_ALL)
        val supportPos = blockPos.relative(facing)
        level.updateNeighborsAt(supportPos, want.block)
    }

    // --- World-space geometry ----------------------------------------------------------------

    /** This orb's centre in world coordinates, accounting for VS2 ships. */
    fun worldCenter(): Vec3? {
        val l = level ?: return null
        return orbWorldCenter(l, blockPos)
    }

    /** Set the per-orb filter from a player's offhand. Pass [ItemStack.EMPTY] to clear.
     *  The stored stack is always a single-count copy so it represents "an item type", not
     *  "a quantity of items". Marks the BE dirty *and* syncs the filter to clients so the
     *  filter renderer updates immediately. Invalidates the cached
     *  [OrbFilter] so the next [orbFilter] call re-parses against
     *  the new stack (written-book pages, NBT braces, tag prefixes
     *  — all freshly resolved). */
    fun setFilter(stack: ItemStack) {
        filter = if (stack.isEmpty) ItemStack.EMPTY else stack.copy().also { it.count = 1 }
        cachedOrbFilter = null
        sync()
    }

    /** Compiled filter predicate parsed from [filter]. Cached: the
     *  parse runs at most once per filter change (cleared in
     *  [setFilter] and on BE load via [loadAdditional]) so per-tick
     *  match calls don't re-walk the filter stack's NBT every time. */
    @Volatile private var cachedOrbFilter: org.shipwrights.enderkinesis.item.OrbFilter? = null
    fun orbFilter(): org.shipwrights.enderkinesis.item.OrbFilter {
        val cached = cachedOrbFilter
        if (cached != null) return cached
        val built = org.shipwrights.enderkinesis.item.OrbFilter.from(filter)
        cachedOrbFilter = built
        return built
    }

    /** Set the per-orb enchanted book. Pass [ItemStack.EMPTY] to clear. Always stored as a
     *  single-count copy so the book represents "an item with these enchantments", not "a
     *  stack". Marks the BE dirty and syncs to clients so the book renderer updates
     *  immediately. */
    fun setBook(stack: ItemStack) {
        book = if (stack.isEmpty) ItemStack.EMPTY else stack.copy().also { it.count = 1 }
        sync()
    }

    // --- Persistence + sync ------------------------------------------------------------------

    override fun saveAdditional(tag: CompoundTag) {
        super.saveAdditional(tag)
        if (outgoing.isNotEmpty()) tag.put("Outgoing", writeLinkMap(outgoing))
        if (incoming.isNotEmpty()) tag.put("Incoming", writeLinkMap(incoming))
        if (inputSignal != 0) tag.putInt("Input", inputSignal)
        if (!filter.isEmpty) tag.put("Filter", filter.save(CompoundTag()))
        if (!book.isEmpty) tag.put("Book", book.save(CompoundTag()))
    }

    override fun load(tag: CompoundTag) {
        super.load(tag)
        outgoing.clear()
        incoming.clear()
        // Old (pre-multi-tome) saves used "TomeKind"/"Receivers"/"SendX..." — those are dropped
        // silently. This mod is pre-release; no migration shim.
        if (tag.contains("Outgoing")) readLinkMap(tag.getList("Outgoing", Tag.TAG_COMPOUND.toInt()), outgoing)
        if (tag.contains("Incoming")) readLinkMap(tag.getList("Incoming", Tag.TAG_COMPOUND.toInt()), incoming)
        inputSignal = if (tag.contains("Input")) tag.getInt("Input") else 0
        filter = if (tag.contains("Filter"))
            ItemStack.of(tag.getCompound("Filter"))
        else ItemStack.EMPTY
        cachedOrbFilter = null
        book = if (tag.contains("Book"))
            ItemStack.of(tag.getCompound("Book"))
        else ItemStack.EMPTY
    }

    private fun writeLinkMap(map: Map<LinkKey, LinkData>): ListTag {
        val list = ListTag()
        for ((key, data) in map) {
            val entry = CompoundTag()
            entry.putString("Tome", key.tomeKind.toString())
            entry.putInt("X", key.peerPos.x); entry.putInt("Y", key.peerPos.y); entry.putInt("Z", key.peerPos.z)
            if (!data.metadata.isEmpty) entry.put("Meta", data.metadata)
            list.add(entry)
        }
        return list
    }

    private fun readLinkMap(list: ListTag, into: MutableMap<LinkKey, LinkData>) {
        for (i in 0 until list.size) {
            val entry = list.getCompound(i)
            val kind = ResourceLocation.tryParse(entry.getString("Tome")) ?: continue
            val pos = BlockPos(entry.getInt("X"), entry.getInt("Y"), entry.getInt("Z"))
            val data = LinkData()
            if (entry.contains("Meta")) data.metadata = entry.getCompound("Meta")
            into[LinkKey(kind, pos)] = data
        }
    }

    /** Immediate BE-data broadcast to every player tracking this chunk. Bypasses the standard
     *  `sendBlockUpdated`-into-chunk-change-tracker path, which only flushes packets at the
     *  next chunk-broadcast tick — that one-tick lag is enough to leave beam particles
     *  flickering after a link is severed. Sending the BE update packet directly drops the
     *  lag to zero on the receiving client. */
    private fun sync() {
        setChanged()
        val srv = level as? ServerLevel ?: return
        // Reflect the current link state into the blockstate so vanilla
        // pushes the new model variant to clients automatically.
        // Different texture per role (unbound/send/receive) is selected
        // by the blockstate JSON; the pulsing haze BER reads the same
        // property to decide whether to draw at all.
        refreshOrbRoleState(srv)
        val pkt = getUpdatePacket() ?: return
        val chunkPos = net.minecraft.world.level.ChunkPos(blockPos)
        for (player in srv.chunkSource.chunkMap.getPlayers(chunkPos, false)) {
            player.connection.send(pkt)
        }
    }

    /** Compute the orb's current high-level role and stamp it into the
     *  blockstate property [OrbOfLinkingBlock.ORB_ROLE] iff it differs
     *  from the present value. SEND wins over RECEIVE for mixed-role
     *  orbs — the active direction follows whichever way data is
     *  being sent. */
    private fun refreshOrbRoleState(level: ServerLevel) {
        val role = when {
            outgoing.isNotEmpty() -> OrbRole.SEND
            incoming.isNotEmpty() -> OrbRole.RECEIVE
            else -> OrbRole.UNBOUND
        }
        val current = blockState
        if (current.getValue(OrbOfLinkingBlock.ORB_ROLE) == role) return
        // Flag 3 = BLOCK_UPDATE | NOTIFY_NEIGHBORS, the standard "state
        // changed" set. Skip neighbour update propagation (16) — link
        // bookkeeping shouldn't trigger redstone/observer chains.
        level.setBlock(blockPos, current.setValue(OrbOfLinkingBlock.ORB_ROLE, role), 3)
    }

    override fun getUpdateTag(): CompoundTag {
        val tag = super.getUpdateTag()
        if (outgoing.isNotEmpty()) tag.put("Outgoing", writeLinkMap(outgoing))
        // Receivers don't need to know their incoming set for client rendering (the SEND side
        // owns the beam render). Skip syncing "Incoming" to save bandwidth.
        // Filter and book both go over the wire so the air-side renderer can show their
        // tumbling indicators.
        if (!filter.isEmpty) tag.put("Filter", filter.save(CompoundTag()))
        if (!book.isEmpty) tag.put("Book", book.save(CompoundTag()))
        return tag
    }

    override fun getUpdatePacket(): Packet<ClientGamePacketListener>? =
        ClientboundBlockEntityDataPacket.create(this)

    override fun setLevel(level: Level) {
        super.setLevel(level)
        if (level.isClientSide) OrbOfLinkingClientRegistry.register(level, this)
    }

    override fun setRemoved() {
        super.setRemoved()
        val l = level ?: return
        if (l.isClientSide) OrbOfLinkingClientRegistry.unregister(l, this)
    }

    // --- Types ---

    /** Composite key — a link is uniquely identified by its tome and its peer position. */
    data class LinkKey(val tomeKind: ResourceLocation, val peerPos: BlockPos)

    /** Per-link runtime + persistent state. Tomes that build VS joints write [jointId] /
     *  [jointPending]; tomes that need per-pair persistent state stash it in [metadata]. */
    class LinkData(
        var jointId: Int = -1,
        var jointPending: Boolean = false,
        var metadata: CompoundTag = CompoundTag(),
    )

    companion object {
        /** Hard cap on world-space link distance (blocks). Measured between orb centres in
         *  world coordinates after the VS2 ship-frame transform. Out-of-range pairs are
         *  refused at link time; runtime drift past this drops the link (joint tomes only). */
        const val MAX_LINK_DISTANCE: Double = 64.0

        /** Visual offset (blocks) from the block centre toward the active face. The orb's
         *  model is a 12-px cube flush against one face, so its geometric centre sits 4 px
         *  (= 0.25 blocks) toward that face from the block centre. */
        private const val ACTIVE_FACE_OFFSET: Double = 0.25

        /** Compute the world-space centre of an orb at [pos] in [level], applying the VS2
         *  ship-to-world transform when the block lives in a shipyard chunk. The point
         *  returned is the orb's **visual** centre — block centre shifted by
         *  [ACTIVE_FACE_OFFSET] toward the active face — so beams and joint anchors line
         *  up with the rendered geometry. Falls back to the block centre if the block at
         *  [pos] is no longer an orb (e.g., just broken, mid-removal). */
        fun orbWorldCenter(level: Level, pos: BlockPos): Vec3? {
            val state = level.getBlockState(pos)
            val facing = if (state.hasProperty(OrbOfLinkingBlock.FACING))
                state.getValue(OrbOfLinkingBlock.FACING) else Direction.DOWN
            val ship = level.getShipManagingPos(pos)
            val v = Vector3d(
                pos.x + 0.5 + facing.stepX * ACTIVE_FACE_OFFSET,
                pos.y + 0.5 + facing.stepY * ACTIVE_FACE_OFFSET,
                pos.z + 0.5 + facing.stepZ * ACTIVE_FACE_OFFSET,
            )
            ship?.shipToWorld?.transformPosition(v)
            return Vec3(v.x, v.y, v.z)
        }
    }
}
