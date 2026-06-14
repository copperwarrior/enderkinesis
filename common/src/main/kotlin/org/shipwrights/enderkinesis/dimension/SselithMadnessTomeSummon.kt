package org.shipwrights.enderkinesis.dimension

import com.mojang.logging.LogUtils
import dev.architectury.networking.NetworkManager
import io.netty.buffer.Unpooled
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.TagKey
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.entity.Cataloger
import org.shipwrights.enderkinesis.registry.EKBlocks
import org.shipwrights.enderkinesis.registry.EKEffects

/**
 * The Cataloger's tome-summon flourish, applied to **players** under
 * the effects of Sselith Madness:
 *
 *  - **Where**: only inside Sselith's Repertory ([SselithRepertory]).
 *  - **Trigger**: per-tick random roll at 1/[TRIGGER_DENOMINATOR],
 *    gated by a post-summon cooldown. Active any time the player has
 *    a non-zero Sselith Madness level.
 *  - **Target pick**: a dart-style raycast from the player's eye in a
 *    random direction (full yaw, ±[PITCH_RANGE_DEG] pitch). Must
 *    hit a Sselith bookshelf within [TRACE_DISTANCE] blocks or the
 *    attempt is wasted (the player resumes whatever they were doing).
 *  - **Stare**: on a successful raycast, the player enters a
 *    [STARE_DURATION_TICKS]-tick stare phase — every tick a
 *    [ClientboundPlayerLookAtPacket] is sent so the camera is held
 *    onto the bookshelf, and a few glyph particles are sown around
 *    the shelf so the player can see Sselith working. The stare gives
 *    the gift a visible cause.
 *  - **Delivery**: at the end of the stare we pick a random item from
 *    [PICKABLE_TOMES_TAG] that the player doesn't already carry,
 *    place it in the first empty slot, and play a final particle puff
 *    + page-turn sound at the shelf and the player. If the player's
 *    inventory has no room, or every eligible tome is already owned,
 *    the gift is skipped silently.
 *
 * Server-only state. Cleared on logout, dimension change, death, or
 * loss of the Sselith Madness effect — the stare aborts cleanly if
 * the player leaves Sselith mid-summon.
 */
object SselithMadnessTomeSummon {

    /** Item tag containing every tome eligible for the random
     *  Madness gift: `wylland_tome` + every `tome_of_*`. Defined in
     *  `data/enderkinesis/tags/items/pickable_tomes.json`. */
    val PICKABLE_TOMES_TAG: TagKey<Item> =
        TagKey.create(Registries.ITEM, EnderkinesisMod.id("pickable_tomes"))

    private val LOG = LogUtils.getLogger()

    /** Per-player game-tick at which the next summon roll is allowed.
     *  Transient server-memory; cleared on logout. */
    private val nextEarliestTrigger = Object2LongOpenHashMap<UUID>().apply {
        defaultReturnValue(0L)
    }

    /** Per-player in-progress summon state. Active from the trigger
     *  tick until the gift is delivered (or the player becomes
     *  ineligible mid-stare). `ConcurrentHashMap` because we mutate
     *  it from a server tick callback and `[onQuit]` from the network
     *  thread. */
    private val activeSummons = ConcurrentHashMap<UUID, SummonState>()

    /** Players we logged an "eligible" message for. Used to dedupe
     *  the once-per-eligibility-transition diagnostic so it doesn't
     *  spam every tick. */
    private val previouslyEligible = HashSet<UUID>()

    /** S2C packet IDs — match
     *  [org.shipwrights.enderkinesis.client.PlayerTomeSummonClient]. */
    val BEGIN_PACKET: ResourceLocation =
        EnderkinesisMod.id("sselith_madness/tome_summon_begin")
    val END_PACKET: ResourceLocation =
        EnderkinesisMod.id("sselith_madness/tome_summon_end")

    /**
     * In-flight state for a player tome summon. Phase math mirrors the
     * Cataloger summon — same [Cataloger.TOME_TOTAL_TICKS] window, same
     * out / dwell / in proportions, so the visual book follows the
     * same arc and pose curves.
     *
     * On a successful delivery (player had inventory space) the IN
     * phase is skipped — the book is consumed into the inventory at
     * the end of DWELL. On a "no space" outcome the IN phase runs and
     * the book floats back to the source shelf.
     *
     * [tomeChoice] is locked at trigger time so the visual matches the
     * actual gift (if delivered). [deliveryDecided] flips once we
     * cross the dwell→in boundary so we don't repeat the inventory
     * check or animation switch.
     */
    private data class SummonState(
        val source: BlockPos,
        val tomeChoice: Item?,
        val startTick: Long,
        var returnTo: BlockPos = source,
        var willReturn: Boolean = false,
        var deliveryDecided: Boolean = false,
    )

    fun init() {
        dev.architectury.event.events.common.TickEvent.PLAYER_POST.register(::tickPlayer)
        dev.architectury.event.events.common.PlayerEvent.PLAYER_QUIT.register(::onQuit)
        LOG.info(
            "Sselith Madness tome summon registered (trigger 1/{}, stare {} ticks, cooldown {} ticks, scan-miss cooldown {} ticks)",
            TRIGGER_DENOMINATOR, STARE_DURATION_TICKS,
            COOLDOWN_AFTER_TICKS, SCAN_MISS_COOLDOWN_TICKS,
        )
    }

    private fun onQuit(player: ServerPlayer) {
        nextEarliestTrigger.removeLong(player.uuid)
        if (activeSummons.remove(player.uuid) != null) {
            broadcastEnd(player)
        }
        previouslyEligible.remove(player.uuid)
    }

    private fun tickPlayer(player: Player) {
        if (player !is ServerPlayer) return
        if (player.isSpectator) return
        val level = player.level() as? ServerLevel ?: return

        val inSselith = level.dimension() == SselithRepertory.LEVEL_KEY
        val amp = player.getEffect(EKEffects.SSELITH_MADNESS.get())?.amplifier ?: -1
        val eligible = inSselith && amp >= 0
        // Log eligibility transitions once each so a player going
        // through Sselith sees confirmation that the tick loop is
        // running and they're in the candidate pool.
        val wasEligible = player.uuid in previouslyEligible
        if (eligible && !wasEligible) {
            previouslyEligible.add(player.uuid)
            LOG.info(
                "Sselith Madness tome summon: player={} uuid={} entered eligibility (madness amp={}, level={})",
                player.gameProfile.name, player.uuid, amp, amp + 1,
            )
        } else if (!eligible && wasEligible) {
            previouslyEligible.remove(player.uuid)
            LOG.info(
                "Sselith Madness tome summon: player={} uuid={} left eligibility (inSselith={}, amp={})",
                player.gameProfile.name, player.uuid, inSselith, amp,
            )
        }

        if (!eligible) {
            if (activeSummons.remove(player.uuid) != null) broadcastEnd(player)
            return
        }

        val now = level.gameTime
        // If a summon is in progress, tick it and skip the trigger
        // path entirely — only one summon at a time per player.
        val active = activeSummons[player.uuid]
        if (active != null) {
            tickActiveSummon(player, level, active, now)
            return
        }

        val cooldownUntil = nextEarliestTrigger.getLong(player.uuid)
        if (now < cooldownUntil) return
        if (player.random.nextInt(TRIGGER_DENOMINATOR) != 0) return

        // Random gate passed — diagnostic so the cadence is visible in
        // the log even when the raycast misses.
        LOG.info(
            "Sselith Madness tome summon: gate roll passed for player={} at tick={}, attempting raycast",
            player.gameProfile.name, now,
        )

        val shelf = raycastForBookshelf(player, level) ?: run {
            LOG.info(
                "Sselith Madness tome summon: raycast found no Sselith bookshelf for player={}; cooldown {} ticks",
                player.gameProfile.name, SCAN_MISS_COOLDOWN_TICKS,
            )
            nextEarliestTrigger.put(player.uuid, now + SCAN_MISS_COOLDOWN_TICKS)
            return
        }

        // Pre-pick the tome at trigger time so the visual matches the
        // gift. If the player's inventory has no room *now*, the
        // book will return to the shelf at the end of dwell.
        val gift = pickNonDuplicateTome(player)
        val initialWillReturn = gift == null
        val state = SummonState(
            source = shelf,
            tomeChoice = gift,
            startTick = now,
            returnTo = shelf,
            willReturn = initialWillReturn,
        )
        activeSummons[player.uuid] = state
        playStareStartEffects(player, level, shelf)
        broadcastBegin(player, state)
        LOG.info(
            "Sselith Madness tome summon begin: player={} uuid={} bookshelf=({},{},{}) gift={} willReturn={}",
            player.gameProfile.name, player.uuid, shelf.x, shelf.y, shelf.z,
            gift?.let { BuiltInRegistries.ITEM.getKey(it) }, initialWillReturn,
        )
    }

    /** Per-tick step for an in-progress player summon. Drives the
     *  same phase split the Cataloger uses (OUT → DWELL → IN), with
     *  IN skipped on a successful delivery. Each tick:
     *
     *   - Pin the player's movement to zero so they don't drift away
     *     from the summon.
     *   - Force the camera onto the right target for the current
     *     phase via [ClientboundPlayerLookAtPacket].
     *   - At the OUT→DWELL boundary, deliver or commit to the IN
     *     phase based on inventory state.
     *   - When the configured total ticks are up, end the summon. */
    private fun tickActiveSummon(
        player: ServerPlayer, level: ServerLevel,
        state: SummonState, now: Long,
    ) {
        // Freeze movement — the summon takes priority over walking.
        player.deltaMovement = Vec3.ZERO
        player.hurtMarked = true  // marks delta dirty for sync

        val elapsed = (now - state.startTick).toInt()
        val totalTicks = if (state.willReturn) Cataloger.TOME_TOTAL_TICKS
                         else Cataloger.TOME_OUTBOUND_TICKS + Cataloger.TOME_DWELL_TICKS

        // Phase boundaries.
        val dwellStart = Cataloger.TOME_OUTBOUND_TICKS
        val inStart = dwellStart + Cataloger.TOME_DWELL_TICKS

        // Decision at OUT→DWELL boundary is server-only. The
        // `willReturn` plumbing was already broadcast in the BEGIN
        // packet at trigger time — re-broadcasting here would
        // re-stamp the client's `startTick` and restart the visual.
        // We accept the rare case where inventory churn during the
        // OUT phase makes the actual delivery diverge from the
        // pre-committed visual.
        if (!state.deliveryDecided && elapsed >= dwellStart) {
            state.deliveryDecided = true
            val choice = state.tomeChoice
            val canDeliver = choice != null && player.inventory.getFreeSlot() >= 0
            // The pre-committed visual already chose willReturn at
            // trigger time. Only override here if the situation has
            // gotten worse (no space now, was space before) — that
            // way the visual at least matches the no-gift case.
            if (!canDeliver && !state.willReturn) {
                LOG.info(
                    "Sselith Madness tome summon: inventory full at delivery for player={}, but visual was committed to deliver; gift skipped",
                    player.gameProfile.name,
                )
            }
        }

        // View control is client-side now (eased rotation in
        // [org.shipwrights.enderkinesis.client.PlayerTomeSummonClient.onAiStep]).
        // The server's [ClientboundPlayerLookAtPacket] was a hard
        // snap each tick — combined with the menu-walk's per-frame
        // rotation it produced a visible shake.

        // Trail particles around the active shelf during flight, fade
        // around the player during dwell.
        if (elapsed % 4 == 0) {
            val (px, py, pz) = when {
                elapsed < dwellStart -> shelfLookTarget(state.source)
                elapsed < inStart -> playerHoldTarget(player)
                else -> shelfLookTarget(state.returnTo)
            }
            level.sendParticles(
                ParticleTypes.ENCHANT,
                px, py, pz, 0,
                (player.random.nextDouble() - 0.5) * 0.4,
                (player.random.nextDouble() - 0.5) * 0.4,
                (player.random.nextDouble() - 0.5) * 0.4,
                1.0,
            )
        }

        if (elapsed >= totalTicks) {
            endSummon(player, level, state, now)
        }
    }

    private fun shelfLookTarget(pos: BlockPos): Triple<Double, Double, Double> =
        Triple(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)

    private fun playerHoldTarget(player: ServerPlayer): Triple<Double, Double, Double> {
        // Same forward-of-head offset the Cataloger uses; HOLD_FORWARD
        // ~1.1 blocks ahead of the eye along the gaze vector.
        val yaw = player.yHeadRot * (Math.PI / 180.0)
        val pitch = player.xRot * (Math.PI / 180.0)
        val cosP = Math.cos(pitch)
        val fx = -Math.sin(yaw) * cosP
        val fy = -Math.sin(pitch)
        val fz = Math.cos(yaw) * cosP
        return Triple(
            player.x + fx * 1.1,
            player.eyeY + fy * 1.1,
            player.z + fz * 1.1,
        )
    }

    /** Final tick of the summon — deliver the tome (or not), play the
     *  closing effects, clean up state, and tell the client. */
    private fun endSummon(
        player: ServerPlayer, level: ServerLevel,
        state: SummonState, now: Long,
    ) {
        if (!state.willReturn) {
            val gift = state.tomeChoice
            if (gift != null && player.inventory.getFreeSlot() >= 0) {
                val stack = ItemStack(gift)
                if (!player.inventory.add(stack)) {
                    player.drop(stack, false)
                }
                playDeliveryEffects(player, level, state.source, success = true)
                LOG.info(
                    "Sselith Madness tome gift: player={} uuid={} bookshelf=({},{},{}) item={}",
                    player.gameProfile.name, player.uuid,
                    state.source.x, state.source.y, state.source.z,
                    BuiltInRegistries.ITEM.getKey(gift),
                )
            } else {
                playDeliveryEffects(player, level, state.source, success = false)
                LOG.info(
                    "Sselith Madness tome summon: no eligible tome at delivery for player={}",
                    player.gameProfile.name,
                )
            }
        } else {
            // Book floated back into the shelf — quieter close.
            playDeliveryEffects(player, level, state.returnTo, success = false)
            LOG.info(
                "Sselith Madness tome summon: returned book to shelf ({},{},{}) for player={} (no inventory space)",
                state.returnTo.x, state.returnTo.y, state.returnTo.z, player.gameProfile.name,
            )
        }
        activeSummons.remove(player.uuid)
        broadcastEnd(player)
        nextEarliestTrigger.put(player.uuid, now + COOLDOWN_AFTER_TICKS)
    }

    /** Broadcast a BEGIN packet to every tracker of [player] so they
     *  can render the floating book. The player's own client is in
     *  this list (it's tracking itself), so first-person rendering
     *  works too. */
    private fun broadcastBegin(player: ServerPlayer, state: SummonState) {
        val totalTicks = if (state.willReturn) Cataloger.TOME_TOTAL_TICKS
                         else Cataloger.TOME_OUTBOUND_TICKS + Cataloger.TOME_DWELL_TICKS
        val recipients = trackingPlayers(player)
        for (r in recipients) {
            val buf = FriendlyByteBuf(Unpooled.buffer())
            buf.writeUUID(player.uuid)
            buf.writeBlockPos(state.source)
            buf.writeBlockPos(state.returnTo)
            buf.writeVarInt(totalTicks)
            buf.writeBoolean(state.willReturn)
            NetworkManager.sendToPlayer(r, BEGIN_PACKET, buf)
        }
    }

    private fun broadcastEnd(player: ServerPlayer) {
        val recipients = trackingPlayers(player)
        for (r in recipients) {
            val buf = FriendlyByteBuf(Unpooled.buffer())
            buf.writeUUID(player.uuid)
            NetworkManager.sendToPlayer(r, END_PACKET, buf)
        }
    }

    /** Server players who can see [player]: everyone tracking the
     *  player entity, plus the player themselves. */
    private fun trackingPlayers(player: ServerPlayer): Collection<ServerPlayer> {
        val level = player.level() as? ServerLevel ?: return listOf(player)
        val tracker = level.chunkSource.chunkMap
        val set = LinkedHashSet<ServerPlayer>()
        set.add(player)
        for (other in level.players()) {
            if (other.distanceToSqr(player) < 64.0 * 64.0) set.add(other)
        }
        return set
    }

    /** Fire [RAYCAST_ATTEMPTS] independent dart-style rays per roll
     *  and return the first that lands on a Sselith bookshelf. Triples
     *  the hit rate in spaces where shelves cover a fraction of the
     *  surrounding sphere, at negligible cost. */
    private fun raycastForBookshelf(player: ServerPlayer, level: ServerLevel): BlockPos? {
        repeat(RAYCAST_ATTEMPTS) { attempt ->
            val hit = singleRaycast(player, level, attempt)
            if (hit != null) return hit
        }
        return null
    }

    /** One random-direction raycast from the player's eyes — same
     *  maths the [org.shipwrights.enderkinesis.entity.SummonTomeFromBookshelfGoal]
     *  uses on a Cataloger. */
    private fun singleRaycast(
        player: ServerPlayer, level: ServerLevel, attempt: Int,
    ): BlockPos? {
        val sselithBookshelf = EKBlocks.SSELITH_BOOKSHELF.get()
        val yawDeg = player.random.nextFloat() * 360f
        val pitchDeg = (player.random.nextFloat() * 2f - 1f) * PITCH_RANGE_DEG
        val yawRad = yawDeg * (Math.PI / 180.0)
        val pitchRad = pitchDeg * (Math.PI / 180.0)
        val cosPitch = Math.cos(pitchRad)
        val dx = -Math.sin(yawRad) * cosPitch
        val dy = -Math.sin(pitchRad)
        val dz = Math.cos(yawRad) * cosPitch

        val from = Vec3(player.x, player.eyeY, player.z)
        val to = Vec3(
            from.x + dx * TRACE_DISTANCE,
            from.y + dy * TRACE_DISTANCE,
            from.z + dz * TRACE_DISTANCE,
        )
        val hit = level.clip(
            ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player),
        )
        if (hit.type != HitResult.Type.BLOCK) {
            LOG.debug(
                "Sselith Madness tome raycast {}/{}: miss (no block hit) player={} dir=(yaw={}°, pitch={}°)",
                attempt + 1, RAYCAST_ATTEMPTS, player.gameProfile.name, yawDeg, pitchDeg,
            )
            return null
        }
        val pos = hit.blockPos
        val state = level.getBlockState(pos)
        if (!state.`is`(sselithBookshelf)) {
            LOG.debug(
                "Sselith Madness tome raycast {}/{}: first hit was {} at ({},{},{}) — not a Sselith bookshelf",
                attempt + 1, RAYCAST_ATTEMPTS,
                BuiltInRegistries.BLOCK.getKey(state.block), pos.x, pos.y, pos.z,
            )
            return null
        }
        return pos.immutable()
    }

    /** Roll a random item from [PICKABLE_TOMES_TAG] that the player
     *  does NOT already carry, returning null when the player has at
     *  least one of every eligible tome OR when their inventory has
     *  no empty slot. Reservoir-pick over the tag so the distribution
     *  is uniform without materialising a list. */
    private fun pickNonDuplicateTome(player: ServerPlayer): Item? {
        if (player.inventory.getFreeSlot() < 0) return null
        val owned = HashSet<Item>()
        for (slot in 0 until player.inventory.containerSize) {
            val stack = player.inventory.getItem(slot)
            if (!stack.isEmpty) owned.add(stack.item)
        }
        val registry = player.server.registryAccess().registryOrThrow(Registries.ITEM)
        val tag = registry.getTag(PICKABLE_TOMES_TAG).orElse(null) ?: return null
        var chosen: Item? = null
        var seen = 0
        for (holder in tag) {
            val item = holder.value()
            if (item in owned) continue
            seen++
            if (player.random.nextInt(seen) == 0) chosen = item
        }
        return chosen
    }

    /** Sound + a small puff at the shelf when the stare starts. Gives
     *  the player an immediate "something is happening" cue, even
     *  before the camera has finished snapping over. */
    private fun playStareStartEffects(player: ServerPlayer, level: ServerLevel, shelf: BlockPos) {
        level.playSound(
            null, shelf.x + 0.5, shelf.y + 0.5, shelf.z + 0.5,
            SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.PLAYERS,
            0.6f, 0.7f + player.random.nextFloat() * 0.2f,
        )
        for (i in 0 until 16) {
            level.sendParticles(
                ParticleTypes.ENCHANT,
                shelf.x + 0.5, shelf.y + 0.5, shelf.z + 0.5,
                0,
                (player.random.nextDouble() - 0.5) * 0.8,
                (player.random.nextDouble() - 0.5) * 0.8,
                (player.random.nextDouble() - 0.5) * 0.8,
                1.2,
            )
        }
    }

    /** Final delivery cue: page-turn click and a glyph burst around
     *  the player. [success] toggles whether we also burst around the
     *  shelf (no-gift cases get a quieter close so the player knows
     *  the summon ended without rewarding them). */
    private fun playDeliveryEffects(
        player: ServerPlayer, level: ServerLevel, shelf: BlockPos, success: Boolean,
    ) {
        level.playSound(
            null, player.x, player.y, player.z,
            SoundEvents.BOOK_PAGE_TURN, SoundSource.PLAYERS,
            1.0f, 0.85f + player.random.nextFloat() * 0.2f,
        )
        if (success) {
            for (i in 0 until 16) {
                level.sendParticles(
                    ParticleTypes.ENCHANT,
                    shelf.x + 0.5, shelf.y + 0.5, shelf.z + 0.5,
                    0,
                    (player.random.nextDouble() - 0.5) * 0.8,
                    (player.random.nextDouble() - 0.5) * 0.8,
                    (player.random.nextDouble() - 0.5) * 0.8,
                    1.2,
                )
            }
        }
        for (i in 0 until 12) {
            level.sendParticles(
                ParticleTypes.ENCHANT,
                player.x, player.y + 1.0, player.z,
                0,
                (player.random.nextDouble() - 0.5) * 0.5,
                (player.random.nextDouble() - 0.5) * 0.5,
                (player.random.nextDouble() - 0.5) * 0.5,
                1.0,
            )
        }
    }

    /** Per-eligible-tick odds: 1 / N chance to roll. 1200 → expected
     *  ~60 s between rolls firing — same cadence Catalogers use.
     *  Effective gift cadence is longer because each roll still has to
     *  pass the random-direction raycast and the [COOLDOWN_AFTER_TICKS]
     *  floor. */
    private const val TRIGGER_DENOMINATOR = 1200

    /** Cooldown after a successful summon or a "no eligible tome"
     *  outcome. 90 s is long enough that even a player AFK in a
     *  bookshelf row doesn't accumulate gifts. */
    private const val COOLDOWN_AFTER_TICKS = 20L * 90L

    /** Short cooldown after the raycast misses entirely — keeps the
     *  scan rate sane even if the player is in a hallway with no
     *  Sselith bookshelves in any direction. */
    private const val SCAN_MISS_COOLDOWN_TICKS = 100L

    /** Maximum reach of the bookshelf-pick raycast (blocks). Matches
     *  the Cataloger goal. */
    private const val TRACE_DISTANCE = 32.0

    /** Half-width (degrees) of the random-pitch band around the
     *  horizon. ±30° keeps the ray mostly horizontal. */
    private const val PITCH_RANGE_DEG = 30f

    /** Number of independent random rays cast per gate roll. The
     *  trigger succeeds if any of them lands on a Sselith bookshelf.
     *  3 → covers a much wider arc per roll without saturating the
     *  hit rate (still plenty of corridors that miss every ray). */
    private const val RAYCAST_ATTEMPTS = 3

    /** How long the player is held looking at the shelf before the
     *  tome arrives. 40 ticks ≈ 2 s — long enough to register as a
     *  deliberate flourish, short enough that the camera-lock doesn't
     *  feel like a hostage situation. */
    private const val STARE_DURATION_TICKS = 40L
}
