package org.shipwrights.enderkinesis.dimension

import dev.architectury.event.events.common.PlayerEvent
import dev.architectury.event.events.common.TickEvent
import dev.architectury.networking.NetworkManager
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap
import java.util.UUID
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.registry.EKEffects
import org.shipwrights.enderkinesis.registry.EKParticles

/**
 * Leveling for the **Sselith Madness** effect ([EKEffects.SSELITH_MADNESS]).
 *
 * The madness lands at level 1 the moment a player enters Sselith's Repertory,
 * climbs a level every [GROWTH_INTERVAL_TICKS] of continued presence up to
 * [MAX_LEVEL], and — once the player leaves — decays a level every
 * [DECAY_INTERVAL_TICKS]. Decay is deliberately slower than growth, so the
 * dimension "follows you home": from level 5 it takes ~50 minutes outside to
 * fully clear.
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

    /** Ticks of presence in Sselith between level-ups (≈6 min). Level 1 is
     *  granted immediately on entry, so reaching [MAX_LEVEL] takes
     *  `(MAX_LEVEL − 1) ×` this ≈ 24 min. */
    private const val GROWTH_INTERVAL_TICKS = 6 * 60 * 20

    /** Ticks outside Sselith between level-downs (≈10 min) — slower than growth
     *  so the madness lingers. From [MAX_LEVEL] a full clear is ~50 min. */
    private const val DECAY_INTERVAL_TICKS = 10 * 60 * 20

    private const val MAX_LEVEL = 5

    /** Duration the hidden effect is (re)applied with. Long, because this handler
     *  manages the level's lifetime; refreshed whenever the remaining duration
     *  drops below [REFRESH_BELOW_TICKS] so it never expires on its own. */
    private const val EFFECT_DURATION_TICKS = 30 * 60 * 20
    private const val REFRESH_BELOW_TICKS = 10 * 60 * 20

    /** Per-player non-negative progress toward the next step (growth inside
     *  Sselith, decay outside). Transient server-memory. */
    private val progress = Object2IntOpenHashMap<UUID>().apply { defaultReturnValue(0) }

    /** C2S: the menu-walk client (`SselithMenuWalk`) pings this each dust tick while
     *  the possessed body is walking. The server emits the dust here so every nearby
     *  player sees the trail — a client-side `addParticle` would be local-only. */
    val MENU_WALK_DUST: ResourceLocation = EnderkinesisMod.id("sselith_madness/menu_walk_dust")

    /** Particles per emit, matching the Cataloger's `spawnDustTrail`. */
    private const val DUST_PER_EMIT = 1

    /** Amplifier (level − 1) gating the menu-walk dust — 3 = level 4, matching the
     *  client's `SselithMenuWalk` activation threshold. */
    private const val MENU_WALK_MIN_AMP = 3

    fun init() {
        TickEvent.PLAYER_POST.register(::tickPlayer)
        PlayerEvent.CHANGE_DIMENSION.register(::onChangeDimension)
        PlayerEvent.PLAYER_QUIT.register(::onQuit)
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
    }

    private fun tickPlayer(player: Player) {
        if (player !is ServerPlayer) return
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
                    val banked = progress.addTo(uuid, 1) + 1
                    if (banked >= GROWTH_INTERVAL_TICKS) {
                        level++
                        applyLevel(player, level)
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
