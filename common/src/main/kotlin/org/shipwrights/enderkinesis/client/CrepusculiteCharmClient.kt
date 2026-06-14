package org.shipwrights.enderkinesis.client

import dev.architectury.event.events.client.ClientTickEvent
import net.minecraft.client.Minecraft
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.registry.EKItems

/**
 * Client-side levitation for the Crepusculite Charm. While the charm is anywhere in the
 * local player's inventory and they hold the jump key, each client tick this handler lifts
 * them toward a soft cap of [MAX_HEIGHT] blocks above the ground directly beneath them.
 *
 * Why client-side: vanilla doesn't sync `LivingEntity.jumping` from client to server for
 * regular player movement (the field is only set via [ServerboundPlayerInputPacket],
 * which is vehicle-only), so a server-side handler can't reliably detect "jump is held".
 * Modifying [LocalPlayer.deltaMovement] here instead and letting vanilla position-sync
 * carry the new Y to the server. The server's flying-anti-cheat counter is reset by the
 * server-side [org.shipwrights.enderkinesis.item.CrepusculiteCharmManager] each tick a
 * charm-holder is online, so prolonged hovering doesn't trip the disconnect.
 *
 * Acts only on the *local* player — other players' lift is replicated through their own
 * client's handling of the charm, which keeps this handler stateless and trivial. */
object CrepusculiteCharmClient {

    /** Maximum height (blocks) above the ground directly below the player. The natural
     *  jump peaks around 1.25 b; this cap of 5 b is "past their jump height" by ~3.75 b. */
    private const val MAX_HEIGHT: Double = 5.0

    /** Per-tick upward velocity floor while lifting. `0.30 m/tick ≈ 6 m/s` — clearly
     *  active without being a teleport. Only raises `deltaMovement.y` to this floor; if
     *  the player is already rising faster (e.g. the initial jump impulse of `0.42 m/tick`),
     *  the natural velocity is left untouched. */
    private const val LIFT_VELOCITY: Double = 0.30

    /** Downward scan range (blocks) for ground search. Larger than [MAX_HEIGHT] so a
     *  player at the cap still finds the ground beneath them. */
    private const val GROUND_SCAN: Int = 8

    /** Lift-particle burst per active tick — small so the trail reads without saturating
     *  the client's particle budget. */
    private const val PARTICLES_PER_TICK: Int = 2

    /** Depth (blocks) below the player's feet to scatter the particle spawns. */
    private const val UNDER_DEPTH: Double = 0.4

    /** Upward velocity (m/tick) for each spawned particle — matches the aether pad's
     *  `BEAM_PARTICLE_DRIFT` so the look stays family-consistent. */
    private const val PARTICLE_DRIFT: Double = 0.04

    fun init() {
        ClientTickEvent.CLIENT_POST.register(::tickClient)
    }

    private fun tickClient(client: Minecraft) {
        val player = client.player ?: return
        if (player.isSpectator) return
        if (player.abilities.flying) return         // creative-flying owns its own lift
        if (player.vehicle != null) return          // jump key controls the mount, not us
        if (!player.input.jumping) return           // GUI-aware jump state (chat/menu suppress it)
        if (!hasCharm(player)) return

        val groundY = findGroundY(player, client) ?: return
        val heightAboveGround = player.y - groundY
        val v = player.deltaMovement

        emitLiftParticles(client, player)

        if (heightAboveGround >= MAX_HEIGHT) {
            // At/above cap — hover stably while jump is held. Forcing `vy = 0` each tick
            // cancels both the residual rise from the approach and the gravity that would
            // otherwise drag the player back through the cap into the lift cycle (which
            // is exactly what the user-visible "bouncy at the top" symptom is).
            player.deltaMovement = Vec3(v.x, 0.0, v.z)
            player.fallDistance = 0f
            return
        }

        // Below cap — lift toward it, but clamp the target so a single tick of lift can't
        // carry us past the cap. Without this clamp the player overshoots by up to
        // [LIFT_VELOCITY] each cycle, which produces the bob.
        val remaining = MAX_HEIGHT - heightAboveGround
        val targetVy = Math.min(LIFT_VELOCITY, remaining)
        if (v.y < targetVy) {
            player.deltaMovement = Vec3(v.x, targetVy, v.z)
            player.fallDistance = 0f                 // lift counts as carried, not falling
        }
    }

    /** Spawn a tick's worth of [ParticleTypes.REVERSE_PORTAL] particles below the
     *  player's feet, drifting upward — the same particle + drift pattern the aether pad
     *  uses for its lift beam, just sourced at the player's feet instead of a beam axis.
     *  Reads visually as "you're being held up by something." */
    private fun emitLiftParticles(client: Minecraft, player: Player) {
        val level = client.level ?: return
        val rng = player.random
        val bb = player.boundingBox
        val cx = (bb.minX + bb.maxX) * 0.5
        val cz = (bb.minZ + bb.maxZ) * 0.5
        val halfWidth = (bb.maxX - bb.minX) * 0.5
        repeat(PARTICLES_PER_TICK) {
            val ox = (rng.nextDouble() - 0.5) * 2.0 * halfWidth
            val oz = (rng.nextDouble() - 0.5) * 2.0 * halfWidth
            val y = bb.minY - rng.nextDouble() * UNDER_DEPTH
            level.addParticle(
                ParticleTypes.REVERSE_PORTAL,
                cx + ox, y, cz + oz,
                0.0, PARTICLE_DRIFT, 0.0,
            )
        }
    }

    private fun hasCharm(player: Player): Boolean {
        val charm = EKItems.CREPUSCULITE_CHARM.get()
        val inv = player.inventory
        for (i in 0 until inv.containerSize) {
            if (inv.getItem(i).`is`(charm)) return true
        }
        return false
    }

    private fun findGroundY(player: Player, client: Minecraft): Double? {
        val level = client.level ?: return null
        return org.shipwrights.enderkinesis.item.CrepusculiteCharmManager
            .findGroundY(level, player.blockX, player.blockZ, player.y, GROUND_SCAN)
    }
}
