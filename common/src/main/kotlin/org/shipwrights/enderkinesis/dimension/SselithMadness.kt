package org.shipwrights.enderkinesis.dimension

import dev.architectury.event.events.common.PlayerEvent
import dev.architectury.event.events.common.TickEvent
import dev.architectury.networking.NetworkManager
import io.netty.buffer.Unpooled
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap
import java.util.UUID
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Mth
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.village.poi.PoiManager
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.entity.Cataloger
import org.shipwrights.enderkinesis.registry.EKBlocks
import org.shipwrights.enderkinesis.registry.EKEffects
import org.shipwrights.enderkinesis.registry.EKParticles
import org.shipwrights.enderkinesis.registry.EKPoiTypes

/**
 * Leveling for the **Sselith Madness** effect ([EKEffects.SSELITH_MADNESS]).
 *
 * The madness lands at level 1 the moment a player enters Sselith's Repertory,
 * climbs a level every [GROWTH_INTERVAL_TICKS] of continued presence up to
 * [MAX_LEVEL], and — once the player leaves — decays a level every
 * [DECAY_INTERVAL_TICKS]. The dimension "follows you home": from [MAX_LEVEL] it
 * takes ~25 minutes outside to fully clear.
 *
 * ## State
 *
 * The **level** lives in the effect's amplifier (`amplifier = level − 1`), so it
 * serialises with the player and survives relog for free — no custom persistence.
 * The effect is kept alive with a long, periodically-refreshed duration
 * ([EFFECT_DURATION_TICKS]); this handler owns its lifetime, removing it only when
 * decay reaches zero.
 *
 * The **progress toward the next/previous level** lives in the transient
 * [progress] map (server-memory, keyed by player UUID): a non-negative tick count
 * in whichever direction the player's current dimension implies (growth inside
 * Sselith, decay outside). It's reset to zero on each Sselith boundary crossing
 * (so leftover growth never bleeds into decay), reset on every level step, and
 * cleared on logout. It resets on relog — a relog keeps your level but restarts
 * the timer to the next step.
 *
 * The effect is applied with `visible = false, showIcon = false`: no particles and
 * no HUD icon, though it still appears in the inventory effects panel. Symptoms
 * (Phase 2+) are gated by amplifier elsewhere.
 */
object SselithMadness {

    /** Ticks of presence in Sselith between level-ups (160 s). Level 1 is
     *  granted immediately on entry, so reaching [MAX_LEVEL] takes
     *  `(MAX_LEVEL − 1) ×` this ≈ 24 min. */
    private const val GROWTH_INTERVAL_TICKS = 160 * 20

    /** Ticks outside Sselith between level-downs (150 s). From [MAX_LEVEL] a full
     *  clear is ~25 min. */
    private const val DECAY_INTERVAL_TICKS = 150 * 20

    const val MAX_LEVEL = 10
    const val FIRST_ANNOUNCED_LEVEL = 2

    /** Duration the hidden effect is (re)applied with. Long, because this handler
     *  manages the level's lifetime; refreshed whenever the remaining duration
     *  drops below [REFRESH_BELOW_TICKS] so it never expires on its own. */
    private const val EFFECT_DURATION_TICKS = 30 * 60 * 20
    private const val REFRESH_BELOW_TICKS = 10 * 60 * 20

    /** Per-player non-negative progress toward the next step (growth inside
     *  Sselith, decay outside). Transient server-memory. */
    private val progress = Object2IntOpenHashMap<UUID>().apply { defaultReturnValue(0) }

    private const val MENU_WALK_DUST_MIN_INTERVAL_TICKS = 2

    // Server tick of the last accepted dust packet for each player. Server thread only.
    private val lastMenuWalkDustTick =
        Object2IntOpenHashMap<UUID>().apply { defaultReturnValue(-MENU_WALK_DUST_MIN_INTERVAL_TICKS) }

    /** C2S: the menu-walk client (`SselithMenuWalk`) pings this each dust tick while
     *  the possessed body is walking. The server emits the dust here so every nearby
     *  player sees the trail — a client-side `addParticle` would be local-only. */
    val MENU_WALK_DUST: ResourceLocation = EnderkinesisMod.id("sselith_madness/menu_walk_dust")

    /** S2C: the player's level went up to the given stage (VarInt). Level 1 has no announcement. */
    val STAGE_UP: ResourceLocation = EnderkinesisMod.id("sselith_madness/stage_up")

    /** S2C: which players have the effect. Vanilla sends an effect change only to its owner. */
    val AFFLICTED: ResourceLocation = EnderkinesisMod.id("sselith_madness/afflicted")

    private const val MAX_AFFLICTED_ENTRIES = 10_000

    // Players that the clients know as afflicted. Server thread only.
    private val announced = HashSet<UUID>()

    /** Particles per emit, matching the Cataloger's `spawnDustTrail`. */
    private const val DUST_PER_EMIT = 1

    /** Amplifier (level − 1) gating the menu-walk dust — 3 = level 4, matching the
     *  client's `SselithMenuWalk` activation threshold. */
    private const val MENU_WALK_MIN_AMP = 3

    /** Cube-radius for the "near a statue" aura. Standing within this many blocks of
     *  any Ulder Statue speeds madness growth; see [statueGrowthRate]. Backed by a PoiManager
     *  `getInRange` query against [EKPoiTypes.STATUE_KEY] so cost is constant
     *  regardless of the dim's total statue count. */
    private const val STATUE_AURA_RADIUS = 6
    private const val STATUE_GROWTH_RATE = 2
    private const val OWL_STATUE_GROWTH_RATE = 3

    fun init() {
        TickEvent.PLAYER_POST.register(::tickPlayer)
        PlayerEvent.CHANGE_DIMENSION.register(::onChangeDimension)
        PlayerEvent.PLAYER_QUIT.register(::onQuit)
        PlayerEvent.PLAYER_JOIN.register(::sendAfflictedTable)
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, MENU_WALK_DUST) { _, ctx ->
            val player = ctx.player as? ServerPlayer ?: return@registerReceiver
            ctx.queue { spawnMenuWalkDust(player) }
        }
    }

    /** Emit the Cataloger's [EKParticles.sselithDust] from the player's bounding box,
     *  broadcast to all nearby clients. Gated on the player actually having the
     *  effect at the menu-walk level, so a spoofed packet can't trail dust off a
     *  player who isn't possessed. */
    private fun spawnMenuWalkDust(player: ServerPlayer) {
        val amp = player.getEffect(EKEffects.SSELITH_MADNESS.get())?.amplifier ?: return
        if (amp < MENU_WALK_MIN_AMP) return
        val now = player.server.tickCount
        if (now - lastMenuWalkDustTick.getInt(player.uuid) < MENU_WALK_DUST_MIN_INTERVAL_TICKS) return
        lastMenuWalkDustTick.put(player.uuid, now)
        val level = player.level() as? ServerLevel ?: return
        val bb = player.boundingBox
        val sx = bb.maxX - bb.minX
        val sy = bb.maxY - bb.minY
        val sz = bb.maxZ - bb.minZ
        repeat(DUST_PER_EMIT) {
            level.sendParticles(
                EKParticles.sselithDust(),
                bb.minX + player.random.nextDouble() * sx,
                bb.minY + player.random.nextDouble() * sy,
                bb.minZ + player.random.nextDouble() * sz,
                1,
                0.0, 0.0, 0.0,
                0.0,
            )
        }
    }

    /** Reset the step timer whenever the player crosses the Sselith boundary, so a
     *  partial growth step never carries over into the first decay step (or back). */
    private fun onChangeDimension(
        player: ServerPlayer,
        oldLevel: ResourceKey<Level>,
        newLevel: ResourceKey<Level>,
    ) {
        val crossed = (oldLevel == SselithRepertory.LEVEL_KEY) != (newLevel == SselithRepertory.LEVEL_KEY)
        if (crossed) progress.removeInt(player.uuid)
    }

    private fun onQuit(player: ServerPlayer) {
        progress.removeInt(player.uuid)
        lastMenuWalkDustTick.removeInt(player.uuid)
        if (announced.remove(player.uuid)) broadcastAfflicted(player, afflicted = false)
    }

    private fun syncAfflicted(player: ServerPlayer) {
        val afflicted = player.hasEffect(EKEffects.SSELITH_MADNESS.get())
        val changed = if (afflicted) announced.add(player.uuid) else announced.remove(player.uuid)
        if (changed) broadcastAfflicted(player, afflicted)
    }

    private fun broadcastAfflicted(player: ServerPlayer, afflicted: Boolean) {
        for (recipient in player.server.playerList.players) {
            NetworkManager.sendToPlayer(
                recipient, AFFLICTED, afflictedPacket(replaceAll = false, afflicted, listOf(player.uuid)),
            )
        }
    }

    private fun sendAfflictedTable(player: ServerPlayer) {
        val afflicted = player.server.playerList.players
            .filter { it.hasEffect(EKEffects.SSELITH_MADNESS.get()) }
            .map { it.uuid }
        NetworkManager.sendToPlayer(player, AFFLICTED, afflictedPacket(replaceAll = true, afflicted = true, afflicted))
    }

    private fun afflictedPacket(replaceAll: Boolean, afflicted: Boolean, players: List<UUID>): FriendlyByteBuf {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeBoolean(replaceAll)
        buf.writeBoolean(afflicted)
        buf.writeVarInt(players.size)
        for (uuid in players) buf.writeUUID(uuid)
        return buf
    }

    /** Decoded [AFFLICTED] packet: replace the table or not, the new state, and the players it applies to. */
    class AfflictedUpdate(val replaceAll: Boolean, val afflicted: Boolean, val players: List<UUID>)

    fun readAfflicted(buf: FriendlyByteBuf): AfflictedUpdate {
        val replaceAll = buf.readBoolean()
        val afflicted = buf.readBoolean()
        val count = buf.readVarInt()
        require(count in 0..MAX_AFFLICTED_ENTRIES) { "Afflicted table too large: $count" }
        val players = ArrayList<UUID>(count)
        repeat(count) { players.add(buf.readUUID()) }
        return AfflictedUpdate(replaceAll, afflicted, players)
    }

    private fun tickPlayer(player: Player) {
        if (player !is ServerPlayer) return
        syncAfflicted(player)
        if (player.isSpectator) return

        val uuid = player.uuid
        val inSselith = player.level().dimension() == SselithRepertory.LEVEL_KEY
        var level = (player.getEffect(EKEffects.SSELITH_MADNESS.get())?.amplifier ?: -1) + 1

        if (inSselith) {
            when {
                level == 0 -> {
                    // Madness takes hold the moment you arrive.
                    level = 1
                    applyLevel(player, level)
                    progress.put(uuid, 0)
                }
                level < MAX_LEVEL -> {
                    val growthRate = statueGrowthRate(player)
                    val banked = progress.addTo(uuid, growthRate) + growthRate
                    if (banked >= GROWTH_INTERVAL_TICKS) {
                        level++
                        applyLevel(player, level)
                        announceStage(player, level)
                        progress.put(uuid, 0)
                    }
                }
                else -> progress.put(uuid, 0)
            }
        } else {
            if (level >= 1) {
                val banked = progress.addTo(uuid, 1) + 1
                if (banked >= DECAY_INTERVAL_TICKS) {
                    level--
                    applyLevel(player, level)
                    progress.put(uuid, 0)
                }
            } else {
                progress.removeInt(uuid)
            }
        }

        // Keep-alive: the level lives in the effect, so make sure a non-zero level
        // never quietly expires out from under us.
        if (level >= 1) {
            val cur = player.getEffect(EKEffects.SSELITH_MADNESS.get())
            if (cur == null || cur.duration < REFRESH_BELOW_TICKS) {
                applyLevel(player, level)
            }
        }

        // At level 4+, the player counts as a Cataloger for the
        // cataloger-vs-cataloger push system. The Cataloger side
        // already shoves us; this is the mirror — we shove back so
        // two real catalogers and a possessed player all behave like
        // a single class for collision purposes.
        if (level - 1 >= Cataloger.PLAYER_CATALOGER_MIN_AMP) {
            pushNearbyCatalogers(player)
        }
    }

    /** Growth multiplier from Ulder Statue POIs within [STATUE_AURA_RADIUS]; an owl outranks
     *  the rest. Caller has already verified the player is in Sselith. */
    private fun statueGrowthRate(player: ServerPlayer): Int {
        val level = player.serverLevel()
        val owl = EKBlocks.STATUE_OWL.get()
        val statues = level.poiManager.getInRange(
            { holder -> holder.`is`(EKPoiTypes.STATUE_KEY) },
            player.blockPosition(),
            STATUE_AURA_RADIUS,
            PoiManager.Occupancy.ANY,
        )
        var rate = 1
        for (record in statues.iterator()) {
            if (level.getBlockState(record.pos).`is`(owl)) return OWL_STATUE_GROWTH_RATE
            rate = STATUE_GROWTH_RATE
        }
        return rate
    }

    /** Mirror of [Cataloger.pushNearbyCatalogers] for high-madness
     *  players: gently shove any Cataloger sharing the player's bbox
     *  outward, using the same falloff + force as the cataloger-vs-
     *  cataloger pass so the visual feel matches in both directions. */
    private fun pushNearbyCatalogers(player: ServerPlayer) {
        val level = player.level()
        val catalogers = level.getEntitiesOfClass(Cataloger::class.java, player.boundingBox)
        if (catalogers.isEmpty()) return
        for (other in catalogers) {
            if (other.isPassengerOfSameVehicle(player)) continue
            if (other.noPhysics || player.noPhysics) continue
            if (other.isVehicle) continue
            val dx = other.x - player.x
            val dz = other.z - player.z
            var d = Mth.absMax(dx, dz)
            if (d < 0.01) continue
            d = Math.sqrt(d)
            val nx = dx / d
            val nz = dz / d
            val scale = (1.0 / d).coerceAtMost(1.0)
            other.push(nx * scale * GENTLE_PUSH_FORCE, 0.0, nz * scale * GENTLE_PUSH_FORCE)
        }
    }

    /** Same force as [Cataloger.GENTLE_PUSH_FORCE] (1/5 vanilla's 0.05).
     *  Duplicated as a private constant rather than reaching through
     *  the cataloger companion so a future tuning split (e.g. softer
     *  player push) is just a constant edit here. */
    private const val GENTLE_PUSH_FORCE = 0.01

    fun level(entity: LivingEntity): Int =
        ((entity.getEffect(EKEffects.SSELITH_MADNESS.get())?.amplifier ?: -1) + 1).coerceIn(0, MAX_LEVEL)

    /** Public hook: bump the player's madness by [delta] levels (clamped to
     *  `[0, MAX_LEVEL]`) and reset the step timer so the next growth/decay
     *  step is a fresh window. Used by items that grant madness as a cost
     *  (e.g. the Scroll of Unravelling). */
    fun addLevel(player: ServerPlayer, delta: Int = 1) {
        val current = (player.getEffect(EKEffects.SSELITH_MADNESS.get())?.amplifier ?: -1) + 1
        val next = (current + delta).coerceIn(0, MAX_LEVEL)
        if (next == current) return
        applyLevel(player, next)
        if (next > current && next >= FIRST_ANNOUNCED_LEVEL) announceStage(player, next)
        progress.put(player.uuid, 0)
    }

    private fun announceStage(player: ServerPlayer, level: Int) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeVarInt(level)
        NetworkManager.sendToPlayer(player, STAGE_UP, buf)
    }

    /** Set the player's madness to [level] (1..[MAX_LEVEL]) as a hidden effect, or
     *  remove it at level ≤ 0. Removes-then-adds so a *lower* amplifier actually
     *  replaces the existing one (vanilla `addEffect` keeps the stronger). */
    private fun applyLevel(player: ServerPlayer, level: Int) {
        val effect = EKEffects.SSELITH_MADNESS.get()
        player.removeEffect(effect)
        if (level >= 1) {
            player.addEffect(
                MobEffectInstance(
                    effect,
                    EFFECT_DURATION_TICKS,
                    level - 1,
                    /* ambient = */ false,
                    /* visible = */ false,
                    /* showIcon = */ false,
                ),
            )
        }
    }
}
